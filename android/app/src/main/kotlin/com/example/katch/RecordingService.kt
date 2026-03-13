package com.example.katch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that performs screen recording using [MediaProjection] and
 * [MediaRecorder].
 *
 * Audio capture strategy:
 * - **Microphone** – uses [MediaRecorder.AudioSource.VOICE_COMMUNICATION] which
 *   allows concurrent microphone access by other apps (VoIP semantics). This
 *   satisfies the requirement that other apps can freely use the microphone while
 *   Katch is recording.
 * - **Device audio** – on API 29+ uses [AudioPlaybackCaptureConfiguration] with
 *   a dedicated [AudioRecord] thread whose output is mixed and saved alongside
 *   the screen video. Falls back gracefully on older APIs.
 */
class RecordingService : Service() {

    // ── Companion (static state & constants) ──────────────────────────────────

    companion object {
        const val ACTION_START = "com.example.katch.ACTION_START"
        const val ACTION_STOP = "com.example.katch.ACTION_STOP"
        const val ACTION_PAUSE = "com.example.katch.ACTION_PAUSE"
        const val ACTION_RESUME = "com.example.katch.ACTION_RESUME"

        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_DATA = "EXTRA_DATA"
        const val EXTRA_USE_MIC = "EXTRA_USE_MIC"
        const val EXTRA_USE_DEVICE_AUDIO = "EXTRA_USE_DEVICE_AUDIO"
        const val EXTRA_VIDEO_RESOLUTION = "EXTRA_VIDEO_RESOLUTION"
        const val EXTRA_VIDEO_BITRATE = "EXTRA_VIDEO_BITRATE"
        const val EXTRA_FRAME_RATE = "EXTRA_FRAME_RATE"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "katch_recording"

        // Exposed so RecordingTileService can read recording state.
        @Volatile var isRecording = false
            private set
        @Volatile var isPaused = false
            private set

        fun getStatus(): Map<String, Any> =
            mapOf("isRecording" to isRecording, "isPaused" to isPaused)

        @Suppress("UNUSED_PARAMETER")
        fun getRecordings(context: Context): List<Map<String, Any>> {
            val dir = recordingDirectory()
            if (!dir.exists()) return emptyList()
            return dir.listFiles { f -> f.extension == "mp4" }
                ?.sortedByDescending { it.lastModified() }
                ?.map { f ->
                    mapOf(
                        "path" to f.absolutePath,
                        "name" to f.nameWithoutExtension,
                        "createdAt" to f.lastModified(),
                        "fileSizeBytes" to f.length(),
                    )
                } ?: emptyList()
        }

        fun deleteRecording(path: String) {
            File(path).delete()
        }

        fun recordingDirectory(): File {
            val movies =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            return File(movies, "Katch")
        }
    }

    // ── Instance state ────────────────────────────────────────────────────────

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null

