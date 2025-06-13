package com.example.sonyrx10m3remote

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.*
import android.view.animation.AnimationUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.util.TypedValue
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.sonyrx10m3remote.camera.CameraController
import com.example.sonyrx10m3remote.camera.CameraControllerProvider
import com.example.sonyrx10m3remote.camera.CameraController.CaptureResult
import com.example.sonyrx10m3remote.camera.CameraDiscovery
import com.example.sonyrx10m3remote.camera.CameraDiscovery.CameraInfo
import com.example.sonyrx10m3remote.camera.Intervalometer
import com.example.sonyrx10m3remote.data.CapturedImage
import com.example.sonyrx10m3remote.media.MediaManager
import com.example.sonyrx10m3remote.gallery.GalleryViewModel
import com.example.sonyrx10m3remote.gallery.GalleryViewModelFactory
import com.example.sonyrx10m3remote.gallery.SessionImageRepository
import com.example.sonyrx10m3remote.media.MediaManager.MediaInfo
import com.google.android.material.button.MaterialButton
import com.google.zxing.integration.android.IntentIntegrator
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.flow.catch
import okhttp3.internal.format
import java.util.concurrent.TimeoutException
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private val LOCATION_PERMISSION_REQUEST_CODE = 1000
        private val STORAGE_PERMISSION_REQUEST_CODE = 1001
    }

    // Controller responsible for handling Sony Camera API RPCs
    private lateinit var cameraController: CameraController
    private lateinit var intervalometer: Intervalometer

    // URL for JSON-RPC endpoint on the camera
    private var rpcBaseUrl: String? = null

    // UI references
    private lateinit var btnConnect: MaterialButton
    private lateinit var focusRectangle: View
    private lateinit var btnAutoFocus: MaterialButton
    private lateinit var btnCapture: MaterialButton
    private lateinit var btnVideo: MaterialButton
    private lateinit var seekBarFNumber: SeekBar
    private lateinit var textFNumber: TextView
    private lateinit var seekBarShutterSpeed: SeekBar
    private lateinit var textShutterSpeed: TextView
    private lateinit var seekBarIso: SeekBar
    private lateinit var textIsoValue: TextView
    private lateinit var seekBarExpComp: SeekBar
    private lateinit var textExpComp: TextView
    private lateinit var textInterval: TextView
    private lateinit var seekBarInterval: SeekBar
    private lateinit var textTotalShots: TextView
    private lateinit var seekBarTotalShots: SeekBar
    private lateinit var btnStartStop: MaterialButton
    private lateinit var btnGallery: MaterialButton

    // Network-related components
    private lateinit var connectivityManager: ConnectivityManager
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var cameraWifiNetwork: Network? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isCameraConnected = false
    private var discoveredCameraId: String? = null

    // Auto-focus flag
    private var isAutoFocusEngaged = false
    private var isTouchAFEngaged = false
    private var liveViewImageView: ImageView? = null

    // Bulb exposure-related components
    private var isBulbMode = false
    private var isBulbCapturing = false
    private var bulbDurationMs = 0L
    private var captureTimerJob: Job? = null

    // Video-related components
    private var isRecordingVideo = false
    private var videoRecordingJob: Job? = null
    private var videoDurationMs: Long = 0L
    private var videoUITimerJob: Job? = null

    // Intervalometer-related components
    private var currentTotalShots: Int? = 10
    private var currentIntervalMs: Long = 0L

    // Battery status
    private var currentBatteryAlerting = false
    private var batteryPollJob: Job? = null

    // Media manager components
    private lateinit var mediaManager: MediaManager
    private lateinit var galleryViewModel: GalleryViewModel

    // QR code scan result handler
    private val qrScannerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        Log.d(TAG, "QR scan resultCode=${result.resultCode}, data=${result.data}")
        if (result.resultCode == RESULT_OK) {
            val scanResult = IntentIntegrator.parseActivityResult(result.resultCode, result.data)
            val qrContents = scanResult?.contents
            if (qrContents == null) {
                showStatus("QR code scan cancelled")
            } else {
                showStatus("QR code scanned: $qrContents")
                continueConnectFlow(qrContents)
            }
        } else {
            showStatus("QR scan failed or cancelled")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Get reference to system connectivity manager
        connectivityManager = getSystemService(ConnectivityManager::class.java)

        // Request location permissions if needed
        checkAndRequestPermissions()

        // Setup UI buttons and listeners
        setupUI()

        // Temporary - making the sharedpreferences file
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("auto_download_jpeg", true).apply()
    }

    // Initialize UI references and click actions.
    private fun setupUI() {
        btnConnect = findViewById<MaterialButton>(R.id.btnConnect)
        focusRectangle = findViewById(R.id.focusRectangle)
        btnAutoFocus = findViewById<MaterialButton>(R.id.btnAutoFocus)
        btnCapture = findViewById<MaterialButton>(R.id.btnCapture)
        btnVideo = findViewById<MaterialButton>(R.id.btnVideo)
        seekBarFNumber = findViewById(R.id.seekBarFNumber)
        textFNumber = findViewById(R.id.textFNumber)
        seekBarShutterSpeed = findViewById(R.id.seekBarShutterSpeed)
        textShutterSpeed = findViewById(R.id.textShutterSpeed)
        seekBarIso = findViewById(R.id.seekBarIso)
        textIsoValue = findViewById(R.id.textIsoValue)
        seekBarExpComp = findViewById(R.id.seekBarExpComp)
        textExpComp = findViewById(R.id.textExpComp)
        textInterval = findViewById(R.id.textInterval)
        seekBarInterval = findViewById(R.id.seekBarInterval)
        textTotalShots = findViewById(R.id.textTotalShots)
        seekBarTotalShots = findViewById(R.id.seekBarTotalShots)
        btnStartStop = findViewById<MaterialButton>(R.id.btnStartStop)
        btnGallery = findViewById<MaterialButton>(R.id.btnGallery)

        val aspectRatioLayout = findViewById<com.example.sonyrx10m3remote.ui.AspectRatioFrameLayout>(R.id.rootLayout)
        aspectRatioLayout.setAspectRatio(4f / 3f) // or 3f / 2f for photo aspect ratio, depending on your source

        setUiEnabled(false)
        btnConnect.isEnabled = true

        // Launch QR scanner and begin connect flow (and disconnect flow once connected)
        btnConnect.setOnClickListener {
            if (isCameraConnected) {
                // ======= DISCONNECT FLOW =======
                lifecycleScope.launch {
                    cameraController.stopAFPoll()

                    val stopResult = stopBulbExposure()  // suspend function returning CaptureResult
                    if (stopResult.success) {
                        showStatus("Bulb exposure stopped successfully during disconnect")
                    } else {
                        showStatus("Warning: Failed to stop bulb exposure during disconnect")
                    }

                    stopVideoRecording()
                    intervalometer?.stop()
                    cameraController.stopMjpegStream()
                    cameraController.disconnect()
                    disconnectFromCameraWifi()

                    resetAFButtonHighlight()
                    isAutoFocusEngaged = false
                    isRecordingVideo = false
                    isBulbCapturing = false
                    updateCaptureButton()
                    updateVideoButton()

                    isCameraConnected = false
                    stopBatteryPolling()
                    enableTouchAF(false)
                    setUiEnabled(false)
                    btnConnect.text = "CONNECT TO CAMERA"
                    showStatus("Disconnected from camera")
                }
            } else {
                // ======= CONNECT FLOW =======
                if (!hasLocationPermission()) {
                    showStatus("Location permission required to scan QR code")
                    checkAndRequestPermissions()
                    return@setOnClickListener
                }

                val integrator = IntentIntegrator(this)
                integrator.captureActivity = CaptureActivity::class.java
                integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                integrator.setPrompt("Scan Camera Wi-Fi QR Code")
                integrator.setBeepEnabled(true)
                integrator.setBarcodeImageEnabled(false)
                val scanIntent = integrator.createScanIntent()
                qrScannerLauncher.launch(scanIntent)
            }
        }

        // Toggle auto-focus
        btnAutoFocus.setOnClickListener {
            lifecycleScope.launch {
                if (!isAutoFocusEngaged && !isTouchAFEngaged) {
                    // Start autofocus
                    val success = cameraController.startAutoFocus()
                    if (success) {
                        isAutoFocusEngaged = true
                        isTouchAFEngaged = false
                        startAutoFocusUI()
                        cameraController.startAFPoll()
                    } else {
                        Toast.makeText(this@MainActivity, "Autofocus not available", Toast.LENGTH_SHORT).show()
                        btnCapture.isEnabled = true
                    }
                } else {
                    // Cancel both AF and Touch AF
                    cameraController.stopAutoFocus()
                    cameraController.cancelTouchAFPosition()
                    isAutoFocusEngaged = false
                    isTouchAFEngaged = false
                    stopAutoFocusUI()
                    cameraController.stopAFPoll()
                }
            }
        }

        // Update interval text when seekBarInterval is changed
        seekBarInterval.max = 109
        seekBarInterval.progress = 0
        textInterval.text = "Burst Mode"
        seekBarInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val interval = intervalometer?.getIntervalFromSlider(progress)
                if (interval != null) {
                    currentIntervalMs = interval
                    textInterval.text = when {
                        progress == 0 -> "Burst Mode"
                        currentIntervalMs < 60_000L -> "Interval: %.1f s".format(currentIntervalMs / 1000.0)
                        else -> {
                            val mins = (currentIntervalMs / 1000) / 60
                            val secs = (currentIntervalMs / 1000) % 60
                            "Interval: ${mins}m ${secs}s"
                        }
                    }
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Update totalShots value when seekBarTotalShots is changed
        seekBarTotalShots.max = 88
        seekBarTotalShots.progress = 9
        seekBarTotalShots.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val totalShots = intervalometer?.getTotalShotsFromSlider(progress)
                if (totalShots != null) {
                    currentTotalShots = totalShots
                    textTotalShots.text = "Total Shots: ${currentTotalShots}"
                }
                else {
                    currentTotalShots = null
                    textTotalShots.text = "Total Shots: ∞"
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Start or stop interval shooting
        btnStartStop.setOnClickListener {
            val currentIntervalometer = intervalometer
            if (currentIntervalometer == null) {
                showStatus("Not connected yet - intervalometer unavailable")
                return@setOnClickListener
            }
            if (!currentIntervalometer.isRunning()) {
                currentIntervalometer.start(currentIntervalMs, currentTotalShots)
                btnStartStop.text = "Stop Intervalometer"
                btnStartStop.applySmartTint(android.R.color.holo_red_dark)
            } else {
                currentIntervalometer.stop()
                btnStartStop.text = "Start Intervalometer"
                btnStartStop.applySmartTint()
            }
        }

        // Open gallery
        btnGallery.setOnClickListener {
            val intent = Intent(this, GalleryActivity::class.java)
            discoveredCameraId?.let { cameraId ->
                intent.putExtra("camera_id", cameraId)
            }
            startActivity(intent)
        }
    }

    private fun setUiEnabled(enabled: Boolean) {
        btnAutoFocus.isEnabled = enabled
        btnCapture.isEnabled = enabled
        btnVideo.isEnabled = enabled
        seekBarFNumber.isEnabled = enabled
        seekBarShutterSpeed.isEnabled = enabled
        seekBarIso.isEnabled = enabled
        seekBarExpComp.isEnabled = enabled
        seekBarInterval.isEnabled = enabled
        seekBarTotalShots.isEnabled = enabled
        btnStartStop.isEnabled = enabled
//        btnGallery.isEnabled = enabled
    }

    private fun hasLocationPermission(): Boolean {
        return checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        } else {
            true // No runtime permission needed on Android Q and above
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (!hasLocationPermission()) {
            permissionsToRequest.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (!hasStoragePermission()) {
            permissionsToRequest.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissions(permissionsToRequest.toTypedArray(), STORAGE_PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == STORAGE_PERMISSION_REQUEST_CODE) {
            permissions.forEachIndexed { index, permission ->
                when (permission) {
                    android.Manifest.permission.ACCESS_FINE_LOCATION -> {
                        if (grantResults[index] == PackageManager.PERMISSION_GRANTED) {
                            showStatus("Location permission granted")
                        } else {
                            showStatus("Location permission denied. Wi-Fi discovery might fail.")
                        }
                    }
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE -> {
                        if (grantResults[index] == PackageManager.PERMISSION_GRANTED) {
                            showStatus("Storage permission granted")
                        } else {
                            showStatus("Storage permission denied. Can't save images.")
                        }
                    }
                }
            }
        }
    }

    // Discover the camera's control endpoint over SSDP.
    private suspend fun startCameraDiscovery(): CameraInfo? {
        showStatus("Starting SSDP camera discovery…")

        val cameraInfo = withContext(Dispatchers.IO) {
            CameraDiscovery.discoverCameraInfo()
        }

        if (cameraInfo == null) {
            showStatus("Camera discovery failed or timed out")
            return null
        }

        rpcBaseUrl = cameraInfo.apiUrl
        showStatus("RPC base will be: ${cameraInfo.apiUrl}")

        return cameraInfo
    }

    // Define data class to collect camera parameters
    data class CameraParams(
        val isoList: List<String>,
        val shutterList: List<String>,
        val fNumberList: List<String>,
        val expCompList: List<Int>,
    )

    // Wait for camera to return available f-number, ISO, shutter speed and exposure compensation settings.
    suspend fun waitForParameters(
        cameraController: CameraController,
        timeoutMillis: Long = 10_000L
    ): CameraParams {
        val startTime = System.currentTimeMillis()

        var isoList = listOf<String>()
        var shutterList = listOf<String>()
        var fNumberList = listOf<String>()
        var expCompList = listOf<Int>()

        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            val infoJson: JSONObject? = try {
                cameraController.getInfo()
            } catch (e: Exception) {
                Log.e("MainActivity", "getInfo() threw: ${e.localizedMessage}")
                null
            }

            if (infoJson == null) {
                delay(200L)
                continue
            }

            val resultArray = infoJson.optJSONArray("result")
            if (resultArray == null) {
                delay(200L)
                continue
            }

            var foundIso = false
            var foundShutter = false
            var foundFNumber = false
            var foundExpComp = false

            // Temporary holders for this iteration
            var tmpIsoList = isoList
            var tmpShutterList = shutterList
            var tmpFNumberList = fNumberList
            var tmpExpCompList = expCompList

            for (i in 0 until resultArray.length()) {
                val item = resultArray.optJSONObject(i) ?: continue
                when (item.optString("type")) {
                    "fNumber" -> {
                        item.optJSONArray("fNumberCandidates")?.let { arr ->
                            val list = mutableListOf<String>()
                            for (j in 0 until arr.length()) {
                                val s = arr.optString(j, null)
                                if (s != null) list.add(s)
                            }
                            if (list.isNotEmpty()) {
                                tmpFNumberList = list
                                foundFNumber = true
                            }
                        }
                    }
                    "shutterSpeed" -> {
                        item.optJSONArray("shutterSpeedCandidates")?.let { arr ->
                            val list = mutableListOf<String>()
                            for (j in 0 until arr.length()) {
                                val s = arr.optString(j, null)
                                if (s != null) list.add(s)
                            }
                            if (list.isNotEmpty()) {
                                tmpShutterList = list
                                foundShutter = true
                            }
                        }
                    }
                    "isoSpeedRate" -> {
                        item.optJSONArray("isoSpeedRateCandidates")?.let { arr ->
                            val list = mutableListOf<String>()
                            for (j in 0 until arr.length()) {
                                val s = arr.optString(j, null)
                                if (s != null) list.add(s)
                            }
                            if (list.isNotEmpty()) {
                                tmpIsoList = list
                                foundIso = true
                            }
                        }
                    }
                    "exposureCompensation" -> {
                        // First, check if the API provides explicit candidates array
                        // Some camera APIs may have "exposureCompensationCandidates" or similar.
                        val arr = item.optJSONArray("exposureCompensationCandidates")
                        if (arr != null) {
                            val list = mutableListOf<Int>()
                            for (j in 0 until arr.length()) {
                                // Could be number or string; try optInt or parse
                                val v = try {
                                    arr.getInt(j)
                                } catch (_: Exception) {
                                    // maybe it's string
                                    val s = arr.optString(j, null)
                                    s?.toIntOrNull() ?: continue
                                }
                                list.add(v)
                            }
                            if (list.isNotEmpty()) {
                                tmpExpCompList = list
                                foundExpComp = true
                                continue  // skip range building if explicit candidates exist
                            }
                        }
                        // Otherwise, fallback to min, max, step fields
                        val min = item.optInt("minExposureCompensation", Int.MIN_VALUE)
                        val max = item.optInt("maxExposureCompensation", Int.MAX_VALUE)
                        var step = item.optInt("stepIndexOfExposureCompensation", 0)
                        // Some APIs might use a different field name for step; verify documentation.
                        // If step <= 0, fallback:
                        if (step <= 0) {
                            Log.w("MainActivity", "Invalid exposureCompensation step: $step; defaulting to 1")
                            step = 1
                        }
                        // If min or max are not present or invalid, skip or fallback:
                        if (min == Int.MIN_VALUE || max == Int.MAX_VALUE) {
                            Log.w("MainActivity", "Exposure comp min/max invalid: min=$min, max=$max; skipping expComp list")
                            tmpExpCompList = emptyList()
                            // foundExpComp remains false, so loop continues
                        } else if (min > max) {
                            Log.w("MainActivity", "Exposure comp min > max ($min > $max); swapping or skipping")
                            // Option A: swap
                            val realMin = max
                            val realMax = min
                            tmpExpCompList = buildRangeList(realMin, realMax, step)
                            foundExpComp = tmpExpCompList.isNotEmpty()
                        } else {
                            tmpExpCompList = buildRangeList(min, max, step)
                            foundExpComp = tmpExpCompList.isNotEmpty()
                        }
                    }
                }
            }

            // If all found, return
            if (foundIso && foundShutter && foundFNumber && foundExpComp) {
                // assign to outer vars before return
                isoList = tmpIsoList
                shutterList = tmpShutterList
                fNumberList = tmpFNumberList
                expCompList = tmpExpCompList
                return CameraParams(isoList, shutterList, fNumberList, expCompList)
            }

            // Optionally, if some parameters found but not all, you could:
            // - partially assign outer vars, so they accumulate across iterations
            //   isoList = tmpIsoList.takeIf { foundIso } ?: isoList
            //   shutterList = tmpShutterList.takeIf { foundShutter } ?: shutterList
            //   fNumberList = tmpFNumberList.takeIf { foundFNumber } ?: fNumberList
            //   expCompList = tmpExpCompList.takeIf { foundExpComp } ?: expCompList
            // This way, you keep previously found values if API intermittently returns partial data.
            //
            // Example:
            if (foundIso) isoList = tmpIsoList
            if (foundShutter) shutterList = tmpShutterList
            if (foundFNumber) fNumberList = tmpFNumberList
            if (foundExpComp) expCompList = tmpExpCompList

            // Wait briefly before polling again
            delay(200L)
        }

        // Timeout reached. You can decide to:
        // - If you have partial lists (e.g., some found but not all), return partial or throw
        // - Here, we throw, but you could also return with defaults
        throw TimeoutException("Timeout fetching camera parameters after $timeoutMillis ms. " +
                "Last lists: iso=$isoList, shutter=$shutterList, fNumber=$fNumberList, expComp=$expCompList")
    }

    // Helper to build a list from min to max inclusive with positive step.
    private fun buildRangeList(min: Int, max: Int, step: Int): List<Int> {
        if (step <= 0) return emptyList()
        val list = mutableListOf<Int>()
        var v = min
        while (v <= max) {
            list.add(v)
            // Prevent potential infinite loop if overflow
            if (v > Int.MAX_VALUE - step) break
            v += step
        }
        return list
    }

    // Parses QR code, connects to Wi-Fi, discovers camera, and connects via RPC.
    private fun continueConnectFlow(qrContents: String) {
        mainScope.launch {
            showStatus("Parsing QR contents...")
            val wifiInfo = parseWifiFromQr(qrContents)
            if (wifiInfo == null) {
                showStatus("Failed to parse QR code contents")
                return@launch
            }

            showStatus("Connecting to ${wifiInfo.ssid}...")
            val connected = connectToWifiApi29Plus(wifiInfo.ssid, wifiInfo.password)
            if (!connected) {
                showStatus("Failed to connect to camera Wi-Fi")
                return@launch
            }

            showStatus("Connected to camera Wi-Fi, discovering camera...")
            val cameraInfo = startCameraDiscovery()
            if (cameraInfo == null) {
                showStatus("Camera could not be found")
                return@launch
            }

            discoveredCameraId = cameraInfo.cameraId

            val base = rpcBaseUrl ?: cameraInfo.apiUrl
            CameraControllerProvider.init(base)
            cameraController = CameraControllerProvider.instance
                ?: throw IllegalStateException("CameraController instance is null after initialization")
            intervalometer = Intervalometer(
                cameraController,
                onStatusUpdate = this@MainActivity::showStatus,
                onFinished = {
                    runOnUiThread {
                        btnStartStop.text = "Start Intervalometer"
                        btnStartStop.applySmartTint()
                    }
                },
                getShutterSpeed = { if (isBulbMode) "BULB" else "OTHER" },
                startBulb = { runBlocking { startBulbExposure() } }, // assuming startBulbExposure returns Boolean
                stopBulb = {
                    runBlocking {
                        val result = stopBulbExposure()
                        result.success
                    }
                },
                performTimedCapture = { performTimedCapture(it) },
                getBulbDurationMs = { bulbDurationMs },
                onError = { errorMessage ->
                    runOnUiThread {
                        showStatus("Error: $errorMessage")
                    }
                },
                onProgressUpdate = { current, total ->
                    runOnUiThread {
                        val totalText = total?.toString() ?: "∞"
                        btnStartStop.text = "Stop (Shot $current/$totalText)"
                    }
                }
            )

            runOnUiThread {
                setUiEnabled(true)
            }

            cameraWifiNetwork?.let { network ->
                connectivityManager.bindProcessToNetwork(network)
                showStatus("Re-bound process to camera Wi-Fi network before JSON-RPC")
            }

            connectToCameraViaJsonRpc(base)
        }
    }

    // Initialize JSON-RPC session and update UI.
    private fun connectToCameraViaJsonRpc(rpcBase: String) {
        mainScope.launch {
            showStatus("Initializing via JSON-RPC at $rpcBase…")

            val ok = cameraController.startRecMode()
            if (!ok) {
                showStatus("Failed to connect")
                return@launch
            }

            showStatus("Waiting for camera to become idle...")
            try {
                cameraController.waitForIdleStatus()

                showStatus("Fetching exposure parameters...")
                val (isoList, shutterSpeedList, fNumberList, expCompList) = waitForParameters(cameraController)
                cameraController.availableISOs = isoList
                cameraController.availableShutterSpeeds = shutterSpeedList
                cameraController.availableFNumbers = fNumberList
                cameraController.availableExpComps = expCompList

                // Get current values
                val infoJson = cameraController.getInfo()
                var currentFNumber: String? = null
                var currentShutter: String? = null
                var currentShutterDurationMs: Long? = null
                var currentISO: String? = null
                var currentExpComp: String? = null

                // Set current values
                infoJson?.optJSONArray("result")?.let { resultArray ->
                    for (i in 0 until resultArray.length()) {
                        val item = resultArray.optJSONObject(i) ?: continue
                        when (item.optString("type")) {
                            "fNumber" -> currentFNumber = item.optString("currentFNumber")
                            "shutterSpeed" -> currentShutter = item.optString("currentShutterSpeed")
                            "isoSpeedRate" -> currentISO = item.optString("currentIsoSpeedRate")
                            "expComp" -> currentExpComp = item.optString("currentExposureCompensation")
                        }
                    }
                }

                currentShutterDurationMs = cameraController.parseShutterSpeedToMs(currentShutter)
                cameraController.currentShutterDurationMs = currentShutterDurationMs

                // Set bulb mode flag based on current shutter speed before populating slider
                isBulbMode = (currentShutter == "BULB")
                updateCaptureButton()
                updateVideoButton()

                // Update UI seek bars
                // For F-number (String)
                populateSeekBar(
                    availableList = fNumberList,
                    currentValue = currentFNumber,
                    name = "F-Number",
                    bar = seekBarFNumber,
                    text = textFNumber,
                    displayFn = { it },  // Just display the string as-is
                    updateFn = cameraController::setFNumber
                )

                // For Shutter Speed (String), with extra logic for bulb mode
                populateSeekBar(
                    availableList = shutterSpeedList,
                    currentValue = currentShutter,
                    name = "Shutter Speed",
                    bar = seekBarShutterSpeed,
                    text = textShutterSpeed,
                    displayFn = { it },
                    updateFn = { selected ->
                        val success = cameraController.setShutterSpeed(selected)
                        if (success) {
                            val parsedMs = cameraController.parseShutterSpeedToMs(selected)
                            cameraController.currentShutterDurationMs = parsedMs
                            isBulbMode = (selected == "BULB")
                            updateCaptureButton()
                        }
                        success
                    }
                )

                // For ISO (String)
                populateSeekBar(
                    availableList = isoList,
                    currentValue = currentISO,
                    name = "ISO",
                    bar = seekBarIso,
                    text = textIsoValue,
                    displayFn = { it },
                    updateFn = cameraController::setISO
                )

                // For Exposure Compensation (Int), show in EV steps
                populateSeekBar(
                    availableList = expCompList,
                    currentValue = currentExpComp?.toIntOrNull() ?: 0,
                    name = "Exposure Compensation",
                    bar = seekBarExpComp,
                    text = textExpComp,
                    displayFn = { "%.1f".format(it / 3.0) },  // EV display
                    updateFn = cameraController::setExpComp
                )

                // Start live view
                showStatus("Camera is idle. Initialising live view...")
                val imageView = findViewById<ImageView>(R.id.liveViewImage)
                liveViewImageView = imageView
                val liveViewUrl = cameraController.startLiveView()
                if (liveViewUrl != null) {
                    showStatus("Live view started!")
                    cameraController.startMjpegStream(liveViewUrl, imageView)
                    enableTouchAF(true)  // Enable touch AF
                    collectFocusStatusFlow()
                } else {
                    showStatus("Failed to start live view.")
                }

                // Instantiate mediaManager
                mediaManager = MediaManager(
                    context = this@MainActivity,
                    cameraController = cameraController,
                    imageViewLivePreview = imageView,
                    resumeLiveViewCallback = { restartLiveView() },
                    cameraId = discoveredCameraId
                )
                MediaManagerProvider.instance = mediaManager

                // Instantiate galleryViewModel
                galleryViewModel = ViewModelProvider(
                    this@MainActivity,
                    GalleryViewModelFactory(applicationContext, mediaManager, cameraController, discoveredCameraId)
                )
                    .get(GalleryViewModel::class.java)

                // Connect mediaManager to sessionImageRepository
                mediaManager.sessionImageListener = object : MediaManager.SessionImageListener {
                    override fun onNewSessionImage(image: CapturedImage) {
                        Log.d("MainActivity", "New session image received: ${image.uri}")
                        SessionImageRepository.addImages(listOf(image))
                    }
                }
                SessionImageRepository.restartLiveViewCallback = {
                    runOnUiThread {
                        restartLiveView()
                    }
                }

                // Make icons visible
                findViewById<ImageView>(R.id.batteryIcon).visibility = View.VISIBLE
                findViewById<ImageView>(R.id.chargingIcon).visibility = View.VISIBLE

                // Update battery status immediately
                val info = cameraController.getInfo(version = "1.2")
                if (info != null) updateBatteryStatus(info)
                startBatteryPolling()

                isCameraConnected = true
                btnConnect.text = "Disconnect from Camera"

            } catch (e: TimeoutException) {
                showStatus("Timeout waiting for camera to become idle or fetching settings.")
            }
        }
    }

    // Connects to a specific Wi-Fi network (API 29+).
    private suspend fun connectToWifiApi29Plus(
        ssid: String,
        password: String
    ): Boolean = suspendCoroutine { cont ->
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(password)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()

        var resumed = false
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                connectivityManager.bindProcessToNetwork(network)
                cameraWifiNetwork = network
                if (!resumed) {
                    resumed = true
                    cont.resume(true)
                }
            }

            override fun onUnavailable() {
                if (!resumed) {
                    resumed = true
                    cont.resume(false)
                }
            }

            override fun onLost(network: Network) {
                connectivityManager.bindProcessToNetwork(null)
                cameraWifiNetwork = null
            }
        }
        connectivityManager.requestNetwork(request, networkCallback!!)
    }

    private fun disconnectFromCameraWifi() {
        // Unbind the app from the network
        connectivityManager.bindProcessToNetwork(null)

        // Release the network callback
        networkCallback?.let {
            connectivityManager.unregisterNetworkCallback(it)
            networkCallback = null
        }

        cameraWifiNetwork = null
    }

    data class WifiInfo(val ssid: String, val password: String)

    // Extracts SSID, password, and camera model from Sony Wi-Fi QR code.
    private fun parseWifiFromQr(qrContent: String): WifiInfo? {
        val ssidRegex = Regex("""S:([^;]+)""")
        val passRegex = Regex("""P:([^;]+)""")
        val camModelRegex = Regex("""C:([^;]+)""")

        val ssidMatch = ssidRegex.find(qrContent)
        val passMatch = passRegex.find(qrContent)
        val camModelMatch = camModelRegex.find(qrContent)

        if (ssidMatch != null && passMatch != null && camModelMatch != null) {
            val partialSsid = ssidMatch.groupValues[1]
            val password = passMatch.groupValues[1]
            val cameraModel = camModelMatch.groupValues[1]
            val fullSsid = "DIRECT-$partialSsid:$cameraModel"
            return WifiInfo(fullSsid, password)
        }
        return null
    }

    // Function to acquire the focus status of the camera
    private fun collectFocusStatusFlow() {
        lifecycleScope.launch {
            cameraController.focusStatus
                .catch { e -> Log.e(TAG, "Error collecting focusStatus", e) }
                .collect { focus ->
                    Log.d(TAG, "Focus status update: $focus")
                    if (isAutoFocusEngaged || isTouchAFEngaged) {
                        onFocusStatusChanged(focus)
                    }
                }
        }
    }

    // Function to update UI based on the focusing state of the camera
    private fun onFocusStatusChanged(focusStatus: String) {
        if (!isAutoFocusEngaged && !isTouchAFEngaged) {
            Log.d(TAG, "Ignoring focus status update: no AF engaged")
            return
        }
        if (cameraController.isCapturing) {
            Log.d(TAG, "Ignoring focus status update: camera is capturing")
            return
        }

        runOnUiThread {
            when (focusStatus) {
                "Focused" -> {
                    btnAutoFocus.applySmartTint(android.R.color.holo_green_light)
                    btnAutoFocus.text = "AF: On"
                    btnCapture.isEnabled = true
                    btnVideo.isEnabled = true
                }
                "Focusing" -> {
                    btnAutoFocus.applySmartTint(android.R.color.holo_orange_light)
                    btnAutoFocus.text = "Focusing..."
                    btnCapture.isEnabled = false
                    btnVideo.isEnabled = false
                }
            }
        }
    }

    // Resets the appearance of the auto-focus button
    private fun resetAFButtonHighlight() {
        btnAutoFocus.applySmartTint()
        btnAutoFocus.text = "Auto Focus"
    }

    private fun startAutoFocusUI() {
        isAutoFocusEngaged = true
        btnAutoFocus.applySmartTint(android.R.color.holo_orange_light)
        btnAutoFocus.text = "Focusing..."
        btnCapture.isEnabled = false
        btnVideo.isEnabled = false
    }

    // Call this to reset UI when autofocus stops (manual or touch)
    private fun stopAutoFocusUI() {
        isAutoFocusEngaged = false
        if (!isTouchAFEngaged) {
            resetAFButtonHighlight()
            focusRectangle.visibility = View.INVISIBLE
        }
        btnCapture.isEnabled = true
        btnVideo.isEnabled = true
    }

    // Function to send the coordinates from a touch on the live image view to set the auto-focus on the camera to that object
    private fun enableTouchAF(enable: Boolean) {
        liveViewImageView?.let { imageView ->
            if (enable) {
                imageView.setOnTouchListener { v, event ->
                    if (event.action == MotionEvent.ACTION_DOWN) {
                        val x = event.x
                        val y = event.y

                        val xPercent = (x / v.width).coerceIn(0f, 1f) * 100f
                        val yPercent = (y / v.height).coerceIn(0f, 1f) * 100f

                        focusRectangle.translationX = x - focusRectangle.width / 2
                        focusRectangle.translationY = y - focusRectangle.height / 2
                        focusRectangle.alpha = 1f
                        focusRectangle.visibility = View.VISIBLE

                        lifecycleScope.launch {
                            isTouchAFEngaged = true
                            isAutoFocusEngaged = false
                            startAutoFocusUI()  // Use same UI changes: orange "Focusing..."
                            val success = cameraController.setTouchAFPosition(xPercent, yPercent)
                            Log.d(
                                "TouchAF",
                                "Requested AF at $xPercent%, $yPercent%: success=$success"
                            )
                            if (success) {
                                cameraController.stopAFPoll()
                                cameraController.startAFPoll()
                            } else {
                                isTouchAFEngaged = false
                                stopAutoFocusUI()
                            }
                        }
                    }
                    true
                }
                imageView.isEnabled = true
            } else {
                imageView.setOnTouchListener(null)
                imageView.isEnabled = false
            }
        }
    }

    // Function to update the appearance of the capture button
    private fun updateCaptureButton() {
        btnCapture.setOnClickListener(null)
        btnCapture.setOnLongClickListener(null)
        captureTimerJob?.cancel()
        captureTimerJob = null

        if (isBulbMode) {
            if (isBulbCapturing) {
                updateCaptureButtonDuringBulbExposure()
            } else {
                updateCaptureButtonBeforeBulbExposure()
            }
        } else {
            updateCaptureButtonNormalMode()
        }
    }

    // --- Bulb exposure active state ---
    private fun updateCaptureButtonDuringBulbExposure() {
        if (bulbDurationMs > 0) {
            startBulbCountdown()
        } else {
            btnCapture.applySmartTint(android.R.color.holo_red_dark)
            startBulbElapsedTimer()
        }

        btnCapture.setOnClickListener {
            mainScope.launch {
                btnCapture.applySmartTint()
                val result = stopBulbExposure()
                handleCaptureResult(result)
                updateCaptureButton()
            }
        }
    }

    // --- Bulb exposure inactive state ---
    private fun updateCaptureButtonBeforeBulbExposure() {
        btnCapture.text = "Start Bulb"
        btnCapture.applySmartTint()

        btnCapture.setOnClickListener {
            mainScope.launch {
                val started = startBulbExposure()
                if (!started) {
                    showStatus("Failed to start bulb exposure")
                    return@launch
                }

                if (bulbDurationMs > 0) {
                    startBulbCountdown()
                    delay(bulbDurationMs)
                    val result = stopBulbExposure()
                    handleCaptureResult(result)
                    updateCaptureButton()
                } else {
                    btnCapture.applySmartTint(android.R.color.holo_red_dark)
                    updateCaptureButton()
                }
            }
        }

        btnCapture.setOnLongClickListener {
            showTimerSetupDialog()
            true
        }
    }

    // --- Normal capture mode ---
    private fun updateCaptureButtonNormalMode() {
        btnCapture.text = "Capture"
        btnCapture.applySmartTint()
        btnCapture.setOnLongClickListener(null)

        btnCapture.setOnClickListener {
            mainScope.launch {
                val shutterDurationMs = cameraController.currentShutterDurationMs ?: 0L
                cameraController.stopMjpegStream()

                if (shutterDurationMs > 1000) {
                    // Long capture duration: run performTimedCapture, which handles capture result internally
                    performTimedCapture(shutterDurationMs)
                } else {
                    // Short capture duration: captureStill returns CaptureResult directly
                    val result = cameraController.captureStill()
                    handleCaptureResult(result)

                    if (isAutoFocusEngaged) {
                        // Autofocus: clear UI and flags after capture
                        stopAutoFocusUI()
                    } else if (isTouchAFEngaged) {
                        // TouchAF: keep focus UI and flags as-is
                        runOnUiThread {
                            btnAutoFocus.applySmartTint(android.R.color.holo_green_light)
                            btnAutoFocus.text = "AF: On"
                            btnCapture.isEnabled = true
                            btnVideo.isEnabled = true
                        }
                    } else {
                        // No focus: reset UI normally
                        runOnUiThread {
                            btnAutoFocus.applySmartTint()
                            btnAutoFocus.text = "Auto Focus"
                            btnCapture.isEnabled = true
                            btnVideo.isEnabled = true
                        }
                    }

                    btnCapture.text = "Capture"
                    btnCapture.applySmartTint()
                    btnCapture.isEnabled = true
                }
            }
        }
    }

    // --- Bulb mode countdown timer ---
    private fun startBulbCountdown() {
        captureTimerJob = mainScope.launch {
            val startTime = System.currentTimeMillis()
            var millisLeft = bulbDurationMs
            while (millisLeft > 0 && isBulbCapturing) {
                btnCapture.text = "Stop (" + formatElapsedTime(millisLeft) + ")"
                btnCapture.applySmartTint(android.R.color.holo_red_dark)
                delay(250)
                millisLeft = bulbDurationMs - (System.currentTimeMillis() - startTime)
            }
        }
    }

    // --- Bulb mode elapsed timer (no fixed duration) ---
    private fun startBulbElapsedTimer() {
        captureTimerJob = mainScope.launch {
            val startTime = System.currentTimeMillis()
            while (isBulbCapturing) {
                val elapsed = System.currentTimeMillis() - startTime
                btnCapture.text = "Stop (" + formatElapsedTime(elapsed) + ")"
                delay(250)
            }
        }
    }

    // --- Process capture result from bulb exposure ---
    private suspend fun handleCaptureResult(result: CameraController.CaptureResult) {
        if (result.success && result.imageUrls.isNotEmpty()) {
            val mediaInfo = buildMediaInfoFromUrls(result.imageUrls)
            if (mediaInfo != null) {
                mediaManager.onPhotoCaptured(mediaInfo)
            }
            showStatus("Captured photo")
            stopAutoFocusUI()
            cameraController.stopAFPoll()
        } else {
            showStatus("Failed to capture photo")
        }
    }

    // Helper to build a MediaInfo object for mediaManager
    private fun buildMediaInfoFromUrls(urls: List<String>): MediaInfo? {
        if (urls.isEmpty()) return null
        val firstUrl = urls.first()
        val filename = firstUrl
            .substringAfterLast("/")
            .substringBefore("?")
        return MediaInfo(
            previewUrl = firstUrl,   // or a specific preview URL if you have one
            fullImageUrl = firstUrl, // assuming preview and full image are same URL here
            filename = filename,
            isVideo = false          // adjust if your URLs correspond to video
        )
    }

    // Helper function to perform timed captures
    fun performTimedCapture(durationMs: Long) {
        captureTimerJob?.cancel()
        captureTimerJob = mainScope.launch {
            val countdownTime = durationMs + 1000L // buffer for focus/shutter lag
            val startTime = System.currentTimeMillis()

            val countdownJob = launch {
                var timeLeft: Long
                do {
                    timeLeft = countdownTime - (System.currentTimeMillis() - startTime)
                    if (timeLeft > 0) {
                        btnCapture.text = "Wait (" + formatElapsedTime(timeLeft) + ")"
                        delay(250)
                    }
                } while (timeLeft > 0)
            }

            val result = cameraController.captureStill()

            countdownJob.cancel()
            captureTimerJob = null
            btnCapture.text = "Capture"
            btnCapture.applySmartTint()

            // Call your suspend function here inside the coroutine
            handleCaptureResult(result)
        }
    }

    // Helper to format elapsed milliseconds as HH:MM:SS
    private fun formatElapsedTime(elapsedMs: Long): String {
        val totalSeconds = elapsedMs / 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    // Function to update the appearance of the video button
    private fun updateVideoButton() {
        btnVideo.text = if (isRecordingVideo) "Stop Video" else "Start Video"
        if (isRecordingVideo) {
            btnVideo.applySmartTint(android.R.color.holo_red_dark)
        } else {
            btnVideo.applySmartTint()
        }

        btnVideo.setOnClickListener {
            mainScope.launch {
                if (!isRecordingVideo) {
                    // If autofocus or touchAF active, capture behavior can adapt here if needed
                    // But mainly, just start video recording
                    startVideoRecording()
                } else {
                    stopVideoRecording()
                    showStatus("Captured video")
                    resetAFButtonHighlight()
                    isAutoFocusEngaged = false
                    // touchAF remains active until cancelled elsewhere
                }
            }
        }

        btnVideo.setOnLongClickListener {
            showVideoTimerSetupDialog()
            true
        }
    }

    // Helper function to handle video button updates
    private fun startVideoUITimer(durationMs: Long) {
        videoUITimerJob?.cancel()

        videoUITimerJob = mainScope.launch {
            val startTime = System.currentTimeMillis()
            Log.d("VideoTimer", "Timer started at $startTime")

            while (isActive && isRecordingVideo) {
                val now = System.currentTimeMillis()
                val elapsed = now - startTime
                Log.d("VideoTimer", "Now: $now, Elapsed: $elapsed")

                val remaining = durationMs - elapsed

                val text = if (durationMs > 0) {
                    "Stop (" + formatElapsedTime(remaining.coerceAtLeast(0L)) + ")"
                } else {
                    "Stop (" + formatElapsedTime(elapsed) + ")"
                }

                withContext(Dispatchers.Main) {
                    btnVideo.text = text
                }

                delay(200)
            }

            Log.d("VideoTimer", "Timer coroutine exiting")
        }
    }

    // Suspend function to start bulb exposure
    suspend fun startBulbExposure(): Boolean {
        if (isBulbCapturing) return true // Already capturing, treat as success

        val started = cameraController.startBulbExposure()
        if (started) {
            isBulbCapturing = true
            updateCaptureButton()
            showStatus("Bulb exposure started")
        } else {
            showStatus("Failed to start bulb exposure")
            isBulbCapturing = false
            updateCaptureButton()
        }
        return started
    }

    // Suspend function to stop bulb exposure and restart live view
    suspend fun stopBulbExposure(): CaptureResult {
        if (!isBulbCapturing) return CaptureResult(true)

        val result = cameraController.stopBulbExposure()
        isBulbCapturing = false
        updateCaptureButton()

        if (result.success) {
            showStatus("Bulb exposure stopped")

            try {
                showStatus("Waiting for camera to become idle...")
                cameraController.waitForIdleStatus()

                showStatus("Camera idle. Processing...")

            } catch (e: TimeoutException) {
                Log.e(TAG, "Camera did not return to IDLE in time", e)
//                showStatus("Live view failed to resume (timeout).")
            }
        } else {
            showStatus("Failed to stop bulb exposure")
        }

        return result
    }

    // Timer dialog - shows up when "Start Bulb" is long-pressed
    private fun showTimerSetupDialog() {
        val context = this

        val hoursPicker = NumberPicker(context).apply {
            minValue = 0
            maxValue = 23
            value = ((bulbDurationMs / 1000) / 3600).toInt()
        }
        val minutesPicker = NumberPicker(context).apply {
            minValue = 0
            maxValue = 59
            value = ((bulbDurationMs / 1000) % 3600 / 60).toInt()
        }
        val secondsPicker = NumberPicker(context).apply {
            minValue = 0
            maxValue = 59
            value = ((bulbDurationMs / 1000) % 60).toInt()
        }

        fun createLabeledPicker(label: String, picker: NumberPicker): LinearLayout {
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(TextView(context).apply {
                    text = label
                    gravity = Gravity.CENTER
                })
                addView(picker)
            }
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(30, 20, 30, 0)
            addView(createLabeledPicker("H", hoursPicker))
            addView(createLabeledPicker("M", minutesPicker))
            addView(createLabeledPicker("S", secondsPicker))
        }

        AlertDialog.Builder(context)
            .setTitle("Bulb Exposure Timer")
            .setView(container)
            .setPositiveButton("OK") { _, _ ->
                bulbDurationMs = ((hoursPicker.value * 3600) + (minutesPicker.value * 60) + secondsPicker.value) * 1000L
                showStatus(
                    if (bulbDurationMs > 0)
                        "Timer set to ${hoursPicker.value}h ${minutesPicker.value}m ${secondsPicker.value}s"
                    else
                        "Timer disabled"
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Video timer dialog - shows up when "Video" is long-pressed
    private fun showVideoTimerSetupDialog() {
        val context = this

        val hoursPicker = NumberPicker(context).apply {
            minValue = 0
            maxValue = 23
            value = ((videoDurationMs / 1000) / 3600).toInt()
        }
        val minutesPicker = NumberPicker(context).apply {
            minValue = 0
            maxValue = 59
            value = ((videoDurationMs / 1000) % 3600 / 60).toInt()
        }
        val secondsPicker = NumberPicker(context).apply {
            minValue = 0
            maxValue = 59
            value = ((videoDurationMs / 1000) % 60).toInt()
        }

        fun createLabeledPicker(label: String, picker: NumberPicker): LinearLayout {
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(TextView(context).apply {
                    text = label
                    gravity = Gravity.CENTER
                })
                addView(picker)
            }
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(30, 20, 30, 0)
            addView(createLabeledPicker("H", hoursPicker))
            addView(createLabeledPicker("M", minutesPicker))
            addView(createLabeledPicker("S", secondsPicker))
        }

        AlertDialog.Builder(context)
            .setTitle("Video Timer")
            .setView(container)
            .setPositiveButton("OK") { _, _ ->
                videoDurationMs = ((hoursPicker.value * 3600) + (minutesPicker.value * 60) + secondsPicker.value) * 1000L
                showStatus(
                    if (videoDurationMs > 0)
                        "Video timer set to ${hoursPicker.value}h ${minutesPicker.value}m ${secondsPicker.value}s"
                    else
                        "Video timer disabled"
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Function to start video recording
    private fun startVideoRecording() {
        if (isRecordingVideo) return
        isRecordingVideo = true
        updateVideoButton()

        videoRecordingJob = mainScope.launch {
            val success = cameraController.startMovieRec()
            if (success) {
                showStatus("Recording started")

                startVideoUITimer(videoDurationMs) // UI update loop

                if (videoDurationMs > 0) {
                    delay(videoDurationMs)

                    if (isRecordingVideo) {
                        stopVideoRecording()
                        showStatus("Recording stopped (timed)")
                        resetAFButtonHighlight()
                        isAutoFocusEngaged = false
                    }
                }
            } else {
                showStatus("Failed to start recording")
                isRecordingVideo = false
                updateVideoButton()
            }
        }
    }

    // Function to stop video recording
    private fun stopVideoRecording() {
        if (!isRecordingVideo) return
        isRecordingVideo = false

        videoRecordingJob?.cancel()
        videoRecordingJob = null
        videoUITimerJob?.cancel()
        videoUITimerJob = null

        mainScope.launch {
            val success = cameraController.stopMovieRec()
            if (success) {
                showStatus("Recording stopped")
            } else {
                showStatus("Failed to stop recording")
            }
            updateVideoButton()
        }
    }

    // Sets up a seek bar to control a specific camera setting.
    private fun <T> populateSeekBar(
        availableList: List<T>,
        currentValue: T?,
        name: String,
        bar: SeekBar,
        text: TextView,
        displayFn: (T) -> String,
        updateFn: suspend (T) -> Boolean
    ) {
        if (availableList.isEmpty()) {
            showStatus("No $name data available")
            return
        }

        bar.max = availableList.size - 1
        val currentIndex = availableList.indexOf(currentValue).takeIf { it >= 0 } ?: 0
        bar.progress = currentIndex
        text.text = "$name: ${displayFn(availableList[currentIndex])}"

        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            var selectedValue: T = availableList[currentIndex]

            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = if (progress in availableList.indices) availableList[progress] else return
                text.text = "$name: ${displayFn(value)}"
                selectedValue = value
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                mainScope.launch {
                    val success = updateFn(selectedValue)
                    showStatus(if (success) "$name set to ${displayFn(selectedValue)}" else "Failed to set $name")
                }
            }
        })
    }

    private fun startBatteryPolling() {
        batteryPollJob = lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                val info = cameraController.getInfo(version = "1.2")
                if (info != null) updateBatteryStatus(info)
            }
        }
    }

    private fun stopBatteryPolling() {
        batteryPollJob?.cancel()
        batteryPollJob = null
    }

    // Function to acquire and update the battery status of the camera
    private fun updateBatteryStatus(infoJson: JSONObject) {
        val resultArray = infoJson.optJSONArray("result") ?: return

        for (i in 0 until resultArray.length()) {
            val item = resultArray.optJSONObject(i) ?: continue
            if (item.optString("type") == "batteryInfo") {
                val batteryArray = item.optJSONArray("batteryInfo") ?: continue
                val batteryObj = batteryArray.optJSONObject(0) ?: continue

                val numer = batteryObj.optInt("levelNumer", 0)
                val iconLevel = numer.coerceIn(0, 4)
                val additionalStatus = batteryObj.optString("additionalStatus", "") // e.g. "charging", "batteryNearEnd"

                runOnUiThread {
                    val batteryIcon = findViewById<ImageView>(R.id.batteryIcon)
                    val chargingIcon = findViewById<ImageView>(R.id.chargingIcon)

                    val addStatus = additionalStatus.lowercase()

                    when {
                        addStatus == "charging" -> {
                            // Show normal battery icon
                            val iconResId = when (iconLevel) {
                                0 -> R.drawable.ic_battery_0
                                1 -> R.drawable.ic_battery_1
                                2 -> R.drawable.ic_battery_2
                                3 -> R.drawable.ic_battery_3
                                4 -> R.drawable.ic_battery_4
                                else -> R.drawable.ic_battery_0
                            }
                            batteryIcon.setImageResource(iconResId)
                            batteryIcon.clearAnimation()
                            batteryIcon.clearColorFilter()
                            currentBatteryAlerting = false

                            chargingIcon.setImageResource(R.drawable.ic_charging)
                            chargingIcon.visibility = View.VISIBLE
                        }
                        addStatus == "batterynearend" -> {
                            batteryIcon.setImageResource(R.drawable.ic_battery_alert)
                            batteryIcon.setColorFilter(Color.RED, PorterDuff.Mode.SRC_IN)
                            if (!currentBatteryAlerting) {
                                val blinkAnim = AnimationUtils.loadAnimation(this, R.anim.blink_red)
                                batteryIcon.startAnimation(blinkAnim)
                                currentBatteryAlerting = true
                            }
                            chargingIcon.visibility = View.GONE
                        }
                        else -> {
                            val iconResId = when (iconLevel) {
                                0 -> R.drawable.ic_battery_0
                                1 -> R.drawable.ic_battery_1
                                2 -> R.drawable.ic_battery_2
                                3 -> R.drawable.ic_battery_3
                                4 -> R.drawable.ic_battery_4
                                else -> R.drawable.ic_battery_0
                            }
                            batteryIcon.setImageResource(iconResId)
                            batteryIcon.clearAnimation()
                            batteryIcon.clearColorFilter()
                            currentBatteryAlerting = false
                            chargingIcon.visibility = View.GONE
                        }
                    }
                }
                break
            }
        }
    }

    // Function to change the background colour of buttons
    fun MaterialButton.applySmartTint(@ColorRes enabledColorRes: Int? = null) {
        val context = this.context

        val enabledColor = if (enabledColorRes != null) {
            ContextCompat.getColor(context, enabledColorRes)
        } else {
            // Resolve colorPrimary from current theme
            val typedValue = TypedValue()
            val resolved = context.theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, typedValue, true)
            if (resolved) {
                if (typedValue.resourceId != 0) {
                    ContextCompat.getColor(context, typedValue.resourceId)
                } else {
                    typedValue.data
                }
            } else {
                // fallback in case attribute not found
                ContextCompat.getColor(context, R.color.purple_200)
            }
        }

        val states = arrayOf(
            intArrayOf(android.R.attr.state_enabled),
            intArrayOf(-android.R.attr.state_enabled)
        )

        val colors = intArrayOf(
            enabledColor,
            getDefaultDisabledColor(context)
        )

        backgroundTintList = ColorStateList(states, colors)
    }

    // Helper to get a button's disabled colour
    private fun getDefaultDisabledColor(context: android.content.Context): Int {
        val outValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.colorButtonNormal, outValue, true)
        val baseColor = ContextCompat.getColor(context, outValue.resourceId)

        // Android default disabled alpha is ~0.38
        return ColorUtils.setAlphaComponent(baseColor, (255 * 0.38f).toInt())
    }

    // Function to restart a live feed to the app
    fun restartLiveView() {
        lifecycleScope.launch {
            showStatus("Restarting live view...")

            val view = liveViewImageView
            if (view == null) {
                showStatus("Error: live view image view is not initialized")
                Log.e("MainActivity", "liveViewImageView is null in restartLiveView()")
                return@launch
            }

            val liveViewUrl = cameraController.startLiveView()
            if (liveViewUrl != null) {
                cameraController.startMjpegStream(liveViewUrl, view)
                showStatus("Live view resumed.")
                enableTouchAF(true)
                collectFocusStatusFlow()
            } else {
                showStatus("Failed to resume live view.")
            }
        }
    }

    // Shows a status message on screen and logs it.
    private var currentToast: Toast? = null
    private fun showStatus(message: String) {
        Log.d(TAG, message)
        // Ensure Toast runs on the main (UI) thread
        runOnUiThread {
            currentToast?.cancel()
            currentToast = Toast.makeText(this, message, Toast.LENGTH_SHORT)
            currentToast?.show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainScope.cancel()
        networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
    }
}

