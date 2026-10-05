package com.jkpark.runwrap.screen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jkpark.runwrap.LocalAppContainer
import com.jkpark.runwrap.engine.RaceEngine
import com.jkpark.runwrap.engine.RaceFavorites
import com.jkpark.runwrap.engine.RaceFile
import com.jkpark.runwrap.engine.RaceFormat
import com.jkpark.runwrap.engine.RaceKey
import com.jkpark.runwrap.net.httpGet
import com.jkpark.runwrap.store.RaceStore
import com.jkpark.runwrap.ui.Eyebrow
import com.jkpark.runwrap.ui.RR
import com.jkpark.runwrap.ui.RRIcons
import com.jkpark.runwrap.ui.RegisterBadge
import com.jkpark.runwrap.ui.mono
import com.jkpark.runwrap.ui.rrCard
import com.jkpark.runwrap.ui.rrStatusBarScrim
import com.jkpark.runwrap.ui.rrTracksScroll
import java.io.IOException
import java.net.URL
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/// 대회 — 전국 마라톤 접수 일정 목록 (기획서 §4.14, 계획서 M13-3).
/// 자료는 로드런(roadrun.co.kr)을 매일 배치로 받아 온 Races.json.
/// 접수 상태 판정·정렬·지난 대회 필터는 RaceEngine — 화면은 결과를 그리기만 한다.
/// (Android: 대회 탭 루트. 행을 누르면 `onOpenRace`로 셸이 `RaceDetail` route를 연다.
///  RaceFormat은 엔진, RegisterBadge는 ui/Theme.kt로 옮겼다)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RaceListScreen(
    onOpenRace: (RaceEngine.Entry) -> Unit,
) {
    /// 대회 목록 — 홈 목표 대회 카드와 함께 쓰려고 RootView가 쥔다 (이슈 #172)
    val container = LocalAppContainer.current
    val store = container.raceStore
    val state by store.state.collectAsStateWithLifecycle()
    val lastRefreshFailed by store.lastRefreshFailed.collectAsStateWithLifecycle()
    /// 목록 필터 — 전체 / 접수중(기획서 §4.14) / 즐겨찾기(이슈 #172). 세션 한정
    var filter by rememberSaveable { mutableStateOf(Filter.all) }
    /// 즐겨찾기한 대회 번호 (`[Int]` JSON) — 상세 화면의 별이 쓰고 여기서는 읽기만 한다 (이슈 #172)
    val favoritesRaw by container.settings.rememberSetting(RaceKey.favorites, "")
    /// 키워드 검색 — 대회명·지역·장소·종목을 대상으로 하고 필터와 AND로 겹친다
    var query by rememberSaveable { mutableStateOf("") }
    /// 목록 판정 기준 시각 — 자정·포그라운드 복귀 때 갱신해 D-day·접수 상태가 어제에 머물지 않게 한다 (#145)
    var now by remember { mutableStateOf(Instant.now()) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    /// 상단 스크림 표시 — 본문이 상태바 밑으로 밀려 올라갔을 때만 (이슈 #211)
    val scrolled = listState.rrTracksScroll()

    // 첫 표시·포그라운드 복귀 — 날짜를 다시 잡고, 원격은 6시간이 지났을 때만 다시 받는다 (#145)
    // (Android: iOS `.task`와 scenePhase `.active`를 ON_RESUME 하나로 받는다 — 둘을 따로 걸면 첫 표시에 load가 겹친다)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        now = Instant.now()
        scope.launch { store.load() }
    }
    // 앱을 켜 둔 채 자정을 넘기면 D-day를 다시 계산한다 (NSCalendarDayChanged → ACTION_DATE_CHANGED)
    val context = LocalContext.current
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                now = Instant.now()
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_DATE_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(RR.bg)
            .rrStatusBarScrim(visible = scrolled),
    ) {
        when (val s = state) {
            RaceStore.State.Idle, RaceStore.State.Loading ->
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = RR.text3)
            RaceStore.State.Failed -> FailedNotice { scope.launch { store.load() } }
            is RaceStore.State.Loaded -> {
                var refreshing by remember { mutableStateOf(false) }
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = {
                        scope.launch {
                            refreshing = true
                            try { store.refresh() } finally { refreshing = false }
                        }
                    },
                ) {
                    RaceList(
                        file = s.file, now = now, refreshFailed = lastRefreshFailed,
                        favorites = RaceFavorites.decode(favoritesRaw).toSet(),
                        filter = filter, onFilter = { filter = it },
                        query = query, onQuery = { query = it },
                        listState = listState, onOpenRace = onOpenRace,
                    )
                }
            }
        }
    }
}

