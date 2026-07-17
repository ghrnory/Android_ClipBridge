package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.network.ClipboardSocketClient
import com.example.network.UdpDiscoveryClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

enum class ConnectionState {
    DISCONNECTED,
    DISCOVERING,
    CONNECTED
}

data class ClipboardItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isSent: Boolean
)

class ClipboardSyncService : Service(), ClipboardSocketClient.Callback {

    private companion object {
        const val TAG = "ClipboardSyncService"
        const val CHANNEL_ID = "ClipboardSyncForegroundChannel"
        const val NOTIFICATION_ID = 4224
        const val ACTION_COPY = "com.example.ACTION_COPY"
        const val EXTRA_TEXT = "com.example.EXTRA_TEXT"
    }

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private val binder = LocalBinder()
    private var clipboardManager: ClipboardManager? = null
    private var socketClient: ClipboardSocketClient? = null
    private var discoveryJob: Job? = null
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null

    private var isManualConnection = false
    private var manualIp: String? = null
    private var manualPort: Int = 19001

    // State flows for the UI to observe
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _serverName = MutableStateFlow<String?>(null)
    val serverName: StateFlow<String?> = _serverName

    private val _serverIp = MutableStateFlow<String?>(null)
    val serverIp: StateFlow<String?> = _serverIp

    private val _clipboardHistory = MutableStateFlow<List<ClipboardItem>>(emptyList())
    val clipboardHistory: StateFlow<List<ClipboardItem>> = _clipboardHistory

    // Track last synced text to prevent feedback loops
    private var lastSyncedText: String? = null

    inner class LocalBinder : Binder() {
        fun getService(): ClipboardSyncService = this@ClipboardSyncService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        
        // Listen for clipboard changes
        clipboardManager?.addPrimaryClipChangedListener(clipListener)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification("Not Connected", "Waiting to start discovery..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand action=${intent?.action}")
        if (intent?.action == ACTION_COPY) {
            val textToCopy = intent.getStringExtra(EXTRA_TEXT)
            if (!textToCopy.isNullOrEmpty()) {
                copyToClipboardLocal(textToCopy)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "Service onBind")
        return binder
    }

    // --- Clipboard Monitoring and Copying ---

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        serviceScope.launch {
            val primaryClip = clipboardManager?.primaryClip
            if (primaryClip != null && primaryClip.itemCount > 0) {
                val text = primaryClip.getItemAt(0).text?.toString()
                if (!text.isNullOrEmpty()) {
                    onLocalClipboardChanged(text)
                }
            }
        }
    }

    private fun onLocalClipboardChanged(text: String) {
        if (text == lastSyncedText) {
            // Avoid duplicate loops
            return
        }

        Log.d(TAG, "Local clipboard changed: $text")
        
        // Send to WebSocket server if connected
        if (_connectionState.value == ConnectionState.CONNECTED) {
            val sentSuccessfully = socketClient?.sendClipboardText(text) == true
            if (sentSuccessfully) {
                lastSyncedText = text
                addHistoryItem(ClipboardItem(text = text, isSent = true))
            }
        } else {
            Log.d(TAG, "Clipboard changed but socket is not connected")
        }
    }

