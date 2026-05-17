package com.corider.tracker.voice

import android.content.Context

object WalkieTalkieSession {
    @Volatile
    private var instance: AgoraWalkieTalkie? = null

    fun get(context: Context): AgoraWalkieTalkie {
        return instance ?: synchronized(this) {
            instance ?: AgoraWalkieTalkie(context.applicationContext).also { instance = it }
        }
    }

    fun releaseIfIdle(context: Context) {
        val walkie = get(context)
        if (!walkie.currentState().joined) {
            walkie.release()
        }
    }
}
