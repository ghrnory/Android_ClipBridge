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
    val isServiceBound: Boolean = false
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

    val uiState: StateFlow<UiState> = combine(
        _serviceConnectionState,
        _serverName,
        _serverIp,
        _clipboardHistory,
        _isServiceBound
    ) { state, name, ip, history, bound ->
        UiState(
            connectionState = state,
            serverName = name,
            serverIp = ip,
            history = history,
            isServiceBound = bound
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = UiState()
    )

    init {
        startAndBindService()
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

    override fun onCleared() {
        super.onCleared()
        unbindService()
    }
}