private enum class Filter { all, open, favorites }

// MARK: 목록

@Composable
private fun RaceList(
    file: RaceFile,
    now: Instant,
    refreshFailed: Boolean,
    favorites: Set<Int>,
    filter: Filter,
    onFilter: (Filter) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    listState: LazyListState,
    onOpenRace: (RaceEngine.Entry) -> Unit,
) {
    val entries = remember(file, now) { RaceEngine.entries(file.races, now) }
    val openCount = entries.count { it.isOpen }
    val keyword = query.trim()
    val visible = entries
        .filter { entry ->
            when (filter) {
                Filter.all -> true
                Filter.open -> entry.isOpen
                Filter.favorites -> entry.id in favorites
            }
        }
        .filter { keyword.isEmpty() || it.matches(keyword) }
    // iOS `.scrollDismissesKeyboard(.immediately)` — 손으로 스크롤하면 검색 키보드를 내린다
    val focusManager = LocalFocusManager.current
    val dismissKeyboard = remember(focusManager) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) focusManager.clearFocus()
                return Offset.Zero
            }
        }
    }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    LazyColumn(
        Modifier.fillMaxSize().nestedScroll(dismissKeyboard),
        state = listState,
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = top + 8.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(Modifier.padding(bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Eyebrow("대회 일정")
                Text("대회", style = RR.display(33.sp), color = RR.text)
                Text(
                    RaceFormat.caption(
                        openCount = openCount, updatedAt = file.generatedAt,
                        refreshFailed = refreshFailed,
                        stale = RaceFormat.isStale(generatedAt = file.generatedAt, now = now)),
                    style = TextStyle(fontSize = 12.sp),
                    color = RR.text3,
                )
            }
        }

        if (entries.isNotEmpty()) {
            item { SearchField(query, onQuery) }
            item { FilterChips(filter, onFilter) }
        }

        if (visible.isEmpty()) {
            item { EmptyCard(searching = keyword.isNotEmpty(), filter = filter) }
        } else {
            items(visible) { entry ->
                RaceRow(entry, isFavorite = entry.id in favorites) { onOpenRace(entry) }
            }
        }
    }
}

/// 키워드 검색 필드 — 내비게이션 바를 숨긴 화면이라 .searchable 대신 직접 그린다
/// (Android: String 값 BasicTextField라 한글 조합 중인 글자도 값에 들어가 필터가 바로 반영된다)
@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    val focusManager = LocalFocusManager.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(RR.surface2, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(RRIcons.named("magnifyingglass"), contentDescription = null, tint = RR.text3, modifier = Modifier.size(14.dp))
        val textStyle = TextStyle(fontSize = 14.sp, color = RR.text)
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.weight(1f).semantics { contentDescription = "대회 검색" },
            textStyle = textStyle,
            singleLine = true,
            cursorBrush = SolidColor(RR.brand),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            decorationBox = { field ->
                Box {
                    if (query.isEmpty()) Text("대회명·지역·종목 검색", style = textStyle, color = RR.text3)
                    field()
                }
            },
        )
        if (query.isNotEmpty()) {
            // 작은 탭 대상은 Compose가 터치 영역을 48dp까지 넓혀 준다 (iOS rrTapTarget 대응)
            Icon(
                RRIcons.named("xmark.circle.fill"), contentDescription = "검색어 지우기", tint = RR.text3,
                modifier = Modifier.size(15.dp).clickable(role = Role.Button) { onQuery("") },
            )
        }
    }
}

