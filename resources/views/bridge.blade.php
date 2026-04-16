<script>
    document.addEventListener('nativephp_event', (event) => {
        const { event: name, payload } = event.detail;

        if (name.startsWith('Theunwindfront\\Audio\\Events\\')) {
            const eventName = name.split('\\').pop();
            const kebabName = eventName.replace(/([a-z0-9])([A-Z])/g, '$1-$2').toLowerCase();
            
            // Dispatch a generic prefixed event e.g. 'audio:playback-started'
            window.dispatchEvent(new CustomEvent(`audio:${kebabName}`, { detail: payload }));
            
            // Legacy mapping for common events
            const legacyMap = {
                'playback-started': 'audio-started',
                'playback-paused': 'audio-paused',
                'playback-stopped': 'audio-stopped',
                'playback-completed': 'audio-completed'
            };
            
            if (legacyMap[kebabName]) {
                window.dispatchEvent(new CustomEvent(legacyMap[kebabName], { detail: payload }));
            }
        }
    });
</script>
