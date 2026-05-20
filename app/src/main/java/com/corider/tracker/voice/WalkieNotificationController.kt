package com.corider.tracker.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.corider.tracker.MainActivity
import com.corider.tracker.R

object WalkieNotificationController {
    private const val CHANNEL_ID = "corider_walkie"
    const val NOTIFICATION_ID = 4207

    const val ACTION_TOGGLE_TALK = "com.corider.tracker.walkie.TOGGLE_TALK"
    const val ACTION_END = "com.corider.tracker.walkie.END"

    fun update(context: Context, state: AgoraWalkieTalkie.State) {
        if (state.groupCode.isBlank() && !state.joined) {
            cancel(context)
        } else {
            show(context, state)
        }
    }

    fun show(context: Context, state: AgoraWalkieTalkie.State) {
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(NotificationManager::class.java)
        ensureChannel(manager)
        manager.notify(NOTIFICATION_ID, build(appContext, state))
    }

    fun build(context: Context, state: AgoraWalkieTalkie.State): Notification {
        val appContext = context.applicationContext
        ensureChannel(appContext.getSystemService(NotificationManager::class.java))
        val talkTitle = if (state.talking) "Stop Talk" else "Talk"
        val talkIcon = if (state.talking) R.drawable.ic_mic_off else R.drawable.ic_mic
        val contentText = when {
            state.onHold -> "On hold during phone call"
            state.talking -> "Talking in ${state.groupCode}"
            state.joined -> "Listening in ${state.groupCode}"
            else -> state.message
        }

        val builder = Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ride_notification)
            .setContentTitle("CoRider walkie talkie")
            .setContentText(contentText)
            .setContentIntent(openAppIntent(appContext))
            .setDeleteIntent(serviceIntent(appContext, WalkieForegroundService.ACTION_RESTORE))
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setPriority(Notification.PRIORITY_HIGH)
        if (!state.onHold) {
            builder
            .addAction(
                Notification.Action.Builder(
                    talkIcon,
                    talkTitle,
                    serviceIntent(appContext, WalkieForegroundService.ACTION_TOGGLE_TALK)
                ).build()
            )
        }
        return builder
            .addAction(
                Notification.Action.Builder(
                    R.drawable.ic_call_end,
                    "End",
                    serviceIntent(appContext, WalkieForegroundService.ACTION_END)
                ).build()
            )
            .build()
            .apply {
                flags = flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT
            }
    }

    fun cancel(context: Context) {
        context.applicationContext
            .getSystemService(NotificationManager::class.java)
            .cancel(NOTIFICATION_ID)
    }

    fun ensureChannel(manager: NotificationManager) {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "CoRider walkie talkie",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
            }
        )
    }

    private fun serviceIntent(context: Context, action: String): PendingIntent {
        val intent = Intent(context, WalkieForegroundService::class.java).setAction(action)
        return PendingIntent.getService(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
