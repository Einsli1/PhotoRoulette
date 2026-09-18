package com.einsli.photoroulette.ui

// 设置页（Settings）：外观/数量/提醒/范围/策略/内容等设置项、行组件与相册选择弹窗（AlbumsPicker）。
// 相册弹窗按设计图（选择要扫描的相册：标题 + 相册勾选列表 + 底部「全选/取消/确定」一行）自绘 Dialog，
// 而非 AlertDialog——M3 的 AlertDialog 按钮槽只能放尾部胶囊，塞不下左对齐的「全选」。
import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.einsli.photoroulette.PhotoViewModel
import com.einsli.photoroulette.data.AppSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun Settings(settings: AppSettings, vm: PhotoViewModel, scrollState: ScrollState, savedScroll: Int, openTrash: () -> Unit) {
    val dc = designColors()
    var showDatePicker by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showAlbumPicker by remember { mutableStateOf(false) }
    val dateFormatter = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    // Restore the exact scroll position saved when leaving this page. The ScrollState is
    // re-created via its Saver, but it can be clamped before the content is laid out, so we
    // wait for the layout (maxValue is valid) and then re-apply the snapshot.
    LaunchedEffect(Unit) {
        if (savedScroll > 0) {
            withFrameNanos { }
            if (scrollState.maxValue > 0) {
                scrollState.scrollTo(savedScroll.coerceAtMost(scrollState.maxValue))
            }
        }
    }

    // Spring pull in both directions; engages only at the scroll limits (nested scroll also
    // swallows the platform stretch overscroll).
    SpringPullBox(
        modifier = Modifier.fillMaxSize(),
        pullAtTop = { scrollState.value.toFloat() },
        pullAtBottom = { (scrollState.maxValue - scrollState.value).coerceAtLeast(0).toFloat() },
    ) {
    Column(
        Modifier
            .fillMaxSize()
            .background(dc.pageBg)
            .verticalScroll(scrollState, flingBehavior = rememberGentleFlingBehavior())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(18.dp))
        Text("设置", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = dc.ink)
        Spacer(Modifier.height(14.dp))

        // ── 外观 ──
        SettingCard {
            SettingValueRow(label = "外观") {
                SettingOptionPicker(
                    options = listOf("跟随系统" to 0, "浅色" to 1, "深色" to 2),
                    selectedValue = settings.darkMode,
                    onSelect = vm::setDarkMode
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // ── 每次整理数量: 药丸触发悬浮滚轮(5–100, 步进1)——浮层不撑高卡片 ──
        SettingCard {
            SettingValueRow(label = "每次整理数量") {
                SettingValueWheel(
                    title = "每次整理数量",
                    label = "${settings.dailyCount} 张",
                    values = (5..100).toList(),
                    selected = settings.dailyCount,
                    unit = "张",
                    onSelect = vm::setDailyCount,
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // ── 每日提醒: 与数量选择同款的悬浮卡片, 双滚轮(时/分) ──
        SettingCard {
        SettingValueRow(label = "每日提醒") {
            SettingTimeWheel(
                label = "${settings.reminderHour.toString().padStart(2, '0')}:${settings.reminderMinute.toString().padStart(2, '0')}",
                title = "每日提醒",
                hour = settings.reminderHour,
                minute = settings.reminderMinute,
                onHour = { vm.setReminderHour(it) },
                onMinute = { vm.setReminderMinute(it) },
            )
        }
            // SCHEDULE_EXACT_ALARM 在 Android 14+ 默认拒绝,没有它提醒可能延迟几分钟。
            // 点这行进系统设置授权,回来(onResume)后会自动改用精确闹钟。
            val ctx = LocalContext.current
            val exactAlarmAvailable = runCatching { ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() }.getOrDefault(false)
            if (!exactAlarmAvailable) {
                HorizontalDivider(color = dc.track.copy(alpha = 0.6f))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                        runCatching {
                            ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
                        }
                    }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("提醒精确到分钟", fontSize = 14.sp, color = dc.ink)
                        Text("未授权时提醒可能延迟,点击去系统设置开启", fontSize = 11.sp, color = dc.labelGray)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = dc.labelGray, modifier = Modifier.size(18.dp))
                }
            }
            // POST_NOTIFICATIONS 在 Android 13+ 需用户授予;被拒时到点通知静默不发(notify 不报错)。
            // 同款提示行:点这行进系统通知设置开启,回来(onResume)后自动生效。
            val notificationsEnabled = runCatching {
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(true)
            if (!notificationsEnabled) {
                HorizontalDivider(color = dc.track.copy(alpha = 0.6f))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                        runCatching {
                            ctx.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                            )
                        }
                    }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("开启通知提醒", fontSize = 14.sp, color = dc.ink)
                        Text("未授权时到点收不到提醒,点击去系统设置开启", fontSize = 11.sp, color = dc.labelGray)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = dc.labelGray, modifier = Modifier.size(18.dp))
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ── 照片范围 ──
        SettingCard {
            SettingValueRow(label = "照片范围") {
                SettingOptionPicker(
                    options = listOf(
                        "全部照片" to "all",
                        "最近一年" to "lastYear",
                        "一年以前" to "beforeLastYear",
                        "自定义时间" to "custom",
                    ),
                    selectedValue = settings.photoRange,
                    onSelect = vm::setPhotoRange
                )
            }
            if (settings.photoRange == "custom") {
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (settings.customRangeStart > 0) "从 ${dateFormatter.format(Date(settings.customRangeStart))} 起" else "尚未选择起始日期",
                        fontSize = 12.sp,
                        color = dc.slate
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { showDatePicker = true }) { Text("选择日期", color = dc.accentText, fontSize = 12.sp) }
                }
            }
            HorizontalDivider(color = dc.track.copy(alpha = 0.6f))
            // 选择相册: resean only adds newly-included photos to the pool — it never clears
            // the organizing history (that's what 重置整理记录 does).
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { showAlbumPicker = true }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("选择相册", fontSize = 14.sp, color = dc.ink, modifier = Modifier.weight(1f))
                Text(
                    if (settings.includedAlbums.isEmpty()) "全部相册" else "已选 ${settings.includedAlbums.size} 个相册",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = dc.accentText
                )
                Spacer(Modifier.width(2.dp))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = dc.labelGray, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(12.dp))

        // ── 整理策略 ──
        SettingCard {
            SettingValueRow(label = "整理策略") {
                SettingOptionPicker(
                    options = listOf(
                        "随机" to "random",
                        "优先旧照片" to "oldest",
                        "优先大照片" to "largest",
                    ),
                    selectedValue = settings.strategy,
                    onSelect = vm::setStrategy
                )
            }
            Spacer(Modifier.height(2.dp))
            Text("策略将在下一次「开始整理」时生效", fontSize = 11.sp, color = dc.labelGray)
        }
        Spacer(Modifier.height(12.dp))

        // ── 内容 ──
        SettingCard {
            Text("内容", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = dc.slate)
            Spacer(Modifier.height(2.dp))
            var videos by remember(settings) { mutableStateOf(settings.includeVideos) }
            SettingSwitchRow("包含视频", videos, { videos = it; vm.setIncludeVideos(it) }, dc)
            HorizontalDivider(color = dc.track.copy(alpha = 0.6f))
            var screenshots by remember(settings) { mutableStateOf(settings.includeScreenshots) }
            SettingSwitchRow("包含截图", screenshots, { screenshots = it; vm.setIncludeScreenshots(it) }, dc)
        }
        Spacer(Modifier.height(12.dp))

        // ── 其他 ──
        SettingCard {
            Text("其他", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = dc.slate)
            Spacer(Modifier.height(2.dp))
            SettingNavRow("回收站", openTrash, dc)
            HorizontalDivider(color = dc.track.copy(alpha = 0.6f))
            SettingNavRow("重置整理记录", { showResetConfirm = true }, dc)
        }
        Spacer(Modifier.height(24.dp))
        }
    }

    if (showDatePicker) {
        val dateState = rememberDatePickerState(initialSelectedDateMillis = if (settings.customRangeStart > 0) settings.customRangeStart else null)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { vm.setCustomRangeStart(it) }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } }
        ) { DatePicker(state = dateState) }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("重置整理记录？") },
            text = { Text("所有已经处理过的照片将重新进入随机池。\n不会删除照片。") },
            confirmButton = {
                TextButton(
                    onClick = { showResetConfirm = false; vm.reset() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("重置") }
            },
            dismissButton = { TextButton(onClick = { showResetConfirm = false }) { Text("取消") } }
        )
    }

    if (showAlbumPicker) {
        AlbumsPicker(vm, settings, onClose = { showAlbumPicker = false })
    }
}

