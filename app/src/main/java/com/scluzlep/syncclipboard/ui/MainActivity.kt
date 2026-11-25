package com.scluzlep.syncclipboard.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.scluzlep.syncclipboard.ui.icons.SyncIcons
import com.scluzlep.syncclipboard.ui.screens.HistoryTab
import com.scluzlep.syncclipboard.ui.screens.NodesTab
import com.scluzlep.syncclipboard.ui.screens.OverviewTab
import com.scluzlep.syncclipboard.ui.screens.SettingsTab
import com.scluzlep.syncclipboard.ui.theme.SyncTheme
import com.scluzlep.syncclipboard.ui.theme.isDarkTheme
import com.scluzlep.syncclipboard.ui.viewmodel.MainTab
import com.scluzlep.syncclipboard.ui.viewmodel.MainViewModel
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingIntent(intent)

        setContent {
            val config by viewModel.configFlow.collectAsState()
            val selectedTab by viewModel.selectedTab.collectAsState()

            val systemDark = isSystemInDarkTheme()
            val darkTheme = isDarkTheme(config.themeMode, systemDark)

            LaunchedEffect(darkTheme) {
                val barStyle = if (darkTheme) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
            }

            val hostLifecycle = this@MainActivity.lifecycle
            DisposableEffect(hostLifecycle) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        viewModel.refreshServiceStatus()
                    }
                }
                hostLifecycle.addObserver(observer)
                onDispose {
                    hostLifecycle.removeObserver(observer)
                }
            }

            SyncTheme(
                themeMode = config.themeMode,
                monetEnabled = config.monetEnabled,
                paletteStyle = config.paletteStyle
            ) {
                val scrollBehavior = MiuixScrollBehavior()
                val colors = MiuixTheme.colorScheme

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        SmallTopAppBar(
                            title = "SyncClipboard",
                            subtitle = selectedTab.subtitle,
                            scrollBehavior = scrollBehavior,
                            actions = {
                                IconButton(
                                    onClick = {
                                        viewModel.refreshServiceStatus()
                                        if (selectedTab == MainTab.HISTORY) {
                                            viewModel.refreshHistory(resetPagination = true)
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Refresh,
                                        contentDescription = "刷新",
                                        modifier = Modifier.size(20.dp),
                                        tint = colors.onSurface
                                    )
                                }
                            }
                        )
                    },
                    bottomBar = {
                        NavigationBar(
                            mode = NavigationBarDisplayMode.IconAndText
                        ) {
                            NavigationBarItem(
                                selected = selectedTab == MainTab.OVERVIEW,
                                onClick = { viewModel.selectTab(MainTab.OVERVIEW) },
                                icon = Icons.Rounded.Home,
                                label = MainTab.OVERVIEW.title
                            )
                            NavigationBarItem(
                                selected = selectedTab == MainTab.NODES,
                                onClick = { viewModel.selectTab(MainTab.NODES) },
                                icon = SyncIcons.ServerNode,
                                label = MainTab.NODES.title
                            )
                            NavigationBarItem(
                                selected = selectedTab == MainTab.HISTORY,
                                onClick = { viewModel.selectTab(MainTab.HISTORY) },
                                icon = SyncIcons.HistoryClock,
                                label = MainTab.HISTORY.title
                            )
                            NavigationBarItem(
                                selected = selectedTab == MainTab.SETTINGS,
                                onClick = { viewModel.selectTab(MainTab.SETTINGS) },
                                icon = Icons.Rounded.Settings,
                                label = MainTab.SETTINGS.title
                            )
                        }
                    }
                ) { padding ->
                    when (selectedTab) {
                        MainTab.OVERVIEW -> OverviewTab(
                            viewModel = viewModel,
                            scrollBehavior = scrollBehavior,
                            contentPadding = padding
                        )

                        MainTab.NODES -> NodesTab(
                            viewModel = viewModel,
                            scrollBehavior = scrollBehavior,
                            contentPadding = padding
                        )

                        MainTab.HISTORY -> HistoryTab(
                            viewModel = viewModel,
                            scrollBehavior = scrollBehavior,
                            contentPadding = padding
                        )

                        MainTab.SETTINGS -> SettingsTab(
                            viewModel = viewModel,
                            scrollBehavior = scrollBehavior,
                            contentPadding = padding
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == ACTION_OPEN_SETTINGS) {
            viewModel.refreshServiceStatus()
            when (intent.getStringExtra(EXTRA_TAB)) {
                MainTab.NODES.name -> viewModel.selectTab(MainTab.NODES)
                MainTab.HISTORY.name -> viewModel.selectTab(MainTab.HISTORY)
                MainTab.SETTINGS.name -> viewModel.selectTab(MainTab.SETTINGS)
                MainTab.OVERVIEW.name -> viewModel.selectTab(MainTab.OVERVIEW)
            }
        }
    }

    companion object {
        const val ACTION_OPEN_SETTINGS = "com.scluzlep.syncclipboard.ACTION_OPEN_SETTINGS"
        const val EXTRA_TAB = "extra_tab"
    }
}
