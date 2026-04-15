import Foundation
import AVFoundation
import MediaPlayer

/**
 * AudioFunctions handles bridge calls for audio playback on iOS.
 * It manages an AVPlayer and interacts with MPMediaPlayer's remote commands
 * to provide a seamless background playback experience.
 */
enum AudioFunctions {
    private static var player: AVPlayer?
    private static var playerItem: AVPlayerItem?
    private static var currentURL: String = ""
    private static var remoteCommandsRegistered = false
    
    // Metadata states
    private static var metaTitle: String?
    private static var metaArtist: String?
    private static var metaAlbum: String?
    private static var metaDuration: Double?
    private static var metaArtworkSource: String?

    // Playlist states
    private static var playlist: [[String: Any]] = []
    private static var playlistIndex: Int = -1
    private static var repeatMode: String = "none" // none, one, all
    private static var shuffleMode: Bool = false
    private static var shuffledOrder: [Int] = []

    // Progress & state
    private static var isBuffering: Bool = false
    private static var playbackRate: Float = 1.0
    private static var progressInterval: Double = 1.0
    private static var progressObserver: Any?
    
    // Background Events
    private static var isInBackground: Bool = false
    private static var pendingEvents: [[String: Any]] = []

    // Observers
    private static var completionObserver: Any?
    private static var interruptionObserver: Any?
    private static var bufferingObserver: NSKeyValueObservation?
    private static var readyObserver: NSKeyValueObservation?

    // MARK: - Lifecycle & Initialization

    static func setup() {
        NotificationCenter.default.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { _ in
            isInBackground = true
        }
        NotificationCenter.default.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { _ in
            isInBackground = false
        }
    }

    // MARK: - Helpers

    private static func sendEvent(_ name: String, _ payload: [String: Any]) {
        let eventName = "Theunwindfront\\Audio\\Events\\\(name)"
        if (isInBackground) {
            pendingEvents.append(["name": eventName, "payload": payload])
            return
        }
        LaravelBridge.shared.send?(eventName, payload)
    }

    private static func syncNowPlaying() {
        var info = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
        if let title = metaTitle { info[MPMediaItemPropertyTitle] = title }
        if let artist = metaArtist { info[MPMediaItemPropertyArtist] = artist }
        if let album = metaAlbum { info[MPMediaItemPropertyAlbumTitle] = album }
        if let duration = metaDuration { info[MPMediaItemPropertyPlaybackDuration] = duration } else if let item = playerItem {
            info[MPMediaItemPropertyPlaybackDuration] = item.duration.seconds
        }
        
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = player?.currentTime().seconds ?? 0.0
        info[MPNowPlayingInfoPropertyPlaybackRate] = player?.rate ?? 0.0
        
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        
        if let artworkUrl = metaArtworkSource {
            loadArtworkAsync(from: artworkUrl)
        }
    }

    private static func loadArtworkAsync(from source: String) {
        let current = currentURL
        DispatchQueue.global(qos: .userInitiated).async {
            let image: UIImage?
            if let url = URL(string: source), let data = try? Data(contentsOf: url) {
                image = UIImage(data: data)
            } else if let local = UIImage(contentsOfFile: source) {
                image = local
            } else {
                image = nil
            }
            guard let loaded = image else { return }
            let artwork = MPMediaItemArtwork(boundsSize: loaded.size) { _ in loaded }
            DispatchQueue.main.async {
                guard currentURL == current else { return }
                var info = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
                info[MPMediaItemPropertyArtwork] = artwork
                MPNowPlayingInfoCenter.default().nowPlayingInfo = info
            }
        }
    }

    private static func setupRemoteCommands() {
        guard !remoteCommandsRegistered else { return }
        remoteCommandsRegistered = true
        let center = MPRemoteCommandCenter.shared()
        
        center.playCommand.isEnabled = true
        center.playCommand.addTarget { _ in
            player?.play(); player?.rate = playbackRate; syncNowPlaying(); startProgressTimer()
            sendEvent("RemotePlayReceived", ["url": currentURL])
            return .success
        }
        
        center.pauseCommand.isEnabled = true
        center.pauseCommand.addTarget { _ in
            player?.pause(); stopProgressTimer(); syncNowPlaying()
            sendEvent("RemotePauseReceived", [:])
            return .success
        }
        
        center.nextTrackCommand.isEnabled = true
        center.nextTrackCommand.addTarget { _ in
            playNext(); sendEvent("RemoteNextTrackReceived", [:]); return .success
        }
        
        center.previousTrackCommand.isEnabled = true
        center.previousTrackCommand.addTarget { _ in
            playPrevious(); sendEvent("RemotePreviousTrackReceived", [:]); return .success
        }

        center.changePlaybackPositionCommand.isEnabled = true
        center.changePlaybackPositionCommand.addTarget { event in
            if let e = event as? MPChangePlaybackPositionCommandEvent {
                player?.seek(to: CMTime(seconds: e.positionTime, preferredTimescale: 600))
                syncNowPlaying(); return .success
            }
            return .commandFailed
        }
    }

