package com.hazbu.kycbypass.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.core.content.ContextCompat
import com.hazbu.kycbypass.KycBypassApp
import com.hazbu.kycbypass.R
import com.hazbu.kycbypass.service.CameraService
import com.hazbu.kycbypass.service.FloatingBubbleService
import com.hazbu.kycbypass.utils.AppPermissionManager

class MainActivity : AppCompatActivity() {

    private val settingsManager = KycBypassApp.settingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Overlay permission check
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = android.net.Uri.parse("package:${packageName}")
                }
                startActivity(intent)
                Toast.makeText(this, "Grant overlay permission for floating bubble", Toast.LENGTH_LONG).show()
            }
        }

        setupUI()
    }

    private fun setupUI() {
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val btnStartCamera = findViewById<Button>(R.id.btnStartCamera)
        val btnStopCamera = findViewById<Button>(R.id.btnStopCamera)
        val btnStartBubble = findViewById<Button>(R.id.btnStartBubble)
        val btnStopBubble = findViewById<Button>(R.id.btnStopBubble)
        val btnGrantPermissions = findViewById<Button>(R.id.btnGrantPermissions)
        val btnRevokePermissions = findViewById<Button>(R.id.btnRevokePermissions)
        val btnPickMedia = findViewById<Button>(R.id.btnPickMedia)
        val tvMediaPath = findViewById<TextView>(R.id.tvMediaPath)
        val spRotation = findViewById<Spinner>(R.id.spRotation)
        val cbMirror = findViewById<CheckBox>(R.id.cbMirror)
        val cbLoop = findViewById<CheckBox>(R.id.cbLoop)
        val tvTargetApps = findViewById<TextView>(R.id.tvTargetApps)

        // Rotation spinner
        val rotationAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            arrayOf("0", "90", "180", "270"))
        rotationAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spRotation.adapter = rotationAdapter
        spRotation.setSelection(settingsManager.getRotationAngle() / 90)
        spRotation.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                settingsManager.setRotationAngle(position * 90)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        cbMirror.isChecked = settingsManager.isMirrored()
        cbMirror.setOnCheckedChangeListener { _, checked ->
            settingsManager.setMirrored(checked)
        }

        cbLoop.isChecked = settingsManager.getLoopVideo()
        cbLoop.setOnCheckedChangeListener { _, checked ->
            settingsManager.setLoopVideo(checked)
        }

        updateStatus(tvStatus)
        tvMediaPath.text = settingsManager.getMediaPath() ?: "No media selected"

        btnPickMedia.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "video/*"
            }
            val picker = Intent.createChooser(intent, "Select video or image")
            startActivity(picker)
        }

        // Handle pick result
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            if (uri != null) {
                val path = uri.path
                if (path != null) {
                    settingsManager.setMediaPath(path)
                    tvMediaPath.text = path
                    Toast.makeText(this, "Media selected", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnStartCamera.setOnClickListener {
            ContextCompat.startForegroundService(this, Intent(this, CameraService::class.java))
            Toast.makeText(this, "Camera service started", Toast.LENGTH_SHORT).show()
            updateStatus(tvStatus)
        }

        btnStopCamera.setOnClickListener {
            stopService(Intent(this, CameraService::class.java))
            Toast.makeText(this, "Camera service stopped", Toast.LENGTH_SHORT).show()
            updateStatus(tvStatus)
        }

        btnStartBubble.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "Overlay permission needed", Toast.LENGTH_LONG).show()
                }
            }
            ContextCompat.startForegroundService(this, Intent(this, FloatingBubbleService::class.java))
            Toast.makeText(this, "Floating bubble started", Toast.LENGTH_SHORT).show()
        }

        btnStopBubble.setOnClickListener {
            stopService(Intent(this, FloatingBubbleService::class.java))
            Toast.makeText(this, "Floating bubble stopped", Toast.LENGTH_SHORT).show()
        }

        btnGrantPermissions.setOnClickListener {
            val pm = AppPermissionManager(this)
            val targetApps = settingsManager.getTargetApps()
            for (i in targetApps.indices) {
                pm.grantTargetAppPermissions(targetApps[i])
            }
            Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show()
        }

        btnRevokePermissions.setOnClickListener {
            val pm = AppPermissionManager(this)
            val targetApps = settingsManager.getTargetApps()
            for (i in targetApps.indices) {
                pm.revokeTargetAppPermissions(targetApps[i])
            }
            Toast.makeText(this, "Permissions revoked", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateStatus(tv: TextView) {
        val sb = StringBuilder()
        sb.append("Settings: ")
        sb.append(if (settingsManager.isActive()) "ACTIVE " else "INACTIVE ")
        sb.append("Media: ${settingsManager.getMediaPath() ?: "none"}\n")

        // Try to detect running target app
        try {
            @Suppress("DEPRECATION")
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            val tasks = am.getRunningTasks(1)
            if (tasks != null && tasks.isNotEmpty()) {
                val topPackage = tasks[0].topActivity?.packageName ?: ""
                val apps = settingsManager.getTargetApps()
                var found = false
                var foundName = ""
                for (i in apps.indices) {
                    val appPkg = apps[i]
                    if (appPkg == topPackage) {
                        found = true
                        foundName = getAppName(appPkg)
                        break
                    }
                }
                if (found) {
                    sb.append("Target app detected: $foundName")
                } else {
                    sb.append("No target app running")
                }
            }
        } catch (_: Exception) {
            sb.append("Cannot check running tasks")
        }

        tv.text = sb.toString()
    }

    private fun getAppName(packageName: String): String {
        try {
            val ai = packageManager.getApplicationInfo(packageName, 0)
            val label = packageManager.getApplicationLabel(ai)
            return if (label != null) label.toString() else packageName
        } catch (_: Exception) {
            return packageName
        }
    }
}