@Composable
private fun SettingCard(content: @Composable ColumnScope.() -> Unit) {
    val dc = designColors()
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = dc.card),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), content = content)
    }
}

@Composable
private fun SettingSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, dc: DesignColors) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, color = dc.ink)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = dc.accent,
                checkedTrackColor = dc.accent.copy(alpha = 0.4f),
                uncheckedThumbColor = dc.labelGray,
                uncheckedTrackColor = dc.track
            )
        )
    }
}

@Composable
private fun SettingNavRow(label: String, onClick: () -> Unit, dc: DesignColors) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, color = dc.ink)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = dc.labelGray, modifier = Modifier.size(20.dp))
    }
}

/** One settings row: label on the left, the value/control on the right. With [onClick] the
 *  whole row is tappable and shows a chevron that rotates when [expanded]. */
@Composable
private fun SettingValueRow(
    label: String,
    onClick: (() -> Unit)? = null,
    expanded: Boolean = false,
    trailing: @Composable () -> Unit,
) {
    val dc = designColors()
    val chevronRotation by animateFloatAsState(if (expanded) 90f else 0f, tween(200), label = "chevron")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, color = dc.ink, modifier = Modifier.weight(1f))
        trailing()
        if (onClick != null) {
            Spacer(Modifier.width(2.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null,
                tint = if (expanded) dc.accent else dc.labelGray,
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer { rotationZ = chevronRotation }
            )
        }
    }
}

