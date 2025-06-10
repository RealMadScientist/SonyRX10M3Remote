package com.example.sonyrx10m3remote

import android.content.pm.ActivityInfo
import android.os.Bundle
import com.journeyapps.barcodescanner.CaptureActivity

class PortraitCaptureActivity : CaptureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Force portrait orientation
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        super.onCreate(savedInstanceState)
    }
}