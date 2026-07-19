package com.example.ui

import androidx.compose.ui.text.TextStyle

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.service.ClipboardItem
import com.example.service.ConnectionState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: ClipboardViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val systemClipboard = LocalClipboardManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "ClipSync",
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.SansSerif,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 24.sp
                        )
                        Text(
                            "LOCAL NETWORK ONLY",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            letterSpacing = 1.2.sp
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                actions = {
                    if (uiState.history.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearHistory() }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Clear History",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)
        ) {
            // 1. Connection Status Card
            item {
                ConnectionStatusBlock(
                    state = uiState.connectionState,
                    serverName = uiState.serverName,
                    serverIp = uiState.serverIp,
                    onToggle = { viewModel.toggleSync() },
                    onRestart = { viewModel.restartDiscovery() },
                    onConnectManually = { viewModel.connectManually(it) }
                )
            }

            // 2. Main Quick Sync Buttons
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { viewModel.manualSyncCurrentClipboard() },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        shape = RoundedCornerShape(50.dp), // Beautiful fully rounded button
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        enabled = uiState.connectionState == ConnectionState.CONNECTED
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Sync Clipboard",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    FilledTonalButton(
                        onClick = {
                            val currentClip = systemClipboard.getText()?.text ?: ""
                            if (currentClip.isNotEmpty()) {
                                viewModel.manualSyncCurrentClipboard()
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(50.dp), // Beautiful fully rounded button
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Verify Content",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // 3. Informational Alert Banner (Android background privacy limitations explanation)
            item {
                InfoPrivacyCard()
            }

            // 3.5 Battery Optimization Alert Card (if not ignoring battery optimizations)
            if (!uiState.isIgnoringBatteryOptimizations) {
                item {
                    BatteryOptimizationCard(
                        onRequestIgnore = { viewModel.requestIgnoreBatteryOptimizations() }
                    )
                }
            }

            // 3.6 Floating Sync Bubble Card
            item {
                var hasOverlayPermission by remember { mutableStateOf(viewModel.hasOverlayPermission()) }
                
                // Refresh permission check state when floating bubble is turned on or off
                LaunchedEffect(uiState.isFloatingBubbleEnabled) {
                    hasOverlayPermission = viewModel.hasOverlayPermission()
                }

                FloatingBubbleCard(
                    isEnabled = uiState.isFloatingBubbleEnabled,
                    hasPermission = hasOverlayPermission,
                    onToggle = { checked ->
                        viewModel.toggleFloatingBubble(checked)
                    },
                    onRequestPermission = {
                        viewModel.requestOverlayPermission()
                    }
                )
            }

            // 4. Clipboard History List Header
            item {
                Text(
                    text = "Clipboard History",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                )
            }

            // 5. History list items or Empty State
            if (uiState.history.isEmpty()) {
                item {
                    EmptyStateBlock()
                }
            } else {
                items(uiState.history, key = { it.id }) { item ->
                    HistoryItemCard(
                        item = item,
                        onCopyClick = {
                            systemClipboard.setText(AnnotatedString(item.text))
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun ConnectionStatusBlock(
    state: ConnectionState,
    serverName: String?,
    serverIp: String?,
    onToggle: () -> Unit,
    onRestart: () -> Unit,
    onConnectManually: (String) -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    var showManualIpInput by remember { mutableStateOf(false) }
    var manualIpText by remember { mutableStateOf("") }

    val config = when (state) {
        ConnectionState.DISCONNECTED -> ConnectionStatusConfig(
            cardColor = MaterialTheme.colorScheme.errorContainer,
            textColor = MaterialTheme.colorScheme.onErrorContainer,
            statusText = "Service Stopped",
            statusDesc = "Automatic sync is currently disabled.",
            icon = Icons.Default.CloudOff,
            ledColor = Color.Red
        )
        ConnectionState.DISCOVERING -> ConnectionStatusConfig(
            cardColor = MaterialTheme.colorScheme.tertiaryContainer,
            textColor = MaterialTheme.colorScheme.onTertiaryContainer,
            statusText = "Searching Network...",
            statusDesc = "Broadcasting UDP packets to discover your Windows Clipboard Server...",
            icon = Icons.Default.Wifi,
            ledColor = Color(0xFFFFB300) // Beautiful Amber
        )
        ConnectionState.CONNECTED -> ConnectionStatusConfig(
            cardColor = MaterialTheme.colorScheme.primaryContainer,
            textColor = MaterialTheme.colorScheme.onPrimaryContainer,
            statusText = "Connected & Synced",
            statusDesc = "Connected to $serverName\nIP: $serverIp",
            icon = Icons.Default.Computer,
            ledColor = Color(0xFF2E7D32) // Emerald Green
        )
    }

    val cardColor = config.cardColor
    val textColor = config.textColor
    val statusText = config.statusText
    val statusDesc = config.statusDesc
    val icon = config.icon
    val ledColor = config.ledColor

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardColor as Color),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = icon as androidx.compose.ui.graphics.vector.ImageVector,
                        contentDescription = null,
                        tint = textColor as Color,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = statusText as String,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = textColor
                    )
                }

                // LED Pulse indicator for searching / connected states
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            if (state == ConnectionState.DISCOVERING) {
                                (ledColor as Color).copy(alpha = pulseAlpha)
                            } else {
                                ledColor as Color
                            }
                        )
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (state == ConnectionState.CONNECTED) {
                Text(
                    text = "Connected to $serverName",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = (textColor as Color).copy(alpha = 0.9f)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Professional Polish Grid System
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // IP Address block
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .padding(10.dp)
                    ) {
                        Text(
                            text = "IP ADDRESS",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = serverIp ?: "0.0.0.0",
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Discovery block
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .padding(10.dp)
                    ) {
                        Text(
                            text = "DISCOVERY",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "UDP Broadcast",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Text(
                    text = statusDesc as String,
                    fontSize = 14.sp,
                    color = (textColor as Color).copy(alpha = 0.85f),
                    lineHeight = 18.sp
                )
            }

            if (state != ConnectionState.CONNECTED) {
                Spacer(modifier = Modifier.height(12.dp))
                if (!showManualIpInput) {
                    TextButton(
                        onClick = { showManualIpInput = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = (textColor as Color).copy(alpha = 0.85f)),
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Connect Manually via IP", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(16.dp)
                            )
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "Enter Windows PC IP Address",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = (textColor as Color).copy(alpha = 0.8f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = manualIpText,
                                onValueChange = { manualIpText = it },
                                placeholder = { Text("e.g. 192.168.1.100", fontSize = 13.sp) },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                textStyle = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                                modifier = Modifier.weight(1f),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = textColor as Color,
                                    unfocusedBorderColor = (textColor as Color).copy(alpha = 0.4f),
                                    focusedTextColor = textColor as Color,
                                    unfocusedTextColor = textColor as Color,
                                    focusedPlaceholderColor = (textColor as Color).copy(alpha = 0.5f),
                                    unfocusedPlaceholderColor = (textColor as Color).copy(alpha = 0.5f)
                                )
                            )
                            
                            Button(
                                onClick = {
                                    if (manualIpText.isNotBlank()) {
                                        onConnectManually(manualIpText.trim())
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = textColor as Color,
                                    contentColor = cardColor as Color
                                ),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp)
                            ) {
                                Text("Connect", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        TextButton(
                            onClick = { showManualIpInput = false },
                            colors = ButtonDefaults.textButtonColors(contentColor = (textColor as Color).copy(alpha = 0.7f)),
                            modifier = Modifier.align(Alignment.End),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text("Cancel", fontSize = 11.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state == ConnectionState.DISCOVERING) {
                    TextButton(onClick = onRestart) {
                        Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Retry Discovery")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Button(
                    onClick = onToggle,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state == ConnectionState.DISCONNECTED) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    ),
                    shape = RoundedCornerShape(50.dp), // Fully rounded matching rounded-full
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Text(
                        if (state == ConnectionState.DISCONNECTED) "Start Connection" else "Disconnect",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
fun InfoPrivacyCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = "Android Privacy Notice",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Due to Android 10+ background limitations, automatic clipboard reading is restricted when the app is in the background. To sync text copied from other apps, tap 'Sync Clipboard' or tap 'Copy' from incoming alerts.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
fun HistoryItemCard(
    item: ClipboardItem,
    onCopyClick: () -> Unit
) {
    val formatter = remember { SimpleDateFormat("hh:mm:ss a", Locale.getDefault()) }
    val timeString = remember(item.timestamp) { formatter.format(Date(item.timestamp)) }
    
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(12.dp),
        border = CardBorder(item.isSent)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (item.isSent) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                        contentDescription = null,
                        tint = if (item.isSent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (item.isSent) "Sent to Windows" else "Received from Windows",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }

                Text(
                    text = timeString,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = item.text,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(
                    onClick = onCopyClick,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy to device clipboard",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun CardBorder(isSent: Boolean): androidx.compose.foundation.BorderStroke {
    val color = if (isSent) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
    } else {
        MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f)
    }
    return androidx.compose.foundation.BorderStroke(1.dp, color)
}

@Composable
fun EmptyStateBlock(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No clipboards synced yet",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Once you connect, copy some text on either device and it will show up here instantly.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
fun BatteryOptimizationCard(onRequestIgnore: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "مزامنة مستقرة في الخلفية",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "لضمان استمرار المزامنة عندما تكون الشاشة مغلقة أو التطبيق في الخلفية، يرجى استثناء التطبيق من تحسين استهلاك البطارية.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                        lineHeight = 16.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = onRequestIgnore,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.align(Alignment.End),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("إعدادات استثناء البطارية", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun FloatingBubbleCard(
    isEnabled: Boolean,
    hasPermission: Boolean,
    onToggle: (Boolean) -> Unit,
    onRequestPermission: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Sync,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "الزر العائم للمزامنة السريعة",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                Switch(
                    checked = isEnabled,
                    onCheckedChange = { checked ->
                        if (checked && !hasPermission) {
                            onRequestPermission()
                        } else {
                            onToggle(checked)
                        }
                    },
                    modifier = Modifier.scale(0.85f)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "يظهر زر عائم صغير فوق كل التطبيقات. بمجرد نسخ أي نص، اضغط على الزر ليتم إرساله فوراً إلى الكمبيوتر دون مغادرة تطبيقك الحالي.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                lineHeight = 16.sp
            )
            
            if (isEnabled && !hasPermission) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "⚠️ يتطلب إذن الظهور فوق التطبيقات",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Button(
                        onClick = onRequestPermission,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("منح الإذن", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private data class ConnectionStatusConfig(
    val cardColor: Color,
    val textColor: Color,
    val statusText: String,
    val statusDesc: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val ledColor: Color
)
