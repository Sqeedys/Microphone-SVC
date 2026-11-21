package dev.sqeedys.microphone;

import java.util.List;
import java.util.UUID;

public final class VoiceRulesTest {
    public static void main(String[] args) {
        var host = UUID.randomUUID();
        var attendee = UUID.randomUUID();
        var laterJoiner = UUID.randomUUID();
        var rules = VoiceRules.OPEN;
        require(!rules.muted(attendee, false) && !rules.deafened(attendee), "baseline proximity");
        rules = rules.setPlayers(true, true, List.of(host)).setAll(true, true);
        require(rules.muted(host, true), "targeted mute survives mute-all and wins over stage exemption");
        require(rules.muted(laterJoiner, false), "later join inherits bulk mute");
        rules = rules.setPlayers(true, false, List.of(host));
        require(!rules.muted(host, false) && !rules.muted(host, true), "targeted unmute updates both routes");
        rules = rules.setAll(true, true);
        require(rules.muted(host, false) && !rules.muted(host, true), "mute-all clears exemptions except stage route");
        rules = rules.setAll(false, true);
        require(rules.deafened(host) && rules.deafened(laterJoiner), "deafen-all covers presenter and later joins");
        rules = rules.setPlayers(false, false, List.of(attendee));
        require(!rules.deafened(attendee) && rules.deafened(laterJoiner), "targeted undeafen is an exception");
        rules = rules.setAll(false, true);
        require(rules.deafened(attendee), "deafen-all clears prior hearing exception");
        rules = rules.setPlayers(false, true, List.of(attendee)).setAll(false, false);
        require(!rules.deafened(host) && !rules.deafened(attendee), "undeafen-all restores hearing for everyone");
        rules = rules.setPlayers(true, true, List.of(attendee)).setAll(true, false);
        require(!rules.muted(attendee, false) && !rules.muted(laterJoiner, false), "unmute-all restores speaking for everyone");
        require(!VoiceRules.OPEN.muted(host, false) && !VoiceRules.OPEN.deafened(host), "open rules remain immutable");
        System.out.println("VoiceRulesTest passed");
    }

    private static void require(boolean expected, String behavior) {
        if (!expected) {
            throw new AssertionError(behavior);
        }
    }
}
