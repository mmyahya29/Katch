package com.example.katch

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private val channelName = "com.example.katch/recording"
    private val projectionRequestCode = 1001

    private var pendingResult: MethodChannel.Result? = null
    private var pendingUseMic = true
    private var pendingUseDeviceAudio = true
    private var pendingVideoResolution = "1080p"
    private var pendingVideoBitrate = 8_000_000
    private var pendingFrameRate = 30

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "startRecording" -> {
                        pendingUseMic = call.argument<Boolean>("useMic") ?: true
                        pendingUseDeviceAudio =
                            call.argument<Boolean>("useDeviceAudio") ?: true
                        pendingVideoResolution =
                            call.argument<String>("videoResolution") ?: "1080p"
                        pendingVideoBitrate =
                            call.argument<Int>("videoBitrate") ?: 8_000_000
                        pendingFrameRate = call.argument<Int>("frameRate") ?: 30
                        requestScreenCapture(result)
                    }
                    "stopRecording" -> {
                        sendServiceAction(RecordingService.ACTION_STOP)
                        result.success(null)
                    }
                    "pauseRecording" -> {
                        sendServiceAction(RecordingService.ACTION_PAUSE)
                        result.success(null)
                    }
                    "resumeRecording" -> {
                        sendServiceAction(RecordingService.ACTION_RESUME)
                        result.success(null)
                    }
                    "getStatus" -> result.success(RecordingService.getStatus())
                    "getRecordings" ->
                        result.success(RecordingService.getRecordings(this))
                    "deleteRecording" -> {
                        val path = call.argument<String>("path") ?: ""
                        RecordingService.deleteRecording(path)
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }
    }

    private fun requestScreenCapture(result: MethodChannel.Result) {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        pendingResult = result
        @Suppress("DEPRECATION")
        startActivityForResult(mgr.createScreenCaptureIntent(), projectionRequestCode)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != projectionRequestCode) return

        val result = pendingResult
        pendingResult = null

        if (resultCode == Activity.RESULT_OK && data != null) {
            val intent = Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_START
                putExtra(RecordingService.EXTRA_RESULT_CODE, resultCode)
                putExtra(RecordingService.EXTRA_DATA, data)
                putExtra(RecordingService.EXTRA_USE_MIC, pendingUseMic)
                putExtra(RecordingService.EXTRA_USE_DEVICE_AUDIO, pendingUseDeviceAudio)
                putExtra(RecordingService.EXTRA_VIDEO_RESOLUTION, pendingVideoResolution)
                putExtra(RecordingService.EXTRA_VIDEO_BITRATE, pendingVideoBitrate)
                putExtra(RecordingService.EXTRA_FRAME_RATE, pendingFrameRate)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            result?.success(true)
        } else {
            result?.success(false)
        }
    }

    private fun sendServiceAction(action: String) {
        startService(Intent(this, RecordingService::class.java).apply {
            this.action = action
        })
    }
}
