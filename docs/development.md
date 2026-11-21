# Build from source

Use Python 3.11 or later and JDK 25. Run the build from the project directory.

```sh
python build.py --java-home "/path/to/jdk-25"
```

The first run downloads hash-checked build inputs into `local/cache/`, compiles with the Java 25 toolchain, runs the Java tests, and writes `build/libs/microphone-0.2.0.jar`. The mod JAR contains this project's classes, resources and MIT license. Install Simple Voice Chat and Fabric API separately.

The tested versions are Minecraft Java 26.3, Fabric Loader 0.20.1, Fabric API 0.165.2+26.3, and Simple Voice Chat Fabric 2.6.27+26.3. This upstream voice build is a beta. Microphone! uses its native data paths and checks its full version at startup. [Upstream release](https://modrinth.com/plugin/simple-voice-chat/version/x9jLR94Q)

See the [verification record](verification.md) for release checks and test scope.
