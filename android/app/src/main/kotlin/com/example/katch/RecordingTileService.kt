package com.example.katch

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

/**
 * Quick Settings Tile that surfaces a "Screen Recorder" shortcut in the
 * Android control centre / notification shade (API 24+).
 *
 * Behaviour:
 *  - **Idle**: tile label shows "Screen Recorder". Tapping launches [MainActivity]
 *    so the user can press Start Recording (screen-capture permission can only be
 *    obtained from an Activity).
 *  - **Active** (while recording): tile label shows "Stop Recording". Tapping
 *    sends [RecordingService.ACTION_STOP] directly to the service without needing
 *    to open the app.
 */
@RequiresApi(Build.VERSION_CODES.N)
class RecordingTileService : TileService() {

    override fun onTileAdded() = updateTile()

    override fun onStartListening() = updateTile()

    override fun onClick() {
        if (RecordingService.isRecording) {
            // Stop recording directly from the tile.
            startService(
                Intent(this, RecordingService::class.java).apply {
                    action = RecordingService.ACTION_STOP
                }
            )
            updateTile()
        } else {
            // Screen-capture permission requires an Activity; open the app.
            val appIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ requires a PendingIntent for startActivityAndCollapse.
                startActivityAndCollapse(
                    PendingIntent.getActivity(
                        this, 0, appIntent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(appIntent)
            }
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        if (RecordingService.isRecording) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "Stop Recording"
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Screen Recorder"
        }
        tile.updateTile()
    }
}
