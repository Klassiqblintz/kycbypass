package com.hazbu.kycbypass.ui

import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.hazbu.kycbypass.KycBypassApp
import com.hazbu.kycbypass.R
import com.hazbu.kycbypass.service.CameraService
import com.hazbu.kycbypass.service.FloatingBubbleService
import com.hazbu.kycbypass.utils.AppPermissionManager
import com.hazbu.kycbypass.ui.PipelineDisplay

/**
 * Main activity — Compose UI for the KYC bypass app.
 *
 * Features:
 * 1. Media picker — select a video or image to use as virtual camera
 * 2. Target app selector — choose which apps get the virtual camera
 * 3. Controls — rotation, mirroring, video loop, enable/disable
 * 4. Service management — start/stop foreground services
 * 5. Status display
 */
class MainActivity : androidx.appcompat.app.AppCompatActivity() {

    private var cameraServiceRunning = false
    private var bubbleServiceRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request overlay permission for floating bubble
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Overlay permission needed for floating bubble", Toast.LENGTH_LONG).show()
            }
        }

        setContent {
            Material3Theme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    KycBypassScreen(
                        context = this@MainActivity,
                        onCameraServiceStateChanged = { running -> cameraServiceRunning = running },
                        onBubbleServiceStateChanged = { running -> bubbleServiceRunning = running }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KycBypassScreen(
    context: android.content.Context,
    onCameraServiceStateChanged: (Boolean) -> Unit,
    onBubbleServiceStateChanged: (Boolean) -> Unit
) {
    val settingsManager = KycBypassApp.settingsManager
    val isCameraActive = remember { derivedStateOf { settingsManager.isActive } }
    val mediaPath = remember { derivedStateOf { settingsManager.mediaPath } }

    var selectedMediaPath by remember { mutableStateOf(mediaPath.value) }
    var rotationAngle by remember { mutableIntStateOf(settingsManager.rotationAngle) }
    var isMirrored by remember { mutableStateOf(settingsManager.isMirrored) }
    var loopVideo by remember { mutableStateOf(settingsManager.loopVideo) }
    var selectedTargetApps by remember { mutableStateOf(settingsManager.getTargetApps()) }

    var showAppPicker by remember { mutableStateOf(false) }
    var showRotationPicker by remember { mutableStateOf(false) }

    // Pipeline state for display
    var camera1Active by remember { mutableStateOf(false) }
    var camera2Active by remember { mutableStateOf(false) }
    var captureHookActive by remember { mutableStateOf(false) }
    var imageReaderActive by remember { mutableStateOf(false) }
    var audioActive by remember { mutableStateOf(false) }
    var targetAppDetected by remember { mutableStateOf(false) }
    var detectedTargetAppName by remember { mutableStateOf("") }

    val contextRef = remember { context }

    // Fetch installed apps for the picker
    var installedApps by remember { mutableStateOf<List<PackageInfo>>(emptyList()) }
    LaunchedEffect(Unit) {
        val pm = contextRef.packageManager
        installedApps = pm.getInstalledPackages(0).filter { pkg ->
            pkg.packageName != contextRef.packageName
        }.map { it }.toList()
    }

    // Media picker launcher
    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val path = uri.path
            if (path != null) {
                selectedMediaPath = path
                settingsManager.mediaPath = path
                Toast.makeText(contextRef, "Media selected: ${uri.lastPathSegment}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Camera service launcher
    val startCameraService = {
        ContextCompat.startForegroundService(contextRef, Intent(contextRef, CameraService::class.java))
        onCameraServiceStateChanged(true)
        Toast.makeText(contextRef, "Camera service started", Toast.LENGTH_SHORT).show()
    }

    val stopCameraService = {
        contextRef.stopService(Intent(contextRef, CameraService::class.java))
        onCameraServiceStateChanged(false)
        Toast.makeText(contextRef, "Camera service stopped", Toast.LENGTH_SHORT).show()
    }

    // Bubble service launcher
    val startBubbleService = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(contextRef)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = android.net.Uri.parse("package:${contextRef.packageName}")
                }
                contextRef.startActivity(intent)
                Toast.makeText(contextRef, "Grant overlay permission to enable bubble", Toast.LENGTH_LONG).show()
            }
        }
        ContextCompat.startForegroundService(contextRef, Intent(contextRef, FloatingBubbleService::class.java))
        onBubbleServiceStateChanged(true)
        Toast.makeText(contextRef, "Floating bubble started", Toast.LENGTH_SHORT).show()
    }

    val stopBubbleService = {
        contextRef.stopService(Intent(contextRef, FloatingBubbleService::class.java))
        onBubbleServiceStateChanged(false)
        Toast.makeText(contextRef, "Floating bubble stopped", Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("KYC Bypass") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Status card
            item {
                StatusCard(
                    isCameraActive = isCameraActive.value,
                    mediaPath = selectedMediaPath,
                    onToggleActive = {
                        settingsManager.isActive = !settingsManager.isActive
                        Toast.makeText(contextRef,
                            if (settingsManager.isActive) "Virtual camera ON"
                            else "Virtual camera OFF",
                            Toast.LENGTH_SHORT).show()
                    }
                )
            }

            // Media selection
            item {
                MediaSelectionCard(
                    selectedPath = selectedMediaPath,
                    onPickMedia = { mediaPickerLauncher.launch("video/*") }
                )
            }

            // Rotation
            item {
                RotationCard(
                    currentRotation = rotationAngle,
                    onRotationChanged = { angle ->
                        rotationAngle = angle
                        settingsManager.rotationAngle = angle
                        Toast.makeText(contextRef, "Rotation: $angle", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            // Mirroring toggle
            item {
                MirroringCard(
                    isMirrored = isMirrored,
                    onToggle = {
                        isMirrored = !isMirrored
                        settingsManager.isMirrored = !isMirrored
                        Toast.makeText(contextRef,
                            if (isMirrored) "Mirroring ON"
                            else "Mirroring OFF",
                            Toast.LENGTH_SHORT).show()
                    }
                )
            }

            // Loop video toggle
            item {
                LoopVideoCard(
                    isLooping = loopVideo,
                    onToggle = {
                        loopVideo = !loopVideo
                        settingsManager.loopVideo = !loopVideo
                        Toast.makeText(contextRef,
                            if (loopVideo) "Loop ON" else "Loop OFF",
                            Toast.LENGTH_SHORT).show()
                    }
                )
            }

            // Target app selector
            item {
                TargetAppCard(
                    selectedApps = selectedTargetApps,
                    allApps = installedApps,
                    onOpenPicker = { showAppPicker = true }
                )
            }

            // Service controls
            item {
                ServiceControlsCard(
                    cameraServiceRunning = cameraServiceRunning,
                    bubbleServiceRunning = bubbleServiceRunning,
                    onStartCameraService = { startCameraService() },
                    onStopCameraService = { stopCameraService() },
                    onStartBubbleService = { startBubbleService() },
                    onStopBubbleService = { stopBubbleService() }
                )
            }

            // Pipeline display
            item {
                PipelineDisplay(
                    isCameraActive = isCameraActive.value,
                    mediaLoaded = selectedMediaPath != null,
                    camera1HookActive = camera1Active,
                    camera2HookActive = camera2Active,
                    captureHookActive = captureHookActive,
                    imageReaderHookActive = imageReaderActive,
                    audioHookActive = audioActive,
                    targetAppDetected = targetAppDetected,
                    targetAppName = detectedTargetAppName.ifEmpty { "None detected" }
                )
            }

            // Grant permissions card
            item {
                PermissionCard(
                    selectedApps = selectedTargetApps,
                    onGrantAll = {
                        for (pkg in selectedTargetApps) {
                            val pm = com.hazbu.kycbypass.utils.AppPermissionManager(contextRef)
                            pm.grantTargetAppPermissions(pkg)
                        }
                        Toast.makeText(contextRef, "Permissions granted to all target apps", Toast.LENGTH_SHORT).show()
                    },
                    onRevokeAll = {
                        for (pkg in selectedTargetApps) {
                            val pm = com.hazbu.kycbypass.utils.AppPermissionManager(contextRef)
                            pm.revokeTargetAppPermissions(pkg)
                        }
                        Toast.makeText(contextRef, "Permissions revoked from all target apps", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            // Disclaimer
            item {
                DisclaimerCard()
            }
        }

        // App picker dialog
        if (showAppPicker) {
            AppPickerDialog(
                allApps = installedApps,
                selectedApps = selectedTargetApps,
                onDismiss = { showAppPicker = false },
                onToggleApp = { packageName ->
                    if (settingsManager.hasTargetApp(packageName)) {
                        settingsManager.removeTargetApp(packageName)
                        selectedTargetApps = settingsManager.getTargetApps()
                    } else {
                        settingsManager.addTargetApp(packageName)
                        selectedTargetApps = settingsManager.getTargetApps()
                    }
                }
            )
        }
    }
}

// ========================
// Composable cards
// ========================

@Composable
private fun StatusCard(
    isCameraActive: Boolean,
    mediaPath: String?,
    onToggleActive: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isCameraActive)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (isCameraActive) "Virtual Camera: ACTIVE"
                        else "Virtual Camera: INACTIVE",
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (isCameraActive)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (mediaPath != null) {
                        Text(
                            text = "Media: ${android.net.Uri.parse(mediaPath).lastPathSegment ?: "unknown"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "No media selected",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Switch(
                    checked = isCameraActive,
                    onCheckedChange = { _ -> onToggleActive() }
                )
            }
        }
    }
}

@Composable
private fun MediaSelectionCard(
    selectedPath: String?,
    onPickMedia: () -> Unit
) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Virtual Media",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (selectedPath != null) {
                Text(
                    text = android.net.Uri.parse(selectedPath).lastPathSegment ?: selectedPath,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = "No media selected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onPickMedia) {
                Text("Select Video / Image")
            }
        }
    }
}

@Composable
private fun RotationCard(
    currentRotation: Int,
    onRotationChanged: (Int) -> Unit
) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Rotation",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                listOf(0, 90, 180, 270).forEach { angle ->
                    FilterChip(
                        selected = currentRotation == angle,
                        onClick = { onRotationChanged(angle) },
                        label = { Text("${angle}°") }
                    )
                }
            }
        }
    }
}

