package com.scluzlep.syncclipboard.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scluzlep.syncclipboard.model.AppConfig
import com.scluzlep.syncclipboard.model.ConnectionState
import com.scluzlep.syncclipboard.model.EngineStatusSnapshot
import com.scluzlep.syncclipboard.model.ServerType
import com.scluzlep.syncclipboard.service.XposedServiceState
import com.scluzlep.syncclipboard.ui.theme.SyncInnerShape
import com.scluzlep.syncclipboard.ui.theme.SyncThemeExtras
import com.scluzlep.syncclipboard.ui.viewmodel.MainTab
import com.scluzlep.syncclipboard.ui.viewmodel.MainViewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun OverviewTab(
    viewModel: MainViewModel,
    scrollBehavior: ScrollBehavior,
    contentPadding: PaddingValues
) {
    val config by viewModel.configFlow.collectAsState()
    val status by viewModel.statusFlow.collectAsState()
    val svcState by viewModel.xposedServiceState.collectAsState()
    val isScopeRequesting by viewModel.isScopeRequesting.collectAsState()
    val isHotReloading by viewModel.isHotReloading.collectAsState()
    val isSyncBusy by viewModel.isSyncBusy.collectAsState()
    val actionFeedback by viewModel.actionFeedback.collectAsState()

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
        item(key = "xposed_status_title") {
            SmallTitle(
                text = "LSPosed API 102 与系统握手",
                insideMargin = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
            )
        }

        item(key = "xposed_status_card") {
            LspModuleStatusCard(
                status = status,
                svcState = svcState,
                isScopeRequesting = isScopeRequesting,
                isHotReloading = isHotReloading,
                actionFeedback = actionFeedback,
                onRequestScope = viewModel::requestXposedScopes,
                onHotReload = viewModel::hotReloadModule
            )
        }

        item(key = "active_node_title") {
            SmallTitle(
                text = "活跃节点与同步控制",
                insideMargin = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
            )
        }

        item(key = "active_node_card") {
            ActiveNodeCard(
                config = config,
                status = status,
                isSyncBusy = isSyncBusy,
                onPushNow = viewModel::pushCurrentClipboard,
                onPullNow = viewModel::pullLatestFromRemote,
                onSyncNow = viewModel::syncNow,
                onOpenNodes = { viewModel.selectTab(MainTab.NODES) }
            )
        }

        item(key = "quick_switches_title") {
            SmallTitle(
                text = "快捷开关",
                insideMargin = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
            )
        }

        item(key = "quick_switches_card") {
            Card(modifier = Modifier.fillMaxWidth()) {
                SyncSwitchItem(
                    title = "自动同步总开关",
                    summary = "启用后在后台自动同步本机与当前活跃节点",
                    checked = config.autoSyncEnabled,
                    onCheckedChange = viewModel::setAutoSyncEnabled,
                    showDivider = true
                )
                SyncSwitchItem(
                    title = "自动上传本机变更",
                    summary = "检测到本地复制文本、图片或文件时自动推送",
                    checked = config.autoUploadEnabled,
                    enabled = config.autoSyncEnabled,
                    onCheckedChange = viewModel::setAutoUploadEnabled,
                    showDivider = true
                )
                SyncSwitchItem(
                    title = "自动下载远端变更",
                    summary = "收到 SignalR 或轮询更新时自动拉取并回写",
                    checked = config.autoDownloadEnabled,
                    enabled = config.autoSyncEnabled,
                    onCheckedChange = viewModel::setAutoDownloadEnabled,
                    showDivider = true
                )
                SyncSwitchItem(
                    title = "验证码自动捕获 (OTP)",
                    summary = "通过 system_server 在脱敏前识别短信/通知验证码并同步",
                    checked = config.otpCaptureEnabled,
                    onCheckedChange = viewModel::setOtpCaptureEnabled,
                    showDivider = false
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LspModuleStatusCard(
    status: EngineStatusSnapshot,
    svcState: XposedServiceState,
    isScopeRequesting: Boolean,
    isHotReloading: Boolean,
    actionFeedback: String,
    onRequestScope: () -> Unit,
    onHotReload: () -> Unit
) {
    val colors = MiuixTheme.colorScheme
    val statusColors = SyncThemeExtras.statusColors

    val bound = svcState.isServiceBound || status.xposedApiActive
    val apiLabel = if (svcState.apiVersion > 0) "${svcState.apiVersion}" else "102"
    val fwName = svcState.frameworkName.ifBlank { status.xposedFrameworkName }.ifBlank { "LSPosed" }
    val fwVer = svcState.frameworkVersion.trim()
    val frameworkDesc = if (bound) {
        listOf(fwName, fwVer).filter { it.isNotBlank() }.joinToString(" ")
    } else {
        "请在 LSPosed 管理器中启用模块并勾选 system 与系统界面"
    }

    val hasSystemScope = svcState.scopeSystem || status.scopeSystem
    val hasSystemUiScope = svcState.scopeSystemUi || status.scopeSystemUi
    val needsScopeRequest = !hasSystemScope || !hasSystemUiScope

    val feedbackText = actionFeedback.ifBlank {
        svcState.hotReloadStatusMessage.ifBlank { svcState.scopeRequestMessage }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (bound) "Xposed 服务已连接" else "Xposed 服务未连接",
                    style = MiuixTheme.textStyles.title3,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurfaceContainer
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = frameworkDesc,
                    style = MiuixTheme.textStyles.body2,
                    color = colors.onSurfaceVariantSummary
                )
            }
            SyncStatusBadge(
                text = if (bound) "API $apiLabel" else "未激活",
                containerColor = if (bound) statusColors.okContainer else statusColors.warningContainer,
                contentColor = if (bound) statusColors.onOkContainer else statusColors.onWarningContainer,
                dotColor = if (bound) statusColors.ok else statusColors.warning
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(thickness = 0.5.dp, color = colors.dividerLine)
        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "作用域与实时 Binder 握手",
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurfaceVariantSummary
        )
        Spacer(modifier = Modifier.height(8.dp))

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SyncStatusBadge(
                text = if (hasSystemScope) "作用域 system ✓" else "作用域 system ✗",
                containerColor = if (hasSystemScope) statusColors.okContainer else statusColors.warningContainer,
                contentColor = if (hasSystemScope) statusColors.onOkContainer else statusColors.onWarningContainer
            )
            SyncStatusBadge(
                text = if (hasSystemUiScope) "作用域 SystemUI ✓" else "作用域 SystemUI ✗",
                containerColor = if (hasSystemUiScope) statusColors.okContainer else statusColors.warningContainer,
                contentColor = if (hasSystemUiScope) statusColors.onOkContainer else statusColors.onWarningContainer
            )
            SyncStatusBadge(
                text = if (status.systemServerBound) "SystemServer Binder 已握手" else "SystemServer Binder 等待中",
                containerColor = if (status.systemServerBound) statusColors.okContainer else statusColors.neutralContainer,
                contentColor = if (status.systemServerBound) statusColors.onOkContainer else statusColors.onNeutralContainer,
                dotColor = if (status.systemServerBound) statusColors.ok else statusColors.neutral
            )
            SyncStatusBadge(
                text = if (status.systemUiBound) "SystemUI Binder 已握手" else "SystemUI Binder 等待中",
                containerColor = if (status.systemUiBound) statusColors.okContainer else statusColors.neutralContainer,
                contentColor = if (status.systemUiBound) statusColors.onOkContainer else statusColors.onNeutralContainer,
                dotColor = if (status.systemUiBound) statusColors.ok else statusColors.neutral
            )
            SyncStatusBadge(
                text = if (status.dozeWhitelisted) "Doze / eBPF 自动白名单已生效" else "Doze / eBPF 白名单待下发",
                containerColor = if (status.dozeWhitelisted) statusColors.infoContainer else statusColors.neutralContainer,
                contentColor = if (status.dozeWhitelisted) statusColors.onInfoContainer else statusColors.onNeutralContainer
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = SyncInnerShape,
            color = colors.surfaceVariant
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "无感保活说明",
                    style = MiuixTheme.textStyles.footnote1,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurfaceContainer
                )
                Text(
                    text = "由 system_server 自动维护 Doze/eBPF 网络白名单与特权绑定常驻，无需手动设置电池优化或显示前台通知。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.onSurfaceVariantSummary
                )
                if (svcState.runningTargets.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    svcState.runningTargets.forEach { target ->
                        val proc = runCatching { target.processName }.getOrDefault("")
                        val pid = runCatching { target.pid }.getOrDefault(0)
                        val stateLabel = runCatching { target.state.name }.getOrDefault("UNKNOWN")
                        Text(
                            text = "$proc · PID $pid · $stateLabel",
                            style = MiuixTheme.textStyles.footnote2,
                            fontFamily = FontFamily.Monospace,
                            color = colors.onSurfaceContainerVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (needsScopeRequest) {
                Button(
                    onClick = onRequestScope,
                    enabled = !isScopeRequesting,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        color = colors.secondaryContainer,
                        contentColor = colors.onSecondaryContainer
                    )
                ) {
                    Text(
                        text = if (isScopeRequesting) "申请中…" else "申请作用域",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Button(
                onClick = onHotReload,
                enabled = !isHotReloading,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColorsPrimary()
            ) {
                Text(
                    text = if (isHotReloading) "重载中…" else "热重载模块",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.onPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (feedbackText.isNotBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = SyncInnerShape,
                color = statusColors.infoContainer
            ) {
                Text(
                    text = feedbackText,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MiuixTheme.textStyles.footnote1,
                    color = statusColors.onInfoContainer
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActiveNodeCard(
    config: AppConfig,
    status: EngineStatusSnapshot,
    isSyncBusy: Boolean,
    onPushNow: () -> Unit,
    onPullNow: () -> Unit,
    onSyncNow: () -> Unit,
    onOpenNodes: () -> Unit
) {
    val colors = MiuixTheme.colorScheme
    val statusColors = SyncThemeExtras.statusColors

    val (connLabel, connBg, connFg, connDot) = when (status.connectionState) {
        ConnectionState.CONNECTED -> Quad(
            "SignalR 已连接",
            statusColors.okContainer,
            statusColors.onOkContainer,
            statusColors.ok
        )
        ConnectionState.POLLING -> Quad(
            "WebDAV 轮询中",
            statusColors.okContainer,
            statusColors.onOkContainer,
            statusColors.ok
        )
        ConnectionState.CONNECTING -> Quad(
            "正在连接",
            statusColors.infoContainer,
            statusColors.onInfoContainer,
            statusColors.info
        )
        ConnectionState.ERROR -> Quad(
            "连接异常",
            statusColors.errorContainer,
            statusColors.onErrorContainer,
            statusColors.error
        )
        ConnectionState.DISCONNECTED -> Quad(
            "未连接",
            statusColors.neutralContainer,
            statusColors.onNeutralContainer,
            statusColors.neutral
        )
    }

    val typeLabel = when (status.activeNodeType) {
        ServerType.OFFICIAL -> "Official v3"
        ServerType.WEBDAV -> "WebDAV"
        null -> "未选择"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = status.activeNodeName?.ifBlank { "未命名节点" } ?: "未配置活跃节点",
                    style = MiuixTheme.textStyles.title3,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurfaceContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (config.nodes.isEmpty()) {
                        "请前往「节点」页添加服务器"
                    } else {
                        "协议：$typeLabel · 策略：${config.selector.mode.name}"
                    },
                    style = MiuixTheme.textStyles.body2,
                    color = colors.onSurfaceVariantSummary
                )
            }
            SyncStatusBadge(
                text = connLabel,
                containerColor = connBg,
                contentColor = connFg,
                dotColor = connDot
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SyncStatusBadge(
                text = status.lastRttMs?.let { "RTT ${it}ms" } ?: "RTT --",
                containerColor = colors.primaryContainer,
                contentColor = colors.onPrimaryContainer
            )
            status.serverCapabilities?.let { caps ->
                if (caps.historySync) {
                    SyncStatusBadge(
                        text = "历史同步",
                        containerColor = statusColors.neutralContainer,
                        contentColor = statusColors.onNeutralContainer
                    )
                }
                if (caps.historyTags) {
                    SyncStatusBadge(
                        text = "标签",
                        containerColor = statusColors.neutralContainer,
                        contentColor = statusColors.onNeutralContainer
                    )
                }
                if (caps.historyTitle) {
                    SyncStatusBadge(
                        text = "标题",
                        containerColor = statusColors.neutralContainer,
                        contentColor = statusColors.onNeutralContainer
                    )
                }
            }
        }

        if (status.lastSyncSummary.isNotBlank() || status.lastSyncAtMs > 0L) {
            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(thickness = 0.5.dp, color = colors.dividerLine)
            Spacer(modifier = Modifier.height(10.dp))
            if (status.lastSyncSummary.isNotBlank()) {
                SyncKeyValueRow(label = "最近同步", value = status.lastSyncSummary)
            }
            if (status.lastSyncAtMs > 0L) {
                Spacer(modifier = Modifier.height(4.dp))
                SyncKeyValueRow(label = "同步时间", value = formatOverviewTime(status.lastSyncAtMs))
            }
        }

        if (!status.lastError.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = SyncInnerShape,
                color = statusColors.errorContainer
            ) {
                Text(
                    text = status.lastError.orEmpty(),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MiuixTheme.textStyles.footnote1,
                    color = statusColors.onErrorContainer
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        if (config.nodes.isEmpty()) {
            Button(
                onClick = onOpenNodes,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary()
            ) {
                Text(
                    text = "前往添加服务器节点",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.onPrimary
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onPushNow,
                    enabled = !isSyncBusy && status.activeNodeId != null,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        color = colors.secondaryContainer,
                        contentColor = colors.onSecondaryContainer
                    )
                ) {
                    Text(
                        text = "推送",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.onSecondaryContainer,
                        maxLines = 1
                    )
                }
                Button(
                    onClick = onPullNow,
                    enabled = !isSyncBusy && status.activeNodeId != null,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        color = colors.secondaryContainer,
                        contentColor = colors.onSecondaryContainer
                    )
                ) {
                    Text(
                        text = "拉取",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.onSecondaryContainer,
                        maxLines = 1
                    )
                }
                Button(
                    onClick = onSyncNow,
                    enabled = !isSyncBusy && status.activeNodeId != null,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary()
                ) {
                    Text(
                        text = if (isSyncBusy) "同步中…" else "同步",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.onPrimary,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

private data class Quad<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)

private fun formatOverviewTime(ms: Long): String {
    return runCatching {
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))
    }.getOrDefault(ms.toString())
}
