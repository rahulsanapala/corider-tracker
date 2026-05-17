package com.corider.tracker.voice

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class WalkieForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val walkie = WalkieTalkieSession.get(this)
        when (intent?.action) {
            ACTION_TOGGLE_TALK -> {
                val state = walkie.currentState()
                if (state.joined) {
                    walkie.setTalking(!state.talking)
                }
                showOrStop(walkie.currentState())
            }
            ACTION_END -> {
                walkie.leave()
                stopWalkieForeground()
                return START_NOT_STICKY
            }
            ACTION_DISMISS -> {
                stopWalkieForeground()
                return START_NOT_STICKY
            }
            ACTION_RESTORE -> showOrStop(walkie.currentState())
            else -> showOrStop(walkie.currentState())
        }
        return START_STICKY
    }

    private fun showOrStop(state: AgoraWalkieTalkie.State) {
        if (state.groupCode.isBlank() && !state.joined) {
            stopWalkieForeground()
            return
        }

        val notification = WalkieNotificationController.build(this, state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                WalkieNotificationController.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(WalkieNotificationController.NOTIFICATION_ID, notification)
        }
    }

    private fun stopWalkieForeground() {
        WalkieNotificationController.cancel(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    companion object {
        const val ACTION_UPDATE = "com.corider.tracker.walkie.UPDATE"
        const val ACTION_TOGGLE_TALK = "com.corider.tracker.walkie.TOGGLE_TALK"
        const val ACTION_END = "com.corider.tracker.walkie.END"
        const val ACTION_DISMISS = "com.corider.tracker.walkie.DISMISS"
        const val ACTION_RESTORE = "com.corider.tracker.walkie.RESTORE"
    }
}
