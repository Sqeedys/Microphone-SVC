# Run an announcement

Crafted microphones work during ordinary play. Start a staff session when you need one designated presenter or audience moderation.

1. As a host, run `/eventvoice start` and `/eventvoice mic give PresenterName`. The presenter needs an empty inventory slot, presentation permission, and a working voice connection.
2. Set announcement reach with `/eventvoice range 1`, `2`, `3` or `4`. Each number selects the corresponding distance in the server config. Sessions start at tier 4, normally 256 blocks. Choose `/eventvoice range global` for an announcement across dimensions.
3. The presenter leaves their voice group, holds the issued mic in either hand or equips it in the head slot, and runs `/eventvoice mic on`. They can use their existing push-to-talk key. The announcement goes to eligible listeners. Local announcements fade with distance within the presenter's dimension.
4. To take a question, run `/eventvoice mute-all` and then `/eventvoice unmute GuestName`. A targeted mute on the presenter takes priority over their permission to speak through mute-all.
5. Run `/eventvoice mic off` to hand over the microphone. Run `/eventvoice end` to clear the session. Session controls and presenter credentials expire on server restart.

## Commands

| Commands | Permission | Purpose |
|---|---|---|
| `/eventvoice start`, `end`, `reset` | `microphone:manage` | Open or close a staff session. Reset also clears its rules. |
| `/eventvoice range <1\|2\|3\|4\|global>` | `microphone:manage` | Set announcement reach. Affects the staff presenter. |
| `/eventvoice mic give [player]` | `microphone:manage` | Issue a presentation mic for this session. |
| `/eventvoice mic on`, `mic off` | `microphone:present` | Arm or disarm your issued mic. A manager can disarm the active presenter. |
| `/eventvoice mute-all`, `unmute-all`, `deafen-all`, `undeafen-all` | `microphone:moderate` | Change who can speak or hear. Bulk rules cover later joins. |
| `/eventvoice mute <players>`, `unmute <players>`, `deafen <players>`, `undeafen <players>` | `microphone:moderate` | Apply an individual rule or admit a player during a bulk restriction. |
| `/eventvoice status [players]` | Any staff node for session status. Manage or moderate for player details. | Inspect reach, the active presenter and moderation. |

Staff commands use the Fabric permission API and fall back to vanilla operator level 2. Simple Voice Chat's speak and listen permissions still apply. Deafen-all also silences announcements. Ending a session restores ordinary voice moderation.
