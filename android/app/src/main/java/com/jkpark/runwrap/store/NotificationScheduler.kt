package com.jkpark.runwrap.store

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.jkpark.runwrap.R
import com.jkpark.runwrap.engine.NotificationScheduler
import com.jkpark.runwrap.engine.NotifyKey
import com.jkpark.runwrap.engine.RaceEngine
import com.jkpark.runwrap.engine.ReportCache
import java.time.Instant
import java.time.ZoneId

/// 로컬 알림 (계획서 M8) — 주간 리포트·수분 알람·대회 접수 알림 예약.
/// 전부 온디바이스 로컬 알림이다 — 원격 푸시·네트워크 전송이 없다.
/// 본문 빌더는 엔진 `object NotificationScheduler`의 순수 함수다 (NotificationContentTests).
///
/// (Android: UNUserNotificationCenter의 예약 요청 → AlarmManager 부정확 알람(setWindow) + NotificationReceiver가 게시.
///  정확 알람 권한을 쓰지 않으므로 발송이 최대 10분(도즈 중엔 유지보수 창까지) 늦을 수 있다.
///  내용은 iOS처럼 예약 시점에 굽는다 — 제목·본문을 PendingIntent extras에 담는다.
///  같은 id(Intent data)로 다시 걸면 기존 예약이 교체된다. 권한 요청(POST_NOTIFICATIONS)은 화면의 결과 계약 몫이다.
///  운동 직후 인사이트(sendWorkoutInsight)는 ios-only — Android는 백그라운드 HC 읽기를 하지 않는다)

/// 지금 알림을 보낼 수 있는지 (이슈 #94) — 시스템 설정에서 거부·해제했으면 false
fun NotificationScheduler.authorizationGranted(context: Context): Boolean =
    context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

/// 포그라운드 복귀 동기화 (이슈 #94) — 시스템 알림 권한이 거부돼 있으면 켜진 토글을 모두 끈다.
/// 토글은 켜져 있는데 알림은 조용히 안 나가는 상태를 남기지 않는다.
/// (Android: 미결정 상태를 따로 알 수 없다 — 아직 묻지 않은 것도 거부로 친다. 토글을 켤 때 화면이 권한을 묻는다)
fun NotificationScheduler.disableTogglesIfDenied(context: Context) {
    if (authorizationGranted(context)) return
    val settings = SettingsStore(context)
    for (key in listOf(NotifyKey.workoutEnabled, NotifyKey.weeklyEnabled, NotifyKey.hydrationEnabled,
                       NotifyKey.raceEnabled)) {
        settings.set(key, false)
    }
    cancelAlarm(context, hydrationId)
}

/// 주간 알림 재예약 — 설정을 읽어 끄면 전부 취소, 켜면 다음 (요일·시)부터 4주치를 예약한다.
/// 첫 회(weeklyId)는 최신 캐시 스냅샷 본문, 이후 3주(weeklyBackupIds)는 수치 없는 백업 본문 (이슈 #94).
/// 포그라운드 진입·설정 변경 때마다 다시 불려 첫 회 본문이 항상 최신 캐시를 반영하고 4주 창이 밀린다
fun NotificationScheduler.rescheduleWeekly(context: Context, now: Instant = Instant.now(),
                                           zone: ZoneId = ZoneId.systemDefault()) {
    for (id in listOf(weeklyId) + weeklyBackupIds) cancelAlarm(context, id)

    val settings = SettingsStore(context)
    if (!settings.bool(NotifyKey.weeklyEnabled)) return
    val weekday = if (settings.contains(NotifyKey.weeklyWeekday)) settings.int(NotifyKey.weeklyWeekday) else 1
    val hour = if (settings.contains(NotifyKey.weeklyHour)) settings.int(NotifyKey.weeklyHour) else 18

    val fireDates = weeklyFireDates(from = now, weekday = weekday, hour = hour,
                                    count = 1 + weeklyBackupIds.size, zone = zone)
    for ((index, local) in fireDates.withIndex()) {
        val fireDate = local.atZone(zone).toInstant()
        // 신선도는 예약 시각이 아니라 실제 발송 시각 기준으로 본다 (이슈 #61)
        val body = if (index == 0) weeklyBody(ReportCache.load(appSupportDir(context)), at = fireDate)
                   else weeklyBackupBody
        val id = if (index == 0) weeklyId else weeklyBackupIds[index - 1]
        scheduleAlarm(context, id, Channel.weekly, "주간 러닝 리포트", body, fireDate)
    }
}