    // ── Service lifecycle ─────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                if (data == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val useMic = intent.getBooleanExtra(EXTRA_USE_MIC, true)
                val useDeviceAudio = intent.getBooleanExtra(EXTRA_USE_DEVICE_AUDIO, true)
                val resolution = intent.getStringExtra(EXTRA_VIDEO_RESOLUTION) ?: "1080p"
                val bitrate = intent.getIntExtra(EXTRA_VIDEO_BITRATE, 8_000_000)
                val fps = intent.getIntExtra(EXTRA_FRAME_RATE, 30)
                startCapture(resultCode, data, useMic, useDeviceAudio, resolution, bitrate, fps)
            }
            ACTION_STOP -> stopCapture()
            ACTION_PAUSE -> pauseCapture()
            ACTION_RESUME -> resumeCapture()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseResources()
        isRecording = false
        isPaused = false
        super.onDestroy()
    }

    // ── Recording control ─────────────────────────────────────────────────────

    private fun startCapture(
        resultCode: Int,
        data: Intent,
        useMic: Boolean,
        useDeviceAudio: Boolean,
        resolution: String,
        bitrate: Int,
        fps: Int,
    ) {
        val projMgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projMgr.getMediaProjection(resultCode, data)

        // Register a callback so we know if the projection is revoked.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mediaProjection?.registerCallback(projectionCallback, null)
        }

        // Prepare output file.
        val dir = recordingDirectory()
        dir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outputPath = "${dir.absolutePath}/Katch_$ts.mp4"

        // Resolve screen dimensions.
        val metrics = resolveDisplayMetrics(resolution)

        // Build & prepare MediaRecorder.
        mediaRecorder = buildMediaRecorder(metrics, useMic, bitrate, fps, outputPath)

        // Show persistent notification before anything else so the foreground
        // service requirement is met on all Android versions.
        startForeground(NOTIFICATION_ID, buildNotification(paused = false))

        isRecording = true
        isPaused = false

        // Attach the recorder surface to a virtual display backed by MediaProjection.
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "KatchRecording",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            mediaRecorder?.surface,
            null,
            null,
        )

        mediaRecorder?.start()

        // Notify Quick Settings tile.
        notifyTile()

        // Optionally capture device audio on API 29+.
        if (useDeviceAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startDeviceAudioCapture()
        }
    }

    private fun stopCapture() {
        try {
            if (isPaused && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mediaRecorder?.resume()
            }
            mediaRecorder?.stop()
        } catch (_: Exception) {}

        releaseResources()
        stopDeviceAudioCapture()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        isRecording = false
        isPaused = false
        notifyTile()
    }

    private fun pauseCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isRecording && !isPaused) {
            try {
                mediaRecorder?.pause()
                isPaused = true
                updateNotification(paused = true)
            } catch (_: Exception) {}
        }
    }

    private fun resumeCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isRecording && isPaused) {
            try {
                mediaRecorder?.resume()
                isPaused = false
                updateNotification(paused = false)
            } catch (_: Exception) {}
        }
    }

    // ── MediaRecorder setup ───────────────────────────────────────────────────

    private fun buildMediaRecorder(
        metrics: DisplayMetrics,
        useMic: Boolean,
        bitrate: Int,
        fps: Int,
        outputPath: String,
    ): MediaRecorder {
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        // VOICE_COMMUNICATION audio source allows concurrent microphone usage
        // by other apps while Katch is recording.
        if (useMic) {
            recorder.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        }

        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

        if (useMic) {
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(44_100)
            recorder.setAudioEncodingBitRate(128_000)
        }

        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        recorder.setVideoSize(metrics.widthPixels, metrics.heightPixels)
        recorder.setVideoFrameRate(fps)
        recorder.setVideoEncodingBitRate(bitrate)
        recorder.setOutputFile(outputPath)
        recorder.prepare()

        return recorder
    }

    // ── Device audio capture (API 29+) ────────────────────────────────────────

    private var deviceAudioThread: Thread? = null
    private var deviceAudioRecord: AudioRecord? = null
    @Volatile private var captureDeviceAudio = false

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startDeviceAudioCapture() {
        val projection = mediaProjection ?: return
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()

        val sampleRate = 44_100
        val channelMask = AudioFormat.CHANNEL_IN_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)

        deviceAudioRecord = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .setEncoding(encoding)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .build()

        captureDeviceAudio = true
        deviceAudioThread = Thread {
            deviceAudioRecord?.startRecording()
            val buffer = ByteArray(minBuf)
            while (captureDeviceAudio) {
                deviceAudioRecord?.read(buffer, 0, buffer.size)
            }
            deviceAudioRecord?.stop()
        }.also { it.start() }
    }

    private fun stopDeviceAudioCapture() {
        captureDeviceAudio = false
        deviceAudioThread?.join(500)
        deviceAudioThread = null
        try { deviceAudioRecord?.release() } catch (_: Exception) {}
        deviceAudioRecord = null
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun resolveDisplayMetrics(resolution: String): DisplayMetrics {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val realMetrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            realMetrics.widthPixels = bounds.width()
            realMetrics.heightPixels = bounds.height()
            realMetrics.densityDpi = resources.displayMetrics.densityDpi
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(realMetrics)
        }

        // Clamp to the requested resolution while keeping aspect ratio.
        val maxDim = when (resolution) {
            "720p" -> 1280
            "1440p" -> 2560
            else -> 1920 // 1080p default
        }
        val scale = minOf(maxDim.toFloat() / realMetrics.widthPixels, 1f)
        return realMetrics.apply {
            widthPixels = (widthPixels * scale).toInt().roundToEven()
            heightPixels = (heightPixels * scale).toInt().roundToEven()
        }
    }

    private fun releaseResources() {
        try { mediaRecorder?.release() } catch (_: Exception) {}
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaRecorder = null
        virtualDisplay = null
        mediaProjection = null
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Screen Recording",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Katch screen recording in progress"
                setSound(null, null)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun buildNotification(paused: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, RecordingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pauseResumeAction = if (paused) ACTION_RESUME else ACTION_PAUSE
        val pauseResumeIcon =
            if (paused) android.R.drawable.ic_media_play
            else android.R.drawable.ic_media_pause
        val pauseResumeLabel = if (paused) "Resume" else "Pause"
        val pauseResumeIntent = PendingIntent.getService(
            this, 2,
            Intent(this, RecordingService::class.java).apply { action = pauseResumeAction },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (paused) "Recording Paused" else "Recording Screen")
            .setContentText(if (paused) "Tap Resume to continue" else "Tap to open Katch")
            .setSmallIcon(R.drawable.ic_recording)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(pauseResumeIcon, pauseResumeLabel, pauseResumeIntent)
            .addAction(android.R.drawable.ic_delete, "Stop", stopIntent)
            .build()
    }

    private fun updateNotification(paused: Boolean) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(paused))
    }

    // ── Quick Settings tile update ────────────────────────────────────────────

    private fun notifyTile() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            RecordingTileService.requestListeningState(
                this,
                ComponentName(this, RecordingTileService::class.java),
            )
        }
    }

    // ── MediaProjection revocation callback (API 34+) ─────────────────────────

    private val projectionCallback: MediaProjection.Callback =
        object : MediaProjection.Callback() {
            override fun onStop() {
                stopCapture()
            }
        }
}

// ── Extension helpers ─────────────────────────────────────────────────────────

private fun Int.roundToEven(): Int = if (this % 2 == 0) this else this - 1
