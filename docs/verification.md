# Microphone! 0.2.0 verification

The build and client/server tests used Minecraft Java 26.3, Java 25, Fabric Loader 0.20.1, Fabric API 0.165.2+26.3 and Simple Voice Chat Fabric 2.6.27+26.3. The client and server evidence below comes from the original `microphone-0.2.0.jar` with SHA-256 `49d7e9a148ca6ea348e93b30f50ceed12f77e5f1ec91de964c827df0bdb41680`.

| Check | Evidence |
|---|---|
| Build | Pinned dependencies were downloaded into a clean cache. Java tests cover configuration validation and moderation rules. |
| Crafting | A dedicated-server fixture loaded the real recipe manager, supplied the nine slots of a crafting table and assembled `microphone:microphone`. Disabling crafting or handheld use removed the shaped recipe, ingredient match and unlock advancement. |
| Handheld controls | Three synthetic players used Minecraft item-use and sneak packets. The test checked all four tiers, an offhand microphone, putting the item away, ordinary proximity, whispering, and dimension and group boundaries. |
| Voice delivery | Authenticated Simple Voice Chat UDP sessions sent Opus silence frames. The test checked recipient selection just inside and outside each radius, packet attenuation distance, speaker position and identity, muted/deafened players and upstream speak/listen permissions. |
| Server config | A second configuration used 72, 136, 200 and 280 blocks with crafting off. In staff-only mode an existing ordinary microphone could no longer amplify voice, while limited and global staff announcements still worked. |
| Staff controls | New sessions started at tier 4. The host selected a lower tier and explicitly selected global for a cross-dimension announcement. An outgoing plugin veto, bulk controls, individual exceptions and reset were exercised. |
| Client graphics | A Minecraft 26.3 client joined a disposable Fabric server. The server put the real registered item in the player's hand. The client rendered the texture through its item renderer, in the inventory and in first person. [Inventory evidence](images/ingame-microphone.png) · [First-person evidence](images/in-hand.png) |

The UDP tests measure packet delivery and range. Audible quality through headsets, external voice proxies, and other servers' full modpacks were outside the test setup. One automated Windows graphics run exited with native error `0xC0000005`. The same original JAR subsequently completed the client render check with Simple Voice Chat's optional native libraries both enabled and disabled. A control with Simple Voice Chat alone also succeeded. The isolated harness did not identify the cause of the intermittent exit.

## Project icon refresh

The [current download checksum](../downloads/SHA256SUMS.txt) records the refreshed JAR’s SHA-256, `27d0b1e2f3c7f771ed73bc1af6497786f1cf162feae938078672e75fd20de445`. Two builds with Temurin 25.0.5+8 and Python 3.13.14 passed the Java suites and produced byte-identical JARs. Comparing the original and refreshed JARs found one changed entry, `assets/microphone/icon.png`. All 23 other entries had identical contents, including the Java classes and in-game item texture. The native voice and client render results above cover the original JAR.

## Repeat the voice tests

Use Python 3.11 or later with `cryptography` and JDK 25. From the project root:

```sh
python -m pip install cryptography
python build.py --java-home "/path/to/jdk-25" --fixture
python tests/native_voice.py --java-home "/path/to/jdk-25" --accept-eula --mode default
python tests/native_voice.py --java-home "/path/to/jdk-25" --accept-eula --mode custom
python tests/native_voice.py --java-home "/path/to/jdk-25" --accept-eula --mode staff-only
```

The EULA flag records your acceptance for the disposable Minecraft test servers. They bind to loopback and use synthetic participants. The test-only permission provider and its commands are packaged separately from the production mod.
