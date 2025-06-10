package com.example.sonyrx10m3remote

import android.graphics.BitmapFactory
import android.os.Environment
import android.util.Log
import android.widget.ImageView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import org.json.JSONArray
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeoutException

class CameraController(baseUrl: String) {

    var availableFNumbers: List<String> = emptyList()
    var availableShutterSpeeds: List<String> = emptyList()
    var availableISOs: List<String> = emptyList()
    var availableExpComps: List<Int> = emptyList()

    var currentShutterDurationMs: Long? = null

    private val client = OkHttpClient()

    private val rpcUrl = if (baseUrl.endsWith("/camera")) baseUrl else "$baseUrl/camera"

    private val jsonMediaType = "application/json; charset=utf-8".toMediaTypeOrNull()

    private val mjpegScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mjpegJob: Job? = null
    private var currentLiveViewUrl: String? = null

    private val _focusStatus = MutableSharedFlow<String>(replay = 0)
    val focusStatus: SharedFlow<String> = _focusStatus
    private var eventPollJob: Job? = null
    private var _isCapturing = false
    val isCapturing: Boolean get() = _isCapturing

    // Posts JSON string to the camera API and returns the response body string or null if failed.
    private suspend fun postJson(jsonBody: String): String? = withContext(Dispatchers.IO) {
        Log.d(TAG, "POST JSON body:\n$jsonBody")
        try {
            val body = RequestBody.create(jsonMediaType, jsonBody)
            val request = Request.Builder()
                .url(rpcUrl)
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "HTTP error code: ${response.code}")
                    return@withContext null
                }
                val responseBody = response.body?.string()
                Log.d(TAG, "POST response body: $responseBody")
                return@withContext responseBody
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network error: ${e.localizedMessage}")
            return@withContext null
        }
    }

    // Generic function to send a method request with parameters and get the raw JSON response.
    private suspend fun callMethod(
        method: String,
        params: List<Any> = emptyList(),
        version: String = "1.0"
    ): JSONObject? {
        val json = JSONObject().apply {
            put("method", method)
            put("params", JSONArray(params))
            put("id", 1)
            put("version", version)
        }
        val resp = postJson(json.toString()) ?: return null
        return try {
            JSONObject(resp)
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse error for $method: ${e.localizedMessage}")
            null
        }
    }

    suspend fun downloadImage(url: String, filename: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val connection = URL(url).openConnection()
            connection.connect()
            val inputStream = connection.getInputStream()
            val buffer = ByteArray(8 * 1024)

            // Directory in external storage Pictures folder
            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            if (!picturesDir.exists()) picturesDir.mkdirs()

            val imageFile = File(picturesDir, filename)
            val outputStream = FileOutputStream(imageFile)

            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()

            // Optionally, notify MediaStore about the new file here if you want it to appear immediately in Gallery

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun startAFPoll() {
        if (eventPollJob?.isActive == true) return

        eventPollJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    val eventJson = getInfo(version = "1.1") ?: continue

                    val results = eventJson.optJSONArray("result") ?: continue

                    for (i in 0 until results.length()) {
                        val eventObj = results.optJSONObject(i) ?: continue
                        val focus = eventObj.optString("focusStatus", null)
                        if (focus != null) {
                            _focusStatus.emit(focus)
                        }
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Error polling getEvent: ${e.localizedMessage}")
                }

                delay(500) // poll every 0.5 seconds (adjust as needed)
            }
        }
    }

    fun stopAFPoll() {
        eventPollJob?.cancel()
        eventPollJob = null
    }

    // Gets the current camera event info.
    suspend fun getInfo(version: String = "1.0"): JSONObject? = callMethod("getEvent", listOf(false), version)
    suspend fun getEventLongPoll(): JSONObject? = callMethod("getEvent", listOf(true))

    // Waits until camera status becomes "IDLE" or times out.
    suspend fun waitForIdleStatus(timeoutMillis: Long = 10000) {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            val eventJson = getInfo()
            val status = eventJson?.optJSONArray("result")?.let { array ->
                (0 until array.length())
                    .mapNotNull { array.optJSONObject(it)?.takeIf { it.optString("type") == "cameraStatus" }?.optString("cameraStatus") }
                    .firstOrNull()
            }
            if (status == "IDLE") return
            delay(200)
        }
        throw TimeoutException("Camera did not become IDLE in time")
    }

    // Starts recording mode on the camera.
    suspend fun startRecMode(): Boolean {
        val resp = callMethod("startRecMode")
        return resp?.has("result") == true.also {
            if (it) Log.d(TAG, "Camera entered recording mode.")
            else Log.e(TAG, "Failed to enter recording mode.")
        }
    }

    // Starts live view and returns the live view URL or null if failed.
    suspend fun startLiveView(): String? {
        val resp = callMethod("startLiveview")
        val url = resp?.optJSONArray("result")?.optString(0)
        if (url == null) {
            Log.e(TAG, "Failed to get liveview URL")
        } else {
            Log.d(TAG, "Liveview URL: $url")
            currentLiveViewUrl = url
        }
        return url
    }

    /**
     * Starts MJPEG streaming from live view URL to update the provided ImageView continuously.
     * Runs in a background coroutine.
     */
    fun startMjpegStream(liveViewUrl: String, imageView: ImageView) {
        stopMjpegStream()  // Cancel existing before starting new

        currentLiveViewUrl = liveViewUrl

        mjpegJob = mjpegScope.launch(Dispatchers.IO) {
            try {
                val url = URL(liveViewUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.doInput = true
                connection.connect()

                val inputStream = BufferedInputStream(connection.inputStream)
                val buffer = ByteArrayOutputStream()
                val temp = ByteArray(4096)
                var insideImage = false

                while (isActive) {
                    val bytesRead = inputStream.read(temp)
                    if (bytesRead == -1) break

                    var i = 0
                    while (i < bytesRead) {
                        val b = temp[i]

                        if (!insideImage) {
                            if (i < bytesRead - 1 && temp[i] == 0xFF.toByte() && temp[i + 1] == 0xD8.toByte()) {
                                buffer.reset()
                                buffer.write(0xFF)
                                buffer.write(0xD8)
                                insideImage = true
                                i += 2
                                continue
                            }
                        } else {
                            buffer.write(b.toInt())
                            if (i < bytesRead - 1 && temp[i] == 0xFF.toByte() && temp[i + 1] == 0xD9.toByte()) {
                                buffer.write(0xD9)
                                val jpegBytes = buffer.toByteArray()
                                withContext(Dispatchers.Main) {
                                    val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                                    if (bitmap != null) {
                                        imageView.setImageBitmap(bitmap)
                                    }
                                }
                                insideImage = false
                                i += 2
                                continue
                            }
                        }
                        i++
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "MJPEG stream error: ${e.message}", e)
            }
        }
    }

    // Stops MJPEG streaming if running,
    fun stopMjpegStream() {
        mjpegJob?.cancel()
        mjpegJob = null
        currentLiveViewUrl = null
    }

    // Requests the current camera function mode.
    suspend fun getCameraFunction(): String? {
        val resp = callMethod("getCameraFunction")
        if (resp == null) {
            Log.e(TAG, "getCameraFunction: null response")
            return null
        }
        return try {
            val resultArray = resp.optJSONArray("result")
            if (resultArray != null && resultArray.length() > 0) {
                resultArray.optString(0)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "getCameraFunction JSON parse error: ${e.localizedMessage}")
            null
        }
    }

    // Sets camera function mode.
    suspend fun setCameraFunction(function: String): Boolean {
        val resp = callMethod("setCameraFunction", listOf(function))
        return resp?.has("result") == true
    }

    // Requests the current shooting mode.
    suspend fun getShootMode(): String? {
        val resp = callMethod("getShootMode")
        if (resp == null) {
            Log.e(TAG, "getShootMode: null response")
            return null
        }
        return try {
            val resultArray = resp.optJSONArray("result")
            if (resultArray != null && resultArray.length() > 0) {
                resultArray.optString(0)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "getShootMode JSON parse error: ${e.localizedMessage}")
            null
        }
    }

    // Sets shooting mode.
    suspend fun setShootMode(mode: String): Boolean {
        val resp = callMethod("setShootMode", listOf(mode))
        return resp?.has("result") == true
    }

    // Sets continuous shooting mode.
    suspend fun setContShootingMode(mode: String): Boolean {
        val params = listOf(mapOf("contShootingMode" to mode))
        val response = callMethod("setContShootingMode", params)
        return response?.optJSONArray("result") != null
    }

    /**
     * Takes a picture in still mode.
     * Ensures camera is in remote shooting and still shoot mode before capture.
     */
    suspend fun captureStill(): Boolean {
        _isCapturing = true
        try {
            // Only change shoot mode if it's not already in "still"
            val currentMode = getShootMode()
            if (currentMode != "still") {
                val modeResult = setShootMode("still")
                if (!modeResult) {
                    Log.e(TAG, "Failed to set shoot mode to still")
                    return false
                }
            }

            // Now capture the image
            val resp = callMethod("actTakePicture")
            if (resp == null) {
                Log.e(TAG, "actTakePicture: null response")
                return false
            }

            val resultArray = resp.optJSONArray("result")
            if (resultArray != null && resultArray.length() > 0) {
                Log.d(TAG, "Image capture success: $resultArray")
                return true
            }
            Log.e(TAG, "Image capture failed: empty result")
            return false

        } catch (e: Exception) {
            Log.e(TAG, "Error in captureStill: ${e.localizedMessage}")
            return false

        } finally {
            _isCapturing = false
        }
    }


    // Helper to get a camera setting value by method name.
    private suspend fun getSetting(method: String): String? {
        val resp = callMethod(method) ?: return null
        return resp.optJSONArray("result")?.optString(0)
    }

    // Helper to set a camera setting value by method name with validation.
    private suspend fun setSetting(
        method: String,
        value: Any,
        validValues: List<Any>? = null
    ): Boolean {
        if (validValues != null && !validValues.contains(value)) {
            Log.w(TAG, "Invalid value for $method: $value")
            return false
        }
        val resp = callMethod(method, listOf(value))
        return resp?.has("result") == true
    }

    // Public getters and setters for camera parameters:

    suspend fun getFNumber(): String? = getSetting("getFNumber")

    suspend fun setFNumber(fNumber: String): Boolean = setSetting("setFNumber", fNumber, availableFNumbers)

    suspend fun getShutterSpeed(): String? = getSetting("getShutterSpeed")

    suspend fun setShutterSpeed(shutterSpeed: String): Boolean = setSetting("setShutterSpeed", shutterSpeed, availableShutterSpeeds)

    fun parseShutterSpeedToMs(shutter: String?): Long? {
        if (shutter == null) return 0L

        // Remove trailing double quote if present (e.g., 4")
        val cleanShutter = shutter.trim().removeSuffix("\"")

        return when {
            cleanShutter.equals("BULB", ignoreCase = true) -> null
            cleanShutter.contains("/") -> {
                val parts = cleanShutter.split("/")
                if (parts.size == 2) {
                    val numerator = parts[0].toDoubleOrNull()
                    val denominator = parts[1].toDoubleOrNull()
                    if (numerator != null && denominator != null && denominator != 0.0) {
                        (1000.0 * (numerator / denominator)).toLong()
                    } else 0L
                } else 0L
            }
            else -> {
                val seconds = cleanShutter.toDoubleOrNull()
                if (seconds != null) (1000.0 * seconds).toLong() else 0L
            }
        }
    }

    suspend fun getISO(): String? = getSetting("getIsoSpeedRate")

    suspend fun setISO(iso: String): Boolean = setSetting("setIsoSpeedRate", iso, availableISOs)

    suspend fun getExpComp(): String? = getSetting("getExposureCompensation")

    suspend fun setExpComp(expComp: Int): Boolean = setSetting("setExposureCompensation", expComp, availableExpComps)

    // Starts continuous shooting mode
    suspend fun startContinuousShooting(): Boolean {
        val resp = callMethod("startContShooting")
        return resp?.has("result") == true
    }

    // Stops continuous shooting mode if active
    suspend fun stopContinuousShooting(): Boolean {
        val resp = callMethod("stopContShooting")
        return resp?.has("result") == true
    }

    // Starts bulb exposure shot.
    suspend fun startBulbExposure(): Boolean {
        try {
            val currentMode = getShootMode()
            if (currentMode != "still") {
                val modeResult = setShootMode("still")
                if (!modeResult) {
                    Log.e(TAG, "Failed to set shoot mode to still for bulb exposure")
                    return false
                }
            }

            val response = callMethod("startBulbShooting")
            return if (response?.has("result") == true) {
                Log.d(TAG, "startBulbShooting succeeded.")
                true
            } else {
                Log.e(TAG, "startBulbShooting failed: $response")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in startBulbExposure: ${e.localizedMessage}")
            return false
        }
    }

    // Stops bulb exposure shot.
    suspend fun stopBulbExposure(): Boolean {
        val response = callMethod("stopBulbShooting")
        return if (response?.has("result") == true) {
            Log.d(TAG, "stopBulbShooting succeeded.")
            true
        } else {
            Log.e(TAG, "stopBulbShooting failed: $response")
            false
        }
    }

    // Starts video recording.
    suspend fun startMovieRec(): Boolean {
        try {
            val currentMode = getShootMode()
            if (currentMode != "movie") {
                val modeResult = setShootMode("movie")
                if (!modeResult) {
                    Log.e(TAG, "Failed to set shoot mode to movie for video recording")
                    return false
                }
            }

            val response = callMethod("startMovieRec")
            return if (response?.has("result") == true) {
                Log.d(TAG, "startMovieRec succeeded.")
                true
            } else {
                Log.e(TAG, "startMovieRec failed: $response")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in startMovieRec: ${e.localizedMessage}")
            return false
        }
    }

    // Stops video recording.
    suspend fun stopMovieRec(): Boolean {
        try {
            val response = callMethod("stopMovieRec")
            if (response?.has("result") != true) {
                Log.e(TAG, "stopMovieRec failed: $response")
                return false
            }

            Log.d(TAG, "stopMovieRec succeeded.")

            // Try to switch back to still mode after stopping
            val modeResult = setShootMode("still")
            if (!modeResult) {
                Log.w(TAG, "Video stopped but failed to switch back to still mode")
            } else {
                Log.d(TAG, "Switched back to still mode after stopping video")
            }

            return true

        } catch (e: Exception) {
            Log.e(TAG, "Error in stopMovieRec: ${e.localizedMessage}")
            return false
        }
    }

    // Starts the auto-focus by simulating a half-press of the shutter
    suspend fun startAutoFocus(): Boolean {
        val response = callMethod("actHalfPressShutter")
        return response != null && !response.has("error")
    }

    // Cancels auto-focus by releasing the half shutter press
    suspend fun stopAutoFocus(): Boolean {
        val response = callMethod("cancelHalfPressShutter")
        return response != null && !response.has("error")
    }

    // Takes a single picture.
    suspend fun takePicture(): Boolean {
        val resp = callMethod("actTakePicture")
        return resp?.has("result") == true
    }

    // Sets the auto-focus position based on coordinates
    suspend fun setTouchAFPosition(xPercent: Float, yPercent: Float): Boolean {
        val result = callMethod("setTouchAFPosition", listOf(xPercent, yPercent))
        return result?.has("result") == true
    }

    // Cancels auto-focus position set by TouchAF
    suspend fun cancelTouchAFPosition(): Boolean {
        val result = callMethod("cancelTouchAFPosition")
        return result?.has("result") == true
    }

    // Disconnect and clean up resources.
    fun disconnect() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        stopMjpegStream()
    }

    companion object {
        private const val TAG = "CameraController"
    }
}