import Foundation
import AVFoundation
import MediaPlayer
import UIKit

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
    private static var cachedArtwork: (source: String, artwork: MPMediaItemArtwork)?

    // MARK: - Observers
    private static var completionObserver: NSObjectProtocol?
    private static var failureObserver: NSObjectProtocol?
    private static var progressObserver: Any?
    private static weak var progressObserverPlayer: AVPlayer?
    private static var bufferingObservation: NSKeyValueObservation?
    private static var readyObservation: NSKeyValueObservation?
    private static var statusObservation: NSKeyValueObservation?

    // MARK: - Flags
    private static var remoteCommandsRegistered = false
    private static var isBuffering = false
    private static var isInBackground = false
    private static var pausedByInterruption = false
    private static var pendingEvents: [[String: Any]] = []
    private static let maxPendingEvents = 200
    private static var lastPlaybackState: (playing: Bool, buffering: Bool)?

    // MARK: - Playlist State
    private static var playlist: [[String: Any]] = []
    /// Position in the play order (equals the playlist index unless shuffle is on).
    private static var playlistIndex: Int = -1
    private static var repeatMode: String = "none"
    private static var shuffleMode: Bool = false
    /// Play order: shuffledOrder[position] = index into `playlist`.
    private static var shuffledOrder: [Int] = []

    // MARK: - Playback Settings
    private static var playbackRate: Float = 1.0
    private static var progressInterval: Double = 1.0
    private static var userVolume: Float = 1.0

    // MARK: - Sleep Timer
    private static var sleepTimer: DispatchWorkItem?

    // MARK: - Events
    private static let eventPrefix = "Theunwindfront\\Audio\\Events\\"

    private static func sendEvent(_ name: String, _ payload: [String: Any]) {
        let fullName = eventPrefix + name
        guard !isInBackground else {
            // Progress updates are only useful live, so they aren't queued.
            guard name != "PlaybackProgressUpdated" else { return }
            if pendingEvents.count >= maxPendingEvents { pendingEvents.removeFirst() }
            pendingEvents.append(["event": fullName, "payload": payload])
            return
        }
        emit(fullName, payload)
    }

    private static func emit(_ fullName: String, _ payload: [String: Any]) {
        let bridgePayload: [String: Any?] = payload.mapValues { Optional($0) }
        LaravelBridge.shared.send?(fullName, bridgePayload)
    }

    /// Delivers events queued while the app was in the background.
    private static func flushPendingEvents() {
        guard !pendingEvents.isEmpty else { return }
        let events = pendingEvents
        pendingEvents = []
        for event in events {
            guard let name = event["event"] as? String else { continue }
            emit(name, event["payload"] as? [String: Any] ?? [:])
        }
    }

    /// Sends PlaybackStateChanged when playing/buffering changes (or metadata changed).
    private static func notifyStateChanged(metadataChanged: Bool = false) {
        let playing = isPlayingNow()
        if !metadataChanged, let last = lastPlaybackState, last.playing == playing, last.buffering == isBuffering {
            return
        }
        lastPlaybackState = (playing, isBuffering)
        var payload = statePayload()
        payload["metadataChanged"] = metadataChanged
        sendEvent("PlaybackStateChanged", payload)
    }

    // MARK: - Payloads
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
            "isPlaying": isPlayingNow(),
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

    private static func isPlayingNow() -> Bool {
        return (player?.rate ?? 0) > 0
    }

    private static func positionSeconds() -> Double {
        let p = player?.currentTime().seconds ?? 0.0
        return (p.isNaN || p.isInfinite) ? 0.0 : p
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
    private static var sessionConfigured = false

    /// Registered as the plugin's `ios.init_function`, so NativePHP calls it once at app startup.
    /// Sets the `.playback` category (background audio, ignores the silent switch) and registers
    /// lifecycle, interruption and route-change observers. The session is only activated when
    /// playback starts, so launching the app doesn't interrupt audio from other apps.
    @objc static func setupAudioSession() {
        guard !sessionConfigured else { return }
        sessionConfigured = true

        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, options: [])

        let center = NotificationCenter.default
        center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { _ in
            isInBackground = true
        }
        center.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { _ in
            isInBackground = false
            flushPendingEvents()
        }

        center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { n in
            guard let typeValue = n.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  let type = AVAudioSession.InterruptionType(rawValue: typeValue) else { return }
            if type == .began {
                guard isPlayingNow() else { return }
                pausedByInterruption = true
                pauseInternal()
                sendEvent("AudioFocusLostTransient", statePayload())
            } else if type == .ended {
                let options = n.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
                let shouldResume = AVAudioSession.InterruptionOptions(rawValue: options).contains(.shouldResume)
                if pausedByInterruption && shouldResume {
                    resumeInternal()
                    sendEvent("AudioFocusGained", statePayload())
                }
                pausedByInterruption = false
            }
        }

        // Pause when headphones / a Bluetooth device are disconnected, like the system players do.
        center.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { n in
            guard let raw = n.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
                  AVAudioSession.RouteChangeReason(rawValue: raw) == .oldDeviceUnavailable,
                  isPlayingNow() else { return }
            pauseInternal()
        }
    }

    private static func activateAudioSession() {
        setupAudioSession()
        try? AVAudioSession.sharedInstance().setActive(true)
    }

    private static func deactivateAudioSession() {
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    // MARK: - Now Playing
    private static func refreshNowPlayingInfo() {
        let fallbackTitle = URL(string: currentURL)?.lastPathComponent ?? ""
        var info: [String: Any] = [MPMediaItemPropertyTitle: metaTitle ?? fallbackTitle]
        if let artist = metaArtist { info[MPMediaItemPropertyArtist] = artist }
        if let album = metaAlbum { info[MPMediaItemPropertyAlbumTitle] = album }
        if isStream {
            info[MPNowPlayingInfoPropertyIsLiveStream] = true
        } else if let duration = metaDuration ?? (durationSeconds() > 0 ? durationSeconds() : nil) {
            info[MPMediaItemPropertyPlaybackDuration] = duration
        }
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = positionSeconds()
        info[MPNowPlayingInfoPropertyPlaybackRate] = player?.rate ?? 0.0
        if let src = metaArtworkSource, let cached = cachedArtwork, cached.source == src {
            info[MPMediaItemPropertyArtwork] = cached.artwork
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        updateRemoteCommandAvailability()

        if let src = metaArtworkSource, cachedArtwork?.source != src { loadArtworkAsync(from: src) }
    }

    private static func loadArtworkAsync(from source: String) {
        let capturedURL = currentURL
        let finish: (UIImage?) -> Void = { image in
            guard let image = image else { return }
            let artwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
            DispatchQueue.main.async {
                cachedArtwork = (source, artwork)
                guard currentURL == capturedURL, metaArtworkSource == source else { return }
                var current = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
                current[MPMediaItemPropertyArtwork] = artwork
                MPNowPlayingInfoCenter.default().nowPlayingInfo = current
            }
        }

        if let url = webURL(from: source) {
            let request = URLRequest(url: url, cachePolicy: .returnCacheDataElseLoad, timeoutInterval: 15)
            URLSession.shared.dataTask(with: request) { data, _, _ in
                finish(data.flatMap { UIImage(data: $0) })
            }.resume()
        } else {
            DispatchQueue.global(qos: .utility).async {
                finish(UIImage(contentsOfFile: source))
            }
        }
    }

    private static func syncNowPlayingState() {
        guard var info = MPNowPlayingInfoCenter.default().nowPlayingInfo else { return }
        info[MPNowPlayingInfoPropertyPlaybackRate] = player?.rate ?? 0.0
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = positionSeconds()
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    // MARK: - Player Lifecycle
    private static func removeItemObservers() {
        if let observer = completionObserver { NotificationCenter.default.removeObserver(observer) }
        if let observer = failureObserver { NotificationCenter.default.removeObserver(observer) }
        completionObserver = nil
        failureObserver = nil
        bufferingObservation?.invalidate()
        readyObservation?.invalidate()
        statusObservation?.invalidate()
        bufferingObservation = nil
        readyObservation = nil
        statusObservation = nil
    }

    private static func resetPlayer() {
        isBuffering = false
        isStream = false
        streamMountpoint = nil
        pausedByInterruption = false
        cancelSleepTimer()
        stopProgressTimer()
        removeItemObservers()
        player?.pause()
        player = nil
        playerItem = nil
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }

    private static func preparePlayer(url: URL) {
        activateAudioSession()
        stopProgressTimer()
        removeItemObservers()
        player?.pause()

        let item = AVPlayerItem(url: url)
        playerItem = item
        player = AVPlayer(playerItem: item)
        player?.volume = userVolume
        isBuffering = false

        // KVO callbacks can arrive on any thread; all state is touched on the main thread.
        bufferingObservation = item.observe(\.isPlaybackBufferEmpty, options: [.new]) { observed, _ in
            DispatchQueue.main.async {
                guard observed === playerItem, observed.isPlaybackBufferEmpty, !isBuffering else { return }
                isBuffering = true
                sendEvent("PlaybackBuffering", statePayload())
                notifyStateChanged()
            }
        }

        readyObservation = item.observe(\.isPlaybackLikelyToKeepUp, options: [.new]) { observed, _ in
            DispatchQueue.main.async {
                guard observed === playerItem, observed.isPlaybackLikelyToKeepUp else { return }
                let wasBuffering = isBuffering
                isBuffering = false
                sendEvent("PlaybackReady", statePayload())
                if wasBuffering { notifyStateChanged() }
            }
        }

        statusObservation = item.observe(\.status, options: [.new]) { observed, _ in
            DispatchQueue.main.async {
                guard observed === playerItem, observed.status == .failed else { return }
                reportFailure(observed.error)
            }
        }

        completionObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { _ in
            sendEvent("PlaybackCompleted", statePayload())
            if isStream {
                notifyStateChanged()
            } else {
                nextTrackInternal(reason: "auto")
            }
        }

        failureObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemFailedToPlayToEndTime, object: item, queue: .main) { n in
            reportFailure(n.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error)
        }
    }

    private static func reportFailure(_ error: Error?) {
        isBuffering = false
        sendEvent("PlaybackFailed", [
            "track": trackPayload(),
            "error": error?.localizedDescription ?? "Playback failed"
        ])
        notifyStateChanged()
    }

    private static func startPlayback() {
        player?.play()
        if playbackRate != 1.0 { player?.rate = playbackRate }
    }

    // MARK: - Transport
    private static func pauseInternal(remote: Bool = false) {
        guard let player = player else { return }
        player.pause()
        syncNowPlayingState()
        sendEvent("PlaybackPaused", statePayload())
        if remote { sendEvent("RemotePauseReceived", statePayload()) }
        notifyStateChanged()
    }

    private static func resumeInternal(remote: Bool = false) {
        guard player != nil else { return }
        activateAudioSession()
        startPlayback()
        syncNowPlayingState()
        sendEvent("PlaybackResumed", statePayload())
        if remote { sendEvent("RemotePlayReceived", statePayload()) }
        notifyStateChanged()
    }

    private static func stopInternal(remote: Bool = false) {
        let payload = statePayload()
        resetPlayer()
        deactivateAudioSession()
        sendEvent("PlaybackStopped", payload)
        if remote { sendEvent("RemoteStopReceived", payload) }
        notifyStateChanged()
    }

    private static func seekInternal(to seconds: Double, remote: Bool = false) {
        guard let player = player else { return }
        let from = positionSeconds()
        let target = max(0, seconds)
        player.seek(to: CMTime(seconds: target, preferredTimescale: 600)) { _ in
            DispatchQueue.main.async { syncNowPlayingState() }
        }
        let payload: [String: Any] = ["from": from, "to": target]
        sendEvent("PlaybackSeeked", payload)
        if remote { sendEvent("RemoteSeekReceived", payload) }
    }

    // MARK: - Playlist
    private static func effectiveIndex(_ position: Int) -> Int {
        guard shuffledOrder.count == playlist.count, position >= 0, position < shuffledOrder.count else { return position }
        return shuffledOrder[position]
    }

    /// The playlist index of the current track, or -1.
    private static func currentTrackIndex() -> Int {
        guard playlistIndex >= 0, playlistIndex < playlist.count else { return -1 }
        return effectiveIndex(playlistIndex)
    }

    /// Rebuilds the play order. When shuffling, `anchor` (default: the current track) is moved to
    /// the front so the playing track doesn't change; `playlistIndex` is updated to match.
    private static func rebuildOrder(anchor: Int? = nil) {
        let anchorTrack = anchor ?? currentTrackIndex()
        var order = Array(0..<playlist.count)
        if shuffleMode {
            order.shuffle()
            if let pos = order.firstIndex(of: anchorTrack) {
                order.remove(at: pos)
                order.insert(anchorTrack, at: 0)
            }
        }
        shuffledOrder = order
        if playlistIndex >= 0, let pos = order.firstIndex(of: anchorTrack) {
            playlistIndex = pos
        }
    }

    private static func mediaURL(from value: String) -> URL? {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        if let url = URL(string: trimmed), url.scheme != nil { return url }
        return URL(fileURLWithPath: trimmed)
    }

    private static func playTrackAt(index: Int, reason: String = "user", autoStart: Bool = true) {
        guard index >= 0, index < playlist.count else { return }

        let lastIdx = playlistIndex
        let lastPos = positionSeconds()
        let lastTrack = (lastIdx >= 0 && lastIdx < playlist.count) ? playlist[effectiveIndex(lastIdx)] : nil

        playlistIndex = index
        let track = playlist[effectiveIndex(index)]

        updateStreamState(from: track)
        metaTitle = track["title"] as? String
        metaArtist = track["artist"] as? String
        metaAlbum = track["album"] as? String
        metaDuration = metadataDouble(track["duration"])
        metaArtworkSource = track["artwork"] as? String
        metaClip = track["clip"] as? String
        metaMetadata = track["metadata"] as? [String: Any]

        guard let urlStr = track["url"] as? String, let url = mediaURL(from: urlStr) else {
            currentURL = ""
            sendEvent("PlaybackFailed", ["track": track, "error": "Missing or invalid track url"])
            return
        }

        currentURL = urlStr
        preparePlayer(url: url)
        if autoStart { startPlayback() }

        var changedPayload: [String: Any] = ["index": index, "reason": reason, "track": trackPayload()]
        if lastIdx >= 0 {
            changedPayload["lastIndex"] = lastIdx
            changedPayload["lastPosition"] = lastPos
            if let lt = lastTrack { changedPayload["lastTrack"] = lt }
        }
        sendEvent("PlaylistTrackChanged", changedPayload)
        sendEvent(autoStart ? "PlaybackStarted" : "PlaybackLoaded", statePayload())

        refreshNowPlayingInfo()
        setupRemoteCommands()
        startProgressTimer()
        notifyStateChanged()
    }

    private static func nextTrackInternal(reason: String = "user") {
        guard !playlist.isEmpty else { return }
        if reason == "auto" && repeatMode == "one" {
            playTrackAt(index: max(playlistIndex, 0), reason: "repeat")
        } else if playlistIndex < playlist.count - 1 {
            playTrackAt(index: playlistIndex + 1, reason: reason)
        } else if repeatMode == "all" {
            playTrackAt(index: 0, reason: reason)
        } else if reason == "auto" {
            sendEvent("PlaylistEnded", statePayload())
            notifyStateChanged()
        }
    }

    private static func previousTrackInternal() {
        if playlistIndex > 0 {
            playTrackAt(index: playlistIndex - 1)
        } else if repeatMode == "all" && !playlist.isEmpty {
            playTrackAt(index: playlist.count - 1)
        }
    }

    // MARK: - Timers
    private static func startProgressTimer() {
        stopProgressTimer()
        let interval = CMTime(seconds: progressInterval, preferredTimescale: 600)
        progressObserver = player?.addPeriodicTimeObserver(forInterval: interval, queue: .main) { _ in
            guard isPlayingNow() else { return }
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

    // MARK: - Remote Commands
    private static func setupRemoteCommands() {
        guard !remoteCommandsRegistered else { return }
        remoteCommandsRegistered = true
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.addTarget { _ in resumeInternal(remote: true); return .success }
        center.pauseCommand.addTarget { _ in pauseInternal(remote: true); return .success }
        center.togglePlayPauseCommand.addTarget { _ in
            if isPlayingNow() { pauseInternal(remote: true) } else { resumeInternal(remote: true) }
            return .success
        }
        center.stopCommand.addTarget { _ in stopInternal(remote: true); return .success }
        center.nextTrackCommand.addTarget { _ in
            nextTrackInternal()
            sendEvent("RemoteNextTrackReceived", statePayload())
            return .success
        }
        center.previousTrackCommand.addTarget { _ in
            previousTrackInternal()
            sendEvent("RemotePreviousTrackReceived", statePayload())
            return .success
        }
        center.changePlaybackPositionCommand.addTarget { e in
            guard let pos = e as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            seekInternal(to: pos.positionTime, remote: true)
            return .success
        }
        updateRemoteCommandAvailability()
    }

    private static func updateRemoteCommandAvailability() {
        guard remoteCommandsRegistered else { return }
        let center = MPRemoteCommandCenter.shared()
        let hasQueue = !isStream && playlist.count > 1
        center.nextTrackCommand.isEnabled = hasQueue
        center.previousTrackCommand.isEnabled = hasQueue
        center.changePlaybackPositionCommand.isEnabled = !isStream
    }

    // MARK: - Parameter Helpers
    private static func metadataDouble(_ value: Any?) -> Double? {
        if let number = value as? NSNumber { return number.doubleValue }
        if let string = value as? String { return Double(string) }
        return nil
    }

    private static func intParameter(_ parameters: [String: Any], _ key: String) -> Int? {
        if let number = parameters[key] as? NSNumber { return number.intValue }
        if let string = parameters[key] as? String { return Int(string) }
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

    private static func applyMetadata(_ parameters: [String: Any], includeClassicalFields: Bool = true) {
        if let title = parameters["title"] as? String { metaTitle = title }
        if let artist = parameters["artist"] as? String { metaArtist = artist }
        if let album = parameters["album"] as? String { metaAlbum = album }
        if includeClassicalFields, let d = metadataDouble(parameters["duration"]) { metaDuration = d }
        if let artwork = parameters["artwork"] as? String { metaArtworkSource = artwork }
        if includeClassicalFields, let clip = parameters["clip"] as? String { metaClip = clip }
        if let metadata = parameters["metadata"] as? [String: Any] { metaMetadata = metadata }
        if parameters.keys.contains("isStream") || parameters.keys.contains("streamType") || parameters.keys.contains("mountpoint") {
            updateStreamState(from: parameters)
        }
        refreshNowPlayingInfo()
    }

    // MARK: - Bridge Functions
    static func handlePlay(_ parameters: [String: Any]) -> [String: Any] {
        playlist = [parameters]
        shuffledOrder = [0]
        playlistIndex = -1
        playTrackAt(index: 0)
        return ["success": true]
    }

    static func handlePlayStream(_ parameters: [String: Any]) -> [String: Any] {
        guard let mountpoint = stringParameter(parameters, keys: ["mountpoint", "url"]),
              let url = resolveStreamURL(from: mountpoint, parameters: parameters) else {
            return ["success": false, "error": "Invalid stream mountpoint or URL"]
        }

        playlist = []
        shuffledOrder = []
        playlistIndex = -1
        applyInitialMetadata(parameters, duration: nil)
        metaDuration = nil
        currentURL = url.absoluteString

        preparePlayer(url: url)
        isStream = true
        streamMountpoint = mountpoint
        startPlayback()

        refreshNowPlayingInfo()
        setupRemoteCommands()
        startProgressTimer()

        sendEvent("PlaybackStarted", statePayload())
        notifyStateChanged()

        return ["success": true]
    }

    static func handleLoad(_ parameters: [String: Any]) -> [String: Any] {
        playlist = [parameters]
        shuffledOrder = [0]
        playlistIndex = -1
        playTrackAt(index: 0, autoStart: false)
        return ["success": true]
    }

    static func handlePause(_ parameters: [String: Any]) -> [String: Any] { pauseInternal(); return ["success": true] }
    static func handleResume(_ parameters: [String: Any]) -> [String: Any] { resumeInternal(); return ["success": true] }
    static func handleStop(_ parameters: [String: Any]) -> [String: Any] { stopInternal(); return ["success": true] }
    static func handleGetState(_ parameters: [String: Any]) -> [String: Any] { return statePayload() }
    static func handleGetDuration(_ parameters: [String: Any]) -> [String: Any] { return ["duration": durationSeconds()] }
    static func handleGetCurrentPosition(_ parameters: [String: Any]) -> [String: Any] { return ["position": positionSeconds()] }

    static func handleSetPlaylist(_ parameters: [String: Any]) -> [String: Any] {
        playlist = parameters["items"] as? [[String: Any]] ?? []
        playlistIndex = -1
        let startIdx = intParameter(parameters, "startIndex") ?? 0
        rebuildOrder(anchor: startIdx)
        sendEvent("PlaylistSet", ["total": playlist.count])
        if (parameters["autoPlay"] as? Bool) != false {
            // With shuffle on, the start track was moved to the front of the play order.
            playTrackAt(index: shuffleMode ? 0 : startIdx)
        }
        return ["success": true]
    }

    static func handleNextTrack(_ parameters: [String: Any]) -> [String: Any] { nextTrackInternal(); return ["success": true] }
    static func handlePreviousTrack(_ parameters: [String: Any]) -> [String: Any] { previousTrackInternal(); return ["success": true] }

    static func handleSkipTrack(_ parameters: [String: Any]) -> [String: Any] {
        guard let idx = intParameter(parameters, "index") else { return ["success": false] }
        playTrackAt(index: idx)
        return ["success": true]
    }

    static func handleGetTrack(_ parameters: [String: Any]) -> [String: Any] {
        guard let idx = intParameter(parameters, "index"), idx >= 0, idx < playlist.count else { return [:] }
        return ["track": playlist[effectiveIndex(idx)]]
    }

    static func handleGetActiveTrack(_ parameters: [String: Any]) -> [String: Any] {
        return (playlistIndex >= 0 || isStream) ? ["track": trackPayload()] : [:]
    }

    static func handleGetActiveTrackIndex(_ parameters: [String: Any]) -> [String: Any] {
        return playlistIndex >= 0 ? ["index": playlistIndex] : [:]
    }

    static func handleGetPlaylist(_ parameters: [String: Any]) -> [String: Any] {
        return ["items": playlist, "index": playlistIndex, "total": playlist.count, "repeatMode": repeatMode, "shuffleMode": shuffleMode]
    }

    static func handleSetRepeatMode(_ parameters: [String: Any]) -> [String: Any] {
        let mode = parameters["mode"] as? String ?? "none"
        repeatMode = ["none", "one", "all"].contains(mode) ? mode : "none"
        sendEvent("PlaylistRepeatModeChanged", ["mode": repeatMode])
        return ["success": true]
    }

    static func handleSeek(_ parameters: [String: Any]) -> [String: Any] {
        guard let seconds = metadataDouble(parameters["seconds"]) else { return ["success": false] }
        seekInternal(to: seconds)
        return ["success": true]
    }

    static func handleSetVolume(_ parameters: [String: Any]) -> [String: Any] {
        if let level = metadataDouble(parameters["level"]) {
            userVolume = Float(min(max(level, 0), 1))
            player?.volume = userVolume
        }
        return ["success": true]
    }

    static func handleSetMetadata(_ parameters: [String: Any]) -> [String: Any] {
        applyMetadata(parameters)
        notifyStateChanged(metadataChanged: true)
        return ["success": true]
    }

    static func handleUpdateStreamMetadata(_ parameters: [String: Any]) -> [String: Any] {
        applyMetadata(parameters, includeClassicalFields: false)
        sendEvent("StreamMetadataChanged", ["track": trackPayload(), "metadata": parameters])
        return ["success": true]
    }

    static func handleSetPlaybackRate(_ parameters: [String: Any]) -> [String: Any] {
        if let rate = metadataDouble(parameters["rate"]) {
            playbackRate = Float(min(max(rate, 0.25), 4.0))
            if isPlayingNow() {
                player?.rate = playbackRate
                syncNowPlayingState()
            }
        }
        return ["success": true]
    }

    static func handleAppendTrack(_ parameters: [String: Any]) -> [String: Any] {
        guard let track = parameters["track"] as? [String: Any] else { return ["success": false] }
        playlist.append(track)
        let newIndex = playlist.count - 1
        if shuffledOrder.count != newIndex {
            rebuildOrder()
        } else if shuffleMode && playlistIndex >= 0 {
            // Insert somewhere after the current track so it plays later in this shuffle.
            shuffledOrder.insert(newIndex, at: Int.random(in: (playlistIndex + 1)...shuffledOrder.count))
        } else {
            shuffledOrder.append(newIndex)
        }
        updateRemoteCommandAvailability()
        return ["success": true]
    }

    static func handleRemoveTrack(_ parameters: [String: Any]) -> [String: Any] {
        guard let idx = intParameter(parameters, "index"), idx >= 0, idx < playlist.count else { return ["success": false] }
        let current = currentTrackIndex()
        playlist.remove(at: idx)
        shuffledOrder = shuffledOrder.filter { $0 != idx }.map { $0 > idx ? $0 - 1 : $0 }
        if current == idx {
            stopInternal()
            playlistIndex = -1
        } else if current >= 0 {
            let shifted = current > idx ? current - 1 : current
            playlistIndex = shuffledOrder.firstIndex(of: shifted) ?? -1
        }
        updateRemoteCommandAvailability()
        return ["success": true]
    }

    static func handlePlayTrackAt(_ parameters: [String: Any]) -> [String: Any] {
        guard let idx = intParameter(parameters, "index") else { return ["success": false] }
        playTrackAt(index: idx)
        return ["success": true]
    }

    static func handleSetShuffleMode(_ parameters: [String: Any]) -> [String: Any] {
        shuffleMode = parameters["shuffle"] as? Bool ?? false
        rebuildOrder()
        sendEvent("PlaylistShuffleChanged", ["shuffle": shuffleMode])
        return ["success": true]
    }

    static func handleSetSleepTimer(_ parameters: [String: Any]) -> [String: Any] {
        let seconds = metadataDouble(parameters["seconds"]) ?? 0
        cancelSleepTimer()
        if seconds > 0 {
            let item = DispatchWorkItem {
                sleepTimer = nil
                stopInternal()
                sendEvent("SleepTimerExpired", [:])
            }
            sleepTimer = item
            DispatchQueue.main.asyncAfter(deadline: .now() + seconds, execute: item)
        }
        return ["success": true]
    }

    static func handleCancelSleepTimer(_ parameters: [String: Any]) -> [String: Any] { cancelSleepTimer(); return ["success": true] }

    static func handleDrainEvents(_ parameters: [String: Any]) -> [String: Any] {
        let events = pendingEvents
        pendingEvents = []
        return ["events": events]
    }

    static func handleSetProgressInterval(_ parameters: [String: Any]) -> [String: Any] {
        progressInterval = max(metadataDouble(parameters["seconds"]) ?? 1.0, 0.1)
        if player != nil { startProgressTimer() }
        return ["success": true]
    }
}

// MARK: - BridgeFunction Wrappers
// NativePHP registers iOS bridge functions as `AudioFunctions.<Name>()`, so each
// entry in nativephp.json needs a type conforming to `BridgeFunction`. Each
// wrapper forwards to the matching static handler on the main thread, since
// AVPlayer, MPNowPlayingInfoCenter and remote commands expect main-thread access.
extension AudioFunctions {
    fileprivate static func onMain(_ work: () -> [String: Any]) -> [String: Any] {
        if Thread.isMainThread { return work() }
        return DispatchQueue.main.sync(execute: work)
    }

    class Play: BridgeFunction {
        func execute(parameters: [String: Any]) -> [String: Any] {
            return AudioFunctions.onMain { AudioFunctions.handlePlay(parameters) }
        }
    }

    class PlayStream: BridgeFunction {
        func execute(parameters: [String: Any]) -> [String: Any] {
            return AudioFunctions.onMain { AudioFunctions.handlePlayStream(parameters) }
        }
    }

    class PlayTrackAt: BridgeFunction {
        func execute(parameters: [String: Any]) -> [String: Any] {
            return AudioFunctions.onMain { AudioFunctions.handlePlayTrackAt(parameters) }
        }
    }

    class Pause: BridgeFunction {
        func execute(parameters: [String: Any]) -> [String: Any] {
            return AudioFunctions.onMain { AudioFunctions.handlePause(parameters) }
        }
    }

    class Resume: BridgeFunction {
        func execute(parameters: [String: Any]) -> [String: Any] {
            return AudioFunctions.onMain { AudioFunctions.handleResume(parameters) }
        }
    }

    class Stop: BridgeFunction {
        func execute(parameters: [String: Any]) -> [String: Any] {
            return AudioFunctions.onMain { AudioFunctions.handleStop(parameters) }
        }
    }

    class Seek: BridgeFunction {
        func execute(parameters: [String: Any]) -> [String: Any] {
            return AudioFunctions.onMain { AudioFunctions.handleSeek(parameters) }
        }
    }

    class SetVolume: BridgeFunction {
        func execute(parameters: [String: Any]) -> [String: Any] {
            return AudioFunctions.onMain { AudioFunctions.handleSetVolume(parameters) }
        }
    }

    class GetDuration: BridgeFunction {
        func execute(parameters: [String: Any]) -> [String: Any] {
            return AudioFunctions.onMain { AudioFunctions.handleGetDuration(parameters) }
        }
    }

    class GetCurrentPosition: BridgeFunction {
        func execute(parameters: [String: Any]) -> [String: Any] {
            return AudioFunctions.onMain { AudioFunctions.handleGetCurrentPosition(parameters) }
        }
    }

    class SetMetadata: BridgeFunction {
        func execute(parameters: [String: Any]) -> [String: Any] {
            return AudioFunctions.onMain { AudioFunctions.handleSetMetadata(parameters) }
        }
    }

    class UpdateStreamMetadata: BridgeFunction {
        func execute(parameters: [String: Any]) -> [String: Any] {
            return AudioFunctions.onMain { AudioFunctions.handleUpdateStreamMetadata(parameters) }
        }
    }

    class SetPlaylist: BridgeFunction {
        func execute(parameters: [String: Any]) -> [String: Any] {
            return AudioFunctions.onMain { AudioFunctions.handleSetPlaylist(parameters) }
        }
    }

    class NextTrack: BridgeFunction {
        func execute(parameters: [String: Any]) -> [String: Any] {
            return AudioFunctions.onMain { AudioFunctions.handleNextTrack(parameters) }
        }
    }

    class PreviousTrack: BridgeFunction {
        func execute(parameters: [String: Any]) -> [String: Any] {
            return AudioFunctions.onMain { AudioFunctions.handlePreviousTrack(parameters) }
        }
    }

    class SetSleepTimer: BridgeFunction {
        func execute(parameters: [String: Any]) -> [String: Any] {
            return AudioFunctions.onMain { AudioFunctions.handleSetSleepTimer(parameters) }
        }
    }

    class SetPlaybackRate: BridgeFunction {
        func execute(parameters: [String: Any]) -> [String: Any] {
            return AudioFunctions.onMain { AudioFunctions.handleSetPlaybackRate(parameters) }
        }
    }

    class Load: BridgeFunction {
        func execute(parameters: [String: Any]) -> [String: Any] {
            return AudioFunctions.onMain { AudioFunctions.handleLoad(parameters) }
        }
    }

    class GetState: BridgeFunction {
        func execute(parameters: [String: Any]) -> [String: Any] {
            return AudioFunctions.onMain { AudioFunctions.handleGetState(parameters) }
        }
    }

    class SetRepeatMode: BridgeFunction {
        func execute(parameters: [String: Any]) -> [String: Any] {
            return AudioFunctions.onMain { AudioFunctions.handleSetRepeatMode(parameters) }
        }
    }

    class SetShuffleMode: BridgeFunction {
        func execute(parameters: [String: Any]) -> [String: Any] {
            return AudioFunctions.onMain { AudioFunctions.handleSetShuffleMode(parameters) }
        }
    }

    class AppendTrack: BridgeFunction {
        func execute(parameters: [String: Any]) -> [String: Any] {
            return AudioFunctions.onMain { AudioFunctions.handleAppendTrack(parameters) }
        }
    }

    class RemoveTrack: BridgeFunction {
        func execute(parameters: [String: Any]) -> [String: Any] {
            return AudioFunctions.onMain { AudioFunctions.handleRemoveTrack(parameters) }
        }
    }

    class SetProgressInterval: BridgeFunction {
        func execute(parameters: [String: Any]) -> [String: Any] {
            return AudioFunctions.onMain { AudioFunctions.handleSetProgressInterval(parameters) }
        }
    }

    class DrainEvents: BridgeFunction {
        func execute(parameters: [String: Any]) -> [String: Any] {
            return AudioFunctions.onMain { AudioFunctions.handleDrainEvents(parameters) }
        }
    }

    class SkipTrack: BridgeFunction {
        func execute(parameters: [String: Any]) -> [String: Any] {
            return AudioFunctions.onMain { AudioFunctions.handleSkipTrack(parameters) }
        }
    }

    class GetTrack: BridgeFunction {
        func execute(parameters: [String: Any]) -> [String: Any] {
            return AudioFunctions.onMain { AudioFunctions.handleGetTrack(parameters) }
        }
    }

    class GetActiveTrack: BridgeFunction {
        func execute(parameters: [String: Any]) -> [String: Any] {
            return AudioFunctions.onMain { AudioFunctions.handleGetActiveTrack(parameters) }
        }
    }

    class GetActiveTrackIndex: BridgeFunction {
        func execute(parameters: [String: Any]) -> [String: Any] {
            return AudioFunctions.onMain { AudioFunctions.handleGetActiveTrackIndex(parameters) }
        }
    }

    class GetPlaylist: BridgeFunction {
        func execute(parameters: [String: Any]) -> [String: Any] {
            return AudioFunctions.onMain { AudioFunctions.handleGetPlaylist(parameters) }
        }
    }

    class CancelSleepTimer: BridgeFunction {
        func execute(parameters: [String: Any]) -> [String: Any] {
            return AudioFunctions.onMain { AudioFunctions.handleCancelSleepTimer(parameters) }
        }
    }
}
