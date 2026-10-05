package com.jkpark.runwrap.ui

import android.graphics.ImageDecoder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jkpark.runwrap.engine.Shoe
import com.jkpark.runwrap.store.ShoeImageStore
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/// 러닝화 편집 시트 (이슈 #171) + 사진 슬롯 (이슈 #206).
/// 설정 화면뿐 아니라 홈 카드·러닝 후 질문 시트에서도 같은 시트를 띄우려고 SettingsScreen에서 꺼냈다.
/// 사진은 저장을 누를 때만 파일로 쓴다 — 닫기로 나가면 아무것도 남기지 않는다

// MARK: - 러닝화 사진 (이슈 #206)

/// 등록한 사진이 있으면 둥근 사각형 안에 통째로 보이고, 없거나 못 읽으면 기본 일러스트(ShoeView). 프레임은 호출부가 정한다
@Composable
fun ShoeImage(shoe: Shoe, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val image = remember(shoe.imageFile) {
        shoe.imageFile?.let { ShoeImageStore.file(context, it) }?.let { decodePreview(it.readBytes()) }
    }
    if (image != null) ShoePhoto(image, modifier) else ShoeView(modifier)
}

/// 사진을 주어진 프레임 안에 통째로 맞춘다 — ShoeImage와 편집 시트의 새로 고른 사진 미리보기가 같이 쓴다.
/// 러닝화 사진은 대개 가로로 길어서, 꽉 채워 자르면 앞코·뒤꿈치가 잘린다. 남는 위아래는 surface로 채운다
@Composable
private fun ShoePhoto(image: ImageBitmap, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(RR.surface)) {
        Image(image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
    }
}

/// (Android: `UIImage(data:)` 대응. EXIF 회전을 반영하고, 고화소 원본(50MP 등)이 Canvas 비트맵 한도를
///  넘지 않게 긴 변 600px 근처로 줄여 읽는다 — 저장본(ShoeImageStore)과 같은 크기라 화질 차이는 없다)
private fun decodePreview(data: ByteArray): ImageBitmap? = try {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(data))) { decoder, info, _ ->
        decoder.setTargetSampleSize(max(1, max(info.size.width, info.size.height) / ShoeImageStore.maxPixelSize))
    }.asImageBitmap()
} catch (_: Exception) {
    null
}

// MARK: - 러닝화 편집 시트 (이슈 #171)