/** 悬浮卡片式单选下拉：与滚轮选择器同一套卡片和展开/收回动画，点选项即确认并收回。 */
@Composable
private fun <T> SettingOptionPicker(
    options: List<Pair<String, T>>,
    selectedValue: T,
    onSelect: (T) -> Unit,
) {
    val dc = designColors()
    val selectedLabel = options.firstOrNull { it.second == selectedValue }?.first ?: options.first().first
    SettingPopupPicker(label = selectedLabel, title = null, cardWidth = 0.dp, showConfirm = false) { dismiss ->
        Column(Modifier.padding(vertical = 2.dp)) {
            options.forEach { (display, value) ->
                val selected = value == selectedValue
                Row(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            onSelect(value)
                            dismiss()
                        }
                        .padding(horizontal = 8.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        display,
                        fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) dc.accentText else dc.ink,
                    )
                    Spacer(Modifier.width(10.dp))
                    if (selected) Icon(Icons.Default.Check, null, tint = dc.accent, modifier = Modifier.size(15.dp))
                }
            }
        }
    }
}

/** 每次整理数量：单滚轮（悬浮卡片内）。 */
@Composable
private fun SettingValueWheel(
    title: String,
    label: String,
    values: List<Int>,
    selected: Int,
    unit: String,
    onSelect: (Int) -> Unit,
) {
    SettingPopupPicker(label = label, title = title, cardWidth = 132.dp) {
        StyledNumberWheel(values = values, selected = selected, unit = unit, onSelected = onSelect)
    }
}

