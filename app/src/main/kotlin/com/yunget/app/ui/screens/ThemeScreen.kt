/*
 * YunGet (云取) - A network drive share-link parser and high-speed downloader for Android.
 * Copyright (C) 2026 CYQawa
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.yunget.app.ui.screens

import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yunget.app.R
import com.yunget.app.data.prefs.SettingsRepository
import com.yunget.app.ui.theme.ThemeController
import com.yunget.app.ui.theme.LocalUiMetrics
import com.yunget.app.ui.theme.autoUiDensity
import com.yunget.app.ui.theme.spatialDefault
import com.yunget.app.ui.components.AppChoiceGroup
import java.util.Locale

/** 种子色仅用于选色样本，页面本身使用 Material 语义颜色。 */
private val presetColors = listOf(
    "云取蓝" to 0xFF2B62ADL, "靛蓝" to 0xFF3F51B5L, "紫色" to 0xFF6750A4L,
    "玫红" to 0xFFC2185BL, "红色" to 0xFFB3261EL, "橙色" to 0xFFF4631CL,
    "金黄" to 0xFFF9A825L, "绿色" to 0xFF38761DL, "青色" to 0xFF00897BL, "天蓝" to 0xFF0288D1L
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ThemeScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val autoDensity = autoUiDensity(configuration.screenWidthDp, configuration.screenHeightDp)
    BackHandler(onBack = onBack)
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var showColorPicker by rememberSaveable { mutableStateOf(false) }
    val effectiveColorMode = if (
        ThemeController.colorMode == 0 && Build.VERSION.SDK_INT < Build.VERSION_CODES.S
    ) 1 else ThemeController.colorMode

    // 保留原图标资源和启动器切换逻辑，只重写选择器。
    val settingsRepo = remember(context) { SettingsRepository(context) }
    var appIconVariant by remember { mutableStateOf(settingsRepo.appIconVariant) }
    val switchAppIcon: (Int) -> Unit = { variant ->
        val pm = context.packageManager
        val main = ComponentName(context, "com.yunget.app.MainActivity")
        val alias = ComponentName(context, "com.yunget.app.MainActivityIcon2")
        if (variant == 1) {
            pm.setComponentEnabledSetting(alias, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            pm.setComponentEnabledSetting(main, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        } else {
            pm.setComponentEnabledSetting(main, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            pm.setComponentEnabledSetting(alias, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
        settingsRepo.appIconVariant = variant
        appIconVariant = variant
    }

    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("主题与外观") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item(key = "ui_style") {
                    ThemePanel("界面风格", "经典版还原 2.7.26 的四个主页面；数据与下载引擎共用") {
                        ThemeChoices(
                            listOf("经典界面", "新版界面"),
                            if (ThemeController.uiStyleMode == 0) 0 else 1,
                        ) {
                            ThemeController.setUiStyleMode(context, it)
                        }
                        SettingsDescriptionsToggle(horizontalPadding = 0.dp)
                    }
                }
                item(key = "preview") { ThemePreview() }
                item(key = "appearance") {
                    ThemePanel("明暗外观", "跟随系统，或固定使用浅色与深色") {
                        ThemeChoices(listOf("跟随系统", "浅色", "深色"), ThemeController.darkMode) {
                            ThemeController.setDarkMode(context, it)
                        }
                    }
                }
                item(key = "color") {
                    ThemePanel(
                        title = "主题颜色",
                        description = when (effectiveColorMode) {
                            0 -> "动态色彩 · 从系统壁纸取色"
                            2 -> "自定义种子色 · ${colorToHex(Color(ThemeController.seedColor.toInt()))}"
                                else -> "云取蓝"
                        }
                    ) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            ThemeSwitchRow(
                                title = "动态色彩",
                                description = "使用壁纸生成的 Material 配色",
                                checked = effectiveColorMode == 0,
                                onCheckedChange = { ThemeController.setColorMode(context, if (it) 0 else 1) }
                            )
                        }
                        if (effectiveColorMode != 0) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth().selectableGroup(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                presetColors.forEach { (name, color) ->
                                    ColorSelectionItem(
                                        color = color, name = name,
                                        selected = (effectiveColorMode == 1 && color == 0xFF2B62ADL) ||
                                            (effectiveColorMode == 2 && ThemeController.seedColor == color),
                                        onClick = {
                                            if (color == 0xFF2B62ADL) ThemeController.setColorMode(context, 1)
                                            else ThemeController.setSeedColor(context, color)
                                        }
                                    )
                                }
                            }
                            FilledTonalButton(
                                onClick = { showColorPicker = true },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Outlined.Palette, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("自定义种子色")
                            }
                            if (effectiveColorMode == 2) {
                                TextButton(
                                    onClick = { ThemeController.setColorMode(context, 1) },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                ) { Text("恢复默认蓝色") }
                            }
                        }
                    }
                }
                item(key = "contrast") {
                    ThemePanel("颜色对比度", "提高文字与背景的明暗差，让内容更容易看清") {
                        ThemeChoices(listOf("标准", "中等", "高"), ThemeController.contrastLevel) {
                            ThemeController.setContrastLevel(context, it)
                        }
                    }
                }
                item(key = "motion") {
                    ThemePanel("界面动效", "选择适合你的过渡节奏") {
                        ThemeChoices(listOf("增强", "标准", "关闭"), ThemeController.motionLevel) {
                            ThemeController.setMotionLevel(context, it)
                        }
                    }
                }
                item(key = "ui_density") {
                    ThemePanel("字号与间距", "按窗口宽高比自动优化，也可以手动调节") {
                        ThemeSwitchRow(
                            title = "自动适配屏占比",
                            description = if (ThemeController.autoUiDensity) autoDensity.reason
                                else "已关闭自动适配，以下数值由你控制",
                            checked = ThemeController.autoUiDensity,
                            onCheckedChange = { enabled ->
                                if (!enabled) {
                                    ThemeController.setUiFontScale(context, autoDensity.fontScale)
                                    ThemeController.setUiSpacingScale(context, autoDensity.spacingScale)
                                }
                                ThemeController.setAutoUiDensity(context, enabled)
                            }
                        )
                        if (!ThemeController.autoUiDensity) {
                            DensitySlider(
                                label = "字号",
                                value = ThemeController.uiFontScale,
                                range = 0.85f..1.10f,
                                steps = 4,
                            ) { ThemeController.setUiFontScale(context, it) }
                            DensitySlider(
                                label = "间距",
                                value = ThemeController.uiSpacingScale,
                                range = 0.85f..1.15f,
                                steps = 5,
                            ) { ThemeController.setUiSpacingScale(context, it) }
                        }
                    }
                }
                item(key = "file_names") {
                    ThemePanel("文件列表") {
                        ThemeSwitchRow(
                            title = "多行显示长文件名",
                            description = if (ThemeController.fileNameMultiLine) "自动折行，完整查看文件名称" else "单行跑马灯显示，列表更紧凑",
                            checked = ThemeController.fileNameMultiLine,
                            onCheckedChange = { ThemeController.setFileNameMultiLine(context, it) }
                        )
                    }
                }
                item(key = "launcher_icon") {
                    ThemePanel("桌面图标") {
                        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AppIconOption(R.drawable.yunget_mark_approved, "新版图标（默认）", appIconVariant == 0, { switchAppIcon(0) }, Modifier.weight(1f))
                            AppIconOption(R.drawable.icon2, "旧版图标", appIconVariant == 1, { switchAppIcon(1) }, Modifier.weight(1f))
                        }
                        Text(
                            "Android 12+ 立即生效；部分设备需回到桌面或重启启动器后查看。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
    if (showColorPicker) {
        ColorPickerDialog(
            initialColor = ThemeController.seedColor,
            onDismiss = { showColorPicker = false },
            onColorSelected = {
                ThemeController.setSeedColor(context, it)
                showColorPicker = false
            }
        )
    }
}

@Composable
private fun ThemePreview() {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primary) {
                Icon(Icons.Outlined.CloudDownload, null, Modifier.padding(9.dp).size(22.dp),
                    tint = MaterialTheme.colorScheme.onPrimary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "Aa  云取  123",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    "链接、文件，随手可取",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ThemePanel(
    title: String, description: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val ui = LocalUiMetrics.current
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = ui.space(4.dp), vertical = ui.space(8.dp)),
            verticalArrangement = Arrangement.spacedBy(ui.space(12.dp))) {
            Column(verticalArrangement = Arrangement.spacedBy(ui.space(2.dp))) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (description != null && com.yunget.app.ui.theme.LocalShowSettingDescriptions.current) {
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            content()
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    }
}

/** 大字体或窄屏切为纵向选项，不压缩标签和触控区域。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeChoices(labels: List<String>, selected: Int, onSelected: (Int) -> Unit) {
    AppChoiceGroup(labels, selected, onSelected)
}

@Composable
private fun <T> themeSelectionSpec(): FiniteAnimationSpec<T> = when (ThemeController.motionLevel) {
    2 -> snap()
    else -> spatialDefault()
}

@Composable
private fun ThemeSwitchRow(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (com.yunget.app.ui.theme.LocalShowSettingDescriptions.current) {
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.heightIn(min = 48.dp))
    }
}

@Composable
private fun DensitySlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    val ui = LocalUiMetrics.current
    Column(verticalArrangement = Arrangement.spacedBy(ui.space(2.dp))) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text("${(value * 100).toInt()}%", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ColorSelectionItem(color: Long, name: String, selected: Boolean, onClick: () -> Unit) {
    val swatch = Color(color.toInt())
    val corner by animateDpAsState(if (selected) 12.dp else 9.dp, animationSpec = themeSelectionSpec(), label = "seedShape")
    Surface(
        modifier = Modifier.widthIn(min = 64.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        shape = RoundedCornerShape(corner),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Surface(shape = CircleShape, color = swatch, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    if (selected) Icon(Icons.Outlined.Check, null, tint = if (swatch.luminance() > 0.5f) Color.Black else Color.White, modifier = Modifier.size(24.dp))
                }
            }
            Text(name, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Material 滑块提供色相、饱和度和明度，与 HEX 输入双向同步。 */
@Composable
private fun ColorPickerDialog(initialColor: Long, onDismiss: () -> Unit, onColorSelected: (Long) -> Unit) {
    val initialHsv = remember(initialColor) { colorToHsv(Color(initialColor.toInt())) }
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initialHsv[1]) }
    var brightness by remember { mutableFloatStateOf(initialHsv[2]) }
    var hexInput by remember { mutableStateOf(colorToHex(Color(initialColor.toInt())).removePrefix("#")) }
    val currentColor = Color.hsv(hue, saturation, brightness)

    fun updateHsv(h: Float = hue, s: Float = saturation, v: Float = brightness) {
        hue = h
        saturation = s
        brightness = v
        hexInput = colorToHex(Color.hsv(h, s, v)).removePrefix("#")
    }

    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("自定义种子色") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(16.dp), color = currentColor, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                        Text(colorToHex(currentColor), style = MaterialTheme.typography.titleMedium, color = if (currentColor.luminance() > 0.5f) Color.Black else Color.White)
                    }
                }
                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { input ->
                        val filtered = input.removePrefix("#").filter { it in "0123456789abcdefABCDEF" }.take(6).uppercase(Locale.ROOT)
                        hexInput = filtered
                        if (filtered.length == 6) {
                            val hsv = colorToHsv(Color((0xFF000000L or filtered.toLong(16)).toInt()))
                            hue = hsv[0]
                            saturation = hsv[1]
                            brightness = hsv[2]
                        }
                    },
                    modifier = Modifier.fillMaxWidth(), label = { Text("HEX 颜色") }, prefix = { Text("#") },
                    supportingText = { Text("输入 6 位十六进制颜色") }, isError = hexInput.length != 6, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
                    shape = RoundedCornerShape(16.dp)
                )
                ThemeColorSlider("色相", "${hue.toInt()}°", hue, 0f..360f) { updateHsv(h = it) }
                ThemeColorSlider("饱和度", "${(saturation * 100).toInt()}%", saturation, 0f..1f) { updateHsv(s = it) }
                ThemeColorSlider("明度", "${(brightness * 100).toInt()}%", brightness, 0f..1f) { updateHsv(v = it) }
            }
        },
        confirmButton = {
            Button(
                onClick = { onColorSelected(currentColor.toArgb().toLong() and 0xFFFFFFFFL) },
                enabled = hexInput.length == 6, modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("应用") }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } }
    )
}

@Composable
private fun ThemeColorSlider(label: String, valueLabel: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(valueLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
    }
}

@Composable
private fun AppIconOption(iconRes: Int, name: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    ) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Image(painterResource(iconRes), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)))
            Text(name, style = MaterialTheme.typography.bodyMedium)
            RadioButton(selected = selected, onClick = null, modifier = Modifier.size(24.dp))
        }
    }
}

private fun colorToHsv(color: Color): FloatArray = FloatArray(3).also {
    android.graphics.Color.colorToHSV(color.toArgb(), it)
}

private fun colorToHex(color: Color): String = String.format(Locale.ROOT, "#%06X", color.toArgb() and 0xFFFFFF)