    private static func setupInterruptionObserver() {
        guard interruptionObserver == nil else { return }
        interruptionObserver = NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { notification in
            guard let typeValue = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  let type = AVAudioSession.InterruptionType(rawValue: typeValue) else { return }
            if type == .began {
                player?.pause(); syncNowPlaying()
                sendEvent("PlaybackPaused", [:])
            } else if type == .ended {
                if let opts = notification.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt,
                   AVAudioSession.InterruptionOptions(rawValue: opts).contains(.shouldResume) {
                    player?.play(); player?.rate = playbackRate; syncNowPlaying()
                    sendEvent("PlaybackStarted", ["url": currentURL])
                }
            }
        }
    }

    // MARK: - Playlist

    private static func getLogicalIndex(_ index: Int) -> Int {
        if !shuffleMode || shuffledOrder.isEmpty { return index }
        return shuffledOrder.count > index ? shuffledOrder[index] : index
    }

    private static func regenerateShuffleOrder() {
        shuffledOrder = Array(0..<playlist.count)
        if (shuffleMode) { shuffledOrder.shuffle() }
    }

    static func playTrackAt(index: Int) {
        guard index >= 0 && index < playlist.count else { return }
        playlistIndex = index
        let track = playlist[getLogicalIndex(index)]
        
        metaTitle = track["title"] as? String
        metaArtist = track["artist"] as? String
        metaAlbum = track["album"] as? String
        metaDuration = (track["duration"] as? NSNumber)?.doubleValue
        metaArtworkSource = track["artwork"] as? String
        
        if let urlStr = track["url"] as? String, let url = URL(string: urlStr) {
            prepareAndPlay(url: url, urlString: urlStr)
        }
    }

    static func playNext() {
        if playlist.isEmpty { return }
        switch repeatMode {
        case "one": playTrackAt(index: playlistIndex)
        case "all": playTrackAt(index: (playlistIndex + 1) % playlist.count)
        default:
            if playlistIndex < playlist.count - 1 { playTrackAt(index: playlistIndex + 1) }
            else { sendEvent("PlaylistEnded", [:]) }
        }
    }

    static func playPrevious() {
        if !playlist.isEmpty && playlistIndex > 0 { playTrackAt(index: playlistIndex - 1) }
    }

    private static func prepareAndPlay(url: URL, urlString: String, autoStart: Bool = true) {
        currentURL = urlString
        stopProgressTimer()
        
        if let obs = completionObserver { NotificationCenter.default.removeObserver(obs) }
        bufferingObserver?.invalidate()
        readyObserver?.invalidate()
        
        playerItem = AVPlayerItem(url: url)
        player = AVPlayer(playerItem: playerItem)

        bufferingObserver = playerItem?.observe(\.isPlaybackBufferEmpty, options: [.new]) { item, _ in
            guard item.isPlaybackBufferEmpty else { return }
            isBuffering = true
            sendEvent("PlaybackBuffering", ["url": urlString])
        }

        readyObserver = playerItem?.observe(\.isPlaybackLikelyToKeepUp, options: [.new]) { item, _ in
            guard item.isPlaybackLikelyToKeepUp else { return }
            isBuffering = false
            sendEvent("PlaybackReady", ["url": urlString, "duration": item.duration.seconds])
        }

        completionObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: playerItem, queue: .main) { _ in
            sendEvent("PlaybackCompleted", ["url": urlString])
            playNext()
        }

        setupRemoteCommands()
        setupInterruptionObserver()
        
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
        try? AVAudioSession.sharedInstance().setActive(true)
        
