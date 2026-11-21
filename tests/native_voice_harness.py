"""Launch a disposable Minecraft 26.3 Fabric test server bound to loopback."""

import argparse
import hashlib
import json
import os
import shutil
import socket
import subprocess
import tempfile
import threading
import time
from contextlib import ExitStack
from itertools import count
from pathlib import Path

from native_voice_client import Participant

ROOT = Path(__file__).resolve().parents[1]
LOCAL = ROOT / "local/voice-tests"


def expect(condition, message):
    if not condition:
        raise AssertionError(message)


def port(socket_type):
    with socket.socket(socket.AF_INET, socket_type) as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def until(check, text, seconds=20):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if check():
            return
        time.sleep(0.05)
    raise TimeoutError(text)


class Console:
    def __init__(self, command, directory):
        self.process = subprocess.Popen(
            command,
            cwd=directory,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            encoding="utf-8",
            errors="replace",
        )
        self.lines = []
        self.n = count()

        def read():
            with (directory / "test-console.log").open("w", encoding="utf-8") as log:
                for line in self.process.stdout:
                    self.lines.append(line)
                    log.write(line)

        self.reader = threading.Thread(target=read, daemon=True)
        self.reader.start()

    def close(self):
        if self.process.poll() is None:
            self.process.stdin.write("stop\n")
            self.process.stdin.flush()
            try:
                self.process.wait(timeout=25)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait()
        self.reader.join(5)

    def run(self, cmd):
        start = len(self.lines)
        marker = "event-test-done-" + str(next(self.n))
        self.process.stdin.write(cmd + "\nsay " + marker + "\n")
        self.process.stdin.flush()
        until(
            lambda: any(marker in line for line in self.lines[start:]),
            "Console command " + cmd,
        )
        return "".join(line for line in self.lines[start:] if marker not in line)


def run(cases):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--java-home", required=True, type=Path)
    p.add_argument("--cache", type=Path, default=ROOT / "local/cache")
    p.add_argument(
        "--runtime", type=Path, help="reuse a prepared Minecraft 26.3 runtime"
    )
    p.add_argument(
        "--mode", choices=("default", "custom", "staff-only"), default="default"
    )
    p.add_argument("--accept-eula", required=True, action="store_true")
    args = p.parse_args()
    LOCAL.mkdir(parents=True, exist_ok=True)
    fixture = Path(tempfile.mkdtemp(prefix=args.mode + "-", dir=LOCAL))
    print("Fixture", fixture, flush=True)
    mods = fixture / "mods"
    mods.mkdir()
    deps = args.cache.resolve()
    voicechat = deps / "voicechat.jar"
    files = [
        deps / "api.jar",
        voicechat,
        ROOT / "build/libs/microphone-0.2.0.jar",
        deps / "microphone-fixture-only.jar",
    ]
    for file in files:
        if not file.is_file():
            p.error(f"Missing {file}; run build.py --fixture with the same cache first")
    voicehash = hashlib.sha256(voicechat.read_bytes()).hexdigest()
    expect(
        voicehash == "9e1c93144a3f7498e546102f6786aea6e4992866c4c5d21bd2e0edb702399153",
        "Voice Chat version",
    )
    for f in files:
        shutil.copyfile(f, mods / f.name)
        print(f.name, hashlib.sha256(f.read_bytes()).hexdigest(), flush=True)
    runtime = args.runtime.resolve() if args.runtime else deps / "runtime-26.3"
    game = runtime / "game.jar"
    cp = [
        game,
        *sorted((runtime / "lib").glob("*.jar")),
        deps / "loader.jar",
        deps / "sponge-mixin.jar",
        *deps.glob("asm*.jar"),
    ]
    java = (args.java_home / "bin/java").resolve()
    command = [
        str(java),
        "-Xmx1100m",
        "-XX:ActiveProcessorCount=3",
        "-Dfabric.gameJarPath=" + str(game),
        "-cp",
        os.pathsep.join(map(str, cp)),
        "net.fabricmc.loader.impl.launch.knot.KnotServer",
        "nogui",
    ]
    tcp = port(socket.SOCK_STREAM)
    udp = port(socket.SOCK_DGRAM)
    (fixture / "eula.txt").write_text("eula=true\n")
    (fixture / "server.properties").write_text(
        f"server-ip=127.0.0.1\nserver-port={tcp}\nonline-mode=false\nwhite-list=false\n"
        "enforce-secure-profile=false\nnetwork-compression-threshold=-1\n"
        "level-type=minecraft:flat\nlevel-seed=27156\ngenerate-structures=false\n"
        'generator-settings={"biome":"minecraft:plains","layers":[{"block":"minecraft:stone","height":4}]}\n'
        "view-distance=2\nsimulation-distance=2\nallow-flight=true\n"
        "spawn-protection=0\npause-when-empty-seconds=0\n"
    )
    vcdir = fixture / "config/voicechat"
    vcdir.mkdir(parents=True)
    mode = args.mode
    config = {
        "custom": {"craftingEnabled": False, "ranges": [72, 136, 200, 280]},
        "staff-only": {"handheldEnabled": False},
    }.get(mode)
    if config:
        (fixture / "config/microphone.json").write_text(json.dumps(config))
    (vcdir / "voicechat-server.properties").write_text(
        f"port={udp}\nbind_address=127.0.0.1\nallow_recording=false\n"
    )
    with ExitStack() as scope:
        con = Console(command, fixture)
        scope.callback(con.close)
        until(
            lambda: any("For help, type" in line for line in con.lines),
            "server start",
            120,
        )
        time.sleep(1.0)
        players = []
        for name in ("Speaker", "ListenerNear", "ListenerFar"):
            client = Participant(tcp, name)
            scope.callback(client.close)
            client.wait_ready()
            players.append(client)
        speaker, near, _ = players
        cmd = con.run
        cmd("difficulty peaceful")
        cmd("gamemode creative @a")
        cmd("tp Speaker 0 70 0")
        cmd("tp ListenerNear 3 70 0")
        cmd("tp ListenerFar 192 70 0")
        time.sleep(0.75)
        numbers = count(100)

        def sound(sender, eligible, whisper=False):
            seq = next(numbers)
            sender.speak(seq, whisper)
            time.sleep(0.4)
            for listener in players:
                if listener.error:
                    raise listener.error
                received = [r for r in listener.received if r[-1] == seq]
                role = eligible.get(listener)
                if role is None:
                    expect(
                        not received,
                        f"unexpected sound: {listener.name}, sequence {seq}",
                    )
                else:
                    expect(
                        len(received) == 1,
                        f"{listener.name}: {len(received)} sound packets, expected one; seq={seq}",
                    )
                    kind, channel, identity, frame, _ = received[0]
                    expect(
                        (kind, channel, identity, frame)
                        == (role, sender.uid, sender.uid, bytes.fromhex("f8fffe")),
                        f"Unexpected sound type/identity/payload for {listener.name}; sequence {seq}",
                    )

        sound(speaker, {near: 2})
        print(
            "PASS native proximity and sender identity with three connected voice clients",
            flush=True,
        )
        cases(cmd, players, sound, mode)
    expect(con.process.returncode == 0, "clean shutdown")
    print("PASS clean shutdown", flush=True)
