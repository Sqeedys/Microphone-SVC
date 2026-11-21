package dev.sqeedys.microphone;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.permission.v1.PermissionContextOwner;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;

import java.util.UUID;

final class EventVoiceCommands {
    private static final Identifier MANAGE = permission("manage");
    private static final Identifier MODERATE = permission("moderate");
    private static final Identifier PRESENT = permission("present");

    static void register(CommandDispatcher<CommandSourceStack> dispatcher, Microphone voice) {
        var root = Commands.literal("eventvoice").requires(source ->
                permitted(source, MANAGE) || permitted(source, MODERATE) || permitted(source, PRESENT));
        root.then(Commands.literal("start").requires(source -> permitted(source, MANAGE))
                .executes(context -> voice.start()
                        ? success(context.getSource(), "Event voice session started.")
                        : error(context.getSource(), "An event voice session is already running.")));
        for (String command : new String[]{"end", "reset"}) {
            root.then(Commands.literal(command).requires(source -> permitted(source, MANAGE))
                    .executes(context -> {
                        voice.reset();
                        return success(context.getSource(), "Event voice session ended and its controls cleared.");
                    }));
        }
        root.then(Commands.literal("status").executes(context -> status(context.getSource(), voice))
                .then(Commands.argument("players", EntityArgument.players())
                        .requires(source -> permitted(source, MANAGE) || permitted(source, MODERATE))
                        .executes(context -> inSession(context.getSource(), voice, source -> {
                            for (ServerPlayer player : EntityArgument.getPlayers(context, "players")) {
                                VoiceRules rules = voice.rules();
                                UUID uuid = player.getUUID();
                                boolean armed = uuid.equals(voice.presenter());
                                success(source, player.getGameProfile().name()
                                        + " | Mic " + (armed ? "armed" : "off")
                                        + " | Ordinary voice " + (rules.muted(uuid, false) ? "muted" : "allowed")
                                        + (armed ? " | Broadcast " + (rules.muted(uuid, true) ? "muted" : "allowed") : "")
                                        + " | Incoming voice " + (rules.deafened(uuid) ? "deafened" : "allowed"));
                            }
                            return 1;
                        }))));
        var range = Commands.literal("range").requires(source -> permitted(source, MANAGE));
        for (int tier = 0; tier < MicrophoneConfig.TIER_COUNT; tier++) {
            int selected = tier;
            range.then(Commands.literal(Integer.toString(tier + 1))
                    .executes(context -> setReach(context.getSource(), voice, MicrophoneConfig.get().blocks(selected))));
        }
        range.then(Commands.literal("global").executes(context -> setReach(context.getSource(), voice, 0)));
        root.then(range);
        root.then(mic(voice));
        addModeration(root, voice, "mute", true, true);
        addModeration(root, voice, "unmute", true, false);
        addModeration(root, voice, "deafen", false, true);
        addModeration(root, voice, "undeafen", false, false);
        dispatcher.register(root);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> mic(Microphone voice) {
        return Commands.literal("mic").requires(source -> permitted(source, MANAGE) || permitted(source, PRESENT))
                .then(Commands.literal("give").requires(source -> permitted(source, MANAGE))
                        .executes(context -> give(context.getSource(), voice, context.getSource().getPlayer()))
                        .then(Commands.argument("player", EntityArgument.player()).executes(context ->
                                give(context.getSource(), voice, EntityArgument.getPlayer(context, "player")))))
                .then(Commands.literal("on").requires(source -> permitted(source, PRESENT))
                        .executes(context -> inSession(context.getSource(), voice, source -> {
                            ServerPlayer player = source.getPlayer();
                            if (player == null) {
                                return error(source, "Run this command as the presenter in game.");
                            }
                            String refusal = voice.arm(player);
                            return refusal == null
                                    ? success(source, "Event microphone armed. Announcement range: " + voice.reachLabel() + ".")
                                    : error(source, refusal);
                        })))
                .then(Commands.literal("off")
                        .executes(context -> inSession(context.getSource(), voice, source -> {
                            ServerPlayer player = source.getPlayer();
                            UUID activePresenter = voice.presenter();
                            if (activePresenter != null && !permitted(source, MANAGE)
                                    && (player == null || !player.getUUID().equals(activePresenter))) {
                                return error(source, "Only the active presenter or a manager can switch off this microphone.");
                            }
                            voice.disarm();
                            return success(source, "Event microphone switched off.");
                        })));
    }

    private static void addModeration(LiteralArgumentBuilder<CommandSourceStack> root, Microphone voice,
                                      String command, boolean mute, boolean value) {
        root.then(Commands.literal(command + "-all").requires(source -> permitted(source, MODERATE))
                .executes(context -> inSession(context.getSource(), voice, source -> {
                    voice.setRules(voice.rules().setAll(mute, value));
                    return success(source, "Event voice applied " + command + " to all players, including later joins.");
                })));
        root.then(Commands.literal(command).requires(source -> permitted(source, MODERATE))
                .then(Commands.argument("players", EntityArgument.players())
                        .executes(context -> inSession(context.getSource(), voice, source -> {
                            var players = EntityArgument.getPlayers(context, "players").stream()
                                    .map(ServerPlayer::getUUID)
                                    .toList();
                            voice.setRules(voice.rules().setPlayers(mute, value, players));
                            return success(source, "Event voice applied " + command + " to " + players.size() + " player(s).");
                        }))));
    }

    private static int give(CommandSourceStack source, Microphone voice, ServerPlayer target) throws CommandSyntaxException {
        return inSession(source, voice, ignored -> {
            if (target == null) {
                return error(source, "Specify the player who should receive the microphone.");
            }
            if (!canPresent(target)) {
                return error(source, "The selected player needs event voice presenter permission.");
            }
            if (!voice.giveMic(target)) {
                return error(source, "The selected player needs an empty inventory slot for the microphone.");
            }
            target.sendSystemMessage(Component.literal("Hold or wear your Event Microphone and run /eventvoice mic on to go live."));
            return success(source, "Event microphone given to " + target.getGameProfile().name() + ".");
        });
    }

    private static int setReach(CommandSourceStack source, Microphone voice, int reach)
            throws CommandSyntaxException {
        return inSession(source, voice, ignored -> {
            voice.setReach(reach);
            return success(source, "Announcement range set to " + voice.reachLabel() + ".");
        });
    }

    private static int status(CommandSourceStack source, Microphone voice) {
        if (!voice.active()) {
            return success(source, "Event voice is inactive.");
        }
        VoiceRules rules = voice.rules();
        UUID presenter = voice.presenter();
        String mic = "off";
        if (presenter != null) {
            ServerPlayer player = source.getServer().getPlayerList().getPlayer(presenter);
            mic = player == null ? "disconnecting" : player.getGameProfile().name();
        }
        return success(source, "Event voice is active | Mic " + mic
                + " | Announcement range " + voice.reachLabel()
                + " | Mute all " + onOff(rules.muteAll()) + " (" + rules.mutes().size() + " individual settings)"
                + " | Deafen all " + onOff(rules.deafenAll()) + " (" + rules.deafens().size() + " individual settings)");
    }

    private static String onOff(boolean enabled) {
        return enabled ? "on" : "off";
    }

    private static int inSession(CommandSourceStack source, Microphone voice,
                                 ThrowingCommand operation) throws CommandSyntaxException {
        return voice.active() ? operation.run(source) : error(source, "Start an event voice session first.");
    }

    @FunctionalInterface
    private interface ThrowingCommand {
        int run(CommandSourceStack source) throws CommandSyntaxException;
    }

    static boolean canPresent(ServerPlayer player) {
        return permitted(player, PRESENT);
    }

    private static boolean permitted(Object source, Identifier permission) {
        return ((PermissionContextOwner) source).checkPermission(permission, PermissionLevel.GAMEMASTERS);
    }

    private static Identifier permission(String name) {
        return Identifier.fromNamespaceAndPath("microphone", name);
    }

    private static int success(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static int error(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(message));
        return 0;
    }
}
