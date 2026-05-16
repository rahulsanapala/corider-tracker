package com.corider.tracker.voice

import android.content.Context
import android.util.Log
import com.corider.tracker.BuildConfig
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine

class AgoraWalkieTalkie(private val context: Context) {
    data class State(
        val groupCode: String = "",
        val joined: Boolean = false,
        val talking: Boolean = false,
        val speakerCount: Int = 0,
        val message: String = "Walkie talkie ready"
    )

    var onStateChanged: ((State) -> Unit)? = null

    private val remoteSpeakers = linkedSetOf<Int>()
    private var engine: RtcEngine? = null
    private var groupCode = ""
    private var channelName = ""
    private var joined = false
    private var talking = false
    private var message = "Walkie talkie ready"

    private val handler = object : IRtcEngineEventHandler() {
        override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
            joined = true
            talking = false
            message = "Voice connected"
            Log.i(TAG, "Joined voice channel=${channel.orEmpty()} uid=$uid")
            publishState()
        }

        override fun onConnectionStateChanged(state: Int, reason: Int) {
            message = connectionMessage(state, reason)
            Log.i(TAG, message)
            publishState()
        }

        override fun onUserJoined(uid: Int, elapsed: Int) {
            remoteSpeakers.add(uid)
            Log.i(TAG, "Remote rider joined voice uid=$uid")
            publishState()
        }

        override fun onUserOffline(uid: Int, reason: Int) {
            remoteSpeakers.remove(uid)
            Log.i(TAG, "Remote rider left voice uid=$uid reason=$reason")
            publishState()
        }

        override fun onError(err: Int) {
            message = "Voice error $err: ${errorMessage(err)}"
            Log.e(TAG, message)
            publishState()
        }
    }

    fun join(groupCode: String, riderId: String): Boolean {
        if (BuildConfig.AGORA_APP_ID.isBlank()) {
            message = "Add AGORA_APP_ID in local.properties"
            Log.e(TAG, message)
            publishState()
            return false
        }
        if (BuildConfig.AGORA_APP_ID == "your_agora_app_id_here") {
            message = "Replace AGORA_APP_ID in local.properties"
            Log.e(TAG, message)
            publishState()
            return false
        }
        val nextChannel = channelFor(groupCode)
        if (joined && channelName == nextChannel) return true

        leave()
        this.groupCode = groupCode
        channelName = nextChannel
        Log.i(TAG, "Joining voice group=$groupCode channel=$channelName")
        return runCatching {
            val rtcEngine = engine ?: createEngine().also { engine = it }
            rtcEngine.enableAudio()
            rtcEngine.setChannelProfile(Constants.CHANNEL_PROFILE_COMMUNICATION)
            rtcEngine.setAudioProfile(
                Constants.AUDIO_PROFILE_SPEECH_STANDARD,
                Constants.AUDIO_SCENARIO_CHATROOM
            )
            rtcEngine.setDefaultAudioRoutetoSpeakerphone(true)
            rtcEngine.muteLocalAudioStream(true)

            val options = ChannelMediaOptions().apply {
                channelProfile = Constants.CHANNEL_PROFILE_COMMUNICATION
                clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
                publishMicrophoneTrack = false
                autoSubscribeAudio = true
            }
            val uid = stableUid(riderId)
            val token = BuildConfig.AGORA_TOKEN.ifBlank { null }
            val result = rtcEngine.joinChannel(token, channelName, uid, options)
            message = if (result == 0) "Connecting voice..." else "Voice join failed $result: ${errorMessage(result)}"
            Log.i(TAG, "joinChannel result=$result uid=$uid channel=$channelName tokenPresent=${token != null}")
            publishState()
            result == 0
        }.getOrElse { error ->
            joined = false
            talking = false
            message = "Voice could not start: ${error.safeMessage()}"
            Log.e(TAG, message, error)
            publishState()
            false
        }
    }

    fun setTalking(enabled: Boolean) {
        if (!joined) {
            message = "Open an active group before talking."
            publishState()
            return
        }
        runCatching {
            talking = enabled
            engine?.muteLocalAudioStream(!enabled)
            engine?.updateChannelMediaOptions(ChannelMediaOptions().apply {
                channelProfile = Constants.CHANNEL_PROFILE_COMMUNICATION
                clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
                publishMicrophoneTrack = enabled
                autoSubscribeAudio = true
            })
            message = if (enabled) "Talking to group" else "Listening to group"
            Log.i(TAG, message)
        }.onFailure { error ->
            talking = false
            message = "Voice mic failed: ${error.safeMessage()}"
            Log.e(TAG, message, error)
        }
        publishState()
    }

    fun leave() {
        if (!joined && channelName.isBlank()) return
        talking = false
        joined = false
        remoteSpeakers.clear()
        runCatching {
            engine?.muteLocalAudioStream(true)
            engine?.leaveChannel()
        }
        groupCode = ""
        channelName = ""
        message = "Walkie talkie ready"
        Log.i(TAG, "Left voice channel")
        publishState()
    }

    fun release() {
        leave()
        engine?.let {
            runCatching { RtcEngine.destroy() }
            engine = null
        }
    }

    fun currentState(): State = State(groupCode, joined, talking, remoteSpeakers.size, message)

    private fun createEngine(): RtcEngine {
        return RtcEngine.create(context.applicationContext, BuildConfig.AGORA_APP_ID, handler)
    }

    private fun publishState() {
        onStateChanged?.invoke(currentState())
    }

    private fun channelFor(groupCode: String): String {
        return "corider_${groupCode.trim().uppercase().replace(Regex("[^A-Z0-9_]"), "_")}".take(64)
    }

    private fun stableUid(value: String): Int {
        val hash = value.fold(1125899907) { acc, char -> acc * 31 + char.code }
        return hash and Int.MAX_VALUE
    }

    private fun connectionMessage(state: Int, reason: Int): String {
        return when (state) {
            Constants.CONNECTION_STATE_CONNECTING -> "Voice connecting..."
            Constants.CONNECTION_STATE_CONNECTED -> "Voice connected"
            Constants.CONNECTION_STATE_RECONNECTING -> "Voice reconnecting..."
            Constants.CONNECTION_STATE_FAILED -> "Voice connection failed: reason $reason"
            Constants.CONNECTION_STATE_DISCONNECTED -> "Voice disconnected: reason $reason"
            else -> "Voice state $state, reason $reason"
        }
    }

    private fun errorMessage(code: Int): String {
        return when (code) {
            101 -> "invalid App ID"
            102 -> "invalid channel name"
            109 -> "token expired"
            110 -> "invalid token, check Agora project token mode"
            111 -> "connection interrupted"
            112 -> "connection lost"
            113 -> "not in channel"
            117 -> "join channel rejected"
            120 -> "encryption mismatch"
            134 -> "invalid user account"
            else -> "check Agora project, token mode, and internet"
        }
    }

    private fun Throwable.safeMessage(): String {
        return message ?: javaClass.simpleName.ifBlank { "unknown error" }
    }

    companion object {
        private const val TAG = "CoRiderVoice"
    }
}
