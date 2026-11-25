package com.scluzlep.syncclipboard.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.scluzlep.syncclipboard.model.NetworkTransportRule
import com.scluzlep.syncclipboard.model.NodeNetworkRule
import com.scluzlep.syncclipboard.model.NodeProbeResult
import com.scluzlep.syncclipboard.model.SelectorMode
import com.scluzlep.syncclipboard.model.ServerNode
import com.scluzlep.syncclipboard.model.ServerType
import com.scluzlep.syncclipboard.ui.theme.SyncInnerShape
import com.scluzlep.syncclipboard.ui.theme.SyncThemeExtras
import com.scluzlep.syncclipboard.ui.viewmodel.MainViewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.UUID

@Composable
fun NodesTab(
    viewModel: MainViewModel,
    scrollBehavior: ScrollBehavior,
    contentPadding: PaddingValues
) {
    val config by viewModel.configFlow.collectAsState()
    val status by viewModel.statusFlow.collectAsState()
    val probeResults by viewModel.probeResultsFlow.collectAsState()
    val isProbingAll by viewModel.isProbingAll.collectAsState()
    val isTestingNode by viewModel.isTestingNode.collectAsState()
    val testNodeResult by viewModel.testNodeResult.collectAsState()
    val actionFeedback by viewModel.actionFeedback.collectAsState()

    var editingNode by remember { mutableStateOf<ServerNode?>(null) }
    var isCreatingNew by remember { mutableStateOf(false) }
    var deletingNode by remember { mutableStateOf<ServerNode?>(null) }

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
        item(key = "selector_title") {
            SmallTitle(
                text = "单活多节点选路",
                insideMargin = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
            )
        }

        item(key = "selector_card") {
            SelectorPolicyCard(
                currentMode = config.selector.mode,
                nodeCount = config.nodes.size,
                isProbingAll = isProbingAll,
                actionFeedback = actionFeedback,
                onSelectMode = viewModel::setSelectorMode,
                onProbeAll = viewModel::probeAllNodes,
                onAddNode = {
                    viewModel.clearTestNodeResult()
                    isCreatingNew = true
                    editingNode = ServerNode(
                        id = UUID.randomUUID().toString(),
                        name = "",
                        baseUrl = "",
                        priority = config.nodes.size
                    )
                }
            )
        }

        item(key = "nodes_list_title") {
            SmallTitle(
                text = "节点列表 (${config.nodes.size})",
                insideMargin = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
            )
        }

        if (config.nodes.isEmpty()) {
            item(key = "nodes_empty") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    insideMargin = PaddingValues(20.dp)
                ) {
                    Text(
                        text = "尚未配置服务器节点",
                        style = MiuixTheme.textStyles.title4,
                        fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.onSurfaceContainer
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "支持 Official SyncClipboard (SignalR + REST v3.3.4) 与 WebDAV 节点。同一时刻仅激活一个节点，历史与缓存按节点严格隔离。",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        } else {
            items(
                items = config.nodes,
                key = { it.id }
            ) { node ->
                ServerNodeCard(
                    node = node,
                    isActive = node.id == status.activeNodeId,
                    probeResult = probeResults[node.id],
                    onSelect = { viewModel.selectActiveNode(node.id) },
                    onTest = { viewModel.testNode(node) },
                    onEdit = {
                        viewModel.clearTestNodeResult()
                        isCreatingNew = false
                        editingNode = node
                    },
                    onDelete = { deletingNode = node }
                )
            }
        }
    }

    editingNode?.let { draft ->
        NodeEditorDialog(
            initialNode = draft,
            isNew = isCreatingNew,
            isTesting = isTestingNode,
            testResult = testNodeResult,
            onTestConnection = viewModel::testNode,
            onSave = { saved ->
                viewModel.upsertNode(saved)
                editingNode = null
            },
            onDismiss = {
                viewModel.clearTestNodeResult()
                editingNode = null
            }
        )
    }

    deletingNode?.let { target ->
        val colors = MiuixTheme.colorScheme
        val statusColors = SyncThemeExtras.statusColors
        Dialog(onDismissRequest = { deletingNode = null }) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(CardDefaults.CornerRadius),
                color = colors.surfaceContainer,
                border = BorderStroke(0.5.dp, colors.dividerLine)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "删除节点",
                        style = MiuixTheme.textStyles.title4,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurfaceContainer
                    )
                    Text(
                        text = "确定要删除「${target.name.ifBlank { target.baseUrl }}」吗？如果它是当前活跃节点，引擎会自动断开并切换到其他可用节点。",
                        style = MiuixTheme.textStyles.body2,
                        color = colors.onSurfaceVariantSummary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { deletingNode = null },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                color = colors.secondaryContainer,
                                contentColor = colors.onSecondaryContainer
                            )
                        ) {
                            Text(
                                text = "取消",
                                fontSize = 13.sp,
                                color = colors.onSecondaryContainer
                            )
                        }
                        Button(
                            onClick = {
                                viewModel.deleteNode(target.id)
                                deletingNode = null
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                color = statusColors.errorContainer,
                                contentColor = statusColors.onErrorContainer
                            )
                        ) {
                            Text(
                                text = "删除",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = statusColors.onErrorContainer
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectorPolicyCard(
    currentMode: SelectorMode,
    nodeCount: Int,
    isProbingAll: Boolean,
    actionFeedback: String,
    onSelectMode: (SelectorMode) -> Unit,
    onProbeAll: () -> Unit,
    onAddNode: () -> Unit
) {
    val colors = MiuixTheme.colorScheme
    val statusColors = SyncThemeExtras.statusColors
    val modes = listOf(
        SelectorMode.MANUAL,
        SelectorMode.FALLBACK,
        SelectorMode.URL_TEST,
        SelectorMode.RULE_AUTO
    )
    val labels = listOf("手动", "顺延", "测速", "规则")
    val modeDesc = when (currentMode) {
        SelectorMode.MANUAL -> "MANUAL：固定使用您点击选中的节点，不自动切换。"
        SelectorMode.FALLBACK -> "FALLBACK：按优先级顺延选择健康节点，主节点恢复后冷却回切。"
        SelectorMode.URL_TEST -> "URL_TEST：定期并发测速，RTT 优势超过 80ms 滞回阈值时切换。"
        SelectorMode.RULE_AUTO -> "RULE_AUTO：按传输类型 (Wi-Fi/蜂窝/VPN)、本地 CIDR 与可选 SSID 自动选路。"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(16.dp)
    ) {
        Text(
            text = "选路模式",
            style = MiuixTheme.textStyles.title4,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurfaceContainer
        )
        Spacer(modifier = Modifier.height(8.dp))
        SyncSegmentedControl(
            options = labels,
            selectedIndex = modes.indexOf(currentMode).coerceAtLeast(0),
            onSelected = { idx -> onSelectMode(modes[idx]) }
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = modeDesc,
            style = MiuixTheme.textStyles.footnote1,
            color = colors.onSurfaceVariantSummary
        )

        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onProbeAll,
                enabled = !isProbingAll && nodeCount > 0,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    color = colors.secondaryContainer,
                    contentColor = colors.onSecondaryContainer
                )
            ) {
                Text(
                    text = if (isProbingAll) "测速中…" else "全部测速",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSecondaryContainer
                )
            }
            Button(
                onClick = onAddNode,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColorsPrimary()
            ) {
                Text(
                    text = "+ 添加节点",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.onPrimary
                )
            }
        }

        if (actionFeedback.isNotBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServerNodeCard(
    node: ServerNode,
    isActive: Boolean,
    probeResult: NodeProbeResult?,
    onSelect: () -> Unit,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = MiuixTheme.colorScheme
    val statusColors = SyncThemeExtras.statusColors

    Surface(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CardDefaults.CornerRadius),
        color = colors.surfaceContainer,
        border = if (isActive) BorderStroke(1.2.dp, colors.primary) else null
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = node.name.ifBlank { node.baseUrl },
                        style = MiuixTheme.textStyles.title4,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurfaceContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = node.baseUrl,
                        style = MiuixTheme.textStyles.footnote1,
                        fontFamily = FontFamily.Monospace,
                        color = colors.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                SyncStatusBadge(
                    text = if (isActive) "当前活跃" else if (node.enabled) "点击切换" else "已禁用",
                    containerColor = when {
                        isActive -> colors.primaryContainer
                        !node.enabled -> statusColors.neutralContainer
                        else -> colors.surfaceVariant
                    },
                    contentColor = when {
                        isActive -> colors.onPrimaryContainer
                        !node.enabled -> statusColors.onNeutralContainer
                        else -> colors.onSurfaceVariantSummary
                    },
                    dotColor = if (isActive) colors.primary else null
                )
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SyncStatusBadge(
                    text = if (node.serverType == ServerType.OFFICIAL) "Official v3" else "WebDAV",
                    containerColor = colors.primaryContainer.copy(alpha = 0.7f),
                    contentColor = colors.onPrimaryContainer
                )
                SyncStatusBadge(
                    text = "优先级 ${node.priority}",
                    containerColor = statusColors.neutralContainer,
                    contentColor = statusColors.onNeutralContainer
                )
                if (node.allowCleartext) {
                    SyncStatusBadge(
                        text = "HTTP 明文",
                        containerColor = statusColors.warningContainer,
                        contentColor = statusColors.onWarningContainer
                    )
                }
                if (!node.customCertSha256.isNullOrBlank()) {
                    SyncStatusBadge(
                        text = "证书 Pin",
                        containerColor = statusColors.infoContainer,
                        contentColor = statusColors.onInfoContainer
                    )
                }
                if (probeResult != null) {
                    val probeText = if (probeResult.reachable) {
                        "可达 · ${probeResult.rttMs ?: 0}ms"
                    } else {
                        "不可达"
                    }
                    SyncStatusBadge(
                        text = probeText,
                        containerColor = if (probeResult.reachable) statusColors.okContainer else statusColors.errorContainer,
                        contentColor = if (probeResult.reachable) statusColors.onOkContainer else statusColors.onErrorContainer,
                        dotColor = if (probeResult.reachable) statusColors.ok else statusColors.error
                    )
                }
            }

            val transportsSummary = node.networkRule.allowedTransports.joinToString("/") { transportShortLabel(it) }
            val extraRuleParts = buildList {
                add("网络：$transportsSummary")
                if (node.networkRule.cidrAllowlist.isNotEmpty()) {
                    add("CIDR ${node.networkRule.cidrAllowlist.size} 条")
                }
                if (node.networkRule.ssidAllowlist.isNotEmpty()) {
                    add("SSID ${node.networkRule.ssidAllowlist.size} 个")
                }
            }.joinToString(" · ")
            Text(
                text = extraRuleParts,
                style = MiuixTheme.textStyles.footnote2,
                color = colors.onSurfaceVariantSummary
            )

            if (probeResult != null && !probeResult.error.isNullOrBlank()) {
                Text(
                    text = probeResult.error.orEmpty(),
                    style = MiuixTheme.textStyles.footnote2,
                    color = statusColors.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            HorizontalDivider(thickness = 0.5.dp, color = colors.dividerLine)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    text = "测速",
                    onClick = onTest,
                    insideMargin = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                )
                TextButton(
                    text = "编辑",
                    onClick = onEdit,
                    insideMargin = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                )
                TextButton(
                    text = "删除",
                    onClick = onDelete,
                    insideMargin = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NodeEditorDialog(
    initialNode: ServerNode,
    isNew: Boolean,
    isTesting: Boolean,
    testResult: NodeProbeResult?,
    onTestConnection: (ServerNode) -> Unit,
    onSave: (ServerNode) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val colors = MiuixTheme.colorScheme
    val statusColors = SyncThemeExtras.statusColors

    var name by remember(initialNode.id) { mutableStateOf(initialNode.name) }
    var serverType by remember(initialNode.id) { mutableStateOf(initialNode.serverType) }
    var baseUrl by remember(initialNode.id) { mutableStateOf(initialNode.baseUrl) }
    var username by remember(initialNode.id) { mutableStateOf(initialNode.username) }
    var password by remember(initialNode.id) { mutableStateOf(initialNode.password) }
    var showPassword by remember { mutableStateOf(false) }
    var enabled by remember(initialNode.id) { mutableStateOf(initialNode.enabled) }
    var allowCleartext by remember(initialNode.id) { mutableStateOf(initialNode.allowCleartext) }
    var customCertSha256 by remember(initialNode.id) { mutableStateOf(initialNode.customCertSha256.orEmpty()) }
    var priorityText by remember(initialNode.id) { mutableStateOf(initialNode.priority.toString()) }
    var transports by remember(initialNode.id) { mutableStateOf(initialNode.networkRule.allowedTransports) }
    var cidrText by remember(initialNode.id) {
        mutableStateOf(initialNode.networkRule.cidrAllowlist.joinToString(", "))
    }
    var ssidText by remember(initialNode.id) {
        mutableStateOf(initialNode.networkRule.ssidAllowlist.joinToString(", "))
    }
    var validationError by remember { mutableStateOf<String?>(null) }

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasLocationPermission = granted
    }

    fun buildDraftNode(): ServerNode {
        val parsedCidrs = cidrText.split(',', '\n', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val parsedSsids = ssidText.split(',', '\n', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return ServerNode(
            id = initialNode.id,
            name = name.trim(),
            enabled = enabled,
            serverType = serverType,
            baseUrl = baseUrl.trim().trimEnd('/'),
            username = username.trim(),
            password = password,
            allowCleartext = allowCleartext,
            customCertSha256 = customCertSha256.trim().takeIf { it.isNotEmpty() },
            priority = priorityText.trim().toIntOrNull() ?: 0,
            networkRule = NodeNetworkRule(
                allowedTransports = transports,
                cidrAllowlist = parsedCidrs,
                ssidAllowlist = parsedSsids
            )
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            shape = RoundedCornerShape(CardDefaults.CornerRadius),
            color = colors.surfaceContainer,
            border = BorderStroke(0.5.dp, colors.dividerLine)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 680.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = if (isNew) "添加服务器节点" else "编辑服务器节点",
                    style = MiuixTheme.textStyles.title3,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurfaceContainer
                )

                SyncSegmentedControl(
                    options = listOf("Official v3", "WebDAV"),
                    selectedIndex = if (serverType == ServerType.OFFICIAL) 0 else 1,
                    onSelected = { idx ->
                        serverType = if (idx == 0) ServerType.OFFICIAL else ServerType.WEBDAV
                    }
                )

                SyncTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "节点名称",
                    placeholder = "例如：家里 NAS / 云端主节点"
                )

                SyncTextField(
                    value = baseUrl,
                    onValueChange = {
                        baseUrl = it
                        validationError = null
                    },
                    label = "服务器地址 (Base URL)",
                    placeholder = "https://sync.example.com:5033",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SyncTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = "用户名",
                        modifier = Modifier.weight(1f)
                    )
                    SyncTextField(
                        value = priorityText,
                        onValueChange = { priorityText = it.filter { ch -> ch.isDigit() || ch == '-' } },
                        label = "优先级 (越小越优先)",
                        modifier = Modifier.weight(0.8f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }

                SyncTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = "密码 / Token",
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(
                            text = if (showPassword) "隐藏" else "显示",
                            onClick = { showPassword = !showPassword },
                            insideMargin = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "启用此节点",
                        style = MiuixTheme.textStyles.body2,
                        color = colors.onSurfaceContainer
                    )
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "允许明文 HTTP (allowCleartext)",
                            style = MiuixTheme.textStyles.body2,
                            color = colors.onSurfaceContainer
                        )
                        Text(
                            text = "仅局域网或反向代理内部使用 HTTP 时开启",
                            style = MiuixTheme.textStyles.footnote2,
                            color = colors.onSurfaceVariantSummary
                        )
                    }
                    Switch(checked = allowCleartext, onCheckedChange = { allowCleartext = it })
                }

                SyncTextField(
                    value = customCertSha256,
                    onValueChange = { customCertSha256 = it },
                    label = "自签证书 SHA-256 指纹 (可选)",
                    placeholder = "64 位十六进制指纹，留空走系统信任链"
                )

                Text(
                    text = "允许的网络传输类型",
                    style = MiuixTheme.textStyles.footnote1,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurfaceVariantSummary
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NetworkTransportRule.entries.forEach { rule ->
                        val selected = rule in transports
                        SyncFilterChip(
                            label = transportShortLabel(rule),
                            selected = selected,
                            onClick = {
                                transports = if (selected) {
                                    transports - rule
                                } else {
                                    transports + rule
                                }
                            }
                        )
                    }
                }

                SyncTextField(
                    value = cidrText,
                    onValueChange = { cidrText = it },
                    label = "本地 IP CIDR 白名单 (逗号分隔，可选)",
                    placeholder = "192.168.1.0/24, 10.0.0.0/8"
                )

                SyncTextField(
                    value = ssidText,
                    onValueChange = { ssidText = it },
                    label = "Wi-Fi SSID 规则 (逗号分隔，可选)",
                    placeholder = "Home-WiFi, Office-5G",
                    supportingText = if (hasLocationPermission) {
                        "已授予精确定位权限，可匹配 Wi-Fi SSID"
                    } else {
                        "默认零定位权限运行；仅填写 SSID 规则时需定位权限读取名称"
                    }
                )

                if (ssidText.isNotBlank() && !hasLocationPermission) {
                    Button(
                        onClick = { locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            color = statusColors.warningContainer,
                            contentColor = statusColors.onWarningContainer
                        )
                    ) {
                        Text(
                            text = "授权定位权限以读取 SSID",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = statusColors.onWarningContainer
                        )
                    }
                }

                if (validationError != null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = SyncInnerShape,
                        color = statusColors.errorContainer
                    ) {
                        Text(
                            text = validationError.orEmpty(),
                            modifier = Modifier.padding(10.dp),
                            style = MiuixTheme.textStyles.footnote1,
                            color = statusColors.onErrorContainer
                        )
                    }
                }

                if (testResult != null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = SyncInnerShape,
                        color = if (testResult.reachable) statusColors.okContainer else statusColors.errorContainer
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = if (testResult.reachable) {
                                    "连接成功 · RTT ${testResult.rttMs ?: 0} ms" +
                                        (testResult.serverVersion?.let { " · v$it" } ?: "")
                                } else {
                                    "连接失败：${testResult.error ?: "未知错误"}"
                                },
                                style = MiuixTheme.textStyles.footnote1,
                                fontWeight = FontWeight.Medium,
                                color = if (testResult.reachable) statusColors.onOkContainer else statusColors.onErrorContainer
                            )
                            testResult.capabilities?.let { caps ->
                                Text(
                                    text = "Capabilities: history=${caps.historySync}, tags=${caps.historyTags}, title=${caps.historyTitle}",
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = if (testResult.reachable) statusColors.onOkContainer else statusColors.onErrorContainer
                                )
                            }
                        }
                    }
                }

                Button(
                    onClick = {
                        val draft = buildDraftNode()
                        if (draft.baseUrl.isBlank()) {
                            validationError = "请先填写服务器 Base URL"
                        } else {
                            onTestConnection(draft)
                        }
                    },
                    enabled = !isTesting,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        color = colors.secondaryContainer,
                        contentColor = colors.onSecondaryContainer
                    )
                ) {
                    Text(
                        text = if (isTesting) "正在测试连接…" else "测试连接 (检测 RTT 与能力)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.onSecondaryContainer
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            color = colors.surfaceVariant,
                            contentColor = colors.onSurfaceContainer
                        )
                    ) {
                        Text(
                            text = "取消",
                            fontSize = 13.sp,
                            color = colors.onSurfaceContainer
                        )
                    }
                    Button(
                        onClick = {
                            val draft = buildDraftNode()
                            if (draft.baseUrl.isBlank()) {
                                validationError = "服务器 Base URL 不能为空"
                            } else {
                                onSave(draft)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColorsPrimary()
                    ) {
                        Text(
                            text = "保存",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.onPrimary
                        )
                    }
                }
            }
        }
    }
}

private fun transportShortLabel(rule: NetworkTransportRule): String = when (rule) {
    NetworkTransportRule.WIFI -> "Wi-Fi"
    NetworkTransportRule.CELLULAR -> "蜂窝"
    NetworkTransportRule.VPN -> "VPN"
    NetworkTransportRule.ETHERNET -> "以太网"
}