// Setup f-number spinner
//                        val fNumberAdapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, fNumberList)
//                        fNumberAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
//                        spinnerFNumber.adapter = fNumberAdapter
//                        currentFNumber?.let {
//                            val pos = fNumberList.indexOf(it)
//                            if (pos >= 0) spinnerFNumber.setSelection(pos)
//                        }

// Setup shutter speed spinner
//                        val shutterAdapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, shutterSpeedList)
//                        shutterAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
//                        spinnerShutterSpeeds.adapter = shutterAdapter
//                        currentShutter?.let {
//                            val pos = shutterSpeedList.indexOf(it)
//                            if (pos >= 0) spinnerShutterSpeeds.setSelection(pos)
//                        }
//
// Setup ISO spinner
//                        val isoAdapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, isoList)
//                        isoAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
//                        spinnerIso.adapter = isoAdapter
//                        currentISO?.let {
//                            val pos = isoList.indexOf(it)
//                            if (pos >= 0) spinnerIso.setSelection(pos)
//                        }

//    private fun populatefNumberSeekBar(fNumberList: List<String>, currentfNumber: String?) {
//        if (fNumberList.isEmpty()) {
//            showStatus("No f-number data available")
//            return
//        }
//
//        seekBarFNumber.max = fNumberList.size - 1
//
//        // Find current f-number index or default to 0
//        val currentIndex = fNumberList.indexOf(currentfNumber).takeIf { it >= 0 } ?: 0
//        seekBarIso.progress = currentIndex
//        textIsoValue.text = fNumberList[currentIndex]
//
//        // Listener to update f-number value on SeekBar change
//        seekBarFNumber.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
//            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
//                val fNumber = fNumberList.getOrNull(progress) ?: "Unknown"
//                textFNumber.text = fNumber
//                if (fromUser) {
//                    mainScope.launch {
//                        val success = cameraController.setFNumber(fNumber)
//                        showStatus(if (success) "F-number set to $fNumber" else "Failed to set f-number")
//                    }
//                }
//            }
//
//            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
//            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
//        })
//    }