/** 每日提醒：双滚轮（时/分），与数量选择同款悬浮卡片。 */
@Composable
private fun SettingTimeWheel(
    title: String,
    label: String,
    hour: Int,
    minute: Int,
    onHour: (Int) -> Unit,
    onMinute: (Int) -> Unit,
) {
    val dc = designColors()
    SettingPopupPicker(label = label, title = title) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            StyledNumberWheel(
                values = (0..23).toList(),
                selected = hour,
                unit = "",
                format = { it.toString().padStart(2, '0') },
                onSelected = onHour,
                modifier = Modifier.weight(1f),
            )
            Text(":", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = dc.ink)
            StyledNumberWheel(
                values = (0..59).toList(),
                selected = minute,
                unit = "",
                format = { it.toString().padStart(2, '0') },
                onSelected = onMinute,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 设置页的相册勾选弹窗（设计图：选择要扫描的相册.png）。
 *
 *  布局自上而下：标题 → 可滚动的相册勾选列表 → 底部一行「全选 + 取消 + 确定」。
 *  底部「全选」是列表的全选/取消全选开关：勾选态由已选项推导（全选=勾、一个都没选=空、
 *  部分选中=半选态），点一下按「是否已全选」整批开或关，而不是无脑反转（半选时也应补齐）。 */
@Composable internal fun AlbumsPicker(viewModel: PhotoViewModel, settings: com.einsli.photoroulette.data.AppSettings, onClose: () -> Unit) {
    val dc = designColors()
    var albums by remember { mutableStateOf<List<String>>(emptyList()) }
    var selected by remember { mutableStateOf(settings.includedAlbums.toSet()) }
    LaunchedEffect(Unit) { albums = viewModel.availableAlbums() }
    val allSelected = albums.isNotEmpty() && selected.containsAll(albums)
    val groupState = when {
        allSelected -> ToggleableState.On
        albums.none { selected.contains(it) } -> ToggleableState.Off
        else -> ToggleableState.Indeterminate
    }
    // 半选态也应补齐而不是“反转成空”，所以判据是「是否已全选」而非当前勾选值。
    val toggleAll = { selected = if (allSelected) emptySet() else albums.toSet() }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = dc.card),
            elevation = CardDefaults.cardElevation(0.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        ) {
            Text(
                "选择要扫描的相册",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = dc.ink,
                modifier = Modifier.padding(start = 20.dp, top = 22.dp, end = 20.dp, bottom = 6.dp),
            )
            // 列表吃掉卡片剩余高度（weight）：相册多时能滚到「DCIM/」，相册少时卡片自然收短，
            // 任何屏幕高度下底部那一行都在卡片内、不会被挤出去。
            if (albums.isEmpty()) {
                Text("未发现相册", fontSize = 14.sp, color = dc.slate, modifier = Modifier.padding(20.dp))
            } else {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f, fill = false).padding(horizontal = 12.dp)) {
                    items(albums) { a ->
                        val checked = selected.contains(a)
                        // 整行可点：勾选框与目录名同属一个开关，不用瞄准 16dp 的小方块。
                        // vertical 12dp + 16dp 勾选框 ≈ 行高 45dp，与设计图的行距一致（不加行间分隔线）。
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .toggleable(
                                    value = checked,
                                    role = Role.Checkbox,
                                    onValueChange = { c -> selected = if (c) selected + a else selected - a },
                                )
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PlCheckbox(checked = checked)
                            Spacer(Modifier.width(18.dp))
                            Text(a, fontSize = 15.sp, color = dc.ink)
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .toggleable(
                            value = groupState,
                            role = Role.Checkbox,
                            onValueChange = { toggleAll() },
                        )
                        .padding(horizontal = 2.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlCheckbox(checked = groupState)
                    Spacer(Modifier.width(18.dp))
                    Text("全选", fontSize = 15.sp, color = dc.ink)
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onClose,
                    modifier = Modifier.widthIn(min = 64.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                ) { Text("取消", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = dc.accentText) }
                TextButton(
                    onClick = {
                        // Save the selection and rescan — updates the photo total only, keeps records.
                        viewModel.updateAlbums(selected.toList())
                        onClose()
                    },
                    modifier = Modifier.widthIn(min = 64.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                ) { Text("确定", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = dc.accentText) }
            }
        }
    }
}

/** 小号圆角勾选框，尺寸/圆角/对勾粗细取自设计图（44px @2.75x ≈ 16dp）。三态：
 *  勾选 = 品牌色实底 + 白色对勾；半选 = 品牌色实底 + 白色横杠；未选 = 透明底 + 细描边。
 *  自身不可点——点击交给外层整行 [toggleable]，避免行与方块两处手势打架。 */
@Composable
private fun PlCheckbox(checked: ToggleableState, modifier: Modifier = Modifier) {
    val dc = designColors()
    val on = checked != ToggleableState.Off
    Box(
        modifier
            .size(16.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (on) dc.accent else Color.Transparent)
            .border(1.5.dp, if (on) dc.accent else dc.labelGray.copy(alpha = 0.7f), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        when (checked) {
            ToggleableState.On -> Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(11.dp))
            ToggleableState.Indeterminate -> Box(
                Modifier.width(8.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(Color.White)
            )
            ToggleableState.Off -> Unit
        }
    }
}
