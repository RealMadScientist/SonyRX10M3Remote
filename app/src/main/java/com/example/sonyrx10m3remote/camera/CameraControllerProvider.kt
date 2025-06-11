package com.example.sonyrx10m3remote.camera

object CameraControllerProvider {
    private var _instance: CameraController? = null

    // Nullable instance, callers must check for null (preferred)
    val instance: CameraController?
        get() = _instance

    // Initialize with baseUrl if not already initialized
    fun init(baseUrl: String) {
        if (_instance == null) {
            _instance = CameraController(baseUrl)
        }
    }

    // Clear the instance, e.g. when camera disconnects
    fun clear() {
        _instance = null
    }
}