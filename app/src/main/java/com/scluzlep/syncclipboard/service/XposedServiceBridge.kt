package com.scluzlep.syncclipboard.service

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class XposedServiceState(
    val isServiceBound: Boolean = false,
    val frameworkName: String = "",
    val frameworkVersion: String = "",
    val frameworkVersionCode: Long = 0L,
    val apiVersion: Int = 0,
    val scopePackages: List<String> = emptyList(),
    val scopeSystemUi: Boolean = false,
    val scopeSystem: Boolean = false,
    val runningTargets: List<HookedTarget> = emptyList(),
    val scopeRequestMessage: String = "",
    val hotReloadStatusMessage: String = ""
)

/**
 * Coordinates LSPosed API 102 `XposedService` binding, scope inspection/requests, and hot reload.
 */
object XposedServiceBridge {

    private val REQUIRED_SCOPES = listOf("com.android.systemui", "system")

    private val initialized = AtomicBoolean(false)

    @Volatile
    private var xposedService: XposedService? = null

    private val _stateFlow = MutableStateFlow(XposedServiceState())
    val stateFlow: StateFlow<XposedServiceState> = _stateFlow.asStateFlow()

    private val serviceListener = object : XposedServiceHelper.OnServiceListener {
        override fun onServiceBind(service: XposedService) {
            xposedService = service
            refreshState(service)
        }

        override fun onServiceDied(service: XposedService) {
            if (xposedService === service) {
                xposedService = null
                _stateFlow.value = XposedServiceState(isServiceBound = false)
            }
        }
    }

    fun init(@Suppress("UNUSED_PARAMETER") context: Context? = null) {
        if (!initialized.compareAndSet(false, true)) return
        runCatching {
            XposedServiceHelper.registerListener(serviceListener)
        }
    }

    fun refreshStatus() {
        val service = xposedService ?: return
        refreshState(service)
    }

    fun getRemotePreferences(name: String): SharedPreferences? {
        val service = xposedService ?: return null
        return runCatching { service.getRemotePreferences(name) }.getOrNull()
    }

    fun requestScopes(onResult: ((approved: Boolean, message: String) -> Unit)? = null): Boolean {
        val service = xposedService
        if (service == null) {
            val msg = "XposedService 未连接，请在 LSPosed 管理器中启用模块作用域"
            _stateFlow.value = _stateFlow.value.copy(scopeRequestMessage = msg)
            onResult?.invoke(false, msg)
            return false
        }
        return runCatching {
            service.requestScope(REQUIRED_SCOPES, object : XposedService.OnScopeEventListener {
                override fun onScopeRequestApproved(approved: MutableList<String>) {
                    refreshState(service)
                    val msg = "已获作用域授权：${approved.joinToString()}"
                    _stateFlow.value = _stateFlow.value.copy(scopeRequestMessage = msg)
                    onResult?.invoke(true, msg)
                }

                override fun onScopeRequestFailed(message: String) {
                    val msg = "作用域申请失败：$message"
                    _stateFlow.value = _stateFlow.value.copy(scopeRequestMessage = msg)
                    onResult?.invoke(false, msg)
                }
            })
            true
        }.getOrElse { err ->
            val msg = "作用域申请异常：${err.message ?: err.javaClass.simpleName}"
            _stateFlow.value = _stateFlow.value.copy(scopeRequestMessage = msg)
            onResult?.invoke(false, msg)
            false
        }
    }

    fun hotReloadAll(onComplete: ((String) -> Unit)? = null) {
        val service = xposedService
        if (service == null) {
            val msg = "XposedService 未连接，无法热重载"
            _stateFlow.value = _stateFlow.value.copy(hotReloadStatusMessage = msg)
            onComplete?.invoke(msg)
            return
        }
        val targets = runCatching { service.getRunningTargets() }.getOrDefault(emptyList())
        if (targets.isEmpty()) {
            val msg = "当前没有运行中的已注入目标进程"
            _stateFlow.value = _stateFlow.value.copy(hotReloadStatusMessage = msg)
            onComplete?.invoke(msg)
            return
        }
        val remaining = AtomicInteger(targets.size)
        val summary = Collections.synchronizedList(mutableListOf<String>())
        for (target in targets) {
            runCatching {
                service.hotReloadModule(target, null) { hookedTarget, result ->
                    val status = result.status()
                    val detail = result.message().orEmpty()
                    summary.add(
                        "${hookedTarget.processName}: ${status.name}" +
                            if (detail.isNotBlank()) " ($detail)" else ""
                    )
                    if (remaining.decrementAndGet() == 0) {
                        refreshState(service)
                        val combined = summary.joinToString("; ")
                        _stateFlow.value = _stateFlow.value.copy(hotReloadStatusMessage = combined)
                        onComplete?.invoke(combined)
                    }
                }
            }.onFailure { err ->
                summary.add("${target.processName}: FAILED (${err.message ?: "error"})")
                if (remaining.decrementAndGet() == 0) {
                    refreshState(service)
                    val combined = summary.joinToString("; ")
                    _stateFlow.value = _stateFlow.value.copy(hotReloadStatusMessage = combined)
                    onComplete?.invoke(combined)
                }
            }
        }
    }

    private fun refreshState(service: XposedService) {
        val scope = runCatching { service.scope }.getOrDefault(emptyList())
        val targets = runCatching { service.getRunningTargets() }.getOrDefault(emptyList())
        val hasSystemUi = scope.contains("com.android.systemui")
        val hasSystem = scope.contains("system") || scope.contains("android")
        _stateFlow.value = _stateFlow.value.copy(
            isServiceBound = true,
            frameworkName = runCatching { service.frameworkName }.getOrDefault(""),
            frameworkVersion = runCatching { service.frameworkVersion }.getOrDefault(""),
            frameworkVersionCode = runCatching { service.frameworkVersionCode }.getOrDefault(0L),
            apiVersion = runCatching { service.apiVersion }.getOrDefault(0),
            scopePackages = scope,
            scopeSystemUi = hasSystemUi,
            scopeSystem = hasSystem,
            runningTargets = targets
        )
    }
}
