package dev.sqeedys.microphone;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

record MicrophoneConfig(boolean craftingEnabled, boolean handheldEnabled, List<Integer> ranges) {
    static final int TIER_COUNT = 4;
    private static final int MAX_RANGE = 1024;
    private static final MicrophoneConfig DEFAULT = new MicrophoneConfig(true, true, List.of(64, 128, 192, 256));
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static MicrophoneConfig config;

    MicrophoneConfig {
        ranges = List.copyOf(ranges);
        if (ranges.size() != TIER_COUNT) {
            throw new IllegalArgumentException("ranges must contain exactly four block distances");
        }
        int previous = 0;
        for (int range : ranges) {
            if (range <= previous || range > MAX_RANGE) {
                throw new IllegalArgumentException("ranges must increase strictly and stay between 1 and " + MAX_RANGE);
            }
            previous = range;
        }
    }

    static synchronized MicrophoneConfig get() {
        if (config == null) {
            config = load(FabricLoader.getInstance().getConfigDir().resolve("microphone.json"));
        }
        return config;
    }

    static MicrophoneConfig load(Path file) {
        try {
            if (Files.notExists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, JSON.toJson(DEFAULT) + "\n", StandardOpenOption.CREATE_NEW);
            }
            return parse(Files.readString(file));
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Invalid Microphone configuration at " + file + ": " + error.getMessage(), error);
        }
    }

    static MicrophoneConfig parse(String text) throws IOException {
        var reader = new JsonReader(new StringReader(text));
        reader.setStrictness(Strictness.STRICT);
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        if (reader.peek() != JsonToken.END_DOCUMENT) {
            throw new IllegalArgumentException("Unexpected content after configuration object");
        }
        List<Integer> ranges = DEFAULT.ranges;
        if (root.has("ranges")) {
            ranges = new ArrayList<>();
            for (JsonElement range : root.getAsJsonArray("ranges")) {
                if (!range.isJsonPrimitive() || !range.getAsJsonPrimitive().isNumber()) {
                    throw new IllegalArgumentException("ranges must contain integer block distances");
                }
                ranges.add(range.getAsBigDecimal().intValueExact());
            }
        }
        return new MicrophoneConfig(
                readFlag(root, "craftingEnabled", DEFAULT.craftingEnabled),
                readFlag(root, "handheldEnabled", DEFAULT.handheldEnabled), ranges);
    }

    private static boolean readFlag(JsonObject root, String name, boolean fallback) {
        if (!root.has(name)) {
            return fallback;
        }
        JsonElement flag = root.get(name);
        if (!flag.isJsonPrimitive() || !flag.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(name + " must be true or false");
        }
        return flag.getAsBoolean();
    }

    boolean recipeEnabled() {
        return craftingEnabled && handheldEnabled;
    }

    int blocks(int tier) {
        return ranges.get(tier);
    }
}
