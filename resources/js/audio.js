/**
 * Calls a native bridge function through NativePHP's `/_native/api/call` endpoint.
 * Resolves with the function's result; rejects if the bridge reports an error.
 */
export async function bridgeCall(method, params = {}) {
    const response = await fetch('/_native/api/call', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'Accept': 'application/json',
            'X-Requested-With': 'XMLHttpRequest',
        },
        body: JSON.stringify({ method, params }),
    });

    let result = null;
    try {
        result = await response.json();
    } catch (_) {
        // Non-JSON response; handled below.
    }

    if (!response.ok || !result || result.status === 'error') {
        const message = result?.message || `Native call ${method} failed (HTTP ${response.status})`;
        const error = new Error(message);
        error.code = result?.code;
        error.response = result;
        throw error;
    }

    return result.data;
}

const audioPlayer = {
    play: async (url, metadata = {}) => {
        return await bridgeCall('Audio.play', { url, ...metadata });
    },
    playStream: async (mountpoint, metadata = {}) => {
        return await bridgeCall('Audio.playStream', { mountpoint, ...metadata });
    },
    load: async (url, metadata = {}) => {
        return await bridgeCall('Audio.load', { url, ...metadata });
    },
    pause: async () => {
        return await bridgeCall('Audio.pause');
    },
    resume: async () => {
        return await bridgeCall('Audio.resume');
    },
    stop: async () => {
        return await bridgeCall('Audio.stop');
    },
    seek: async (seconds) => {
        return await bridgeCall('Audio.seek', { seconds });
    },
    setVolume: async (level) => {
        return await bridgeCall('Audio.setVolume', { level });
    },
    setMetadata: async (metadata) => {
        return await bridgeCall('Audio.setMetadata', metadata);
    },
    updateStreamMetadata: async (metadata) => bridgeCall('Audio.updateStreamMetadata', metadata),
    setPlaybackRate: async (rate) => {
        return await bridgeCall('Audio.setPlaybackRate', { rate });
    },
    setSleepTimer: async (seconds) => {
        return await bridgeCall('Audio.setSleepTimer', { seconds });
    },
    cancelSleepTimer: async () => {
        return await bridgeCall('Audio.cancelSleepTimer');
    },
    setProgressInterval: async (seconds) => {
        return await bridgeCall('Audio.setProgressInterval', { seconds });
    },
    
    // Playlist Management
    setPlaylist: async (tracks, autoPlay = true, startIndex = 0) => {
        return await bridgeCall('Audio.setPlaylist', { items: tracks, autoPlay, startIndex });
    },
    appendTrack: async (track) => {
        return await bridgeCall('Audio.appendTrack', { track });
    },
    removeTrack: async (index) => {
        return await bridgeCall('Audio.removeTrack', { index });
    },
    playTrackAt: async (index) => {
        return await bridgeCall('Audio.playTrackAt', { index });
    },
    skipTo: async (index) => {
        return await bridgeCall('Audio.skipTrack', { index });
    },
    next: async () => {
        return await bridgeCall('Audio.nextTrack');
    },
    previous: async () => {
        return await bridgeCall('Audio.previousTrack');
    },
    setRepeatMode: async (mode) => {
        return await bridgeCall('Audio.setRepeatMode', { mode });
    },
    setShuffleMode: async (enabled) => {
        return await bridgeCall('Audio.setShuffleMode', { shuffle: enabled });
    },

    // State & Introspection
    getState: async () => {
        return await bridgeCall('Audio.getState');
    },
    getDuration: async () => {
        return await bridgeCall('Audio.getDuration');
    },
    getCurrentPosition: async () => {
        return await bridgeCall('Audio.getCurrentPosition');
    },
    getTrack: async (index) => {
        return await bridgeCall('Audio.getTrack', { index });
    },
    getActiveTrack: async () => {
        return await bridgeCall('Audio.getActiveTrack');
    },
    getActiveTrackIndex: async () => {
        return await bridgeCall('Audio.getActiveTrackIndex');
    },
    getPlaylist: async () => {
        return await bridgeCall('Audio.getPlaylist');
    },
    drainEvents: async () => {
        return await bridgeCall('Audio.drainEvents');
    }
};

export default audioPlayer;
