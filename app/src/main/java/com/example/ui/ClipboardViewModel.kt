package com.example.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.service.ClipboardItem
import com.example.service.ClipboardSyncService
import com.example.service.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class UiState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val serverName: String? = null,
    val serverIp: String? = null,
    val history: List<ClipboardItem> = emptyList(),
    val isServiceBound: Boolean = false,
    val isIgnoringBatteryOptimizations: Boolean = true,
    val isFloatingBubbleEnabled: Boolean = false
)

class ClipboardViewModel(application: Application) : AndroidViewModel(application), ServiceConnection {

    private companion object {
        const val TAG = "ClipboardViewModel"
    }

    private val context = application.applicationContext
    private var boundService: ClipboardSyncService? = null

    private val _isServiceBound = MutableStateFlow(false)
    val isServiceBound: StateFlow<Boolean> = _isServiceBound.asStateFlow()

    private val _serviceConnectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    private val _serverName = MutableStateFlow<String?>(null)
    private val _serverIp = MutableStateFlow<String?>(null)
    private val _clipboardHistory = MutableStateFlow<List<ClipboardItem>>(emptyList())
    private val _isIgnoringBatteryOptimizations = MutableStateFlow(true)
    private val _isFloatingBubbleEnabled = MutableStateFlow(false)
    private val prefs = context.getSharedPreferences("clipboard_prefs", Context.MODE_PRIVATE)

    val uiState: StateFlow<UiState> = combine(
        combine(
            _serviceConnectionState,
            _serverName,
            _serverIp,
            _clipboardHistory,
            _isServiceBound
        ) { state, name, ip, history, bound ->
            Triple(state, name, ip) to Pair(history, bound)
        },
        _isIgnoringBatteryOptimizations,
        _isFloatingBubbleEnabled
    ) { combined5, ignoreBattery, isBubbleEnabled ->
        val (triple, pair) = combined5
        val (state, name, ip) = triple
        val (history, bound) = pair
        UiState(
            connectionState = state,
            serverName = name,
            serverIp = ip,
            history = history,
            isServiceBound = bound,
            isIgnoringBatteryOptimizations = ignoreBattery,
            isFloatingBubbleEnabled = isBubbleEnabled
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = UiState()
    )

    init {
        _isFloatingBubbleEnabled.value = prefs.getBoolean("pref_floating_bubble_enabled", false)
        startAndBindService()
        checkBatteryOptimizations()
    }

    fun checkBatteryOptimizations() {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        if (powerManager != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            _isIgnoringBatteryOptimizations.value = powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            _isIgnoringBatteryOptimizations.value = true
        }
    }

    fun requestIgnoreBatteryOptimizations() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            try {
                val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request ignore battery optimizations", e)
            }
        }
    }

    fun startAndBindService() {
        Log.d(TAG, "Starting and binding ClipboardSyncService")
        val intent = Intent(context, ClipboardSyncService::class.java)
        // Ensure service runs even if UI is not active
        context.startService(intent)
        context.bindService(intent, this, Context.BIND_AUTO_CREATE)
    }

    fun unbindService() {
        if (_isServiceBound.value) {
            Log.d(TAG, "Unbinding service")
            context.unbindService(this)
            _isServiceBound.value = false
            boundService = null
        }
    }

    // --- Service Connection ---

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        Log.d(TAG, "Service connected to UI")
        val binder = service as? ClipboardSyncService.LocalBinder
        if (binder != null) {
            val serviceInstance = binder.getService()
            boundService = serviceInstance
            _isServiceBound.value = true

            // Synchronize floating bubble state immediately on bind
            serviceInstance.updateFloatingBubbleState()

            // Gather values and observe flows from Service
            viewModelScope.launch {
                serviceInstance.connectionState.collect { _serviceConnectionState.value = it }
            }
            viewModelScope.launch {
                serviceInstance.serverName.collect { _serverName.value = it }
            }
            viewModelScope.launch {
                serviceInstance.serverIp.collect { _serverIp.value = it }
            }
            viewModelScope.launch {
                serviceInstance.clipboardHistory.collect { _clipboardHistory.value = it }
            }

            // Trigger auto-discovery immediately if it was disconnected
            if (serviceInstance.connectionState.value == ConnectionState.DISCONNECTED) {
                serviceInstance.startAutoDiscovery()
            }
        }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        Log.d(TAG, "Service disconnected from UI")
        _isServiceBound.value = false
        boundService = null
    }

    // --- UI Triggerable Actions ---

    fun toggleSync() {
        val service = boundService ?: return
        if (service.connectionState.value == ConnectionState.DISCONNECTED) {
            service.startAutoDiscovery()
        } else {
            service.stopConnection()
        }
    }

    fun connectManually(ip: String) {
        val service = boundService ?: return
        service.connectManually(ip)
    }

    fun restartDiscovery() {
        val service = boundService ?: return
        service.stopConnection()
        service.startAutoDiscovery()
    }

    fun manualSyncCurrentClipboard() {
        val service = boundService ?: return
        service.syncCurrentClipboardManual()
    }

    fun clearHistory() {
        boundService?.clearHistory()
    }

    fun hasOverlayPermission(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.provider.Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    fun toggleFloatingBubble(enabled: Boolean) {
        prefs.edit().putBoolean("pref_floating_bubble_enabled", enabled).apply()
        _isFloatingBubbleEnabled.value = enabled
        boundService?.onFloatingBubbleSettingsChanged()
    }

    fun requestOverlayPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            try {
                val intent = Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${context.packageName}")
                ).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                } catch (e2: Exception) {
                    Log.e(TAG, "Failed to request overlay permission", e2)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        unbindService()
    }
}