/// 수분 알람 재예약 (계획서 M9) — 오늘 탭이 날씨를 받아올 때마다 당일분 1건을
/// 갱신한다. 백그라운드 예보 폴링은 하지 않는다 — 앱을 열지 않은 날은 알람도 없다.
fun NotificationScheduler.rescheduleHydration(context: Context, forecastMaxC: Double?,
                                              now: Instant = Instant.now(),
                                              zone: ZoneId = ZoneId.systemDefault()) {
    cancelAlarm(context, hydrationId)

    val settings = SettingsStore(context)
    if (forecastMaxC == null) return
    val alarm = hydrationAlarm(
        forecastMaxC = forecastMaxC,
        runHour = if (settings.contains(NotifyKey.runHour)) settings.int(NotifyKey.runHour) else 19,
        enabled = settings.bool(NotifyKey.hydrationEnabled),
        now = now, zone = zone) ?: return
    scheduleAlarm(context, hydrationId, Channel.hydration, "수분 보충", hydrationBody(forecastMaxC),
                  alarm.atZone(zone).toInstant())
}

/// 대회 접수 알림 재예약 (이슈 #172) — 기존 `runwrap.race.` 요청을 전부 거두고,
/// 토글이 켜져 있고 알림 권한이 있을 때만 즐겨찾기 대회분을 다시 건다.
/// 대회 목록 로드·즐겨찾기 토글·설정 토글 때 불린다 (RaceStore.rescheduleRaceAlarms)
/// (Android: AlarmManager는 대기 알람 목록을 줄 수 없어, 건 id를 별도 SharedPreferences `alarms`에 적어 두고 거둔다)
fun NotificationScheduler.rescheduleRaceAlarms(context: Context, entries: List<RaceEngine.Entry>,
                                               favorites: List<Int>, enabled: Boolean, now: Instant) {
    val ledger = context.getSharedPreferences("alarms", Context.MODE_PRIVATE)
    ledger.getStringSet(raceIdsKey, emptySet()).orEmpty()
        .filter { it.startsWith(raceIdPrefix) }
        .forEach { cancelAlarm(context, it) }

    val alarms = if (enabled && authorizationGranted(context)) {
        val favoriteSet = favorites.toSet()
        raceAlarms(favorites = entries.filter { it.id in favoriteSet }, now = now)
    } else emptyList()
    for (alarm in alarms) {
        scheduleAlarm(context, alarm.id, Channel.race, alarm.title, alarm.body, alarm.fire.toInstant())
    }
    ledger.edit().putStringSet(raceIdsKey, alarms.map { it.id }.toSet()).apply()
}

private const val raceIdsKey = "race.ids"

/// 알림 채널 — iOS에는 없는 개념이라 이름은 각 알림의 제목을 그대로 쓴다
private enum class Channel(val title: String) {
    weekly("주간 러닝 리포트"),
    hydration("수분 보충"),
    race("대회 접수 알림"),
}

/// iOS는 분 단위로 정확히 울린다 — 정확 알람 권한 없이 쓸 수 있는 최소 창(10분)
private const val alarmWindowMillis = 10 * 60_000L

private fun alarmIntent(context: Context, id: String): Intent =
    Intent(context, NotificationReceiver::class.java).setData(Uri.fromParts("runwrap", id, null))

private fun scheduleAlarm(context: Context, id: String, channel: Channel, title: String, body: String, at: Instant) {
    val intent = alarmIntent(context, id)
        .putExtra(NotificationReceiver.extraChannel, channel.name)
        .putExtra(NotificationReceiver.extraTitle, title)
        .putExtra(NotificationReceiver.extraBody, body)
    val pending = PendingIntent.getBroadcast(context, id.hashCode(), intent,
                                             PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    context.getSystemService(AlarmManager::class.java)
        .setWindow(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), alarmWindowMillis, pending)
}

private fun cancelAlarm(context: Context, id: String) {
    val pending = PendingIntent.getBroadcast(context, id.hashCode(), alarmIntent(context, id),
                                             PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return
    context.getSystemService(AlarmManager::class.java).cancel(pending)
    pending.cancel()
}

/// 예약된 알람이 울리면 굽혀 둔 제목·본문으로 알림을 게시한다. 탭하면 앱을 연다
class NotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = Channel.valueOf(intent.getStringExtra(extraChannel) ?: return)
        manager.createNotificationChannels(Channel.entries.map {
            NotificationChannel(it.name, it.title, NotificationManager.IMPORTANCE_DEFAULT)
        })
        val body = intent.getStringExtra(extraBody)
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val notification = Notification.Builder(context, channel.name)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(intent.getStringExtra(extraTitle))
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(launch?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE) })
            .setAutoCancel(true)
            .build()
        // 같은 id는 같은 태그 — 다시 울리면 교체된다
        manager.notify(intent.data?.schemeSpecificPart, 0, notification)
    }

    companion object {
        const val extraChannel = "channel"
        const val extraTitle = "title"
        const val extraBody = "body"
    }
}

/// 재부팅하면 AlarmManager 예약이 사라진다(iOS는 유지) — 주간·대회 알림을 다시 건다.
/// 수분 알람은 당일 예보가 있어야 해서 다음 날씨 조회(앱 실행) 때 다시 걸린다
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        NotificationScheduler.rescheduleWeekly(context)
        RaceStore.rescheduleRaceAlarms(context, RaceStore.readLocal(context))
    }
}