    fun syncCurrentClipboardManual() {
        val primaryClip = clipboardManager?.primaryClip
        if (primaryClip != null && primaryClip.itemCount > 0) {
            val text = primaryClip.getItemAt(0).text?.toString()
            if (!text.isNullOrEmpty()) {
                Log.d(TAG, "Manual sync triggered: $text")
                onLocalClipboardChanged(text)
            } else {
                Toast.makeText(this, "Local clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Local clipboard is empty", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyToClipboardLocal(text: String) {
        try {
            lastSyncedText = text // update this so clipListener ignores this change
            val clip = ClipData.newPlainText("Synced Clipboard", text)
            clipboardManager?.setPrimaryClip(clip)
            Toast.makeText(this, "Copied to phone Clipboard!", Toast.LENGTH_SHORT).show()
            
            // Remove the "New text available" copy action from notifications
            updateNotificationOnCopy()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy locally", e)
        }
    }

    // --- History Management ---

    private fun addHistoryItem(item: ClipboardItem) {
        val currentList = _clipboardHistory.value.toMutableList()
        // Prevent duplicate adjacent histories
        if (currentList.isNotEmpty() && currentList.first().text == item.text) {
            return
        }
        currentList.add(0, item)
        // Keep history to 50 items
        if (currentList.size > 50) {
            currentList.removeAt(currentList.size - 1)
        }
        _clipboardHistory.value = currentList
    }

    fun clearHistory() {
        _clipboardHistory.value = emptyList()
    }

    // --- Discovery and Connections ---

    fun startAutoDiscovery() {
        if (_connectionState.value != ConnectionState.DISCONNECTED) return
        isManualConnection = false

        // Acquire MulticastLock to make sure Android receives UDP broadcasts on all devices
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("ClipboardSyncMulticastLock")?.apply {
                    setReferenceCounted(true)
                }
            }
            if (multicastLock?.isHeld == false) {
                multicastLock?.acquire()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire multicast lock", e)
        }

        discoveryJob?.cancel()
        discoveryJob = serviceScope.launch {
            _connectionState.value = ConnectionState.DISCOVERING
            updateForegroundNotification("Discovering...", "Searching for Windows Clipboard Server...")

            var attempts = 0
            while (_connectionState.value == ConnectionState.DISCOVERING) {
                attempts++
                Log.d(TAG, "Discovery attempt #$attempts")
                val serverInfo = UdpDiscoveryClient.discoverServer(this@ClipboardSyncService)
                if (serverInfo != null) {
                    _serverName.value = serverInfo.name
                    _serverIp.value = serverInfo.ipAddress
                    connectToWebSocket(serverInfo.ipAddress, serverInfo.port)
                    break
                }
                // Wait 4 seconds before trying to broadcast again
                delay(4000)
            }
        }
    }

    fun stopConnection() {
        isManualConnection = false
        manualIp = null
        discoveryJob?.cancel()
        socketClient?.disconnect()
        socketClient = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _serverName.value = null
        _serverIp.value = null
        updateForegroundNotification("Disconnected", "Auto-sync is disabled")

        // Release multicast lock if held
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release multicast lock", e)
        }
    }

    fun connectManually(ip: String, port: Int = 19001) {
        discoveryJob?.cancel()
        isManualConnection = true
        manualIp = ip
        manualPort = port

        _connectionState.value = ConnectionState.DISCOVERING
        _serverName.value = "Windows PC (Manual)"
        _serverIp.value = ip
        updateForegroundNotification("Connecting...", "Connecting to $ip:$port")
        connectToWebSocket(ip, port)
    }

    private fun connectToWebSocket(ip: String, port: Int) {
        socketClient?.disconnect()
        socketClient = ClipboardSocketClient(ip, port, this).apply {
            connect()
        }
    }

    // --- WebSocket Callback ---

    override fun onConnected() {
        serviceScope.launch {
            _connectionState.value = ConnectionState.CONNECTED
            val server = _serverName.value ?: "Windows PC"
            updateForegroundNotification("Connected to $server", "Real-time clipboard synchronization active")
            
            // Sync immediately on connection
            syncCurrentClipboardManual()
        }
    }

    override fun onDisconnected(reason: String) {
        serviceScope.launch {
            if (_connectionState.value == ConnectionState.CONNECTED || _connectionState.value == ConnectionState.DISCOVERING) {
                Log.w(TAG, "WebSocket disconnected: $reason")
                _connectionState.value = ConnectionState.DISCONNECTED
                _serverName.value = null
                _serverIp.value = null
                
                if (isManualConnection) {
                    val ip = manualIp
                    if (ip != null) {
                        updateForegroundNotification("Reconnecting...", "Retrying connection to $ip:$manualPort in 5s")
                        delay(5000)
                        if (_connectionState.value == ConnectionState.DISCONNECTED && isManualConnection) {
                            connectManually(ip, manualPort)
                        }
                    }
                } else {
                    // Trigger auto discovery to reconnect automatically!
                    startAutoDiscovery()
                }
            }
        }
    }

    override fun onMessageReceived(text: String) {
        if (text == lastSyncedText) return

        serviceScope.launch {
            lastSyncedText = text
            addHistoryItem(ClipboardItem(text = text, isSent = false))
            
            // Copy directly to the system clipboard
            copyToClipboardLocal(text)
            
            // Post a system notification with an instant Copy button action as a fallback/alert
            postIncomingTextNotification(text)
        }
    }

    // --- Notification Helper functions ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Clipboard Sync Service Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Clipboard Sync running in the background"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(title: String, text: String): Notification {
        val pendingIntent = Intent(this, MainActivity::class.java).let { notificationIntent ->
            PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share) // Temporary built-in icon, clean look
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun updateForegroundNotification(title: String, text: String) {
        val notification = buildForegroundNotification(title, text)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, notification)
    }

    private fun postIncomingTextNotification(text: String) {
        val truncatedText = if (text.length > 50) text.take(47) + "..." else text

        // Action to copy the text directly from the notification
        val copyIntent = Intent(this, ClipboardSyncService::class.java).apply {
            action = ACTION_COPY
            putExtra(EXTRA_TEXT, text)
        }
        val copyPendingIntent = PendingIntent.getService(
            this,
            1,
            copyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("New Clipboard Text Received")
            .setContentText(truncatedText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(mainPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .addAction(android.R.drawable.ic_menu_save, "Copy to Clipboard", copyPendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        // Use a different notification ID for incoming messages so the service notification isn't replaced!
        manager?.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun updateNotificationOnCopy() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.cancel(NOTIFICATION_ID + 1)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")
        clipboardManager?.removePrimaryClipChangedListener(clipListener)
        stopConnection()
        serviceJob.cancel()
    }
}