        if autoStart {
            player?.play()
            player?.rate = playbackRate
            startProgressTimer()
            sendEvent("PlaybackStarted", ["url": urlString])
        } else {
            sendEvent("PlaybackLoaded", ["url": urlString])
        }
        syncNowPlaying()
    }

    // MARK: - Timers

    private static func startProgressTimer() {
        stopProgressTimer()
        guard let p = player else { return }
        progressObserver = p.addPeriodicTimeObserver(forInterval: CMTime(seconds: progressInterval, preferredTimescale: 600), queue: .main) { _ in
            sendEvent("PlaybackProgressUpdated", [
                "position": player?.currentTime().seconds ?? 0.0,
                "duration": playerItem?.duration.seconds ?? 0.0
            ])
        }
    }

    private static func stopProgressTimer() {
        if let obs = progressObserver {
            player?.removeTimeObserver(obs)
            progressObserver = nil
        }
    }

    // MARK: - Bridge Functions

    class Play: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            guard let urlString = parameters["url"] as? String, let url = URL(string: urlString) else {
                return BridgeResponse.error(code: "INVALID_URL", message: "Missing valid URL")
            }
            AudioFunctions.playlist = []
            AudioFunctions.playlistIndex = -1
            AudioFunctions.metaTitle = parameters["title"] as? String
            AudioFunctions.metaArtist = parameters["artist"] as? String
            AudioFunctions.metaAlbum = parameters["album"] as? String
            AudioFunctions.metaDuration = (parameters["duration"] as? NSNumber)?.doubleValue
            AudioFunctions.metaArtworkSource = parameters["artwork"] as? String
            
            AudioFunctions.prepareAndPlay(url: url, urlString: urlString)
            return BridgeResponse.success(data: ["success": true])
        }
    }

    class Load: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            guard let urlString = parameters["url"] as? String, let url = URL(string: urlString) else { return BridgeResponse.error(code: "INVALID_URL", message: "Missing valid URL") }
            AudioFunctions.prepareAndPlay(url: url, urlString: urlString, autoStart: false)
            return BridgeResponse.success(data: ["success": true])
        }
    }

    class GetState: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            return BridgeResponse.success(data: [
                "url": currentURL,
                "position": player?.currentTime().seconds ?? 0.0,
                "duration": playerItem?.duration.seconds ?? 0.0,
                "isPlaying": player?.rate ?? 0 > 0,
                "isBuffering": isBuffering,
                "repeatMode": repeatMode,
                "shuffleMode": shuffleMode,
                "playlistIndex": playlistIndex,
                "playlistTotal": playlist.count,
                "title": metaTitle ?? "",
                "artist": metaArtist ?? ""
            ])
        }
    }

    class SetPlaylist: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            guard let tracks = parameters["tracks"] as? [[String: Any]] else { return BridgeResponse.error(code: "INVALID_TRACKS", message: "Missing tracks array") }
            AudioFunctions.playlist = tracks
            AudioFunctions.regenerateShuffleOrder()
            if !tracks.isEmpty { AudioFunctions.playTrackAt(index: 0) }
            return BridgeResponse.success(data: ["success": true])
        }
    }

    class AppendTrack: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            AudioFunctions.playlist.append(parameters)
            AudioFunctions.regenerateShuffleOrder()
            return BridgeResponse.success(data: ["success": true])
        }
    }

    class RemoveTrack: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let index = (parameters["index"] as? NSNumber)?.intValue ?? -1
            if index >= 0 && index < AudioFunctions.playlist.count {
                AudioFunctions.playlist.remove(at: index)
                AudioFunctions.regenerateShuffleOrder()
            }
            return BridgeResponse.success(data: ["success": true])
        }
    }

    class SetRepeatMode: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            AudioFunctions.repeatMode = parameters["mode"] as? String ?? "none"
            return BridgeResponse.success(data: ["success": true])
        }
    }

    class SetShuffleMode: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            AudioFunctions.shuffleMode = parameters["enabled"] as? Bool ?? false
            AudioFunctions.regenerateShuffleOrder()
            return BridgeResponse.success(data: ["success": true])
        }
    }

    class SetProgressInterval: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            AudioFunctions.progressInterval = (parameters["seconds"] as? NSNumber)?.doubleValue ?? 1.0
            if (AudioFunctions.player?.rate ?? 0 > 0) { AudioFunctions.startProgressTimer() }
            return BridgeResponse.success(data: ["success": true])
        }
    }

    class DrainEvents: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let events = AudioFunctions.pendingEvents
            AudioFunctions.pendingEvents = []
            return BridgeResponse.success(data: ["events": events])
        }
    }

    class Pause: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            AudioFunctions.player?.pause(); AudioFunctions.stopProgressTimer(); AudioFunctions.syncNowPlaying()
            return BridgeResponse.success(data: ["success": true])
        }
    }
    class Resume: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            AudioFunctions.player?.play(); AudioFunctions.player?.rate = AudioFunctions.playbackRate; AudioFunctions.startProgressTimer(); AudioFunctions.syncNowPlaying()
            return BridgeResponse.success(data: ["success": true])
        }
    }
    class Stop: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            AudioFunctions.player?.pause(); AudioFunctions.player = nil; AudioFunctions.playerItem = nil
            AudioFunctions.syncNowPlaying(); return BridgeResponse.success(data: ["success": true])
        }
    }
    class Seek: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let sec = (parameters["seconds"] as? NSNumber)?.doubleValue ?? 0.0
            AudioFunctions.player?.seek(to: CMTime(seconds: sec, preferredTimescale: 600)); AudioFunctions.syncNowPlaying()
            return BridgeResponse.success(data: ["success": true])
        }
    }
    class SetVolume: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            AudioFunctions.player?.volume = (parameters["level"] as? NSNumber)?.floatValue ?? 1.0
            return BridgeResponse.success(data: ["success": true])
        }
    }
    class SetPlaybackRate: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            AudioFunctions.playbackRate = (parameters["rate"] as? NSNumber)?.floatValue ?? 1.0
            if (AudioFunctions.player?.rate ?? 0 > 0) { AudioFunctions.player?.rate = AudioFunctions.playbackRate }
            AudioFunctions.syncNowPlaying(); return BridgeResponse.success(data: ["success": true])
        }
    }
}
