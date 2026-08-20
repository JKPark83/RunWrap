package com.jkpark.runwrap.ui

import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jkpark.runwrap.ui.theme.RR

/// 세로 스냅 휠 피커 — iOS `Picker(.wheel)` 대응 (Compose에는 기본 휠이 없다).
/// LazyColumn + 스냅 플링으로 3행을 보여주고 가운데 항목을 선택값으로 확정한다.
/// 온보딩 Q8(시:분)·생년 입력, 설정의 목표 기록(시:분:초)이 함께 쓴다.
///
/// 가운데 선택 띠(surface2 박스)는 호출부가 뒤에 깔아야 한다 — 휠 여러 개를
/// 한 Row로 묶을 때 띠가 행 전체를 가로지르게 하기 위해서다 (iOS 시안 동일).
@Composable
fun NumberWheel(
    unit: String,
    range: IntRange,
    value: Int,
    onValue: (Int) -> Unit,
    modifier: Modifier = Modifier,
    rowHeight: Dp = 44.dp,
) {
    val initialIndex = (value - range.first).coerceIn(0, range.last - range.first)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val rowHeightPx = with(LocalDensity.current) { rowHeight.toPx() }
    // 가운데 줄에 온 항목 — 첫 보이는 항목 인덱스 + 오프셋 반올림
    val centered by remember(range) {
        derivedStateOf {
            val extra = if (state.firstVisibleItemScrollOffset > rowHeightPx / 2) 1 else 0
            (range.first + state.firstVisibleItemIndex + extra).coerceIn(range)
        }
    }

    // 스크롤이 멈추면 확정값을 알린다
    LaunchedEffect(state.isScrollInProgress) {
        if (!state.isScrollInProgress && centered != value) onValue(centered)
    }
    // 밖에서 값이 바뀌면(프리셋 칩 탭) 휠을 그 값으로 옮긴다
    LaunchedEffect(value) {
        if (!state.isScrollInProgress && centered != value) {
            state.scrollToItem((value - range.first).coerceIn(0, range.last - range.first))
        }
    }

    LazyColumn(
        state = state,
        flingBehavior = rememberSnapFlingBehavior(state),
        // 위아래 한 행씩 여백을 둬야 첫/끝 항목도 가운데 줄에 올 수 있다
        contentPadding = PaddingValues(vertical = rowHeight),
        modifier = modifier.height(rowHeight * 3),
    ) {
        items(range.last - range.first + 1) { i ->
            val item = range.first + i
            Box(
                Modifier.fillParentMaxWidth().height(rowHeight),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "$item$unit",
                    fontSize = 17.sp,
                    fontWeight = if (item == centered) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (item == centered) RR.text else RR.text3,
                )
            }
        }
    }
}
