package com.jkpark.runwrap.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jkpark.runwrap.LocalAppContainer
import com.jkpark.runwrap.engine.BirdSpecies
import com.jkpark.runwrap.engine.CollectedBird
import com.jkpark.runwrap.ui.Eyebrow
import com.jkpark.runwrap.ui.RR
import com.jkpark.runwrap.ui.RRIcons
import com.jkpark.runwrap.ui.SpeciesBirdView
import com.jkpark.runwrap.ui.rrCard
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/// 도감 — 수집한 새를 성조 이미지 + 당시 목표 + 수집일과 함께 보여준다 (기획서 §5).
///
/// 홈의 새 스테이지 헤더에서 진입한다. 별도 탭은 만들지 않는다 — 5탭 유지가 §6 원칙이다.
/// 전 종을 칸으로 깔아 두고 미수집은 실루엣으로 남긴다: 도감의 재미는 빈 칸에서 온다.
/// (Android: iOS 인라인 내비게이션 타이틀 "도감" 자리에 뒤로가기 + 제목 행을 직접 그린다)
@Composable
fun CollectionScreen(
    onBack: () -> Unit = {},
) {
    val birds by LocalAppContainer.current.collection.birds.collectAsStateWithLifecycle()
    /// 이력 시트를 띄울 종 — 수집된 칸을 탭하면 채워진다 (이슈 #117)
    var historySpecies by rememberSaveable { mutableStateOf<BirdSpecies?>(null) }
    val zone = ZoneId.systemDefault()

    Column(Modifier.fillMaxSize().background(RR.bg).statusBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(44.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(RRIcons.named("chevron.left"), contentDescription = "뒤로", tint = RR.brand)
            }
            Text("도감", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = RR.text,
                modifier = Modifier.align(Alignment.Center))
        }

        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
            // MARK: - 요약
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Eyebrow("도감")
                Text("${birds.size}마리를 키워 냈어요", style = RR.display(22.sp), color = RR.text)
                Text("성조가 된 새는 도감에 남습니다. 새 목표를 잡으면 새 알에서 다시 시작해요.",
                    fontSize = 13.sp, color = RR.text2)
            }

            // 2열 격자 — 6종뿐이라 Lazy 없이 줄로 나눠 그린다
            Column(
                Modifier.padding(horizontal = 20.dp).padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BirdSpecies.entries.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { species ->
                            Cell(species, birds, zone, Modifier.weight(1f)) { historySpecies = species }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }

            Text(
                "새 종류는 키우는 동안 실제로 달린 기록이 정해요. 더 멀리, 더 빨리 달릴수록 큰 새가 됩니다.",
                fontSize = 11.5.sp, color = RR.text2,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 18.dp),
            )
        }
    }

    historySpecies?.let { species ->
        SpeciesHistorySheet(
            species = species,
            birds = birds.filter { it.species == species }.sortedByDescending { it.collectedAt },
            zone = zone,
            onDismiss = { historySpecies = null },
        )
    }
}

// MARK: - 칸

@Composable
private fun Cell(species: BirdSpecies, birds: List<CollectedBird>, zone: ZoneId, modifier: Modifier, onOpen: () -> Unit) {
    // 같은 종을 여러 사이클에서 수집할 수 있다 — 칸에는 가장 최근 것을 세우고
    // 2마리 이상이면 개수를 덧붙인다
    val collected = birds.filter { it.species == species }
    val latest = collected.maxByOrNull { it.collectedAt }
    val label = accessibilityLabel(species, latest, collected.size, zone)

    Column(
        modifier
            .rrCard()
            .clip(RoundedCornerShape(12.dp))
            // 칸에는 최근 1마리만 서므로 같은 종의 이력은 시트로 연다. 미수집 칸은 반응하지 않는다
            // (Android: iOS 접근성 힌트 "탭하면 수집 이력" → TalkBack 클릭 라벨 "수집 이력")
            .then(if (latest != null) Modifier.clickable(role = Role.Button, onClickLabel = "수집 이력", onClick = onOpen) else Modifier)
            .clearAndSetSemantics { contentDescription = label }
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 미수집도 종의 모양·색은 보여준다 — 무엇을 모으는지 보여야 모으고 싶어진다.
        // 흐리게 눌러 두었다가 수집하면 제 색이 된다
        SpeciesBirdView(species, Modifier.size(104.dp).alpha(if (latest != null) 1f else 0.55f))

        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(species.label, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = if (latest != null) RR.text else RR.text2)
                if (collected.size > 1) {
                    Text("×${collected.size}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = RR.brand)
                }
            }

            if (latest != null) {
                Text(latest.goalLabel, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = RR.text2,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(collectedDate(latest.collectedAt, zone), fontSize = 10.5.sp, color = RR.text2)
            } else {
                Text(species.goalHint, fontSize = 11.sp, color = RR.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("아직 비어 있어요", fontSize = 10.5.sp, color = RR.text2, modifier = Modifier.alpha(0.7f))
            }
        }
    }
}

private fun accessibilityLabel(species: BirdSpecies, latest: CollectedBird?, count: Int, zone: ZoneId): String {
    if (latest == null) return "${species.label}, 미수집. ${species.goalHint}"
    val repeated = if (count > 1) " ${count}마리." else ""
    return "${species.label}, 수집함.$repeated ${latest.goalLabel}, " +
        "${collectedDate(latest.collectedAt, zone)} 수집"
}

/// "2026년 8월 13일" — 도감은 이력이라 연도까지 적는다
/// (Android: 로케일 패턴 대신 직접 조립한다 — android/CLAUDE.md 한국어 날짜 규칙)
private fun collectedDate(instant: Instant, zone: ZoneId): String {
    val d = instant.atZone(zone).toLocalDate()
    return "${d.year}년 ${d.monthValue}월 ${d.dayOfMonth}일"
}

/// 같은 종의 수집 이력 — 도감 칸에는 최근 1마리만 서므로 나머지는 여기서 본다 (기획서 §5, 이슈 #117).
///
/// 종별 성조 이미지는 에셋 대기라 목록만 보여준다. 행마다 당시 목표 · 수집일 · 걸린 일수.
/// - birds: 그 종의 수집 이력 — collectedAt 내림차순(최근이 위)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeciesHistorySheet(species: BirdSpecies, birds: List<CollectedBird>, zone: ZoneId, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = RR.bg, dragHandle = null) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            TextButton(
                onClick = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } },
                colors = ButtonDefaults.textButtonColors(contentColor = RR.brand),
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Text("닫기", fontSize = 17.sp)
            }
        }

        Column(
            Modifier.verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Eyebrow(species.label)
                Text("${birds.size}마리를 키워 냈어요", style = RR.display(22.sp), color = RR.text)
            }

            Column(Modifier.fillMaxWidth().rrCard()) {
                birds.forEachIndexed { index, bird ->
                    HistoryRow(bird, zone)
                    if (index < birds.size - 1) {
                        HorizontalDivider(Modifier.padding(start = 16.dp), color = RR.line)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(bird: CollectedBird, zone: ZoneId) {
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(bird.goalLabel, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = RR.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(collectedDate(bird.collectedAt, zone), fontSize = 12.sp, color = RR.text2)
        }
        Spacer(Modifier.width(8.dp))
        Text(cycleText(bird.cycleDays), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = RR.brand)
    }
}

/// "27일 만에" — 시작한 날 바로 성조가 된 경우(0 이하)는 "당일"
private fun cycleText(days: Int): String = if (days > 0) "${days}일 만에" else "당일"
