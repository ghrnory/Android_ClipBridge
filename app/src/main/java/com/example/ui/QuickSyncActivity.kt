package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.service.ClipboardSyncService
import com.example.service.ConnectionState
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay

class QuickSyncActivity : ComponentActivity() {

    companion object {
        const val TAG = "QuickSyncActivity"
        const val ACTION_SYNC_TO_PC = "com.example.ACTION_SYNC_TO_PC"
        const val ACTION_COPY_TO_PHONE = "com.example.ACTION_COPY_TO_PHONE"
        const val EXTRA_TEXT = "com.example.EXTRA_TEXT"
    }

    private var syncService: ClipboardSyncService? = null
    private var isServiceBound = false
    
    // Live states for Compose
    private val _connectionState = mutableStateOf<ConnectionState>(ConnectionState.DISCONNECTED)
    private val _serverName = mutableStateOf<String?>(null)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? ClipboardSyncService.LocalBinder
            if (binder != null) {
                val s = binder.getService()
                syncService = s
                isServiceBound = true
                _connectionState.value = s.connectionState.value
                _serverName.value = s.serverName.value
                
                // Handle silent quick action if triggered from notification
                handleSilentActionIfNeeded()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            syncService = null
            isServiceBound = false
            _connectionState.value = ConnectionState.DISCONNECTED
            _serverName.value = null
        }
    }

    private fun handleSilentActionIfNeeded() {
        val action = intent?.action ?: ""
        val s = syncService ?: return

        when (action) {
            ACTION_SYNC_TO_PC -> {
                val text = getSystemClipboardText()
                if (text.isNotEmpty()) {
                    if (s.connectionState.value == ConnectionState.CONNECTED) {
                        s.syncText(text)
                        Toast.makeText(this, "📤 تمت مزامنة حافظة الهاتف مع الكمبيوتر!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "⚠️ الكمبيوتر غير متصل حالياً", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "ℹ️ حافظة الهاتف فارغة", Toast.LENGTH_SHORT).show()
                }
                safeUnbindAndFinish()
            }
            ACTION_COPY_TO_PHONE -> {
                val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
                if (text.isNotEmpty()) {
                    copyTextToLocalClipboard(text)
                }
                safeUnbindAndFinish()
            }
            Intent.ACTION_PROCESS_TEXT -> {
                val text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString() ?: ""
                } else {
                    ""
                }
                if (text.isNotEmpty()) {
                    if (s.connectionState.value == ConnectionState.CONNECTED) {
                        s.syncText(text)
                        Toast.makeText(this, "📤 تم إرسال النص المحدد إلى الكمبيوتر!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "⚠️ الكمبيوتر غير متصل حالياً", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "ℹ️ النص المحدد فارغ", Toast.LENGTH_SHORT).show()
                }
                safeUnbindAndFinish()
            }
        }
    }

    private fun safeUnbindAndFinish() {
        if (isServiceBound) {
            try {
                unbindService(serviceConnection)
                isServiceBound = false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unbind service", e)
            }
        }
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Hide transition animations to make it completely seamless
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }

        val action = intent?.action ?: ""
        Log.d(TAG, "QuickSyncActivity started with action: $action")

        val isSilent = action == ACTION_SYNC_TO_PC || action == ACTION_COPY_TO_PHONE || action == Intent.ACTION_PROCESS_TEXT

        // Bind to service
        try {
            val intent = Intent(this, ClipboardSyncService::class.java)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind service", e)
        }

        var textToSync = ""
        var isIncoming = false

        when (action) {
            ACTION_SYNC_TO_PC -> {
                textToSync = getSystemClipboardText()
                isIncoming = false
            }
            ACTION_COPY_TO_PHONE -> {
                textToSync = intent.getStringExtra(EXTRA_TEXT) ?: ""
                isIncoming = true
            }
            Intent.ACTION_PROCESS_TEXT -> {
                textToSync = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString() ?: ""
                } else {
                    ""
                }
                isIncoming = false
            }
        }

        if (!isSilent) {
            setContent {
                MyApplicationTheme {
                    QuickSyncBottomSheet(
                        text = textToSync,
                        isIncoming = isIncoming,
                        connectionState = _connectionState.value,
                        serverName = _serverName.value,
                        onSyncRequested = { text ->
                            if (isIncoming) {
                                copyTextToLocalClipboard(text)
                            } else {
                                sendTextToPc(text)
                            }
                        },
                        onDismiss = {
                            animateDismissAndFinish()
                        }
                    )
                }
            }
        }
    }

    private fun getSystemClipboardText(): String {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val primaryClip = clipboard.primaryClip
            if (primaryClip != null && primaryClip.itemCount > 0) {
                return primaryClip.getItemAt(0).text?.toString() ?: ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read system clipboard", e)
        }
        return ""
    }

    private fun copyTextToLocalClipboard(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Synced Clipboard", text)
            clipboard.setPrimaryClip(clip)
            
            syncService?.let { service ->
                service.syncText(text)
            }
            Toast.makeText(this, "تم النسخ إلى حافظة الهاتف!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy to phone clipboard", e)
            Toast.makeText(this, "فشل في نسخ النص", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendTextToPc(text: String) {
        val s = syncService
        if (s != null) {
            if (s.connectionState.value == ConnectionState.CONNECTED) {
                s.syncText(text)
                Toast.makeText(this, "تم إرسال النص إلى الكمبيوتر!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "الكمبيوتر غير متصل حالياً", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "جاري تهيئة الاتصال بالخدمة...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun animateDismissAndFinish() {
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            try {
                unbindService(serviceConnection)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unbind service", e)
            }
        }
    }
}

@Composable
fun QuickSyncBottomSheet(
    text: String,
    isIncoming: Boolean,
    connectionState: ConnectionState,
    serverName: String?,
    onSyncRequested: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var animateIn by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        animateIn = true
    }

    val handleDismiss = {
        animateIn = false
    }
    
    LaunchedEffect(animateIn) {
        if (!animateIn) {
            delay(280) // Wait for slide-out animation
            onDismiss()
        }
    }
    
    val bgAlpha by animateFloatAsState(
        targetValue = if (animateIn) 0.5f else 0.0f,
        animationSpec = tween(durationMillis = 280),
        label = "bgAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = bgAlpha))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                handleDismiss()
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedVisibility(
            visible = animateIn,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = tween(durationMillis = 280)
            ) + fadeIn(animationSpec = tween(durationMillis = 180)),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(durationMillis = 280)
            ) + fadeOut(animationSpec = tween(durationMillis = 180))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* Consume clicks to prevent dismissal */ }
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Drag handle indicator
                Box(
                    modifier = Modifier
                        .width(44.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                )
                
                Spacer(modifier = Modifier.height(20.dp))
                
                // Content Title with Icon
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = if (isIncoming) Icons.Default.ContentCopy else Icons.Default.Sync,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (isIncoming) "استلام نص من الكمبيوتر" else "مزامنة سريعة مع الكمبيوتر",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                
                Spacer(modifier = Modifier.height(10.dp))
                
                // Connection state pill
                val isConnected = connectionState == ConnectionState.CONNECTED
                val statusText = if (isConnected) {
                    "متصل بـ: ${serverName ?: "جهاز الكمبيوتر"}"
                } else {
                    "غير متصل بالكمبيوتر حالياً"
                }
                val statusBg = if (isConnected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                } else {
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                }
                val statusTextColor = if (isConnected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                }
                
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50.dp))
                        .background(statusBg)
                        .padding(horizontal = 14.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(RoundedCornerShape(50.dp))
                            .background(if (isConnected) Color(0xFF4CAF50) else Color(0xFFF44336))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = statusText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = statusTextColor
                    )
                }
                
                Spacer(modifier = Modifier.height(18.dp))
                
                // Text view card
                if (text.isNotEmpty()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Text(
                                text = text,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Right,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                } else {
                    Text(
                        text = "لا يوجد نص محدد أو منسوخ حالياً",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Actions Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { handleDismiss() },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("تجاهل", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    
                    Button(
                        onClick = {
                            onSyncRequested(text)
                            handleDismiss()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        enabled = text.isNotEmpty() && (isIncoming || isConnected),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(
                            text = if (isIncoming) "حفظ بالهاتف" else "إرسال الآن",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}