@Composable
private fun MirroringCard(
    isMirrored: Boolean,
    onToggle: () -> Unit
) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Mirror (Horizontal Flip)",
                    style = MaterialTheme.typography.titleMedium
                )
                Switch(
                    checked = isMirrored,
                    onCheckedChange = { _ -> onToggle() }
                )
            }
        }
    }
}

@Composable
private fun LoopVideoCard(
    isLooping: Boolean,
    onToggle: () -> Unit
) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Loop Video",
                    style = MaterialTheme.typography.titleMedium
                )
                Switch(
                    checked = isLooping,
                    onCheckedChange = { _ -> onToggle() }
                )
            }
        }
    }
}

@Composable
private fun TargetAppCard(
    selectedApps: Set<String>,
    allApps: List<PackageInfo>,
    onOpenPicker: () -> Unit
) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Target Apps (${selectedApps.size} selected)",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (selectedApps.isEmpty()) {
                Text(
                    text = "No apps selected. Tap to choose which apps get the virtual camera.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = selectedApps.joinToString(", ") { pkg ->
                        pkg.split(".").lastOrNull() ?: pkg
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onOpenPicker) {
                Text("Select Target Apps")
            }
        }
    }
}

@Composable
private fun ServiceControlsCard(
    cameraServiceRunning: Boolean,
    bubbleServiceRunning: Boolean,
    onStartCameraService: () -> Unit,
    onStopCameraService: () -> Unit,
    onStartBubbleService: () -> Unit,
    onStopBubbleService: () -> Unit
) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Services",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Camera Service")
                Row {
                    if (cameraServiceRunning) {
                        Button(
                            onClick = onStopCameraService,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("Stop")
                        }
                    } else {
                        Button(onClick = onStartCameraService) {
                            Text("Start")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Floating Bubble")
                Row {
                    if (bubbleServiceRunning) {
                        Button(
                            onClick = onStopBubbleService,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("Stop")
                        }
                    } else {
                        Button(onClick = onStartBubbleService) {
                            Text("Start")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DisclaimerCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Usage Notes",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "• Select a video/image to use as the virtual camera feed\n" +
                       "• Choose which apps should receive the virtual camera\n" +
                       "• Start the Camera Service before opening a target app\n" +
                       "• Start the Floating Bubble for quick toggle\n" +
                       "• Works best on rooted devices with Xposed/LSposed\n" +
                       "• Non-root devices may need overlay permission",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

// ========================
// Dialogs
// ========================

@Composable
private fun AppPickerDialog(
    allApps: List<PackageInfo>,
    selectedApps: Set<String>,
    onDismiss: () -> Unit,
    onToggleApp: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = { Text("Select Target Apps") },
        text = {
            Column {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search apps...") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))

                val filteredApps = allApps.filter { pkg ->
                    val appName = pkg.applicationInfo?.loadLabel(packageManager)?.toString() ?: ""
                    val packageName = pkg.packageName
                    searchQuery.isEmpty() ||
                            appName.contains(searchQuery, ignoreCase = true) ||
                            packageName.contains(searchQuery, ignoreCase = true)
                }.sortedBy { it.applicationInfo?.loadLabel(packageManager)?.toString() ?: "" }

                if (filteredApps.isEmpty()) {
                    Text("No matching apps", style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 400.dp)
                    ) {
                        items(filteredApps) { pkg ->
                            val packageName = pkg.packageName
                            val appName = pkg.applicationInfo?.loadLabel(packageManager)?.toString()
                                ?: packageName

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(appName, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Checkbox(
                                    checked = selectedApps.contains(packageName),
                                    onCheckedChange = { _ -> onToggleApp(packageName) }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onDismiss() }) {
                Text("Done")
            }
        }
    )
}

// ========================
// Permission card
// ========================

@Composable
private fun PermissionCard(
    selectedApps: Set<String>,
    onGrantAll: () -> Unit,
    onRevokeAll: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Target App Permissions",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Auto-grants CAMERA, STORAGE, and LOCATION permissions to selected apps so the virtual camera passes through cleanly.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = onGrantAll,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Grant All")
                }
                Button(
                    onClick = onRevokeAll,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onErrorContainer,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Revoke All")
                }
            }
        }
    }
}

// ========================
// Theme
// ========================

@Composable
private fun Material3Theme(content: @Composable () -> Unit) {
    val colorScheme = darkColorScheme(
        primary = Color(0xFF6750A6),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE9DDFF),
        onPrimaryContainer = Color(0xFF21005D),
        secondary = Color(0xFF625B71),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFE8DEF8),
        onSecondaryContainer = Color(0xFF1D192B),
        tertiary = Color(0xFF7D5260),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFFD8E4),
        onTertiaryContainer = Color(0xFF31111D),
        error = Color(0xFFBA1A1A),
        onError = Color.White,
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410001),
        background = Color(0xFF1C1B1F),
        onBackground = Color(0xFFE6E1E5),
        surface = Color(0xFF1C1B1F),
        onSurface = Color(0xFFE6E1E5),
        surfaceVariant = Color(0xFF49454F),
        onSurfaceVariant = Color(0xFFCAC4D0),
        outline = Color(0xFF938F99),
        outlineVariant = Color(0xFF49454F),
        inverseSurface = Color(0xFFE6E1E5),
        inverseOnSurface = Color(0xFF313033),
        inversePrimary = Color(0xFFD0BCFF),
        surfaceDim = Color(0xFF1C1B1F),
        surfaceBright = Color(0xFF444248),
        surfaceContainerLowest = Color(0xFF151418),
        surfaceContainerLow = Color(0xFF232226),
        surfaceContainer = Color(0xFF27262A),
        surfaceContainerHigh = Color(0xFF313034),
        surfaceContainerHighest = Color(0xFF3C3B3F)
    )

    androidx.compose.material3.MaterialTheme(
        colorScheme = colorScheme,
        typography = androidx.compose.material3.Typography(),
        content = content
    )
}