/// 사진·이름·등록 전 누적 거리·교체 기준·기본 지정·은퇴·삭제. 신규 등록에서는 은퇴·삭제를 숨긴다
/// (Android: 시트를 이 함수가 직접 띄운다 — 호출부는 보일 때만 부르고 `onDismiss`에서 내린다.
///  내비게이션 툴바 → 시트 상단 행, PhotosPicker → 시스템 사진 선택기, 삭제 확인 → AlertDialog)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoeEditSheet(
    shoe: Shoe,
    isNew: Boolean,
    isDefault: Boolean,
    onSave: (Shoe, Boolean) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by remember { mutableStateOf(shoe) }
    var isDefaultOn by remember { mutableStateOf(isDefault) }
    var confirmsDelete by remember { mutableStateOf(false) }
    /// 사진 (이슈 #206) — 고른 사진은 저장 전까지 메모리에만 둔다. clearsPhoto는 기존 사진을 지우기로 했다는 표시
    var loadingPhoto by remember { mutableStateOf(false) }
    var pickedData by remember { mutableStateOf<ByteArray?>(null) }
    var clearsPhoto by remember { mutableStateOf(false) }
    val pickedImage = remember(pickedData) { pickedData?.let(::decodePreview) }

    val trimmedName = draft.name.trim()
    /// 새로 고른 사진이 있거나, 지우지 않은 기존 사진이 있는가
    val hasPhoto = pickedData != null || (!clearsPhoto && draft.imageFile != null)

    fun dismiss() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        loadingPhoto = true
        scope.launch {
            val data = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (data != null) {
                pickedData = data
                clearsPhoto = false
            }
            loadingPhoto = false
        }
    }
    val pickPhoto = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = RR.bg,
        dragHandle = null,
    ) {
        Column(Modifier.fillMaxHeight()) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                TextButton(
                    onClick = ::dismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = RR.brand),
                    modifier = Modifier.align(Alignment.CenterStart),
                ) {
                    Text("닫기", style = TextStyle(fontSize = 17.sp))
                }
                Text(
                    if (isNew) "러닝화 추가" else "러닝화 편집",
                    style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                    color = RR.text,
                    modifier = Modifier.align(Alignment.Center),
                )
                TextButton(
                    onClick = {
                        var saved = draft.copy(name = trimmedName)
                        // 사진은 저장할 때만 파일에 반영한다 (이슈 #206) — 축소 실패면 기존 사진을 그대로 둔다
                        val file = pickedData?.let { ShoeImageStore.save(context, it, saved.id) }
                        if (file != null) {
                            saved.imageFile?.let { ShoeImageStore.remove(context, it) }
                            saved = saved.copy(imageFile = file)
                        } else if (clearsPhoto) {
                            saved.imageFile?.let {
                                ShoeImageStore.remove(context, it)
                                saved = saved.copy(imageFile = null)
                            }
                        }
                        onSave(saved, isDefaultOn && !saved.isRetired)
                        dismiss()
                    },
                    enabled = trimmedName.isNotEmpty() && !loadingPhoto,   // 사진을 읽는 중에는 저장을 막는다
                    colors = ButtonDefaults.textButtonColors(contentColor = RR.brand, disabledContentColor = RR.text3),
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    Text("저장", style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold))
                }
            }

            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 26.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 사진 슬롯 (이슈 #206) — 고른 사진 > 기존 사진 > 기본 일러스트 순. 탭하면 사진 선택(권한 불필요)
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val slot = Modifier
                        .size(120.dp)
                        .clickable(role = Role.Button, onClick = pickPhoto)
                        .semantics { contentDescription = if (hasPhoto) "사진 바꾸기" else "사진 선택" }
                    when {
                        pickedImage != null -> ShoePhoto(pickedImage, slot)
                        clearsPhoto -> ShoeView(slot)
                        else -> ShoeImage(draft, slot)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BorderedButton(if (hasPhoto) "사진 바꾸기" else "사진 선택", RR.brand, onClick = pickPhoto)
                        if (hasPhoto) {
                            BorderedButton("사진 지우기", RR.text2) {
                                pickedData = null
                                clearsPhoto = true
                            }
                        }
                    }
                }
                Field("이름") {
                    BasicTextField(
                        value = draft.name,
                        onValueChange = { draft = draft.copy(name = it) },
                        textStyle = TextStyle(fontSize = 15.sp, color = RR.text),
                        cursorBrush = SolidColor(RR.brand),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        decorationBox = { inner ->
                            if (draft.name.isEmpty()) {
                                Text("예: 페가수스 41", style = TextStyle(fontSize = 15.sp), color = RR.text3)
                            }
                            inner()
                        },
                    )
                }
                Field("거리") {
                    SettingRow("등록 전 누적 ${draft.startKm.toInt()} km", "앱에 등록하기 전에 이미 달린 거리예요") {
                        RRStepper(draft.startKm, 0.0..2_000.0, step = 10.0) { draft = draft.copy(startKm = it) }
                    }
                    HorizontalDivider(thickness = Dp.Hairline, color = RR.line)
                    SettingRow("교체 기준 ${draft.replaceKm.toInt()} km", "누적이 이 거리를 넘으면 홈에서 알려드려요") {
                        RRStepper(draft.replaceKm, 300.0..1_200.0, step = 50.0) { draft = draft.copy(replaceKm = it) }
                    }
                }
                Field("상태") {
                    ToggleRow(
                        "기본 신발로 지정", "새 러닝이 자동으로 이 신발에 기록돼요",
                        isOn = isDefaultOn, enabled = !draft.isRetired,
                    ) { isDefaultOn = it }
                    if (!isNew) {
                        HorizontalDivider(thickness = Dp.Hairline, color = RR.line)
                        ToggleRow("은퇴", "목록 끝으로 옮기고 러닝 배정 후보에서 빼요", isOn = draft.isRetired) { retired ->
                            draft = draft.copy(isRetired = retired)
                            // 은퇴한 신발은 자동 배정 대상이 아니다 — 기본 지정을 함께 푼다
                            if (retired) isDefaultOn = false
                        }
                    }
                }
                if (!isNew) {
                    Text(
                        "러닝화 삭제",
                        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
                        color = RR.dang,
                        modifier = Modifier
                            .fillMaxWidth()
                            .rrCard()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(role = Role.Button) { confirmsDelete = true }
                            .padding(vertical = 14.dp),
                    )
                }
            }
        }
    }

    if (confirmsDelete) {
        AlertDialog(
            onDismissRequest = { confirmsDelete = false },
            title = { Text("이 러닝화를 삭제할까요?") },
            text = { Text("배정된 러닝은 '없음'으로 바뀌어요. 그만 신는다면 은퇴가 기록을 남겨요.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmsDelete = false
                        onDelete()
                        dismiss()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = RR.dang),
                ) { Text("삭제") }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmsDelete = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = RR.brand),
                ) { Text("취소") }
            },
            containerColor = RR.surface,
            titleContentColor = RR.text,
            textContentColor = RR.text2,
        )
    }
}

/// iOS `.buttonStyle(.bordered).tint(…)` 대응 — 옅은 tint 배경 + tint 글자
@Composable
private fun BorderedButton(title: String, tint: Color, onClick: () -> Unit) {
    Text(
        title,
        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
        color = tint,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(8.dp))
            .background(tint.copy(alpha = 0.15f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/// stepperRow·toggleRow 공통 — 제목·설명 + 오른쪽 컨트롤
@Composable
private fun SettingRow(
    title: String, caption: String, modifier: Modifier = Modifier, control: @Composable () -> Unit,
) {
    Row(
        modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = RR.text)
            Text(caption, style = TextStyle(fontSize = 12.5.sp), color = RR.text2)
        }
        Spacer(Modifier.width(8.dp))
        control()
    }
}

/// (Android: 행 전체를 토글 대상으로 둔다 — 스위치 단독보다 터치 영역이 넓고, 접근성 라벨이 행의 글자가 된다)
@Composable
private fun ToggleRow(label: String, caption: String, isOn: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    SettingRow(
        label, caption,
        Modifier
            .toggleable(isOn, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .alpha(if (enabled) 1f else 0.45f),
    ) {
        Switch(
            checked = isOn,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = RR.onBrand,
                checkedTrackColor = RR.brand,
                uncheckedThumbColor = RR.surface,
                uncheckedTrackColor = RR.barFill,
                uncheckedBorderColor = RR.line,
            ),
        )
    }
}

@Composable
private fun Field(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            title,
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold),
            color = RR.text2,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Column(Modifier.fillMaxWidth().rrCard(), content = content)
    }
}
