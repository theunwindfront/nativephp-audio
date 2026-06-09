import Foundation
import AVFoundation
import MediaPlayer

@objc(AudioFunctions)
class AudioFunctions: NSObject {

    // MARK: - Player State
    private static var player: AVPlayer?
    private static var playerItem: AVPlayerItem?
    private static var currentURL = ""
    private static var isStream: Bool = false
    private static var streamMountpoint: String?
    
    // MARK: - Stored Metadata
    private static var metaTitle: String?
    private static var metaArtist: String?
    private static var metaAlbum: String?
    private static var metaDuration: Double?
    private static var metaArtworkSource: String?
    private static var metaClip: String?
    private static var metaMetadata: [String: Any]?
    
    // MARK: - Observers
    private static var completionObserver: Any?
    private static var failureObserver: Any?
    private static var interruptionObserver: Any?
    private static var routeChangeObserver: Any?
    private static var progressObserver: Any?
    private static weak var progressObserverPlayer: AVPlayer?
    private static var bufferingObservation: NSKeyValueObservation?
    private static var readyObservation: NSKeyValueObservation?
    private static var loadedObservation: NSKeyValueObservation?

    // MARK: - Flags
    private static var remoteCommandsRegistered = false
    private static var isDucked = false
    private static var isBuffering = false
    private static var isInBackground = false
    private static var pendingEvents: [[String: Any]] = []

    // MARK: - Playlist State
    private static var playlist: [[String: Any]] = []
    private static var playlistIndex: Int = -1
    private static var repeatMode: String = "none"
    private static var shuffleMode: Bool = false
    private static var shuffledOrder: [Int] = []

    // MARK: - Playback Settings
    private static var playbackRate: Float = 1.0
    private static var progressInterval: Double = 1.0
    private static var userVolume: Float = 1.0

    // MARK: - Sleep Timer
    private static var sleepTimer: DispatchWorkItem?

    // MARK: - Helpers
    private static let eventPrefix = "Theunwindfront\\Audio\\Events\\"

    private static func sendEvent(_ name: String, _ payload: [String: Any]) {
        let fullName = eventPrefix + name
        guard !isInBackground else {
            pendingEvents.append(["event": fullName, "payload": payload])
            return
        }
        NativePHPBridge.dispatchEvent(fullName, data: payload)
    }

    private static func trackPayload() -> [String: Any] {
        var t: [String: Any] = ["url": currentURL]
        if let title = metaTitle { t["title"] = title }
        if let artist = metaArtist { t["artist"] = artist }
        if let album = metaAlbum { t["album"] = album }
        if let d = metaDuration { t["duration"] = d }
        if let artwork = metaArtworkSource { t["artwork"] = artwork }
        if let clip = metaClip { t["clip"] = clip }
        if let metadata = metaMetadata { t["metadata"] = metadata }
        t["isStream"] = isStream
        if isStream { t["streamType"] = "radio" }
        if let mountpoint = streamMountpoint { t["mountpoint"] = mountpoint }
        return t
    }

    private static func statePayload() -> [String: Any] {
        var state: [String: Any] = [
            "track": trackPayload(),
            "position": positionSeconds(),
            "duration": durationSeconds(),
            "isPlaying": player?.rate ?? 0 > 0,
            "isBuffering": isBuffering,
            "playbackRate": playbackRate,
            "repeatMode": repeatMode,
            "shuffleMode": shuffleMode,
            "playlistIndex": playlistIndex,
            "playlistTotal": playlist.count
        ]
        state["isStream"] = isStream
        if isStream { state["streamType"] = "radio" }
        if let mountpoint = streamMountpoint { state["mountpoint"] = mountpoint }
        return state
    }

    private static func positionSeconds() -> Double {
        let p = player?.currentTime().seconds ?? 0.0
        return p.isNaN ? 0.0 : p
    }

    private static func durationSeconds() -> Double {
        guard !isStream else { return 0.0 }
        let d = playerItem?.duration.seconds ?? 0.0
        return (d.isNaN || d.isInfinite) ? 0.0 : d
    }

