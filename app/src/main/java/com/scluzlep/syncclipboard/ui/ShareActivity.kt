package com.scluzlep.syncclipboard.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scluzlep.syncclipboard.SyncClipboardApp
import com.scluzlep.syncclipboard.ui.theme.SyncInnerShape
import com.scluzlep.syncclipboard.ui.theme.SyncTheme
import com.scluzlep.syncclipboard.ui.theme.SyncThemeExtras
import com.scluzlep.syncclipboard.ui.theme.isDarkTheme
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private sealed interface ShareUiState {
    data object Uploading : ShareUiState
    data class Success(val message: String) : ShareUiState
    data class Error(val message: String) : ShareUiState
}

class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as SyncClipboardApp
        val engine = app.engine
        val (sharedText, uriStrings) = extractSharePayload(intent)

        setContent {
            val config by engine.configFlow.collectAsState()
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

            var shareState by remember { mutableStateOf<ShareUiState>(ShareUiState.Uploading) }

            LaunchedEffect(Unit) {
                if (sharedText.isNullOrEmpty() && uriStrings.isEmpty()) {
                    val msg = "未检测到可同步的文本或文件"
                    shareState = ShareUiState.Error(msg)
                    return@LaunchedEffect
                }
                val result = engine.shareUrisOrText(sharedText, uriStrings)
                result.onSuccess { summary ->
                    val display = summary.ifBlank { "已发送到 SyncClipboard" }
                    shareState = ShareUiState.Success(display)
                    Toast.makeText(this@ShareActivity, display, Toast.LENGTH_SHORT).show()
                    delay(850L)
                    finish()
                }.onFailure { err ->
                    shareState = ShareUiState.Error(err.message ?: err.javaClass.simpleName)
                }
            }

            SyncTheme(
                themeMode = config.themeMode,
                monetEnabled = config.monetEnabled,
                paletteStyle = config.paletteStyle
            ) {
                val colors = MiuixTheme.colorScheme
                val statusColors = SyncThemeExtras.statusColors

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.background.copy(alpha = 0.72f))
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(CardDefaults.CornerRadius),
                        color = colors.surfaceContainer,
                        border = BorderStroke(0.5.dp, colors.dividerLine)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "发送到 SyncClipboard",
                                style = MiuixTheme.textStyles.title3,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onSurfaceContainer
                            )

                            when (val current = shareState) {
                                ShareUiState.Uploading -> {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(22.dp),
                                            strokeWidth = 2.5.dp,
                                            color = colors.primary
                                        )
                                        Text(
                                            text = if (uriStrings.size > 1) {
                                                "正在打包 ${uriStrings.size} 个文件为 Group 并上传…"
                                            } else if (uriStrings.size == 1) {
                                                "正在读取并上传文件…"
                                            } else {
                                                "正在上传文本到活跃节点…"
                                            },
                                            style = MiuixTheme.textStyles.body2,
                                            color = colors.onSurfaceVariantSummary
                                        )
                                    }
                                }

                                is ShareUiState.Success -> {
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = SyncInnerShape,
                                        color = statusColors.okContainer
                                    ) {
                                        Text(
                                            text = current.message,
                                            modifier = Modifier.padding(12.dp),
                                            style = MiuixTheme.textStyles.body2,
                                            fontWeight = FontWeight.Medium,
                                            color = statusColors.onOkContainer
                                        )
                                    }
                                }

                                is ShareUiState.Error -> {
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = SyncInnerShape,
                                        color = statusColors.errorContainer
                                    ) {
                                        Text(
                                            text = current.message,
                                            modifier = Modifier.padding(12.dp),
                                            style = MiuixTheme.textStyles.body2,
                                            color = statusColors.onErrorContainer
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Button(
                                        onClick = { finish() },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(
                                            color = colors.secondaryContainer,
                                            contentColor = colors.onSecondaryContainer
                                        )
                                    ) {
                                        Text(
                                            text = "关闭",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = colors.onSecondaryContainer
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun extractSharePayload(intent: Intent?): Pair<String?, List<String>> {
        if (intent == null) return null to emptyList()
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                text to listOfNotNull(uri?.toString())
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                text to uris.orEmpty().map { it.toString() }
            }

            else -> null to emptyList()
        }
    }
}