/// 전체/접수중/즐겨찾기 필터 칩 — StatsScreen 지표 전환 칩과 같은 스타일
@Composable
private fun FilterChips(filter: Filter, onFilter: (Filter) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip("전체", selected = filter == Filter.all) { onFilter(Filter.all) }
        FilterChip("접수중", selected = filter == Filter.open) { onFilter(Filter.open) }
        FilterChip("즐겨찾기", selected = filter == Filter.favorites) { onFilter(Filter.favorites) }
    }
}

@Composable
private fun FilterChip(title: String, selected: Boolean, action: () -> Unit) {
    Text(
        title,
        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
        color = if (selected) RR.onBrand else RR.text2,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) RR.brand else RR.surface2)
            .selectable(selected = selected, role = Role.Tab, onClick = action)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun RaceRow(entry: RaceEngine.Entry, isFavorite: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .rrCard()
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // "10/10"처럼 월·일이 모두 두 자리면 46pt에 안 들어가 밀렸다 — 최대 폭 기준으로 고정 (#32)
        Column(
            Modifier.width(54.dp).padding(top = 1.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                RaceFormat.monthDay(entry.raceDate),
                style = mono(15.sp, FontWeight.Bold),
                color = RR.text,
            )
            Text(RaceFormat.weekdayParen(entry.raceDate), style = TextStyle(fontSize = 10.5.sp), color = RR.text3)
        }

        RaceThumbnail(entry.race.imageUrl)

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                entry.race.name,
                style = TextStyle(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold),
                color = RR.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            entry.race.categories?.let { categories ->
                Text(
                    categories.joinToString(" · "),
                    style = TextStyle(fontSize = 11.5.sp), color = RR.text2,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            val placeLine = listOfNotNull(entry.race.region, entry.race.place).joinToString(" · ")
            if (placeLine.isNotEmpty()) {
                Text(
                    placeLine, style = TextStyle(fontSize = 11.sp), color = RR.text3,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            RaceFormat.registerPeriod(entry.race)?.let { period ->
                Text(period, style = TextStyle(fontSize = 11.sp, fontFeatureSettings = "tnum"), color = RR.text3)
            }
        }

        Spacer(Modifier.width(8.dp))

        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                // 즐겨찾기 표시 (이슈 #172) — 토글은 상세 화면의 별에서만 한다
                if (isFavorite) {
                    Icon(RRIcons.named("star.fill"), contentDescription = "즐겨찾기", tint = RR.warn, modifier = Modifier.size(11.dp))
                }
                RegisterBadge(entry.status)
            }
            Text(
                RaceFormat.dDay(entry.dDay),
                style = mono(11.sp, FontWeight.Bold),
                color = RR.text2,
            )
        }
    }
}

// MARK: 빈 목록·실패

/// 카드 소형 썸네일 — 크롤러가 홈페이지에서 뽑은 대표 이미지(imageUrl)를 보여주고,
/// 없거나 로딩 전·실패면 코드로 그린 기본 그림으로 대신한다 (#32)
@Composable
private fun RaceThumbnail(urlString: String?) {
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))) {
        if (!urlString.isNullOrEmpty()) {
            RemoteImage(urlString) { phase ->
                if (phase is RemoteImagePhase.Success) {
                    Image(phase.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    ThumbnailPlaceholder()
                }
            }
        } else {
            ThumbnailPlaceholder()
        }
    }
}

