package com.hazbu.kycbypass.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Pipeline display — visualizes the hook flow while the camera service is running.
 *
 * Shows each stage of the pipeline:
 * 1. Media loaded → VideoEngine → Camera1/Camera2 hooks → Target app
 * 2. Each hook's status (active/inactive/error)
 * 3. Connection lines between stages
 *
 * This gives the user a clear view of what's happening under the hood.
 */
@Composable
fun PipelineDisplay(
    isCameraActive: Boolean,
    mediaLoaded: Boolean,
    camera1HookActive: Boolean,
    camera2HookActive: Boolean,
    captureHookActive: Boolean,
    imageReaderHookActive: Boolean,
    audioHookActive: Boolean,
    targetAppDetected: Boolean,
    targetAppName: String
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Hook Pipeline",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Pipeline stages as a vertical flow
            PipelineStage("Media Source", mediaLoaded, "Video/Image loaded", isCameraActive)
            PipelineArrow(isCameraActive)
            PipelineStage("VideoEngine", mediaLoaded, "ExoPlayer pool active", isCameraActive)
            PipelineArrow(isCameraActive)

            // Dual path: Camera1 and Camera2
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PipelineStage("Camera1 Hook", camera1HookActive, "setPreviewTexture", isCameraActive && camera1HookActive)
                PipelineStage("Camera2 Hook", camera2HookActive, "OutputConfiguration", isCameraActive && camera2HookActive)
            }

            PipelineArrow(isCameraActive)

            // Capture hooks
            PipelineStage("Capture Hooks", captureHookActive, "Bitmap/MediaStore", isCameraActive && captureHookActive)
            PipelineArrow(isCameraActive)
            PipelineStage("ImageReader", imageReaderHookActive, "JPEG replacement", isCameraActive && imageReaderHookActive)
            PipelineArrow(isCameraActive)
            PipelineStage("Audio Injection", audioHookActive, "AudioRecord.read", isCameraActive && audioHookActive)
            PipelineArrow(isCameraActive)

            // Target app
            PipelineStage(
                "Target App: $targetAppName",
                targetAppDetected,
                "Virtual camera active",
                targetAppDetected
            )
        }
    }
}

@Composable
private fun PipelineStage(
    name: String,
    isActive: Boolean,
    description: String,
    glow: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status indicator
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(
                    color = if (isActive) Color(0xFF4CAF50) else Color(0xFF757575),
                    shape = androidx.compose.foundation.shape.CircleShape
                )
        )

        Spacer(modifier = Modifier.width(8.dp))

        Column {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isActive)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun PipelineArrow(isActive: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Arrow connector
        Text(
            text = "↓",
            style = MaterialTheme.typography.bodySmall,
            color = if (isActive)
                MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
        )
    }
}
