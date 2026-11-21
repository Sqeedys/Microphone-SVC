"""Build Microphone! against pinned Minecraft 26.3 and Simple Voice Chat dependencies."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import tempfile
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
BUNDLE_SHA1 = "33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c"
GAME_SHA256 = "a362163eec5d1612d520772bc16e5b39c09e3b234fdc045f56bf544284ee8ae6"
MAVEN = "https://maven.fabricmc.net/"
LOADER = "net/fabricmc/fabric-loader/0.20.1/fabric-loader-0.20.1.jar"
MIXIN = "net/fabricmc/sponge-mixin/0.17.5+mixin.0.8.7/sponge-mixin-0.17.5+mixin.0.8.7.jar"
LOADER_FILES = {
    "loader.jar": (LOADER, "d0c5a082e07410aaff9de7ab196aa6b4236002870f9ba3ad5c1260fcce5110ad"),
    "sponge-mixin.jar": (MIXIN, "03f0065023bfc3045d71bfb99872451d9ad5a0800a5a0dac34ab8c7d5c134f5d"),
}
MOD_FILES = {
    "api.jar": (MAVEN + "net/fabricmc/fabric-api/fabric-api/0.165.2+26.3/fabric-api-0.165.2+26.3.jar",
                "2612a078a5daa06d9a14cabad40fe66de36b8cb22ee8cdf346d5d9894478c392"),
    "voicechat.jar": ("https://cdn.modrinth.com/data/9eGKb6K1/versions/x9jLR94Q/voicechat-fabric-2.6.27%2B26.3.jar",
                      "9e1c93144a3f7498e546102f6786aea6e4992866c4c5d21bd2e0edb702399153"),
}
API_MODULES = ("fabric-api-base-", "fabric-command-api-v2-", "fabric-lifecycle-events-v1-",
               "fabric-permission-api-v1-", "fabric-resource-conditions-api-v1-")
ASM_HASHES = {
    "asm": "ed825d10ab1399c8c0cb669e688cf0c8c82629b4c8399b58352b68e92ca10fcb",
    "asm-analysis": "dede75a21306b65974ecd8f87114ff6970f09fb794157a4ca09ab25c888c2bfc",
    "asm-commons": "6d0abefb7cbf972ea16edb37ec14835372505063a45f976ab7ea889ed9497895",
    "asm-tree": "3dfb0d5b6a106cd40b5b250e39935fbf2f927f4477546a5369a3ac609cf0506b",
    "asm-util": "1bb99d091fba2597dc6d51193e9bbcf0d8447e7ed96bd8f0198b18152f09655c",
}


def digest(file: Path, algorithm: str = "sha256") -> str:
    with file.open("rb") as stream:
        return hashlib.file_digest(stream, algorithm).hexdigest()


def download(path: Path, url: str, expected: str, algorithm: str = "sha256") -> Path:
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=path.parent) as temporary:
            staging = Path(temporary) / path.name
            request = urllib.request.Request(url, headers={"User-Agent": "Microphone-SVC-Build/0.2.0"})
            with urllib.request.urlopen(request, timeout=60) as response, staging.open("wb") as stream:
                while chunk := response.read(1024 * 1024):
                    stream.write(chunk)
            if digest(staging, algorithm) != expected:
                raise ValueError(f"Download hash mismatch for {path.name}")
            staging.replace(path)
    if digest(path, algorithm) != expected:
        raise ValueError(f"Cached hash mismatch for {path.name}")
    return path


def prepare_runtime(cache: Path) -> Path:
    bundle = download(cache / "minecraft-26.3-server.jar",
                      f"https://piston-data.mojang.com/v1/objects/{BUNDLE_SHA1}/server.jar", BUNDLE_SHA1, "sha1")
    runtime_dir = cache / "runtime-26.3"
    hashes = {}
    with zipfile.ZipFile(bundle) as archive:
        for group in ("versions", "libraries"):
            entries = archive.read(f"META-INF/{group}.list").decode().splitlines()
            if group == "versions" and len(entries) != 1:
                raise ValueError("Expected one game jar")
            for number, row in enumerate(entries):
                expected, _, member = row.split("\t")
                relative = "game.jar" if group == "versions" else f"lib/{number:04d}.jar"
                target = runtime_dir / relative
                content = archive.read(f"META-INF/{group}/{member}")
                if hashlib.sha256(content).hexdigest() != expected:
                    raise ValueError(f"Server bundle hash mismatch for {member}")
                target.parent.mkdir(parents=True, exist_ok=True)
                if not target.exists():
                    target.write_bytes(content)
                hashes[relative] = expected
    (runtime_dir / "runtime.json").write_text(
        json.dumps({"minecraft_version": "26.3", "artifacts": hashes}, indent=2), encoding="utf-8")
    return runtime_dir


def classpath(runtime: Path, cache: Path) -> str:
    manifest = json.loads((runtime / "runtime.json").read_text(encoding="utf-8-sig"))
    game = runtime / "game.jar"
    jars = sorted((runtime / "lib").glob("*.jar"))
    if manifest["minecraft_version"] != "26.3" or digest(game) != GAME_SHA256 or not jars:
        raise ValueError("Expected the exact Minecraft 26.3 server and libraries")
    declared_libraries = {name for name in manifest["artifacts"] if name.startswith("lib/")}
    if declared_libraries != {jar.relative_to(runtime).as_posix() for jar in jars}:
        raise ValueError("Library inventory does not match the runtime manifest")
    for jar in jars:
        if digest(jar) != manifest["artifacts"][jar.relative_to(runtime).as_posix()]:
            raise ValueError(f"Runtime library hash mismatch for {jar.name}")
    dependencies = dict(LOADER_FILES)
    for artifact, expected in ASM_HASHES.items():
        dependencies[f"{artifact}.jar"] = (f"org/ow2/asm/{artifact}/9.10.1/{artifact}-9.10.1.jar", expected)
    loader_files = [download(cache / name, MAVEN + path, expected)
                    for name, (path, expected) in dependencies.items()]
    api, voicechat = (download(cache / name, url, expected) for name, (url, expected) in MOD_FILES.items())
    modules = []
    with zipfile.ZipFile(api) as jar:
        for member in jar.namelist():
            name = Path(member).name
            if member.startswith("META-INF/jars/") and name.startswith(API_MODULES):
                destination = cache / name
                content = jar.read(member)
                if not destination.exists() or destination.read_bytes() != content:
                    destination.write_bytes(content)
                modules.append(destination)
    if len(modules) != len(API_MODULES):
        raise ValueError("Fabric API archive has an unexpected nested module inventory")
    return os.pathsep.join(map(str, [game, *jars, *loader_files, *modules, voicechat]))


def compile_java(javac: Path, cp: str, output: Path, sources: Path) -> None:
    output.mkdir()
    commands = output.with_suffix(".args")
    args = ["--release", "25", "-encoding", "UTF-8", "-proc:none", "-g:source,lines",
            "-cp", cp, "-d", str(output), *map(str, sorted(sources.rglob("*.java")))]
    commands.write_text("\n".join(json.dumps(arg.replace("\\", "/"), ensure_ascii=False) for arg in args), encoding="utf-8")
    subprocess.run([str(javac), "@" + str(commands)], check=True)


def package(classes: Path, resources: Path, output: Path, include_license: bool = False) -> None:
    entries = [(file.relative_to(base).as_posix(), file)
               for base in (classes, resources)
               for file in sorted(base.rglob("*")) if file.is_file()]
    if include_license:
        entries.append(("LICENSE", ROOT / "LICENSE"))
    with tempfile.TemporaryDirectory(dir=output.parent) as temp:
        staged = Path(temp) / output.name
        with zipfile.ZipFile(staged, "w") as archive:
            for name, file in sorted(entries):
                info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o100644 << 16
                data = file.read_bytes()
                if file.suffix == ".json" or name == "LICENSE":
                    data = data.replace(b"\r\n", b"\n")
                archive.writestr(info, data)
        staged.replace(output)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, default=os.getenv("JAVA_HOME"),
                        help="JDK 25 path (defaults to JAVA_HOME)")
    parser.add_argument("--runtime", type=Path, help="reuse a prepared Minecraft 26.3 runtime")
    parser.add_argument("--cache", type=Path, default=ROOT / "local/cache")
    parser.add_argument("--fixture", action="store_true", help="also build the loopback-only test fixture")
    args = parser.parse_args()
    suffix = ".exe" if os.name == "nt" else ""
    bin_dir = args.java_home.resolve() / "bin" if args.java_home else Path()
    java = bin_dir / ("java" + suffix)
    javac = bin_dir / ("javac" + suffix)
    version = subprocess.run([str(javac), "-version"], check=True, capture_output=True, text=True)
    if not re.match(r"javac 25(?:[.\s]|$)", version.stdout + version.stderr):
        parser.error("JDK 25 is required")
    cache = args.cache.resolve()
    runtime = args.runtime.resolve() if args.runtime else prepare_runtime(cache)
    cp = classpath(runtime, cache)
    resources = ROOT / "src/main/resources"
    metadata = json.loads((resources / "fabric.mod.json").read_text(encoding="utf-8"))
    output = ROOT / "build/libs" / f"{metadata['id']}-{metadata['version']}.jar"
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output.parent) as directory:
        work = Path(directory)
        classes = work / "main"
        tests = work / "tests"
        compile_java(javac, cp, classes, ROOT / "src/main/java")
        test_classpath = os.pathsep.join((str(classes), cp))
        compile_java(javac, test_classpath, tests, ROOT / "src/test/java")
        for test in ("VoiceRulesTest", "MicrophoneConfigTest"):
            subprocess.run([str(java), "-cp", os.pathsep.join((str(tests), test_classpath)),
                            f"dev.sqeedys.microphone.{test}"], check=True)
        package(classes, resources, output, include_license=True)
        if args.fixture:
            fixture = ROOT / "tests/fixture"
            fixture_classes = work / "fixture"
            compile_java(javac, cp, fixture_classes, fixture / "java")
            harness = cache / "microphone-fixture-only.jar"
            package(fixture_classes, fixture / "resources", harness)
            print(f"Loopback fixture: {harness}")
    print(f"{output}\nSHA-256 {digest(output)}", flush=True)


if __name__ == "__main__":
    main()
