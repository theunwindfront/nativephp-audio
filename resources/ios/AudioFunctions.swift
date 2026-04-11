import Foundation
import AVFoundation
import MediaPlayer

/**
 * AudioFunctions houses all BridgeFunction implementations for audio playback control
 * on iOS. Each inner class maps 1-to-1 with a PHP-side bridge call exposed by the
 * NativePHP audio-player plugin.
 */
enum AudioFunctions {
    private static var player: AVPlayer?
    private static var playerItem: AVPlayerItem?
    private static var completionObserver: Any?
    private static var interruptionObserver: Any?
    private static var currentURL: String = ""
    private static var remoteCommandsRegistered = false
    
    // Playlist state
    private static var playlist: [[String: Any]] = []
    private static var playlistIndex: Int = -1
    
    // Metadata state
    private static var metaTitle: String?
    private static var metaArtist: String?
    private static var metaAlbum: String?
    private static var metaDuration: Double?
    private static var metaArtwork: String?

    // MARK: - Helpers

    static func syncNowPlayingState() {
        var info = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
        
        if let title = metaTitle { info[MPMediaItemPropertyTitle] = title }
        if let artist = metaArtist { info[MPMediaItemPropertyArtist] = artist }
        if let album = metaAlbum { info[MPMediaItemPropertyAlbumTitle] = album }
        if let duration = metaDuration { info[MPMediaItemPropertyPlaybackDuration] = duration }
        
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = player?.currentTime().seconds ?? 0.0
        info[MPNowPlayingInfoPropertyPlaybackRate] = player?.rate ?? 0.0
        
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        
        if let artworkUrl = metaArtwork {
            loadArtworkAsync(from: artworkUrl)
        }
    }

