package com.example.sonyrx10m3remote

import kotlinx.coroutines.*
import kotlin.system.measureTimeMillis
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.TimeoutException

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
    private var job: Job? = null
    private var isRunning = false

    /**
     * Maps slider value to interval in milliseconds:
     *  0           → burst (continuous shooting)
     *  1..20       → 0.5s steps (0.5…10 s)
     * 21..70       → 1s steps (10…60 s)
     * 71..88      → 30s steps (1…10 min)
     * 89..108      → 60s steps (10…30 min)
     */
    fun getIntervalFromSlider(value: Int): Long = when {
        value == 0          -> 0L
        value in  1..20     -> 500L  * value
        value in 21..70     -> 1000L * (value - 10)
        value in 71..88     -> 30_000L * (value - 68)
        value in 89..107    -> 60_000L * (value - 78)
        else                -> 1_800_000L
    }

    fun getTotalShotsFromSlider(value: Int): Int? = when {
        value in  0..19     -> value + 1
        value in 20..35     -> 5 * (value - 15)
        value in 36..55     -> 20 * (value - 30)
        value in 56..86    -> 100 * (value - 50)
        else                -> null
    }

    /**
     * Start shooting:
     *  - if intervalMs == 0 → configure continuous mode and trigger one burst command
     *  - otherwise → single-shot at each interval
     */
    fun start(intervalMs: Long, totalShots: Int?, contShootingMode: String = "Continuous") {
        if (isRunning) {
            onStatusUpdate("Intervalometer already running")
            return
        }
        isRunning = true

        fun findStorageInfo(results: JSONArray): JSONObject? {
            for (i in 0 until results.length()) {
                val obj = results.opt(i)
                when (obj) {
                    is JSONObject -> {
                        if (obj.optString("type") == "storageInformation") {
                            return obj
                        }
                        val keys = obj.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val child = obj.opt(key)
                            if (child is JSONArray) {
                                val found = findStorageInfo(child)
                                if (found != null) return found
                            } else if (child is JSONObject) {
                                if (child.optString("type") == "storageInformation") return child
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

        job = CoroutineScope(Dispatchers.Default).launch {
            if (intervalMs == 0L) {
                // ── Burst mode ──

                val shutterSpeed = getShutterSpeed()
                if (shutterSpeed == "BULB") {
                    onStatusUpdate("Burst mode shooting cannot be used with bulb shutter speed")
                    stop()
                    return@launch
                }

                onStatusUpdate("Starting burst mode shooting")

                if (!cameraController.setShootMode("still")) {
                    onStatusUpdate("Failed to set shoot mode to 'still'")
                    stop()
                    return@launch
                }

                if (!cameraController.setContShootingMode(contShootingMode)) {
                    onStatusUpdate("Failed to set continuous shooting mode")
                    stop()
                    return@launch
                }

                val info = cameraController.getInfo()
                val resultsStart = info?.optJSONArray("result")
                val storageInfoStart = resultsStart?.let { findStorageInfo(it) }
                val initialStorageCount = storageInfoStart?.optInt("numberOfRecordableImages", -1) ?: -1
                if (initialStorageCount < 0) {
                    onStatusUpdate("Failed to get initial storage count")
                    stop()
                    return@launch
                }

                if (!cameraController.startContinuousShooting()) {
                    onStatusUpdate("Failed to start burst")
                    stop()
                    return@launch
                }

                onStatusUpdate("Continuous shooting started")

                var shotsTaken = 0
                while (isActive) {
                    val evt = cameraController.getEventLongPoll() ?: continue
                    val results = evt.optJSONArray("result") ?: continue
                    val storageInfo = findStorageInfo(results)
                    val currentStorageCount = storageInfo?.optInt("numberOfRecordableImages", -1) ?: -1
                    if (currentStorageCount < 0) continue

                    shotsTaken = initialStorageCount - currentStorageCount
                    onProgressUpdate(shotsTaken, totalShots)

                    if (totalShots != null && shotsTaken >= totalShots) {
                        onStatusUpdate("Reached target shots, stopping burst")
                        break
                    }

                    if (currentStorageCount == 0) {
                        onStatusUpdate("Storage full, stopping burst")
                        break
                    }
                }

                stop()

            } else {
                // ── Interval mode ──
                onStatusUpdate("Starting interval shooting every ${intervalMs / 1000.0} s, total shots: ${totalShots ?: "∞"}")
                cameraController.setShootMode("still")

                if (totalShots == null) {
                    var shotCount = 0
                    while (isActive) {
                        val shutterSpeed = getShutterSpeed()

                        val elapsed = measureTimeMillis {
                            if (shutterSpeed == "BULB") {
                                val bulbDuration = getBulbDurationMs()
                                if (bulbDuration <= 0L) {
                                    onError("Bulb duration cannot be zero or negative")
                                    stop()
                                    return@launch
                                }

                                val started = startBulb()
                                if (!started) {
                                    onStatusUpdate("Failed to start bulb exposure, stopping intervalometer")
                                    stop()
                                    return@launch
                                }
                                delay(bulbDuration)
                                val stopped = stopBulb()
                                if (!stopped) {
                                    onStatusUpdate("Failed to stop bulb exposure cleanly")
                                }
                            } else {
                                val shutterDurationMs = cameraController.currentShutterDurationMs ?: 0L
                                if (shutterDurationMs > 1000L) {
                                    performTimedCapture(shutterDurationMs)
                                } else {
                                    cameraController.takePicture()
                                }
                            }
                            shotCount++
                            onProgressUpdate(shotCount, null)
                        }

                        if (elapsed > intervalMs) {
                            onStatusUpdate("⚠ Shot took ${elapsed} ms, longer than interval (${intervalMs} ms)")
                            delay(0)
                        } else {
                            delay(intervalMs - elapsed)
                        }
                    }
                } else {
                    repeat(totalShots.toInt()) { i ->
                        if (!isActive) return@repeat

                        val shutterSpeed = getShutterSpeed()

                        val elapsed = measureTimeMillis {
                            if (shutterSpeed == "BULB") {
                                val bulbDuration = getBulbDurationMs()
                                if (bulbDuration <= 0L) {
                                    onError("Bulb duration cannot be zero or negative")
                                    stop()
                                    return@repeat
                                }

                                val started = startBulb()
                                if (!started) {
                                    onStatusUpdate("Failed to start bulb exposure, stopping intervalometer")
                                    stop()
                                    return@repeat
                                }
                                delay(bulbDuration)
                                val stopped = stopBulb()
                                if (!stopped) {
                                    onStatusUpdate("Failed to stop bulb exposure cleanly")
                                }
                            } else {
                                val shutterDurationMs = cameraController.currentShutterDurationMs ?: 0L
                                if (shutterDurationMs > 1000L) {
                                    performTimedCapture(shutterDurationMs)
                                } else {
                                    cameraController.takePicture()
                                }
                            }
                            onProgressUpdate(i + 1, totalShots)
                        }

                        if (elapsed > intervalMs) {
                            onStatusUpdate("⚠ Shot took ${elapsed} ms, longer than interval (${intervalMs} ms)")
                            delay(0)
                        } else {
                            delay(intervalMs - elapsed)
                        }
                    }
                    stop()
                }
            }
        }
    }

    /**
     * Stop either burst or interval shooting.
     * Cancels the coroutine and resets mode.
     */
    fun stop() {
        if (!isRunning) {
            onStatusUpdate("Intervalometer not running")
            return
        }

        CoroutineScope(Dispatchers.Default).launch {
            val stopped = cameraController.stopContinuousShooting()
            if (!stopped) {
                onStatusUpdate("Failed to stop continuous shooting")
            } else {
                onStatusUpdate("Continuous shooting stopped")
            }

            onStatusUpdate("Waiting for camera to become idle...")
            try {
                cameraController.waitForIdleStatus(timeoutMillis = 30000)
            } catch (e: TimeoutException) {
                onStatusUpdate("Warning: camera did not become idle within timeout")
            }

            val contModeReset = cameraController.setContShootingMode("Single")
            if (!contModeReset) {
                onStatusUpdate("Warning: Failed to reset continuous shooting mode to 'Single'")
            }

            val shootModeReset = cameraController.setShootMode("still")
            if (!shootModeReset) {
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

    /** Whether the intervalometer is active right now. */
    fun isRunning() = isRunning
}
