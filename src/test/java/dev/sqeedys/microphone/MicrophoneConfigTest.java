package dev.sqeedys.microphone;

import java.nio.file.Files;
import java.util.List;

public final class MicrophoneConfigTest {
    public static void main(String[] args) throws Exception {
        var config = MicrophoneConfig.parse("{}");
        check(config.recipeEnabled() && config.ranges().equals(List.of(64, 128, 192, 256)), "defaults");
        var custom = MicrophoneConfig.parse("""
                {"craftingEnabled":false,"ranges":[72,140,300,1024]}
                """);
        check(!custom.recipeEnabled() && custom.handheldEnabled() && custom.blocks(3) == 1024, "valid fixed block config");
        var disabled = MicrophoneConfig.parse("""
                {"handheldEnabled":false}
                """);
        check(disabled.craftingEnabled() && !disabled.handheldEnabled() && !disabled.recipeEnabled(),
                "disabled handheld suppresses built-in recipe");
        var folder = Files.createTempDirectory("microphone-config-test-");
        var file = folder.resolve("microphone.json");
        try {
            check(MicrophoneConfig.load(file).equals(config) && Files.isRegularFile(file), "write defaults");
            Files.writeString(file, "{\"craftingEnabled\":false}");
            check(!MicrophoneConfig.load(file).craftingEnabled() && Files.readString(file).contains("false"),
                    "read and preserve operator config");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(folder);
        }

        for (String invalid : List.of(
                "{\"ranges\":[1,2,3]}",
                "{\"ranges\":[1,2,3,4,5]}",
                "{\"ranges\":[1,2,2,4]}",
                "{\"ranges\":[0,2,3,4]}",
                "{\"ranges\":[1,2,3,1025]}",
                "{\"ranges\":[1,2,3,4.5]}",
                "{\"ranges\":[1,2,3,\"4\"]}",
                "{\"ranges\":null}",
                "{\"craftingEnabled\":\"false\"}",
                "{\"craftingEnabled\":null}",
                "{\"handheldEnabled\":0}",
                "{'craftingEnabled':false}",
                "{} {}")) {
            try {
                MicrophoneConfig.parse(invalid);
                throw new AssertionError("Accepted invalid config: " + invalid);
            } catch (Exception expected) {
                // A malformed configuration must never silently bypass disabled crafting.
            }
        }
        System.out.println("Microphone config tests passed.");
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }
}
