package com.scluzlep.syncclipboard.ui.screens

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.scluzlep.syncclipboard.BuildConfig
import com.scluzlep.syncclipboard.model.HistoryFilterState
import com.scluzlep.syncclipboard.model.LocalHistoryItem
import com.scluzlep.syncclipboard.model.ProfileType
import com.scluzlep.syncclipboard.protocol.GroupArchiveCodec
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryTab(
    viewModel: MainViewModel,
    scrollBehavior: ScrollBehavior,
    contentPadding: PaddingValues
) {
    val status by viewModel.statusFlow.collectAsState()
    val history by viewModel.historyFlow.collectAsState()
    val availableTags by viewModel.availableTagsFlow.collectAsState()
    val filter by viewModel.historyFilterFlow.collectAsState()
    val isRefreshing by viewModel.isHistoryRefreshing.collectAsState()
    val isLoadingMore by viewModel.isLoadingMoreHistory.collectAsState()
    val hasMore by viewModel.hasMoreHistory.collectAsState()
    val actionFeedback by viewModel.actionFeedback.collectAsState()

    var selectedItem by remember { mutableStateOf<LocalHistoryItem?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

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
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "history_header") {
            HistoryHeaderCard(
                activeNodeName = status.activeNodeName,
                itemCount = history.size,
                isRefreshing = isRefreshing,
                actionFeedback = actionFeedback,
                onRefresh = { viewModel.refreshHistory(resetPagination = true) },
                onRequestClearAll = { showClearConfirm = true }
            )
        }

        item(key = "history_search_filter") {
            HistoryFilterCard(
                filter = filter,
                availableTags = availableTags,
                onFilterChange = viewModel::setHistoryFilter
            )
        }

        item(key = "history_list_title") {
            SmallTitle(
                text = "记录列表 (${history.size})",
                insideMargin = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
            )
        }

        if (history.isEmpty()) {
            item(key = "history_empty") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    insideMargin = PaddingValues(20.dp)
                ) {
                    Text(
                        text = if (status.activeNodeId == null) {
                            "未选择活跃节点"
                        } else {
                            "当前筛选条件下暂无历史记录"
                        },
                        style = MiuixTheme.textStyles.title4,
                        fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.onSurfaceContainer
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "历史数据库与缓存严格按节点隔离，切换节点后互不串库。",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        } else {
            items(
                items = history,
                key = { "${it.nodeStorageKey}:${it.type.name}:${it.hashUpper}" }
            ) { item ->
                HistoryItemCard(
                    item = item,
                    onClick = { selectedItem = item },
                    onCopy = { viewModel.copyHistoryItemToClipboard(item.type, item.hashUpper) },
                    onToggleStar = {
                        viewModel.updateHistoryItemMetadata(
                            type = item.type,
                            hashUpper = item.hashUpper,
                            starred = !item.starred
                        )
                    }
                )
            }

            if (hasMore) {
                item(key = "history_load_more") {
                    Button(
                        onClick = viewModel::loadMoreHistory,
                        enabled = !isLoadingMore,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            color = MiuixTheme.colorScheme.secondaryContainer,
                            contentColor = MiuixTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Text(
                            text = if (isLoadingMore) "正在加载更多…" else "加载更多历史",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }
    }

    val liveSelectedItem = selectedItem?.let { current ->
        history.firstOrNull {
            it.nodeStorageKey == current.nodeStorageKey &&
                it.type == current.type &&
                it.hashUpper == current.hashUpper
        } ?: current
    }
    if (liveSelectedItem != null) {
        HistoryDetailDialog(
            item = liveSelectedItem,
            viewModel = viewModel,
            onDismiss = { selectedItem = null }
        )
    }

    if (showClearConfirm) {
        val colors = MiuixTheme.colorScheme
        val statusColors = SyncThemeExtras.statusColors
        Dialog(onDismissRequest = { showClearConfirm = false }) {
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
                        text = "清空当前节点历史",
                        style = MiuixTheme.textStyles.title4,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurfaceContainer
                    )
                    Text(
                        text = "将向服务器发起 DELETE /api/history/clear 并清空当前节点本地缓存记录（不影响其他节点）。确定继续吗？",
                        style = MiuixTheme.textStyles.body2,
                        color = colors.onSurfaceVariantSummary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { showClearConfirm = false },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                color = colors.secondaryContainer,
                                contentColor = colors.onSecondaryContainer
                            )
                        ) {
                            Text("取消", fontSize = 13.sp, color = colors.onSecondaryContainer)
                        }
                        Button(
                            onClick = {
                                showClearConfirm = false
                                viewModel.clearAllHistory()
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                color = statusColors.errorContainer,
                                contentColor = statusColors.onErrorContainer
                            )
                        ) {
                            Text(
                                text = "确认清空",
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
private fun HistoryHeaderCard(
    activeNodeName: String?,
    itemCount: Int,
    isRefreshing: Boolean,
    actionFeedback: String,
    onRefresh: () -> Unit,
    onRequestClearAll: () -> Unit
) {
    val colors = MiuixTheme.colorScheme
    val statusColors = SyncThemeExtras.statusColors
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = activeNodeName?.ifBlank { "未命名节点" } ?: "未激活节点",
                    style = MiuixTheme.textStyles.title4,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurfaceContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "节点物理隔离历史 · 当前展示 $itemCount 条",
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.onSurfaceVariantSummary
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(
                    text = if (isRefreshing) "刷新中" else "刷新",
                    enabled = !isRefreshing && activeNodeName != null,
                    onClick = onRefresh,
                    insideMargin = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                )
                TextButton(
                    text = "清空",
                    enabled = activeNodeName != null,
                    onClick = onRequestClearAll,
                    insideMargin = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
        if (actionFeedback.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = SyncInnerShape,
                color = statusColors.infoContainer
            ) {
                Text(
                    text = actionFeedback,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    style = MiuixTheme.textStyles.footnote1,
                    color = statusColors.onInfoContainer
                )
            }
        }
    }
}

@Composable
private fun HistoryFilterCard(
    filter: HistoryFilterState,
    availableTags: List<String>,
    onFilterChange: (HistoryFilterState) -> Unit
) {
    val colors = MiuixTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SyncTextField(
                value = filter.searchText,
                onValueChange = { onFilterChange(filter.copy(searchText = it)) },
                label = "搜索内容" + if (filter.searchTitles) "与标题" else "",
                placeholder = "关键词…",
                modifier = Modifier.weight(1f)
            )
            SyncFilterChip(
                label = "搜标题",
                selected = filter.searchTitles,
                onClick = { onFilterChange(filter.copy(searchTitles = !filter.searchTitles)) }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val typeOptions: List<Pair<String, ProfileType?>> = listOf(
                "全部" to null,
                "文本" to ProfileType.Text,
                "图片" to ProfileType.Image,
                "文件" to ProfileType.File,
                "文件组" to ProfileType.Group
            )
            typeOptions.forEach { (label, type) ->
                SyncFilterChip(
                    label = label,
                    selected = filter.selectedType == type,
                    onClick = { onFilterChange(filter.copy(selectedType = type)) }
                )
            }
            SyncFilterChip(
                label = "★ 收藏",
                selected = filter.starredOnly,
                onClick = { onFilterChange(filter.copy(starredOnly = !filter.starredOnly)) }
            )
            SyncFilterChip(
                label = "无标签",
                selected = filter.onlyUntagged,
                onClick = {
                    onFilterChange(
                        filter.copy(
                            onlyUntagged = !filter.onlyUntagged,
                            selectedTag = if (!filter.onlyUntagged) null else filter.selectedTag
                        )
                    )
                }
            )
            SyncFilterChip(
                label = "含已删",
                selected = filter.includeDeleted,
                onClick = { onFilterChange(filter.copy(includeDeleted = !filter.includeDeleted)) }
            )
        }

        if (availableTags.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "标签:",
                    style = MiuixTheme.textStyles.footnote2,
                    color = colors.onSurfaceVariantSummary
                )
                availableTags.forEach { tag ->
                    val selected = filter.selectedTag == tag
                    SyncFilterChip(
                        label = "#$tag",
                        selected = selected,
                        onClick = {
                            onFilterChange(
                                filter.copy(
                                    selectedTag = if (selected) null else tag,
                                    onlyUntagged = false
                                )
                            )
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryItemCard(
    item: LocalHistoryItem,
    onClick: () -> Unit,
    onCopy: () -> Unit,
    onToggleStar: () -> Unit
) {
    val colors = MiuixTheme.colorScheme
    val statusColors = SyncThemeExtras.statusColors

    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CardDefaults.CornerRadius),
        color = colors.surfaceContainer,
        border = if (item.pinned) BorderStroke(1.dp, colors.primary.copy(alpha = 0.45f)) else null
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SyncStatusBadge(
                        text = profileTypeLabel(item.type),
                        containerColor = colors.primaryContainer,
                        contentColor = colors.onPrimaryContainer
                    )
                    if (item.pinned) {
                        SyncStatusBadge(
                            text = "置顶",
                            containerColor = statusColors.infoContainer,
                            contentColor = statusColors.onInfoContainer
                        )
                    }
                    if (item.starred) {
                        SyncStatusBadge(
                            text = "★ 收藏",
                            containerColor = statusColors.warningContainer,
                            contentColor = statusColors.onWarningContainer
                        )
                    }
                    if (item.isDeleted) {
                        SyncStatusBadge(
                            text = "已删除",
                            containerColor = statusColors.errorContainer,
                            contentColor = statusColors.onErrorContainer
                        )
                    }
                }
                Text(
                    text = formatHistoryTime(item.lastModifiedEpochMs),
                    style = MiuixTheme.textStyles.footnote2,
                    color = colors.onSurfaceVariantSummary
                )
            }

            if (!item.title.isNullOrBlank()) {
                Text(
                    text = item.title.orEmpty(),
                    style = MiuixTheme.textStyles.title4,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurfaceContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                text = item.text.ifBlank { "(无文本摘要)" },
                style = MiuixTheme.textStyles.body2,
                color = colors.onSurfaceContainer,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            if (!item.tags.isNullOrEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    item.tags.orEmpty().forEach { tag ->
                        SyncStatusBadge(
                            text = "#$tag",
                            containerColor = statusColors.neutralContainer,
                            contentColor = statusColors.onNeutralContainer
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatItemSize(item.type, item.size) +
                        if (!item.localDataPath.isNullOrBlank()) " · 已缓存" else "",
                    style = MiuixTheme.textStyles.footnote2,
                    color = colors.onSurfaceVariantSummary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        text = if (item.starred) "取消收藏" else "收藏",
                        onClick = onToggleStar,
                        insideMargin = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    )
                    TextButton(
                        text = "复制",
                        onClick = onCopy,
                        insideMargin = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryDetailDialog(
    item: LocalHistoryItem,
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val colors = MiuixTheme.colorScheme
    val statusColors = SyncThemeExtras.statusColors

    var draftTitle by remember(item.hashUpper, item.title) { mutableStateOf(item.title.orEmpty()) }
    var draftTagsText by remember(item.hashUpper, item.tags) {
        mutableStateOf(item.tags.orEmpty().joinToString(", "))
    }
    var draftStarred by remember(item.hashUpper, item.starred) { mutableStateOf(item.starred) }
    var draftPinned by remember(item.hashUpper, item.pinned) { mutableStateOf(item.pinned) }
    var busyMessage by remember { mutableStateOf<String?>(null) }
    var unpackedGroup by remember(item.hashUpper) { mutableStateOf<GroupArchiveCodec.GroupUnpackResult?>(null) }

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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${profileTypeLabel(item.type)} · 详情与编辑",
                        style = MiuixTheme.textStyles.title3,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurfaceContainer
                    )
                    SyncStatusBadge(
                        text = "v${item.version}",
                        containerColor = statusColors.neutralContainer,
                        contentColor = statusColors.onNeutralContainer
                    )
                }

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
                            text = item.text.ifBlank { "(无文本内容)" },
                            style = MiuixTheme.textStyles.body2,
                            color = colors.onSurfaceContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "SHA-256: ${item.hashUpper}",
                            style = MiuixTheme.textStyles.footnote2,
                            fontFamily = FontFamily.Monospace,
                            color = colors.onSurfaceVariantSummary
                        )
                        Text(
                            text = "大小: ${formatItemSize(item.type, item.size)} · 修改于 ${formatHistoryTime(item.lastModifiedEpochMs)}",
                            style = MiuixTheme.textStyles.footnote2,
                            color = colors.onSurfaceVariantSummary
                        )
                    }
                }

                SyncTextField(
                    value = draftTitle,
                    onValueChange = { draftTitle = it },
                    label = "自定义标题 (Title，最多 256 字符)",
                    placeholder = "留空则清除标题"
                )

                SyncTextField(
                    value = draftTagsText,
                    onValueChange = { draftTagsText = it },
                    label = "标签 (Tags，逗号分隔)",
                    placeholder = "例如：工作, 验证码",
                    trailingIcon = {
                        TextButton(
                            text = "清空 []",
                            onClick = { draftTagsText = "" },
                            insideMargin = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("收藏 (Star)", style = MiuixTheme.textStyles.body2, color = colors.onSurfaceContainer)
                    Switch(checked = draftStarred, onCheckedChange = { draftStarred = it })
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("置顶 (Pin)", style = MiuixTheme.textStyles.body2, color = colors.onSurfaceContainer)
                    Switch(checked = draftPinned, onCheckedChange = { draftPinned = it })
                }

                Button(
                    onClick = {
                        val parsedTags = draftTagsText
                            .split(',', '，', '\n')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .distinct()
                        busyMessage = "正在保存元数据…"
                        viewModel.updateHistoryItemMetadata(
                            type = item.type,
                            hashUpper = item.hashUpper,
                            title = draftTitle.trim(),
                            tags = parsedTags, // Explicit emptyList() serializes "tags": [] to clear tags
                            starred = draftStarred,
                            pinned = draftPinned
                        ) { res ->
                            busyMessage = res.fold(
                                onSuccess = { "已保存标题/标签/状态" },
                                onFailure = { err -> "保存失败：${err.message ?: "error"}" }
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColorsPrimary()
                ) {
                    Text(
                        text = "保存标题与标签",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.onPrimary
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.copyHistoryItemToClipboard(item.type, item.hashUpper) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            color = colors.secondaryContainer,
                            contentColor = colors.onSecondaryContainer
                        )
                    ) {
                        Text("复制到剪贴板", fontSize = 13.sp, color = colors.onSecondaryContainer)
                    }

                    if (item.hasData || item.type != ProfileType.Text) {
                        Button(
                            onClick = {
                                busyMessage = "正在准备文件…"
                                viewModel.downloadHistoryItemData(item.type, item.hashUpper) { res ->
                                    res.onSuccess { file ->
                                        busyMessage = "已就绪：${file.name}"
                                        openOrShareFile(context, file, shareMode = false)
                                    }.onFailure { err ->
                                        busyMessage = "打开失败：${err.message ?: "error"}"
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                color = colors.secondaryContainer,
                                contentColor = colors.onSecondaryContainer
                            )
                        ) {
                            Text("下载 / 打开", fontSize = 13.sp, color = colors.onSecondaryContainer)
                        }
                    }
                }

                if (item.hasData || item.type != ProfileType.Text) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                busyMessage = "正在准备分享…"
                                viewModel.downloadHistoryItemData(item.type, item.hashUpper) { res ->
                                    res.onSuccess { file ->
                                        busyMessage = null
                                        openOrShareFile(context, file, shareMode = true)
                                    }.onFailure { err ->
                                        busyMessage = "分享失败：${err.message ?: "error"}"
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                color = colors.surfaceVariant,
                                contentColor = colors.onSurfaceContainer
                            )
                        ) {
                            Text("系统分享文件", fontSize = 13.sp, color = colors.onSurfaceContainer)
                        }

                        if (item.type == ProfileType.Group) {
                            Button(
                                onClick = {
                                    busyMessage = "正在下载并解包 Group .zip…"
                                    viewModel.downloadHistoryItemData(item.type, item.hashUpper) { dlRes ->
                                        dlRes.onSuccess { zipFile ->
                                            viewModel.extractGroupArchive(zipFile, item.hashUpper) { unpackRes ->
                                                unpackRes.onSuccess { unpacked ->
                                                    unpackedGroup = unpacked
                                                    busyMessage = "解包完成，共 ${unpacked.topLevelFiles.size} 个顶层条目"
                                                }.onFailure { err ->
                                                    busyMessage = "解包失败：${err.message ?: "error"}"
                                                }
                                            }
                                        }.onFailure { err ->
                                            busyMessage = "下载失败：${err.message ?: "error"}"
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    color = statusColors.infoContainer,
                                    contentColor = statusColors.onInfoContainer
                                )
                            ) {
                                Text("解包 Group .zip", fontSize = 13.sp, color = statusColors.onInfoContainer)
                            }
                        }
                    }
                }

                unpackedGroup?.let { unpacked ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = SyncInnerShape,
                        color = colors.surfaceVariant
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "已解包文件 (SHA-256 校验通过)",
                                style = MiuixTheme.textStyles.footnote1,
                                fontWeight = FontWeight.SemiBold,
                                color = statusColors.ok
                            )
                            unpacked.topLevelFiles.forEach { entryFile ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (entryFile.isDirectory) "📁 ${entryFile.name}" else "📄 ${entryFile.name}",
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = colors.onSurfaceContainer,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (entryFile.isFile) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            TextButton(
                                                text = "打开",
                                                onClick = { openOrShareFile(context, entryFile, shareMode = false) },
                                                insideMargin = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                            )
                                            TextButton(
                                                text = "分享",
                                                onClick = { openOrShareFile(context, entryFile, shareMode = true) },
                                                insideMargin = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (!busyMessage.isNullOrBlank()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = SyncInnerShape,
                        color = statusColors.infoContainer
                    ) {
                        Text(
                            text = busyMessage.orEmpty(),
                            modifier = Modifier.padding(10.dp),
                            style = MiuixTheme.textStyles.footnote1,
                            color = statusColors.onInfoContainer
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            viewModel.softDeleteHistoryItem(item.type, item.hashUpper) { res ->
                                if (res.isSuccess) onDismiss()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            color = statusColors.errorContainer,
                            contentColor = statusColors.onErrorContainer
                        )
                    ) {
                        Text("软删除", fontSize = 13.sp, color = statusColors.onErrorContainer)
                    }
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            color = colors.secondaryContainer,
                            contentColor = colors.onSecondaryContainer
                        )
                    ) {
                        Text("关闭", fontSize = 13.sp, color = colors.onSecondaryContainer)
                    }
                }
            }
        }
    }
}

private fun openOrShareFile(context: Context, file: File, shareMode: Boolean) {
    runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            file
        )
        val ext = file.extension.lowercase(Locale.ROOT)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        val intent = if (shareMode) {
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                file.name
            )
        } else {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

private fun profileTypeLabel(type: ProfileType): String = when (type) {
    ProfileType.Text -> "文本"
    ProfileType.Image -> "图片"
    ProfileType.File -> "文件"
    ProfileType.Group -> "文件组"
    ProfileType.Unknown -> "未知"
    ProfileType.None -> "空"
}

private fun formatItemSize(type: ProfileType, size: Long): String {
    if (type == ProfileType.Text) {
        return "$size 字符"
    }
    return when {
        size >= 1024L * 1024L -> String.format(Locale.US, "%.2f MB", size / (1024.0 * 1024.0))
        size >= 1024L -> String.format(Locale.US, "%.1f KB", size / 1024.0)
        else -> "$size B"
    }
}

private fun formatHistoryTime(epochMs: Long): String {
    if (epochMs <= 0L) return "--"
    return runCatching {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))
    }.getOrDefault("--")
}