    private static func updateStreamState(from track: [String: Any]) {
        let streamType = (track["streamType"] as? String)?.lowercased()
        let explicitStream = track["isStream"] as? Bool
        streamMountpoint = track["mountpoint"] as? String
        isStream = explicitStream ?? (streamType == "radio" || streamMountpoint != nil)
        if !isStream { streamMountpoint = nil }
    }

    // MARK: - Initialization
    @objc static func setupAudioSession() {
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, options: [])
        try? AVAudioSession.sharedInstance().setActive(true)
        
        NotificationCenter.default.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: .main) { _ in isInBackground = true }
        NotificationCenter.default.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { _ in isInBackground = false }
        
        setupAudioSessionObservers()
    }

    private static func refreshNowPlayingInfo() {
        guard let title = metaTitle else { return }
        var info: [String: Any] = [MPMediaItemPropertyTitle: title]
        if let artist = metaArtist { info[MPMediaItemPropertyArtist] = artist }
        if let album = metaAlbum { info[MPMediaItemPropertyAlbumTitle] = album }
        if isStream {
            info[MPNowPlayingInfoPropertyIsLiveStream] = true
        } else if let duration = metaDuration {
            info[MPMediaItemPropertyPlaybackDuration] = duration
        }
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = positionSeconds()
        info[MPNowPlayingInfoPropertyPlaybackRate] = player?.rate ?? 0.0
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info

        if let src = metaArtworkSource { loadArtworkAsync(from: src) }
    }

    private static func loadArtworkAsync(from source: String) {
        let capturedURL = currentURL
        DispatchQueue.global(qos: .background).async {
            var image: UIImage?
            if source.hasPrefix("http"), let url = URL(string: source), let data = try? Data(contentsOf: url) {
                image = UIImage(data: data)
            } else {
                image = UIImage(contentsOfFile: source)
            }
            guard let loaded = image else { return }
            let artwork = MPMediaItemArtwork(boundsSize: loaded.size) { _ in loaded }
            DispatchQueue.main.async {
                guard currentURL == capturedURL else { return }
                var current = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
                current[MPMediaItemPropertyArtwork] = artwork
                MPNowPlayingInfoCenter.default().nowPlayingInfo = current
            }
        }
    }

    private static func syncNowPlayingState() {
        guard var info = MPNowPlayingInfoCenter.default().nowPlayingInfo else { return }
        info[MPNowPlayingInfoPropertyPlaybackRate] = player?.rate ?? 0.0
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = positionSeconds()
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    private static func resetPlayer() {
        isBuffering = false
        isStream = false
        streamMountpoint = nil
        cancelSleepTimer()
        stopProgressTimer()
        player?.pause()
        player = nil
        playerItem = nil
        completionObserver = nil
        failureObserver = nil
        bufferingObservation?.invalidate()
        readyObservation?.invalidate()
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }

    private static func effectiveIndex(_ idx: Int) -> Int {
        guard !shuffledOrder.isEmpty, idx < shuffledOrder.count else { return idx }
        return shuffledOrder[idx]
    }

    private static func metadataDouble(_ value: Any?) -> Double? {
        if let number = value as? NSNumber { return number.doubleValue }
        if let double = value as? Double { return double }
        if let string = value as? String { return Double(string) }
        return nil
    }

    private static func applyInitialMetadata(_ parameters: [String: Any], duration: Double? = nil) {
        metaTitle = parameters["title"] as? String
        metaArtist = parameters["artist"] as? String
        metaAlbum = parameters["album"] as? String
        metaDuration = duration ?? metadataDouble(parameters["duration"])
        metaArtworkSource = parameters["artwork"] as? String
        metaClip = parameters["clip"] as? String
        metaMetadata = parameters["metadata"] as? [String: Any]
    }

    private static func stringParameter(_ parameters: [String: Any], keys: [String]) -> String? {
        for key in keys {
            if let value = parameters[key] as? String {
                let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
                if !trimmed.isEmpty { return trimmed }
            }
        }
        return nil
    }

    private static func webURL(from value: String) -> URL? {
        guard let url = URL(string: value),
              let scheme = url.scheme?.lowercased(),
              ["http", "https"].contains(scheme),
              url.host != nil else { return nil }
        return url
    }

    private static func resolveStreamURL(from source: String, parameters: [String: Any]) -> URL? {
        if let url = webURL(from: source) { return url }
        if URL(string: source)?.scheme != nil { return nil }

        guard let server = stringParameter(parameters, keys: ["serverUrl", "serverURL", "baseUrl", "baseURL", "host"]),
              let baseURL = webURL(from: server) else { return nil }

        let mountpoint = source.hasPrefix("/") ? String(source.dropFirst()) : source
        let baseString = baseURL.absoluteString.hasSuffix("/") ? baseURL.absoluteString : baseURL.absoluteString + "/"
        return URL(string: mountpoint, relativeTo: URL(string: baseString))?.absoluteURL
    }

    private static func playTrackAt(index: Int, reason: String = "user") {
        guard index >= 0, index < playlist.count else { return }
        
        let lastIdx = playlistIndex
        let lastPos = positionSeconds()
        let lastTrack = (lastIdx >= 0 && lastIdx < playlist.count) ? playlist[effectiveIndex(lastIdx)] : nil

        playlistIndex = index
        let track = playlist[effectiveIndex(index)]
        
        isStream = false
        streamMountpoint = nil

        if let urlStr = track["url"] as? String {
            let url: URL
            if let webURL = URL(string: urlStr), webURL.scheme != nil {
                url = webURL
            } else {
                url = URL(fileURLWithPath: urlStr)
            }
            currentURL = urlStr
            preparePlayer(url: url)
            
            player?.play()
            if playbackRate != 1.0 { player?.rate = playbackRate }
        }
        
        updateStreamState(from: track)
        metaTitle = track["title"] as? String
        metaArtist = track["artist"] as? String
        metaAlbum = track["album"] as? String
        metaDuration = (track["duration"] as? NSNumber)?.doubleValue
        metaArtworkSource = track["artwork"] as? String
        metaClip = track["clip"] as? String
        metaMetadata = track["metadata"] as? [String: Any]

        var changedPayload: [String: Any] = ["index": index, "reason": reason, "track": trackPayload()]
        if lastIdx >= 0 {
            changedPayload["lastIndex"] = lastIdx
            changedPayload["lastPosition"] = lastPos
            if let lt = lastTrack { changedPayload["lastTrack"] = lt }
        }
        sendEvent("PlaylistTrackChanged", changedPayload)
        sendEvent("PlaybackStarted", ["track": trackPayload()])
        
        refreshNowPlayingInfo()
        setupRemoteCommands()
        startProgressTimer()
    }

    private static func preparePlayer(url: URL) {
        stopProgressTimer()
        bufferingObservation?.invalidate()
        readyObservation?.invalidate()
        
        playerItem = AVPlayerItem(url: url)
        player = AVPlayer(playerItem: playerItem)
        player?.volume = userVolume
        
        bufferingObservation = playerItem?.observe(\.isPlaybackBufferEmpty, options: [.new]) { item, _ in
            guard item.isPlaybackBufferEmpty else { return }
            isBuffering = true
            sendEvent("PlaybackBuffering", statePayload())
        }
        
        readyObservation = playerItem?.observe(\.isPlaybackLikelyToKeepUp, options: [.new]) { item, _ in
            guard item.isPlaybackLikelyToKeepUp else { return }
            isBuffering = false
            sendEvent("PlaybackReady", ["track": trackPayload()])
        }
        
        completionObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: playerItem, queue: .main) { _ in
            sendEvent("PlaybackCompleted", ["track": trackPayload()])
            if !isStream { nextTrackInternal() }
        }
    }

    private static func nextTrackInternal() {
        guard !playlist.isEmpty else { return }
        switch repeatMode {
        case "one": playTrackAt(index: playlistIndex, reason: "repeat")
        case "all": playTrackAt(index: (playlistIndex + 1) % playlist.count, reason: "auto")
        default:
            if playlistIndex < playlist.count - 1 { playTrackAt(index: playlistIndex + 1, reason: "auto") }
            else { sendEvent("PlaylistEnded", statePayload()) }
        }
    }

    private static func startProgressTimer() {
        stopProgressTimer()
        let interval = CMTime(seconds: progressInterval, preferredTimescale: 600)
        progressObserver = player?.addPeriodicTimeObserver(forInterval: interval, queue: .main) { _ in
            sendEvent("PlaybackProgressUpdated", statePayload())
            syncNowPlayingState()
        }
        progressObserverPlayer = player
    }

    private static func stopProgressTimer() {
        if let observer = progressObserver {
            progressObserverPlayer?.removeTimeObserver(observer)
            progressObserver = nil
            progressObserverPlayer = nil
        }
    }

    private static func cancelSleepTimer() {
        sleepTimer?.cancel()
        sleepTimer = nil
    }

    private static func setupRemoteCommands() {
        guard !remoteCommandsRegistered else { return }
        remoteCommandsRegistered = true
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.addTarget { _ in player?.play(); syncNowPlayingState(); sendEvent("PlaybackResumed", statePayload()); sendEvent("RemotePlayReceived", statePayload()); return .success }
        center.pauseCommand.addTarget { _ in player?.pause(); syncNowPlayingState(); sendEvent("PlaybackPaused", statePayload()); sendEvent("RemotePauseReceived", statePayload()); return .success }
        center.nextTrackCommand.addTarget { _ in nextTrackInternal(); sendEvent("RemoteNextTrackReceived", statePayload()); return .success }
        center.previousTrackCommand.addTarget { _ in if playlistIndex > 0 { playTrackAt(index: playlistIndex - 1) }; sendEvent("RemotePreviousTrackReceived", statePayload()); return .success }
        center.changePlaybackPositionCommand.addTarget { e in
            guard let pos = e as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            player?.seek(to: CMTime(seconds: pos.positionTime, preferredTimescale: 600))
            syncNowPlayingState()
            return .success
        }
    }

    private static func setupAudioSessionObservers() {
        interruptionObserver = NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { n in
            guard let typeValue = n.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt, let type = AVAudioSession.InterruptionType(rawValue: typeValue) else { return }
            if type == .began {
                player?.pause()
                sendEvent("PlaybackPaused", statePayload())
                sendEvent("AudioFocusLostTransient", statePayload())
            } else if type == .ended {
                let options = n.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
                if (AVAudioSession.InterruptionOptions(rawValue: options)).contains(.shouldResume) {
                    player?.play()
                    sendEvent("PlaybackResumed", statePayload())
                    sendEvent("AudioFocusGained", statePayload())
                }
            }
        }
    }

    // MARK: - Bridge Functions
    @objc static func Play(_ parameters: [String: Any]) -> [String: Any] {
        playlist = [parameters]
        playlistIndex = -1
        playTrackAt(index: 0)
        return ["success": true]
    }

    @objc static func PlayStream(_ parameters: [String: Any]) -> [String: Any] {
        guard let mountpoint = stringParameter(parameters, keys: ["mountpoint", "url"]),
              let url = resolveStreamURL(from: mountpoint, parameters: parameters) else {
            return ["success": false, "error": "Invalid stream mountpoint or URL"]
        }

        playlist = []
        playlistIndex = -1
        isStream = true
        streamMountpoint = mountpoint
        metaDuration = nil
        currentURL = url.absoluteString

        preparePlayer(url: url)
        applyInitialMetadata(parameters, duration: nil)
        metaDuration = nil

        player?.play()
        if playbackRate != 1.0 { player?.rate = playbackRate }

        refreshNowPlayingInfo()
        setupRemoteCommands()
        startProgressTimer()

        sendEvent("PlaybackStarted", [
            "track": trackPayload(),
            "isStream": true,
            "streamType": "radio",
            "mountpoint": mountpoint
        ])

        return ["success": true]
    }

    @objc static func Load(_ parameters: [String: Any]) -> [String: Any] {
        playlist = [parameters]
        playlistIndex = -1
        playTrackAt(index: 0)
        player?.pause() // Prepare only
        return ["success": true]
    }

    @objc static func Pause(_ parameters: [String: Any]) -> [String: Any] { player?.pause(); syncNowPlayingState(); sendEvent("PlaybackPaused", statePayload()); return ["success": true] }
    @objc static func Resume(_ parameters: [String: Any]) -> [String: Any] { player?.play(); syncNowPlayingState(); sendEvent("PlaybackResumed", statePayload()); return ["success": true] }
    @objc static func Stop(_ parameters: [String: Any]) -> [String: Any] { resetPlayer(); return ["success": true] }
    @objc static func GetState(_ parameters: [String: Any]) -> [String: Any] { return statePayload() }
    @objc static func GetDuration(_ parameters: [String: Any]) -> [String: Any] { return ["duration": durationSeconds()] }
    @objc static func GetCurrentPosition(_ parameters: [String: Any]) -> [String: Any] { return ["position": positionSeconds()] }

    @objc static func SetPlaylist(_ parameters: [String: Any]) -> [String: Any] {
        playlist = parameters["items"] as? [[String: Any]] ?? []
        playlistIndex = -1
        shuffledOrder = Array(0..<playlist.count)
        if shuffleMode { shuffledOrder.shuffle() }
        let startIdx = (parameters["startIndex"] as? NSNumber)?.intValue ?? 0
        if (parameters["autoPlay"] as? Bool) != false { playTrackAt(index: startIdx) }
        return ["success": true]
    }

    @objc static func NextTrack(_ parameters: [String: Any]) -> [String: Any] { nextTrackInternal(); return ["success": true] }
    @objc static func PreviousTrack(_ parameters: [String: Any]) -> [String: Any] { if playlistIndex > 0 { playTrackAt(index: playlistIndex - 1) }; return ["success": true] }
    @objc static func SkipTrack(_ parameters: [String: Any]) -> [String: Any] { if let idx = parameters["index"] as? Int { playTrackAt(index: idx) }; return ["success": true] }
    
    @objc static func GetTrack(_ parameters: [String: Any]) -> [String: Any] {
        guard let idx = parameters["index"] as? Int, idx < playlist.count else { return [:] }
        return ["track": playlist[effectiveIndex(idx)]]
    }

    @objc static func GetActiveTrack(_ parameters: [String: Any]) -> [String: Any] {
        return playlistIndex >= 0 ? ["track": trackPayload()] : [:]
    }

    @objc static func GetActiveTrackIndex(_ parameters: [String: Any]) -> [String: Any] {
        return playlistIndex >= 0 ? ["index": playlistIndex] : [:]
    }

    @objc static func GetPlaylist(_ parameters: [String: Any]) -> [String: Any] {
        return ["items": playlist, "index": playlistIndex, "total": playlist.count, "repeatMode": repeatMode, "shuffleMode": shuffleMode]
    }

    @objc static func SetRepeatMode(_ parameters: [String: Any]) -> [String: Any] { repeatMode = parameters["mode"] as? String ?? "none"; return ["success": true] }

    @objc static func Seek(_ parameters: [String: Any]) -> [String: Any] {
        if let seconds = (parameters["seconds"] as? NSNumber)?.doubleValue {
            player?.seek(to: CMTime(seconds: seconds, preferredTimescale: 600))
            syncNowPlayingState()
        }
        return ["success": true]
    }

    @objc static func SetVolume(_ parameters: [String: Any]) -> [String: Any] {
        if let level = (parameters["level"] as? NSNumber)?.floatValue {
            userVolume = level
            player?.volume = level
        }
        return ["success": true]
    }

    private static func applyMetadata(_ parameters: [String: Any], includeClassicalFields: Bool = true) {
        if let title = parameters["title"] as? String { metaTitle = title }
        if let artist = parameters["artist"] as? String { metaArtist = artist }
        if let album = parameters["album"] as? String { metaAlbum = album }
        if includeClassicalFields, let d = (parameters["duration"] as? NSNumber)?.doubleValue { metaDuration = d }
        if let artwork = parameters["artwork"] as? String { metaArtworkSource = artwork }
        if includeClassicalFields, let clip = parameters["clip"] as? String { metaClip = clip }
        if let metadata = parameters["metadata"] as? [String: Any] { metaMetadata = metadata }
        if parameters.keys.contains("isStream") || parameters.keys.contains("streamType") || parameters.keys.contains("mountpoint") {
            updateStreamState(from: parameters)
        }
        refreshNowPlayingInfo()
    }

    @objc static func SetMetadata(_ parameters: [String: Any]) -> [String: Any] {
        applyMetadata(parameters)
        return ["success": true]
    }

    @objc static func UpdateStreamMetadata(_ parameters: [String: Any]) -> [String: Any] {
        applyMetadata(parameters, includeClassicalFields: false)
        sendEvent("StreamMetadataChanged", ["track": trackPayload(), "metadata": parameters])
        return ["success": true]
    }

    @objc static func SetPlaybackRate(_ parameters: [String: Any]) -> [String: Any] {
        if let rate = (parameters["rate"] as? NSNumber)?.floatValue {
            playbackRate = rate
            if player?.rate ?? 0 > 0 {
                player?.rate = rate
            }
        }
        return ["success": true]
    }

    @objc static func AppendTrack(_ parameters: [String: Any]) -> [String: Any] {
        if let track = parameters["track"] as? [String: Any] {
            playlist.append(track)
            shuffledOrder = Array(0..<playlist.count)
            if shuffleMode { shuffledOrder.shuffle() }
        }
        return ["success": true]
    }

    @objc static func RemoveTrack(_ parameters: [String: Any]) -> [String: Any] {
        if let idx = parameters["index"] as? Int, idx < playlist.count {
            playlist.remove(at: idx)
            if playlistIndex == idx { resetPlayer() }
            else if playlistIndex > idx { playlistIndex -= 1 }
            shuffledOrder = Array(0..<playlist.count)
            if shuffleMode { shuffledOrder.shuffle() }
        }
        return ["success": true]
    }

    @objc static func PlayTrackAt(_ parameters: [String: Any]) -> [String: Any] {
        if let idx = parameters["index"] as? Int {
            playTrackAt(index: idx)
        }
        return ["success": true]
    }
    @objc static func SetShuffleMode(_ parameters: [String: Any]) -> [String: Any] {
        shuffleMode = parameters["shuffle"] as? Bool ?? false
        shuffledOrder = Array(0..<playlist.count)
        if shuffleMode { shuffledOrder.shuffle() }
        return ["success": true]
    }

    @objc static func SetSleepTimer(_ parameters: [String: Any]) -> [String: Any] {
        let seconds = (parameters["seconds"] as? NSNumber)?.doubleValue ?? 0
        cancelSleepTimer()
        if seconds > 0 {
            let item = DispatchWorkItem { resetPlayer(); sendEvent("SleepTimerExpired", [:]) }
            sleepTimer = item
            DispatchQueue.main.asyncAfter(deadline: .now() + seconds, execute: item)
        }
        return ["success": true]
    }

    @objc static func CancelSleepTimer(_ parameters: [String: Any]) -> [String: Any] { cancelSleepTimer(); return ["success": true] }
    @objc static func DrainEvents(_ parameters: [String: Any]) -> [String: Any] { let events = pendingEvents; pendingEvents = []; return ["events": events] }
    @objc static func SetProgressInterval(_ parameters: [String: Any]) -> [String: Any] {
        progressInterval = (parameters["seconds"] as? NSNumber)?.doubleValue ?? 1.0
        if player?.rate ?? 0 > 0 { startProgressTimer() }
        return ["success": true]
    }
}