@Composable
private fun ThumbnailPlaceholder() {
    Box(Modifier.fillMaxSize().background(RR.surface2), contentAlignment = Alignment.Center) {
        Icon(RRIcons.named("figure.run"), contentDescription = null, tint = RR.text3, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun EmptyCard(searching: Boolean, filter: Filter) {
    val (title, subtitle) = when {
        searching -> "맞는 대회를 못 찾았어요" to "다른 키워드로 다시 찾아보시겠어요?"
        filter == Filter.open -> "지금 접수받는 대회가 없어요" to "접수가 열리면 접수중 배지로 알려드릴게요."
        filter == Filter.favorites -> "즐겨찾기한 대회가 없어요" to "상세에서 별을 눌러 보세요."
        else -> "지금 보여드릴 대회가 없어요" to "자료가 갱신되면 다시 찾아뵐게요."
    }
    Column(
        Modifier.fillMaxWidth().rrCard().padding(vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            RRIcons.named(if (searching) "magnifyingglass" else if (filter == Filter.favorites) "star" else "flag.slash"),
            contentDescription = null, tint = RR.text3, modifier = Modifier.size(22.dp),
        )
        Text(title, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = RR.text)
        Text(subtitle, style = TextStyle(fontSize = 12.sp), color = RR.text3)
    }
}

@Composable
private fun FailedNotice(onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(RRIcons.named("wifi.slash"), contentDescription = null, tint = RR.text3, modifier = Modifier.size(24.dp))
        Text("대회 소식을 못 가져왔어요", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold), color = RR.text)
        Text("네트워크 연결을 확인하고 다시 시도해 주세요.", style = TextStyle(fontSize = 12.5.sp), color = RR.text2)
        Button(onClick = onRetry, modifier = Modifier.padding(top = 4.dp)) { Text("다시 시도") }
    }
}

private val RaceEngine.Entry.isOpen: Boolean
    get() = status is RaceEngine.RegisterStatus.open

/// 키워드가 대회명·지역·장소·종목 중 하나에라도 들어 있으면 매칭 (한글 친화 비교)
/// (Android: `localizedStandardContains` 대신 대소문자 무시 포함 — 한글은 대소문자가 없어 결과가 같다)
private fun RaceEngine.Entry.matches(keyword: String): Boolean {
    val fields = listOfNotNull(race.name, race.region, race.place) + race.categories.orEmpty()
    return fields.any { it.contains(keyword, ignoreCase = true) }
}

// MARK: 원격 이미지 — iOS AsyncImage 대응 (상세 화면 포스터와 같이 쓴다)

/// iOS `AsyncImagePhase` 대응 — 로딩 중(Empty)·성공·실패
internal sealed interface RemoteImagePhase {
    data object Empty : RemoteImagePhase
    data class Success(val image: ImageBitmap) : RemoteImagePhase
    data object Failure : RemoteImagePhase
}

/// 대회 이미지(Races.json의 imageUrl, 외부 호스트)를 받아 그린다 — 수신 전용, 아무것도 내보내지 않는다.
/// (Android: 이미지 라이브러리 없이 `httpGet` + BitmapFactory. 목록 썸네일과 상세 포스터가 같은 URL이라 메모리 LRU로 한 번만 받는다)
@Composable
internal fun RemoteImage(url: String, content: @Composable (RemoteImagePhase) -> Unit) {
    val phase by produceState<RemoteImagePhase>(RemoteImagePhase.Empty, url) {
        value = remoteImageCache.get(url)?.let { RemoteImagePhase.Success(it) } ?: RemoteImagePhase.Empty
        if (value is RemoteImagePhase.Success) return@produceState
        value = loadRemoteImage(url)?.let { RemoteImagePhase.Success(it) } ?: RemoteImagePhase.Failure
    }
    content(phase)
}

/// 디코드한 이미지 바이트 기준 32MB — ponytail: 고정 상한, 기기 메모리 비례가 필요하면 maxMemory로 바꾼다
private val remoteImageCache = object : LruCache<String, ImageBitmap>(32 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
}

/// 긴 변 상한 — 포스터 원본이 수천 px이어도 화면 폭(약 1440px) 이상으로는 디코드하지 않는다
private const val maxImageSide = 1440

private suspend fun loadRemoteImage(url: String): ImageBitmap? {
    val bytes = try {
        val parsed = URL(url)
        if (parsed.protocol != "http" && parsed.protocol != "https") return null
        val response = httpGet(parsed, timeoutMillis = 60_000)   // 60초는 iOS URLSession.shared 기본값
        if (response.status !in 200 until 300) return null
        response.body
    } catch (_: IOException) {   // 잘못된 URL·연결 실패·시간 초과·평문 HTTP 차단
        return null
    }
    val image = withContext(Dispatchers.Default) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxImageSide) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    } ?: return null
    remoteImageCache.put(url, image)
    return image
}
