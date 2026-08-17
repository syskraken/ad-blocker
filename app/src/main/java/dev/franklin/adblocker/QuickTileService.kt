package dev.franklin.adblocker

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

/**
 * Quick Settings tile for toggling the tunnel without opening the app.
 *
 * Android will not add a tile to the panel on an app's behalf — the user drags
 * it in, or accepts the system prompt offered on Android 13+.
 */
class QuickTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()

        if (AdVpnService.isRunning) {
            startService(Intent(this, AdVpnService::class.java).setAction(AdVpnService.ACTION_STOP))
            return
        }

        if (VpnService.prepare(this) == null) {
            // Consent already granted, so the tunnel starts without any UI.
            ContextCompat.startForegroundService(
                this,
                Intent(this, AdVpnService::class.java).setAction(AdVpnService.ACTION_START),
            )
        } else {
            // First run, or consent was revoked: only an Activity may ask for it.
            openApp()
        }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14 removed the Intent overload; it throws if called there.
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val running = AdVpnService.isRunning

        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(if (running) R.string.tile_on else R.string.tile_off)
        }
        tile.updateTile()
    }

    companion object {
        /**
         * Asks the system to call [onStartListening] so the tile re-reads state.
         * Needed because the tunnel can also be stopped from the notification or
         * by Android revoking the VPN, neither of which the tile would notice.
         */
        fun refresh(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, QuickTileService::class.java))
            } catch (e: Exception) {
                // Thrown when the tile is not in the user's panel; nothing to update.
            }
        }
    }
}
