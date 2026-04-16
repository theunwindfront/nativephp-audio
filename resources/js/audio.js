const audioPlayer = {
    play: async (url, metadata = {}) => {
        return await window.nativephp.call('Audio.play', { url, ...metadata });
    },
    load: async (url, metadata = {}) => {
        return await window.nativephp.call('Audio.load', { url, ...metadata });
    },
    pause: async () => {
        return await window.nativephp.call('Audio.pause');
    },
    resume: async () => {
        return await window.nativephp.call('Audio.resume');
    },
    stop: async () => {
        return await window.nativephp.call('Audio.stop');
    },
    seek: async (seconds) => {
        return await window.nativephp.call('Audio.seek', { seconds });
    },
    setVolume: async (level) => {
        return await window.nativephp.call('Audio.setVolume', { level });
    },
    setMetadata: async (metadata) => {
        return await window.nativephp.call('Audio.setMetadata', metadata);
    },
    setPlaybackRate: async (rate) => {
        return await window.nativephp.call('Audio.setPlaybackRate', { rate });
    },
    setSleepTimer: async (seconds) => {
        return await window.nativephp.call('Audio.setSleepTimer', { seconds });
    },
    cancelSleepTimer: async () => {
        return await window.nativephp.call('Audio.cancelSleepTimer');
    },
    setProgressInterval: async (seconds) => {
        return await window.nativephp.call('Audio.setProgressInterval', { seconds });
    },
    
    // Playlist Management
    setPlaylist: async (tracks, autoPlay = true, startIndex = 0) => {
        return await window.nativephp.call('Audio.setPlaylist', { items: tracks, autoPlay, startIndex });
    },
    appendTrack: async (track) => {
        return await window.nativephp.call('Audio.appendTrack', { track });
    },
    removeTrack: async (index) => {
        return await window.nativephp.call('Audio.removeTrack', { index });
    },
    playTrackAt: async (index) => {
        return await window.nativephp.call('Audio.playTrackAt', { index });
    },
    skipTo: async (index) => {
        return await window.nativephp.call('Audio.skipTrack', { index });
    },
    next: async () => {
        return await window.nativephp.call('Audio.nextTrack');
    },
    previous: async () => {
        return await window.nativephp.call('Audio.previousTrack');
    },
    setRepeatMode: async (mode) => {
        return await window.nativephp.call('Audio.setRepeatMode', { mode });
    },
    setShuffleMode: async (enabled) => {
        return await window.nativephp.call('Audio.setShuffleMode', { shuffle: enabled });
    },

    // State & Introspection
    getState: async () => {
        return await window.nativephp.call('Audio.getState');
    },
    getDuration: async () => {
        return await window.nativephp.call('Audio.getDuration');
    },
    getCurrentPosition: async () => {
        return await window.nativephp.call('Audio.getCurrentPosition');
    },
    getTrack: async (index) => {
        return await window.nativephp.call('Audio.getTrack', { index });
    },
    getActiveTrack: async () => {
        return await window.nativephp.call('Audio.getActiveTrack');
    },
    getActiveTrackIndex: async () => {
        return await window.nativephp.call('Audio.getActiveTrackIndex');
    },
    getPlaylist: async () => {
        return await window.nativephp.call('Audio.getPlaylist');
    },
    drainEvents: async () => {
        return await window.nativephp.call('Audio.drainEvents');
    }
};

export default audioPlayer;
