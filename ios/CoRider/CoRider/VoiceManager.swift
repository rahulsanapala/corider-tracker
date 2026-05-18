import AgoraRtcKit
import Foundation

final class VoiceManager: NSObject, ObservableObject, AgoraRtcEngineDelegate {
    static let shared = VoiceManager()

    @Published var groupCode = ""
    @Published var joined = false
    @Published var talking = false
    @Published var speakerCount = 0
    @Published var message = "Walkie talkie ready"

    private var engine: AgoraRtcEngineKit?
    private var channelName = ""
    private var remoteSpeakers = Set<UInt>()

    private override init() {
        super.init()
    }

    func join(groupCode nextGroup: String, riderId: String) {
        let appId = Bundle.main.object(forInfoDictionaryKey: "AgoraAppId") as? String ?? ""
        guard !appId.isEmpty, appId != "your_agora_app_id_here" else {
            message = "Add AGORA_APP_ID in Config.xcconfig"
            return
        }

        let nextChannel = channelFor(nextGroup)
        if joined && channelName == nextChannel { return }
        leave()

        groupCode = nextGroup
        channelName = nextChannel
        let rtc = engine ?? AgoraRtcEngineKit.sharedEngine(withAppId: appId, delegate: self)
        engine = rtc
        rtc.enableAudio()
        rtc.setChannelProfile(.communication)
        rtc.setAudioProfile(.speechStandard, scenario: .chatRoom)
        rtc.setDefaultAudioRouteToSpeakerphone(true)
        rtc.muteLocalAudioStream(true)

        let options = AgoraRtcChannelMediaOptions()
        options.channelProfile = .communication
        options.clientRoleType = .broadcaster
        options.publishMicrophoneTrack = false
        options.autoSubscribeAudio = true

        let token = (Bundle.main.object(forInfoDictionaryKey: "AgoraToken") as? String)?.nilIfBlank
        let result = rtc.joinChannel(byToken: token, channelId: nextChannel, uid: stableUid(riderId), mediaOptions: options)
        message = result == 0 ? "Voice connecting..." : "Voice join failed \(result)"
    }

    func setTalking(_ enabled: Bool) {
        guard joined else {
            message = "Start walkie first."
            return
        }
        talking = enabled
        engine?.muteLocalAudioStream(!enabled)
        let options = AgoraRtcChannelMediaOptions()
        options.channelProfile = .communication
        options.clientRoleType = .broadcaster
        options.publishMicrophoneTrack = enabled
        options.autoSubscribeAudio = true
        engine?.updateChannel(with: options)
        message = enabled ? "Talking to group" : "Listening to group"
    }

    func toggleTalk() {
        setTalking(!talking)
    }

    func leave() {
        talking = false
        joined = false
        remoteSpeakers.removeAll()
        engine?.muteLocalAudioStream(true)
        engine?.leaveChannel()
        groupCode = ""
        channelName = ""
        message = "Walkie talkie ready"
    }

    func rtcEngine(_ engine: AgoraRtcEngineKit, didJoinChannel channel: String, withUid uid: UInt, elapsed: Int) {
        joined = true
        talking = false
        message = "Voice connected"
    }

    func rtcEngine(_ engine: AgoraRtcEngineKit, didJoinedOfUid uid: UInt, elapsed: Int) {
        remoteSpeakers.insert(uid)
        speakerCount = remoteSpeakers.count
    }

    func rtcEngine(_ engine: AgoraRtcEngineKit, didOfflineOfUid uid: UInt, reason: AgoraUserOfflineReason) {
        remoteSpeakers.remove(uid)
        speakerCount = remoteSpeakers.count
    }

    func rtcEngine(_ engine: AgoraRtcEngineKit, didOccurError errorCode: AgoraErrorCode) {
        message = "Voice error \(errorCode.rawValue)"
    }

    private func channelFor(_ groupCode: String) -> String {
        let clean = groupCode.uppercased().map { char -> Character in
            char.isLetter || char.isNumber || char == "_" ? char : "_"
        }
        return String("corider_\(String(clean))".prefix(64))
    }

    private func stableUid(_ value: String) -> UInt {
        var hash = 1_125_899_907
        for scalar in value.unicodeScalars {
            hash = hash &* 31 &+ Int(scalar.value)
        }
        return UInt(hash & Int(Int32.max))
    }
}

private extension String {
    var nilIfBlank: String? {
        trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : self
    }
}
