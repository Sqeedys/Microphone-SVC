package dev.sqeedys.microphone;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

record VoiceRules(boolean muteAll, boolean deafenAll, Map<UUID, Boolean> mutes, Map<UUID, Boolean> deafens) {
    static final VoiceRules OPEN = new VoiceRules(false, false, Map.of(), Map.of());

    boolean muted(UUID player, boolean presenting) {
        return mutes.getOrDefault(player, muteAll && !presenting);
    }

    boolean deafened(UUID player) {
        return deafens.getOrDefault(player, deafenAll);
    }

    VoiceRules setAll(boolean mute, boolean value) {
        return mute
                ? new VoiceRules(value, deafenAll, value ? keepMutes() : Map.of(), deafens)
                : new VoiceRules(muteAll, value, mutes, Map.of());
    }

    private Map<UUID, Boolean> keepMutes() {
        var stillMuted = new HashMap<>(mutes);
        stillMuted.values().removeIf(muted -> !muted);
        return Map.copyOf(stillMuted);
    }

    VoiceRules setPlayers(boolean mute, boolean value, Collection<UUID> players) {
        var overrides = new HashMap<>(mute ? mutes : deafens);
        players.forEach(player -> overrides.put(player, value));
        return mute
                ? new VoiceRules(muteAll, deafenAll, Map.copyOf(overrides), deafens)
                : new VoiceRules(muteAll, deafenAll, mutes, Map.copyOf(overrides));
    }
}