    private static func loadArtworkAsync(from source: String) {
        let capturedURL = currentURL
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
                guard currentURL == capturedURL else { return }
                var current = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
                current[MPMediaItemPropertyArtwork] = artwork
                MPNowPlayingInfoCenter.default().nowPlayingInfo = current
            }
        }
    }

    private static func setupRemoteCommands() {
        guard !remoteCommandsRegistered else { return }
        remoteCommandsRegistered = true
        
        let center = MPRemoteCommandCenter.shared()
        
        center.playCommand.isEnabled = true
        center.playCommand.addTarget { _ in
            player?.play()
            syncNowPlayingState()
            LaravelBridge.shared.send?("Theunwindfront\\Audio\\Events\\RemotePlayReceived", ["url": currentURL])
            return .success
        }
        
        center.pauseCommand.isEnabled = true
        center.pauseCommand.addTarget { _ in
            player?.pause()
            syncNowPlayingState()
            LaravelBridge.shared.send?("Theunwindfront\\Audio\\Events\\RemotePauseReceived", [:])
            return .success
        }
        
        center.nextTrackCommand.isEnabled = true
        center.nextTrackCommand.addTarget { _ in
            playNext()
            LaravelBridge.shared.send?("Theunwindfront\\Audio\\Events\\RemoteNextTrackReceived", [:])
            return .success
        }
        
        center.previousTrackCommand.isEnabled = true
        center.previousTrackCommand.addTarget { _ in
            playPrevious()
            LaravelBridge.shared.send?("Theunwindfront\\Audio\\Events\\RemotePreviousTrackReceived", [:])
            return .success
        }
    }

    private static func setupAudioSessionObservers() {
        guard interruptionObserver == nil else { return }
        
        interruptionObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: nil, queue: .main
        ) { notification in
            guard let typeValue = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  let type = AVAudioSession.InterruptionType(rawValue: typeValue) else { return }
            
            if type == .began {
                player?.pause()
                syncNowPlayingState()
                LaravelBridge.shared.send?("Theunwindfront\\Audio\\Events\\PlaybackPaused", [:])
            } else if type == .ended {
                if let optionsValue = notification.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt,
                   AVAudioSession.InterruptionOptions(rawValue: optionsValue).contains(.shouldResume) {
                    player?.play()
                    syncNowPlayingState()
                    LaravelBridge.shared.send?("Theunwindfront\\Audio\\Events\\PlaybackStarted", ["url": currentURL])
                }
            }
        }
    }
    
    // MARK: - Playlist logic
    
    static func playTrackAt(index: Int) {
        guard index >= 0 && index < playlist.count else { return }
        
        playlistIndex = index
        let track = playlist[index]
        guard let urlString = track["url"] as? String,
              let url = URL(string: urlString) else { return }
        
        metaTitle = track["title"] as? String
        metaArtist = track["artist"] as? String
        metaAlbum = track["album"] as? String
        metaDuration = (track["duration"] as? NSNumber)?.doubleValue
        metaArtwork = track["artwork"] as? String
        
        startPlayback(url: url, urlString: urlString)
    }
    
    static func playNext() {
        if playlistIndex < playlist.count - 1 {
            playTrackAt(index: playlistIndex + 1)
        }
    }
    
    static func playPrevious() {
        if playlistIndex > 0 {
            playTrackAt(index: playlistIndex - 1)
        }
    }
    
    private static func startPlayback(url: URL, urlString: String) {
        currentURL = urlString
        
        if let observer = completionObserver {
            NotificationCenter.default.removeObserver(observer)
        }

        playerItem = AVPlayerItem(url: url)
        player = AVPlayer(playerItem: playerItem)

        completionObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: playerItem,
            queue: .main
        ) { _ in
            LaravelBridge.shared.send?("Theunwindfront\\Audio\\Events\\PlaybackCompleted", ["url": urlString])
            if playlistIndex < playlist.count - 1 {
                playNext()
            }
        }

        setupRemoteCommands()
        setupAudioSessionObservers()

        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
        try? AVAudioSession.sharedInstance().setActive(true)
        
        player?.play()
        syncNowPlayingState()
        
        LaravelBridge.shared.send?("Theunwindfront\\Audio\\Events\\PlaybackStarted", ["url": urlString])
    }

    // MARK: - Bridge Functions

    class Play: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            guard let urlString = parameters["url"] as? String,
                  let url = URL(string: urlString) else {
                return BridgeResponse.error(code: "INVALID_PARAMETERS", message: "URL is required and must be valid.")
            }

            AudioFunctions.playlist = []
            AudioFunctions.playlistIndex = -1
            AudioFunctions.startPlayback(url: url, urlString: urlString)

            return BridgeResponse.success(data: ["success": true])
        }
    }

    class SetPlaylist: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            guard let tracks = parameters["tracks"] as? [[String: Any]] else {
                return BridgeResponse.error(code: "INVALID_PARAMETERS", message: "Tracks array is required.")
            }
            
            AudioFunctions.playlist = tracks
            if !tracks.isEmpty {
                AudioFunctions.playTrackAt(index: 0)
            }
            
            return BridgeResponse.success(data: ["success": true])
        }
    }

    class NextTrack: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            AudioFunctions.playNext()
            return BridgeResponse.success(data: ["success": true])
        }
    }

    class PreviousTrack: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            AudioFunctions.playPrevious()
            return BridgeResponse.success(data: ["success": true])
        }
    }

    class SetMetadata: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            AudioFunctions.metaTitle = parameters["title"] as? String
            AudioFunctions.metaArtist = parameters["artist"] as? String
            AudioFunctions.metaAlbum = parameters["album"] as? String
            AudioFunctions.metaDuration = (parameters["duration"] as? NSNumber)?.doubleValue
            AudioFunctions.metaArtwork = parameters["artwork"] as? String
            
            AudioFunctions.syncNowPlayingState()
            
            return BridgeResponse.success(data: ["success": true])
        }
    }

    class Pause: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            AudioFunctions.player?.pause()
            AudioFunctions.syncNowPlayingState()
            LaravelBridge.shared.send?("Theunwindfront\\Audio\\Events\\PlaybackPaused", [:])
            return BridgeResponse.success(data: ["success": true])
        }
    }

    class Resume: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            AudioFunctions.player?.play()
            AudioFunctions.syncNowPlayingState()
            LaravelBridge.shared.send?("Theunwindfront\\Audio\\Events\\PlaybackStarted", [:])
            return BridgeResponse.success(data: ["success": true])
        }
    }

    class Stop: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            AudioFunctions.player?.pause()
            AudioFunctions.player = nil
            AudioFunctions.playerItem = nil
            if let observer = AudioFunctions.completionObserver {
                NotificationCenter.default.removeObserver(observer)
                AudioFunctions.completionObserver = nil
            }
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            LaravelBridge.shared.send?("Theunwindfront\\Audio\\Events\\PlaybackStopped", [:])
            return BridgeResponse.success(data: ["success": true])
        }
    }

    class Seek: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let seconds = (parameters["seconds"] as? NSNumber)?.doubleValue ?? 0.0
            let time = CMTime(seconds: seconds, preferredTimescale: 600)
            AudioFunctions.player?.seek(to: time)
            AudioFunctions.syncNowPlayingState()
            return BridgeResponse.success(data: ["success": true])
        }
    }

    class SetVolume: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let level = (parameters["level"] as? NSNumber)?.floatValue ?? 1.0
            AudioFunctions.player?.volume = level
            return BridgeResponse.success(data: ["success": true])
        }
    }

    class GetDuration: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let duration = AudioFunctions.playerItem?.duration.seconds ?? 0.0
            let validDuration = duration.isNaN ? 0.0 : duration
            return BridgeResponse.success(data: ["duration": validDuration])
        }
    }

    class GetCurrentPosition: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let position = AudioFunctions.player?.currentTime().seconds ?? 0.0
            let validPosition = position.isNaN ? 0.0 : position
            return BridgeResponse.success(data: ["position": validPosition])
        }
    }
}
