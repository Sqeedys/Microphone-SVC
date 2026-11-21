"""Minimal Minecraft 26.3 offline players with Fabric registry sync and encrypted Simple Voice Chat packets."""

import io
import os
import socket
import struct
import threading
import time
import uuid

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM


def varint(number):
    encoded = bytearray()
    while number >= 128:
        encoded.append((number & 127) | 128)
        number >>= 7
    encoded.append(number)
    return bytes(encoded)


def read_number(stream):
    number = 0
    for shift in range(0, 35, 7):
        raw = stream.read(1)
        if not raw:
            raise EOFError()
        number |= (raw[0] & 127) << shift
        if raw[0] < 128:
            return number
    raise ValueError("Invalid VarInt")


def string(value):
    data = value.encode("utf-8")
    return varint(len(data)) + data


class Participant:
    """Synthetic player on the fixture's loopback server with its own voice session."""

    def __init__(self, port, name):
        self.name = name
        self.tcp = socket.create_connection(("127.0.0.1", port), timeout=8)
        self.tcp.settimeout(30)
        self.udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.udp.bind(("127.0.0.1", 0))
        self.ready = threading.Event()
        self.auth = threading.Event()
        self.lock = threading.Lock()
        self.received = []
        self.positional = {}
        self.error = None
        self.stopping = False
        self.voicethread = None
        self.position = None
        self.send(
            0, varint(777) + string("localhost") + struct.pack(">H", port) + varint(2)
        )
        self.send(0, string(name) + uuid.uuid5(uuid.NAMESPACE_DNS, name).bytes)
        self.thread = threading.Thread(target=self.listen, daemon=True)
        self.thread.start()

    def send(self, packet, payload=b""):
        data = varint(packet) + payload
        with self.lock:
            self.tcp.sendall(varint(len(data)) + data)

    def payload(self, channel, body):
        self.send(22, string(channel) + body)

    def listen(self):
        try:
            with self.tcp.makefile("rb") as stream:
                phase = "login"
                while not self.stopping:
                    size = read_number(stream)
                    if not 0 < size <= 2**24:
                        raise ValueError("Invalid game packet size")
                    data = io.BytesIO(stream.read(size))
                    packet = read_number(data)
                    if phase == "login":
                        if packet == 2:
                            self.send(3)
                            phase = "config"
                        elif packet == 4:
                            self.send(2, varint(read_number(data)) + b"\0")
                        else:
                            raise RuntimeError(f"Unexpected login packet {packet}")
                    elif phase == "config":
                        if packet == 15:
                            self.send(7, varint(0))
                        elif packet == 1:
                            channel = data.read(read_number(data)).decode()
                            if channel == "minecraft:register":
                                self.send(
                                    2,
                                    string("minecraft:register")
                                    + b"fabric:registry/sync",
                                )
                            elif channel == "fabric:registry/sync":
                                if b"microphone" not in data.read():
                                    raise RuntimeError(
                                        "Registry sync omitted the microphone"
                                    )
                                self.send(2, string("fabric:registry/sync/complete"))
                        elif packet == 3:
                            self.send(3)
                            phase = "play"
                        elif packet in (4, 5):
                            self.send(packet, data.read())
                        elif packet == 20:
                            self.send(9)
                        elif packet == 2:
                            raise RuntimeError("Disconnected in configuration")
                    elif packet == 45:
                        self.send(28, data.read())
                    elif packet == 73:
                        teleport = read_number(data)
                        pos = data.read(24)
                        data.read(24)
                        angles = data.read(8)
                        self.send(0, varint(teleport) + pos + angles)
                        self.send(44)
                        self.position = struct.unpack(">ddd", pos)
                        if not self.ready.is_set():
                            self.payload(
                                "minecraft:register",
                                b"voicechat:secret\x00voicechat:state\x00voicechat:states",
                            )
                            self.payload(
                                "voicechat:request_secret", struct.pack(">i", 20)
                            )
                            self.ready.set()
                    elif packet == 24:
                        channel = data.read(read_number(data)).decode()
                        if channel == "voicechat:secret" and self.voicethread is None:
                            key = data.read(16)
                            self.port = struct.unpack(">i", data.read(4))[0]
                            self.uid = data.read(16)
                            self.cipher = AESGCM(key)
                            self.voicethread = threading.Thread(
                                target=self.listen_voice, args=(key,), daemon=True
                            )
                            self.voicethread.start()
        except (EOFError, OSError, RuntimeError, ValueError, struct.error) as err:
            if not self.stopping:
                self.error = RuntimeError(
                    f"{self.name}: game phase={phase}, last packet={locals().get('packet')}, failure={err!r}"
                )

    def use_item(self, sequence, hand=0, sneak=False):
        self.send(43, bytes([0x20 if sneak else 0]))  # ServerboundPlayerInputPacket
        self.send(67, varint(hand) + varint(sequence) + struct.pack(">ff", 0, 0))
        self.send(43, bytes([0]))
        self.send(13)  # ServerboundClientTickEndPacket

    def command(self, line):
        self.send(7, string(line))

    def speak(self, sequence, whisper=False):
        # Standard Opus silence frame; no microphones, samples or recordings needed.
        self.send_voice(b"\x01\x03\xf8\xff\xfe" + struct.pack(">q?", sequence, whisper))

    def send_voice(self, data):
        iv = os.urandom(12)
        encoded = iv + self.cipher.encrypt(iv, data, None)
        self.udp.sendto(
            b"\xff" + self.uid + varint(len(encoded)) + encoded,
            ("127.0.0.1", self.port),
        )

    def listen_voice(self, key):
        try:
            self.udp.settimeout(0.3)
            self.send_voice(b"\x05" + self.uid + key)
            while not self.stopping:
                try:
                    raw, address = self.udp.recvfrom(8192)
                except TimeoutError:
                    continue
                if address != ("127.0.0.1", self.port):
                    continue
                frame = io.BytesIO(raw)
                if frame.read(1) != b"\xff":
                    raise ValueError("Invalid loopback voice frame")
                enc = frame.read(read_number(frame))
                decoded = self.cipher.decrypt(enc[:12], enc[12:], None)
                kind = decoded[0]
                if kind == 6:
                    self.send_voice(b"\x09")
                elif kind == 10:
                    self.payload("voicechat:update_state", b"\0")
                    self.auth.set()
                elif kind == 8:
                    self.send_voice(b"\x08")
                elif kind in (2, 3, 4):
                    body = io.BytesIO(decoded[1:])
                    channel = body.read(16)
                    sender = body.read(16)
                    if kind == 4:
                        position = struct.unpack(">ddd", body.read(24))
                    content = body.read(read_number(body))
                    sequence = struct.unpack(">q", body.read(8))[0]
                    if kind == 4:
                        distance = struct.unpack(">f", body.read(4))[0]
                        self.positional[sequence] = (position, distance)
                    self.received.append((kind, channel, sender, content, sequence))
        except (EOFError, InvalidTag, OSError, ValueError, struct.error) as err:
            if not self.stopping:
                self.error = err

    def wait_ready(self):
        deadline = time.monotonic() + 14
        while not self.auth.wait(0.05):
            if self.error:
                raise self.error
            if time.monotonic() >= deadline:
                raise TimeoutError(
                    f"{self.name} voice login (game={self.ready.is_set()}, udp={self.voicethread is not None})"
                )

    def close(self):
        self.stopping = True
        try:
            self.tcp.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.tcp.close()
        self.udp.close()
        self.thread.join(timeout=2)
        if self.voicethread:
            self.voicethread.join(timeout=2)
