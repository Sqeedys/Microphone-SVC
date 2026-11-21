"""Run Microphone's native crafting, item use, permissions and UDP routing checks."""

import struct
import time
from itertools import count

import native_voice_harness as harness
from native_voice_client import string

expect = harness.expect
uses = count(1)


def use(player, hand=0, sneak=False):
    player.use_item(next(uses), hand, sneak)
    time.sleep(0.24)


def group(player):
    player.payload(
        "voicechat:create_group",
        string("PrivateFixture") + b"\0" + struct.pack(">h", 2),
    )
    time.sleep(0.4)


def leave(player):
    player.payload("voicechat:leave_group", b"")
    time.sleep(0.4)


def check_attenuation(receiver, radius, x=0.5):
    seq = receiver.received[-1][-1]
    position, distance = receiver.positional[seq]
    expect(
        abs(position[0] - x) < 0.01
        and 70 < position[1] < 73
        and abs(position[2] - 0.5) < 0.01,
        f"positional voice carries speaker's eyes: {position}",
    )
    expect(distance == radius, f"expected radius {radius}, got {distance}")


def cases(cmd, players, sound, mode):
    host, near, far = players
    custom = mode == "custom"
    ranges = (72, 136, 200, 280) if custom else (64, 128, 192, 256)
    expect(
        "Event voice is inactive" in cmd("eventvoice status"),
        "no session needed for crafted mic",
    )
    recipe = cmd("microphonefixture recipe")
    expected = (
        "true microphone:microphone true" if mode == "default" else "false none false"
    )
    expect("Recipe fixture: " + expected in recipe, recipe)
    print(
        "PASS native crafting-grid result, recipe registration and unlock advancement",
        flush=True,
    )
    cmd("gamemode survival Speaker")
    cmd("effect give Speaker minecraft:slow_falling infinite 0 true")
    # Teleport onto a floating floor so survival mode preserves exact distances.
    for name, x in (
        ("Speaker", 0),
        ("ListenerNear", 3),
        ("ListenerFar", ranges[0] - 1),
    ):
        cmd(f"tp {name} {x} 70 0")
        cmd(f"setblock {x} 69 0 minecraft:stone")
    cmd("item replace entity Speaker weapon.mainhand with microphone:microphone")
    use(host)
    if mode == "staff-only":
        sound(host, {near: 2})
        use(host, sneak=True)
        expect(
            "microphone_range_tier" not in cmd("data get entity Speaker SelectedItem"),
            "disabled sneak-use",
        )
        use(host)
        sound(host, {near: 2})
        cmd("clear Speaker microphone:microphone")
        cmd("eventvoice start")
        cmd("eventvoice mic give Speaker")
        host.command("eventvoice mic on")
        harness.until(
            lambda: "Mic Speaker" in cmd("eventvoice status"),
            "staff presenter while handheld disabled",
        )
        sound(host, {near: 4, far: 4})
        cmd("eventvoice range global")
        sound(host, {near: 3, far: 3})
        print(
            "PASS handheld disabled blocks crafted and existing microphones, staff local and global remain usable",
            flush=True,
        )
        return
    sound(host, {near: 4, far: 4})
    check_attenuation(far, ranges[0])
    cmd(f"tp ListenerFar {ranges[0] + 1} 70 0")
    sound(host, {near: 4})
    use(host)
    sound(host, {near: 2})
    use(host)
    for index, radius in enumerate(ranges):
        cmd(f"tp ListenerFar {radius - 1} 70 0")
        sound(host, {near: 4, far: 4})
        check_attenuation(far, radius)
        cmd(f"tp ListenerFar {radius + 1} 70 0")
        sound(host, {near: 4})
        if index < 3:
            use(host, sneak=True)
            data = cmd("data get entity Speaker SelectedItem")
            expect(
                f"microphone_range_tier: {index + 1}" in data,
                f"range persisted on item stack: {data}",
            )
    cmd(f"tp ListenerFar {ranges[-1] - 1} 70 0")
    sound(host, {near: 2}, whisper=True)
    use(host, sneak=True)
    sound(host, {near: 4})
    check_attenuation(near, ranges[0])
    for i in range(3):
        use(host, sneak=True)
    print(
        "PASS real item interactions as survival non-op, toggle, all four distance fields, cutoff, persisted and cycled tiers, whispers",
        flush=True,
    )

    cmd(
        "item replace entity Speaker weapon.offhand from entity Speaker weapon.mainhand"
    )
    cmd("item replace entity Speaker weapon.mainhand with air")
    sound(host, {near: 2})
    use(host, hand=1)
    sound(host, {near: 4, far: 4})
    check_attenuation(far, ranges[-1])
    cmd("execute in minecraft:the_nether run tp ListenerFar 0 80 0")
    sound(host, {near: 4})
    cmd(f"execute in minecraft:overworld run tp ListenerFar {ranges[-1] - 1} 70 0")
    group(far)
    sound(host, {near: 4})
    leave(far)
    sound(host, {near: 4, far: 4})
    group(host)
    sound(host, {})
    leave(host)
    sound(host, {near: 2})
    use(host, hand=1)
    far.payload("voicechat:update_state", b"\x01")
    time.sleep(0.3)
    sound(host, {near: 4})
    far.payload("voicechat:update_state", b"\x00")
    time.sleep(0.3)
    expect(
        "Voice fixture gate listen" in cmd("microphonefixture listen"),
        "fixture listen permission",
    )
    sound(host, {near: 4})
    cmd("microphonefixture speak")
    sound(host, {})
    cmd("microphonefixture none")
    sound(host, {near: 2})
    use(host, hand=1)
    sound(host, {near: 4, far: 4})
    print(
        "PASS offhand, held identity, cross-dimension exclusion, groups and native SPEAK/LISTEN and disabled gates",
        flush=True,
    )

    expect("session started" in cmd("eventvoice start"), "start session")
    expect(f"{ranges[-1]} blocks" in cmd("eventvoice status"), "default staff range")
    cmd("eventvoice mute Speaker")
    sound(host, {})
    cmd("eventvoice unmute Speaker")
    cmd("eventvoice deafen ListenerFar")
    sound(host, {near: 4})
    cmd("eventvoice undeafen ListenerFar")
    expect("microphone given" in cmd("eventvoice mic give Speaker"), "issue staff mic")
    cmd("item replace entity Speaker armor.head from entity Speaker hotbar.0")
    # The ordinary mic occupies offhand, so the ticket is given into the empty hotbar.
    host.command("eventvoice mic on")
    harness.until(lambda: "Mic Speaker" in cmd("eventvoice status"), "staff presenter")
    sound(host, {near: 4, far: 4})
    check_attenuation(far, ranges[-1])
    expect(f"{ranges[0]} blocks" in cmd("eventvoice range 1"), "staff tier command")
    sound(host, {near: 4})
    check_attenuation(near, ranges[0])
    cmd("execute in minecraft:the_nether run tp ListenerFar 0 80 0")
    sound(host, {near: 4})
    expect(
        "set to global" in cmd("eventvoice range global"),
        "staff explicit global setting",
    )
    sound(host, {near: 3, far: 3})
    cmd("microphonefixture outgoing")
    sound(host, {near: 3})
    cmd("microphonefixture none")
    cmd("eventvoice mute-all")
    sound(near, {})
    sound(host, {near: 3, far: 3})
    cmd("eventvoice mute Speaker")
    sound(host, {})
    cmd("eventvoice unmute Speaker")
    cmd("eventvoice deafen-all")
    sound(host, {})
    cmd("eventvoice undeafen ListenerFar")
    sound(host, {far: 3})
    cmd("eventvoice reset")
    cmd("item replace entity Speaker weapon.offhand with air")
    sound(host, {near: 2})
    print(
        "PASS staff local default, configurable range, explicit global, outgoing plugin veto, targeted and bulk moderation and reset",
        flush=True,
    )


if __name__ == "__main__":
    harness.run(cases)
