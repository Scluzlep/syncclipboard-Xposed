package com.scluzlep.syncclipboard.ui.screens

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.scluzlep.syncclipboard.BuildConfig
import com.scluzlep.syncclipboard.model.PaletteStyleOption
import com.scluzlep.syncclipboard.model.ThemeMode
import com.scluzlep.syncclipboard.ui.theme.SyncInnerShape
import com.scluzlep.syncclipboard.ui.theme.SyncThemeExtras
import com.scluzlep.syncclipboard.ui.theme.paletteStyleDescription
import com.scluzlep.syncclipboard.ui.theme.paletteStyleLabel
import com.scluzlep.syncclipboard.ui.theme.themeModeLabel
import com.scluzlep.syncclipboard.ui.viewmodel.MainViewModel
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val GITHUB_REPO_URL = "https://github.com/Scluzlep/syncclipboard-Xposed"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsTab(
    viewModel: MainViewModel,
    scrollBehavior: ScrollBehavior,
    contentPadding: PaddingValues
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val cfg by viewModel.configFlow.collectAsState()
    val svc by viewModel.xposedServiceState.collectAsState()
    val actionFeedback by viewModel.actionFeedback.collectAsState()

    val colors = MiuixTheme.colorScheme
    val statusColors = SyncThemeExtras.statusColors
    val supportsSystemMonet = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    var pendingExportIncludePasswords by remember { mutableStateOf<Boolean?>(null) }
    var showExportChoiceDialog by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val includePwd = pendingExportIncludePasswords ?: false
        pendingExportIncludePasswords = null
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val payload = viewModel.exportConfigJson(includePasswords = includePwd)
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(payload.toByteArray(Charsets.UTF_8))
            }
            viewModel.setFeedback("已导出配置文件" + if (includePwd) "（含密码）" else "（已脱敏）")
        }.onFailure { err ->
            viewModel.setFeedback("导出失败：${err.message ?: err.javaClass.simpleName}")
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val raw = context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            } ?: error("无法读取所选文件")
            viewModel.importConfigJson(raw)
        }.onFailure { err ->
            viewModel.setFeedback("导入失败：${err.message ?: err.javaClass.simpleName}")
        }
    }

    val lsposedVersionText = if (svc.isServiceBound) {
        val fwName = svc.frameworkName.ifBlank { "LSPosed" }
        val fwVer = svc.frameworkVersion.trim()
        val base = if (fwVer.isNotBlank()) "$fwName $fwVer" else fwName
        if (svc.apiVersion > 0) "$base (API ${svc.apiVersion})" else base
    } else {
        "未连接"
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 20.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "sync_limits_title") {
            SmallTitle(
                text = "同步限制与息屏策略",
                insideMargin = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
            )
        }

        item(key = "sync_limits_card") {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "自动下载大小上限",
                            style = MiuixTheme.textStyles.body1,
                            fontWeight = FontWeight.Medium,
                            color = colors.onSurfaceContainer
                        )
                        SyncStatusBadge(
                            text = "${cfg.autoDownloadMaxSizeMb} MB",
                            containerColor = colors.primaryContainer,
                            contentColor = colors.onPrimaryContainer
                        )
                    }
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(5, 20, 50, 100, 500).forEach { mb ->
                            SyncFilterChip(
                                label = "${mb} MB",
                                selected = cfg.autoDownloadMaxSizeMb == mb,
                                onClick = { viewModel.setAutoDownloadMaxSizeMb(mb) }
                            )
                        }
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    thickness = 0.5.dp,
                    color = colors.dividerLine
                )

                SyncSwitchItem(
                    title = "图片自动写入系统剪贴板",
                    summary = "接收远端图片后通过 FileProvider 授权 SystemUI 写入剪贴板",
                    checked = cfg.writeImageToClipboard,
                    onCheckedChange = viewModel::setWriteImageToClipboard,
                    showDivider = true
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "息屏断开 SignalR 延迟",
                            style = MiuixTheme.textStyles.body1,
                            fontWeight = FontWeight.Medium,
                            color = colors.onSurfaceContainer
                        )
                        SyncStatusBadge(
                            text = if (cfg.screenOffDisconnectDelaySec == 0) {
                                "0s · 不断连"
                            } else {
                                "${cfg.screenOffDisconnectDelaySec}s"
                            },
                            containerColor = if (cfg.screenOffDisconnectDelaySec == 0) {
                                statusColors.okContainer
                            } else {
                                statusColors.neutralContainer
                            },
                            contentColor = if (cfg.screenOffDisconnectDelaySec == 0) {
                                statusColors.onOkContainer
                            } else {
                                statusColors.onNeutralContainer
                            }
                        )
                    }
                    Text(
                        text = "默认 0 秒（息屏持续保持长连接，依靠 Doze/eBPF 白名单与 15s 心跳收发，不弹剪贴板浮窗）",
                        style = MiuixTheme.textStyles.footnote1,
                        color = colors.onSurfaceVariantSummary
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(0 to "0s (不断连)", 30 to "30s", 60 to "60s", 300 to "300s").forEach { (sec, label) ->
                            SyncFilterChip(
                                label = label,
                                selected = cfg.screenOffDisconnectDelaySec == sec,
                                onClick = { viewModel.setScreenOffDisconnectDelaySec(sec) }
                            )
                        }
                    }
                }
            }
        }

        item(key = "otp_title") {
            SmallTitle(
                text = "验证码捕获 (OTP)",
                insideMargin = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
            )
        }

        item(key = "otp_card") {
            Card(modifier = Modifier.fillMaxWidth()) {
                SyncSwitchItem(
                    title = "自动捕获通知验证码",
                    summary = "默认开启：在 system_server 入队脱敏前提取验证码并同步（10s 同哈希防双传）",
                    checked = cfg.otpCaptureEnabled,
                    onCheckedChange = viewModel::setOtpCaptureEnabled,
                    showDivider = true
                )
                SyncSwitchItem(
                    title = "验证码同时写入本机剪贴板",
                    summary = "写入时标记 EXTRA_IS_SENSITIVE 敏感字段，便于其他设备与本机直接粘贴",
                    checked = cfg.writeOtpToClipboard,
                    enabled = cfg.otpCaptureEnabled,
                    onCheckedChange = viewModel::setWriteOtpToClipboard,
                    showDivider = false
                )
            }
        }

        item(key = "theme_title") {
            SmallTitle(
                text = "外观与莫奈取色",
                insideMargin = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
            )
        }

        item(key = "theme_card") {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "主题模式",
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.Medium,
                        color = colors.onSurfaceContainer
                    )
                    val modes = ThemeMode.entries
                    SyncSegmentedControl(
                        options = modes.map { themeModeLabel(it) },
                        selectedIndex = modes.indexOf(cfg.themeMode).coerceAtLeast(0),
                        onSelected = { idx -> viewModel.setThemeMode(modes[idx]) }
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    thickness = 0.5.dp,
                    color = colors.dividerLine
                )

                SyncSwitchItem(
                    title = "莫奈取色",
                    summary = if (supportsSystemMonet) {
                        "随系统壁纸自动取色"
                    } else {
                        "系统低于 Android 12，使用 HyperOS 默认蓝"
                    },
                    checked = cfg.monetEnabled,
                    onCheckedChange = viewModel::setMonetEnabled,
                    showDivider = true
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "配色风格",
                            style = MiuixTheme.textStyles.body1,
                            fontWeight = FontWeight.Medium,
                            color = colors.onSurfaceContainer
                        )
                        SyncStatusBadge(
                            text = paletteStyleLabel(cfg.paletteStyle),
                            containerColor = if (cfg.monetEnabled) colors.primaryContainer else statusColors.neutralContainer,
                            contentColor = if (cfg.monetEnabled) colors.onPrimaryContainer else statusColors.onNeutralContainer
                        )
                    }

                    if (!cfg.monetEnabled) {
                        Text(
                            text = "已关闭莫奈取色，当前使用默认 HyperOS 蓝，开启后生效",
                            style = MiuixTheme.textStyles.footnote1,
                            color = colors.onSurfaceVariantSummary
                        )
                    }

                    val styles = PaletteStyleOption.entries
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        styles.chunked(3).forEach { rowItems ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowItems.forEach { style ->
                                    val selected = cfg.paletteStyle == style
                                    Surface(
                                        onClick = { viewModel.setPaletteStyle(style) },
                                        modifier = Modifier.weight(1f),
                                        shape = SyncInnerShape,
                                        color = if (selected) colors.primaryContainer else colors.surfaceVariant,
                                        border = if (selected) {
                                            BorderStroke(1.dp, colors.primary)
                                        } else {
                                            BorderStroke(0.5.dp, colors.dividerLine)
                                        }
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Text(
                                                text = paletteStyleLabel(style),
                                                style = MiuixTheme.textStyles.footnote1,
                                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (selected) colors.onPrimaryContainer else colors.onSurfaceContainer,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = style.name,
                                                style = MiuixTheme.textStyles.footnote2,
                                                color = if (selected) {
                                                    colors.onPrimaryContainer.copy(alpha = 0.8f)
                                                } else {
                                                    colors.onSurfaceVariantSummary
                                                },
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = SyncInnerShape,
                        color = colors.surfaceVariant
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "配色预览 · ${paletteStyleDescription(cfg.paletteStyle)}",
                                style = MiuixTheme.textStyles.footnote1,
                                color = colors.onSurfaceVariantSummary
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ColorSwatchItem("主色", colors.primary)
                                ColorSwatchItem("主容器", colors.primaryContainer)
                                ColorSwatchItem("辅色", colors.secondary)
                                ColorSwatchItem("强调色", colors.tertiaryContainer)
                                ColorSwatchItem("卡片", colors.surfaceContainerHigh)
                            }
                        }
                    }
                }
            }
        }

        item(key = "app_config_title") {
            SmallTitle(
                text = "桌面入口与配置备份",
                insideMargin = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
            )
        }

        item(key = "app_config_card") {
            Card(modifier = Modifier.fillMaxWidth()) {
                SyncSwitchItem(
                    title = "隐藏桌面图标",
                    summary = "禁用 LauncherAlias 桌面入口（仍可通过 LSPosed 模块页或控制中心磁贴打开）",
                    checked = cfg.hideLauncherIcon,
                    onCheckedChange = viewModel::setHideLauncherIcon,
                    showDivider = true
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { showExportChoiceDialog = true },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                color = colors.secondaryContainer,
                                contentColor = colors.onSecondaryContainer
                            )
                        ) {
                            Text(
                                text = "导出配置 JSON",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = colors.onSecondaryContainer
                            )
                        }
                        Button(
                            onClick = {
                                importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColorsPrimary()
                        ) {
                            Text(
                                text = "导入配置 JSON",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = colors.onPrimary
                            )
                        }
                    }

                    if (actionFeedback.isNotBlank()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = SyncInnerShape,
                            color = statusColors.infoContainer
                        ) {
                            Text(
                                text = actionFeedback,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MiuixTheme.textStyles.footnote1,
                                color = statusColors.onInfoContainer
                            )
                        }
                    }
                }
            }
        }

        item(key = "about_title") {
            SmallTitle(
                text = "关于",
                insideMargin = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
            )
        }

        item(key = "about_card") {
            Card(modifier = Modifier.fillMaxWidth()) {
                BasicComponent(
                    title = "软件名称",
                    summary = "SyncClipboard Xposed"
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    thickness = 0.5.dp,
                    color = colors.dividerLine
                )
                BasicComponent(
                    title = "软件版本",
                    summary = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    thickness = 0.5.dp,
                    color = colors.dividerLine
                )
                BasicComponent(
                    title = "模块开发者",
                    summary = "Scluzlep"
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    thickness = 0.5.dp,
                    color = colors.dividerLine
                )
                BasicComponent(
                    title = "GitHub",
                    summary = GITHUB_REPO_URL,
                    onClick = {
                        runCatching { uriHandler.openUri(GITHUB_REPO_URL) }
                    }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    thickness = 0.5.dp,
                    color = colors.dividerLine
                )
                BasicComponent(
                    title = "LSPosed 版本",
                    summary = lsposedVersionText
                )
            }
        }
    }

    if (showExportChoiceDialog) {
        Dialog(onDismissRequest = { showExportChoiceDialog = false }) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(CardDefaults.CornerRadius),
                color = colors.surfaceContainer,
                border = BorderStroke(0.5.dp, colors.dividerLine)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "导出配置 JSON",
                        style = MiuixTheme.textStyles.title4,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurfaceContainer
                    )
                    Text(
                        text = "请选择导出时是否包含服务器节点密码/Token。脱敏配置再次导入时会自动保留本机已有节点的密码。",
                        style = MiuixTheme.textStyles.body2,
                        color = colors.onSurfaceVariantSummary
                    )
                    Button(
                        onClick = {
                            showExportChoiceDialog = false
                            pendingExportIncludePasswords = false
                            exportLauncher.launch("syncclipboard-config-redacted.json")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColorsPrimary()
                    ) {
                        Text("脱敏导出（不含密码）", fontSize = 13.sp, color = colors.onPrimary)
                    }
                    Button(
                        onClick = {
                            showExportChoiceDialog = false
                            pendingExportIncludePasswords = true
                            exportLauncher.launch("syncclipboard-config-full.json")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            color = statusColors.warningContainer,
                            contentColor = statusColors.onWarningContainer
                        )
                    ) {
                        Text("完整导出（含密码）", fontSize = 13.sp, color = statusColors.onWarningContainer)
                    }
                    Button(
                        onClick = { showExportChoiceDialog = false },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            color = colors.secondaryContainer,
                            contentColor = colors.onSecondaryContainer
                        )
                    ) {
                        Text("取消", fontSize = 13.sp, color = colors.onSecondaryContainer)
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorSwatchItem(label: String, swatchColor: Color) {
    val colors = MiuixTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(swatchColor)
        )
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            fontFamily = FontFamily.Default,
            color = colors.onSurfaceVariantSummary
        )
    }
}
