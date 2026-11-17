<script>
    (() => {
        if (window.__nativeAudioBridge) return;
        window.__nativeAudioBridge = true;

        const prefix = 'Theunwindfront\\Audio\\Events\\';
        const legacyMap = {
            'playback-started': 'audio-started',
            'playback-paused': 'audio-paused',
            'playback-stopped': 'audio-stopped',
            'playback-completed': 'audio-completed',
        };

        // NativePHP fires `native-event` on the document for every event sent from native code.
        document.addEventListener('native-event', (event) => {
            const { event: name, payload } = event.detail || {};

            if (typeof name !== 'string' || !name.startsWith(prefix)) return;

            const eventName = name.slice(prefix.length);
            const kebabName = eventName.replace(/([a-z0-9])([A-Z])/g, '$1-$2').toLowerCase();

            // e.g. 'audio:playback-started'
            window.dispatchEvent(new CustomEvent(`audio:${kebabName}`, { detail: payload }));

            if (legacyMap[kebabName]) {
                window.dispatchEvent(new CustomEvent(legacyMap[kebabName], { detail: payload }));
            }
        });
    })();
</script>
