package com.example.sonyrx10m3remote.camera

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeoutException
import kotlin.system.measureTimeMillis

/**
 * Intervalometer handles both burst (continuous) and interval shooting modes.
 *
 * It manages coroutine lifecycle for shooting sequences,
 * updates UI via callbacks, and interacts with the CameraController.
 */
class Intervalometer(
    private val cameraController: CameraController,
    private val onStatusUpdate: (String) -> Unit,
    private val onFinished: () -> Unit,
    private val getShutterSpeed: () -> String,
    private val startBulb: suspend () -> Boolean,
    private val stopBulb: suspend () -> Boolean,
    private val performTimedCapture: (Long) -> Unit,
    private val getBulbDurationMs: () -> Long,
    private val onError: (String) -> Unit,
    private val onProgressUpdate: (Int, Int?) -> Unit
) {
    private val TAG = "Intervalometer"

    // Job for the shooting coroutine, to control cancellation
    private var job: Job? = null

    // Flag indicating if intervalometer is currently running
    private var isRunning = false

    /**
     * Converts a slider value to interval duration in milliseconds.
     *
     * Mapping ranges:
     * - 0         → burst (continuous shooting)
     * - 1..20     → 0.5s steps (0.5 to 10 seconds)
     * - 21..70    → 1s steps (10 to 60 seconds)
     * - 71..88    → 30s steps (1 to 10 minutes)
     * - 89..107   → 60s steps (10 to 30 minutes)
     * - else      → 30 minutes (1,800,000 ms)
     */
    fun getIntervalFromSlider(value: Int): Long = when {
        value == 0          -> 0L
        value in  1..20     -> 500L  * value
        value in 21..70     -> 1000L * (value - 10)
        value in 71..88     -> 30_000L * (value - 68)
        value in 89..107    -> 60_000L * (value - 78)
        else                -> 1_800_000L
    }

    /**
     * Converts a slider value to total number of shots.
     *
     * Mapping ranges:
     * - 0..19    → value + 1
     * - 20..35   → 5 * (value - 15)
     * - 36..55   → 20 * (value - 30)
     * - 56..86   → 100 * (value - 50)
     * - else     → null (infinite shots)
     */
    fun getTotalShotsFromSlider(value: Int): Int? = when {
        value in  0..19     -> value + 1
        value in 20..35     -> 5 * (value - 15)
        value in 36..55     -> 20 * (value - 30)
        value in 56..86     -> 100 * (value - 50)
        else                -> null
    }

    /**
     * Starts the intervalometer with specified interval and total shots.
     *
     * If intervalMs == 0, burst mode (continuous shooting) is used.
     * Otherwise, single shots are taken at each interval.
     *
     * @param intervalMs Interval between shots in milliseconds
     * @param totalShots Number of shots to take (null for infinite)
     * @param contShootingMode Continuous shooting mode name (default "Continuous")
     */
    fun start(intervalMs: Long, totalShots: Int?, contShootingMode: String = "Continuous") {
        if (isRunning) {
            onStatusUpdate("Intervalometer already running")
            return
        }
        isRunning = true

        // Launch the main shooting coroutine on background thread
        job = CoroutineScope(Dispatchers.Default).launch {
            if (intervalMs == 0L) {
                // Burst mode (continuous shooting)
                runBurstMode(totalShots, contShootingMode)
            } else {
                // Interval shooting mode
                runIntervalMode(intervalMs, totalShots)
            }
        }
    }

    /**
     * Stops shooting gracefully.
     * Cancels shooting coroutine and resets camera modes.
     */
    fun stop() {
        if (!isRunning) {
            onStatusUpdate("Intervalometer not running")
            return
        }

        // Run stop operations on a background coroutine
        CoroutineScope(Dispatchers.Default).launch {
            val stopped = cameraController.stopContinuousShooting()
            if (!stopped) {
                onStatusUpdate("Failed to stop continuous shooting")
            } else {
                onStatusUpdate("Continuous shooting stopped")
            }

            onStatusUpdate("Waiting for camera to become idle...")
            try {
                cameraController.waitForIdleStatus(timeoutMillis = 30_000)
            } catch (e: TimeoutException) {
                onStatusUpdate("Warning: camera did not become idle within timeout")
            }

            // Reset continuous shooting mode to 'Single'
            if (!cameraController.setContShootingMode("Single")) {
                onStatusUpdate("Warning: Failed to reset continuous shooting mode to 'Single'")
            }

            // Reset shoot mode to 'still'
            if (!cameraController.setShootMode("still")) {
                onStatusUpdate("Warning: Failed to set shoot mode to 'still'")
            }

            withContext(Dispatchers.Main) {
                job?.cancel()
                isRunning = false
                onStatusUpdate("Intervalometer stopped")
                onFinished()
            }
        }
    }

    /**
     * Whether the intervalometer is active right now.
     */
    fun isRunning() = isRunning


    /* -------------------- Private helper functions -------------------- */

    /**
     * Recursively searches a JSONArray for a JSONObject with "type" == "storageInformation".
     *
     * Used to parse camera info and event JSON to find storage details.
     *
     * @param array JSONArray to search in
     * @return JSONObject with storage information or null if not found
     */
    private fun findStorageInfo(array: JSONArray): JSONObject? {
        for (i in 0 until array.length()) {
            val obj = array.opt(i)
            when (obj) {
                is JSONObject -> {
                    if (obj.optString("type") == "storageInformation") {
                        return obj
                    }
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val child = obj.opt(key)
                        when (child) {
                            is JSONArray -> {
                                val found = findStorageInfo(child)
                                if (found != null) return found
                            }
                            is JSONObject -> {
                                if (child.optString("type") == "storageInformation") return child
                            }
                        }
                    }
                }
                is JSONArray -> {
                    val found = findStorageInfo(obj)
                    if (found != null) return found
                }
            }
        }
        return null
    }

    /**
     * Runs burst (continuous) shooting mode.
     *
     * Continuously polls for shot events and tracks shot count.
     * Stops on reaching target shots or storage full.
     *
     * @param totalShots Optional max number of shots
     * @param contShootingMode Continuous shooting mode to set
     */
    private suspend fun CoroutineScope.runBurstMode(totalShots: Int?, contShootingMode: String) {
        val shutterSpeed = getShutterSpeed()
        if (shutterSpeed == "BULB") {
            onStatusUpdate("Burst mode shooting cannot be used with bulb shutter speed")
            stop()
            return
        }

        onStatusUpdate("Starting burst mode shooting")

        if (!cameraController.setShootMode("still")) {
            onStatusUpdate("Failed to set shoot mode to 'still'")
            stop()
            return
        }

        if (!cameraController.setContShootingMode(contShootingMode)) {
            onStatusUpdate("Failed to set continuous shooting mode")
            stop()
            return
        }

        val info = cameraController.getInfo()
        val resultsStart = info?.optJSONArray("result")
        val storageInfoStart = resultsStart?.let { findStorageInfo(it) }
        val initialStorageCount = storageInfoStart?.optInt("numberOfRecordableImages", -1) ?: -1
        if (initialStorageCount < 0) {
            onStatusUpdate("Failed to get initial storage count")
            stop()
            return
        }

        if (!cameraController.startContinuousShooting()) {
            onStatusUpdate("Failed to start burst")
            stop()
            return
        }

        onStatusUpdate("Continuous shooting started")

        var shotsTaken = 0

        // Loop while coroutine is active
        while (isActive) {
            val evt = cameraController.getEventLongPoll() ?: continue
            val results = evt.optJSONArray("result") ?: continue
            val storageInfo = findStorageInfo(results)
            val currentStorageCount = storageInfo?.optInt("numberOfRecordableImages", -1) ?: -1
            if (currentStorageCount < 0) continue

            shotsTaken = initialStorageCount - currentStorageCount
            onProgressUpdate(shotsTaken, totalShots)

            // Stop if reached target shot count
            if (totalShots != null && shotsTaken >= totalShots) {
                onStatusUpdate("Reached target shots, stopping burst")
                break
            }

            // Stop if storage is full
            if (currentStorageCount == 0) {
                onStatusUpdate("Storage full, stopping burst")
                break
            }
        }

        stop()
    }

    /**
     * Runs interval shooting mode.
     *
     * If totalShots is null, shoots indefinitely until stopped.
     * Handles bulb and normal shutter speeds.
     *
     * @param intervalMs Interval between shots in milliseconds
     * @param totalShots Number of shots to take or null for infinite
     */
    private suspend fun CoroutineScope.runIntervalMode(intervalMs: Long, totalShots: Int?) {
        onStatusUpdate("Starting interval shooting every ${intervalMs / 1000.0} s, total shots: ${totalShots ?: "∞"}")
        cameraController.setShootMode("still")

        if (totalShots == null) {
            runInfiniteIntervalShooting(intervalMs)
        } else {
            runFiniteIntervalShooting(intervalMs, totalShots)
            stop()
        }
    }

    /**
     * Runs interval shooting indefinitely until coroutine cancelled.
     */
    private suspend fun CoroutineScope.runInfiniteIntervalShooting(intervalMs: Long) {
        var shotCount = 0
        while (isActive) {
            val elapsed = measureTimeMillis {
                takeShotOrBulbExposure(shotCount + 1, null)
            }

            shotTimingDelay(intervalMs, elapsed)
            shotCount++
        }
    }

    /**
     * Runs interval shooting for a fixed number of shots.
     */
    private suspend fun CoroutineScope.runFiniteIntervalShooting(intervalMs: Long, totalShots: Int) {
        repeat(totalShots) { i ->
            if (!isActive) return@repeat

            val elapsed = measureTimeMillis {
                takeShotOrBulbExposure(i + 1, totalShots)
            }

            shotTimingDelay(intervalMs, elapsed)
        }
    }

    /**
     * Performs a single shot, either bulb or normal.
     *
     * Updates progress and reports errors.
     *
     * @param shotNumber Current shot number
     * @param totalShots Total shots or null if infinite
     */
    private suspend fun takeShotOrBulbExposure(shotNumber: Int, totalShots: Int?) {
        val shutterSpeed = getShutterSpeed()

        if (shutterSpeed == "BULB") {
            val bulbDuration = getBulbDurationMs()
            if (bulbDuration <= 0L) {
                onError("Bulb duration cannot be zero or negative")
                stop()
                return
            }

            val started = startBulb()
            if (!started) {
                onStatusUpdate("Failed to start bulb exposure, stopping intervalometer")
                stop()
                return
            }

            delay(bulbDuration)

            val stopped = stopBulb()
            if (!stopped) {
                onStatusUpdate("Failed to stop bulb exposure cleanly")
            }

        } else {
            // For shutter speeds over 1 second, use timed capture function
            val shutterDurationMs = cameraController.currentShutterDurationMs ?: 0L
            if (shutterDurationMs > 1000L) {
                performTimedCapture(shutterDurationMs)
            } else {
                cameraController.takePicture()
            }
        }

        onProgressUpdate(shotNumber, totalShots)
    }

    /**
     * Delays for the remainder of the interval after accounting for shot time.
     * Logs warning if shot time exceeds interval.
     */
    private suspend fun shotTimingDelay(intervalMs: Long, elapsedMs: Long) {
        if (elapsedMs > intervalMs) {
            onStatusUpdate("⚠ Shot took ${elapsedMs} ms, longer than interval (${intervalMs} ms)")
            delay(0)
        } else {
            delay(intervalMs - elapsedMs)
        }
    }
}