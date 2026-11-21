package dev.sqeedys.microphone;

import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.api.events.SoundPacketEvent;
import de.maxhenkel.voicechat.plugins.PluginManager;
import de.maxhenkel.voicechat.intercompatibility.CommonCompatibilityManager;
import de.maxhenkel.voicechat.permission.PermissionManager;
import de.maxhenkel.voicechat.voice.common.GroupSoundPacket;
import de.maxhenkel.voicechat.voice.common.LocationSoundPacket;
import de.maxhenkel.voicechat.voice.common.MicPacket;
import de.maxhenkel.voicechat.voice.common.PlayerState;
import de.maxhenkel.voicechat.voice.server.Server;
import de.maxhenkel.voicechat.voice.server.ServerPlayerManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.resource.conditions.v1.ResourceConditions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.equipment.Equippable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class Microphone implements ModInitializer {
    public static final String BROADCAST_SOURCE = "microphone:broadcast";
    private static final String VERIFIED_VOICE_VERSION = "2.6.27+26.3";
    private static final String TICKET_TAG = "microphone_mic";
    // At 20 ms per packet, this holds up to 240 ms of recent presenter audio during a stall.
    private static final int MAX_QUEUED_FRAMES = 12;
    private static final long MAX_FRAME_AGE_NS = 240_000_000L;
    private static final ResourceKey<Item> ITEM_KEY = ResourceKey.create(Registries.ITEM, id("microphone"));
    private static final Item ITEM = new MicrophoneItem(new Item.Properties().setId(ITEM_KEY).stacksTo(1));
    private static final ThreadLocal<Delivery> deliveringAs = new ThreadLocal<>();
    private static volatile Microphone instance;

    private volatile MinecraftServer server;
    private volatile Session session;
    private ThreadPoolExecutor delivery;
    // The game thread owns ItemStack references. The voice thread consumes immutable snapshots.
    private final HashMap<UUID, HeldMicrophone> heldMicrophones = new HashMap<>();
    private final ConcurrentHashMap<UUID, Amplification> amplifiers = new ConcurrentHashMap<>();

    private static final class Session {
        final HashMap<UUID, String> micTickets = new HashMap<>();
        final ArrayBlockingQueue<Frame> frames = new ArrayBlockingQueue<>(MAX_QUEUED_FRAMES);
        volatile VoiceRules rules = VoiceRules.OPEN;
        volatile Presenter presenter;
        volatile int reach = MicrophoneConfig.get().blocks(MicrophoneConfig.TIER_COUNT - 1);
    }

    private record HeldMicrophone(ServerPlayer player, ItemStack item) {}
    private record Amplification(ServerPlayer player, int range) {}
    private record Delivery(Session session, Presenter presenter, int reach) {}
    private record Presenter(UUID owner, String ticket) {}
    private record Frame(Presenter presenter, MicPacket packet, long receivedAt) {}

    static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("microphone", path);
    }

    @Override
    public void onInitialize() {
        String installedVoiceVersion = FabricLoader.getInstance().getModContainer("voicechat").orElseThrow()
                .getMetadata().getVersion().getFriendlyString();
        if (!VERIFIED_VOICE_VERSION.equals(installedVoiceVersion)) {
            throw new IllegalStateException("Microphone! requires Simple Voice Chat " + VERIFIED_VOICE_VERSION
                    + " since it hooks that version's native packet paths. Found " + installedVoiceVersion + ".");
        }
        Registry.register(BuiltInRegistries.ITEM, ITEM_KEY, ITEM);
        ResourceConditions.register(CraftingEnabled.TYPE);
        instance = this;
        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) ->
                EventVoiceCommands.register(dispatcher, this));
        ServerLifecycleEvents.SERVER_STARTING.register(starting -> {
            MicrophoneConfig.get();
            session = null;
            heldMicrophones.clear();
            amplifiers.clear();
            server = starting;
            delivery = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(2), task -> {
                var thread = new Thread(task, "Microphone Announcement Delivery");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.DiscardPolicy());
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(stopping -> {
            if (server == stopping) {
                session = null;
                heldMicrophones.clear();
                amplifiers.clear();
                server = null;
                delivery.shutdownNow();
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(ticking -> {
            verifyAmplifiers(ticking);
            deliver(ticking);
        });
    }

    private static Session running(Server voiceServer) {
        var addon = instance;
        return addon != null && addon.server == voiceServer.getServer() ? addon.session : null;
    }

    public static boolean suppressMic(Server voiceServer, UUID speaker, MicPacket packet) {
        Session session = running(voiceServer);
        if (session == null) {
            return false;
        }
        VoiceRules rules = session.rules;
        if (!rules.muted(speaker, false)) {
            return false;
        }
        if (rules.muted(speaker, true) || packet.isWhispering()) {
            return true;
        }
        Presenter presenter = session.presenter;
        if (presenter == null || !presenter.owner.equals(speaker)) {
            return true;
        }
        PlayerState state = voiceServer.getPlayerStateManager().getState(speaker);
        return state == null || state.hasGroup() || state.isDisabled() || state.isDisconnected();
    }

    public static boolean routeMic(Server voiceServer, ServerPlayer speaker, PlayerState state, MicPacket packet) {
        Session session = running(voiceServer);
        UUID uuid = speaker.getUUID();
        if (session != null) {
            Presenter presenter = session.presenter;
            if (presenter != null && presenter.owner.equals(uuid) && !state.hasGroup() && !packet.isWhispering()) {
                if (!state.isDisabled() && !state.isDisconnected() && !session.rules.muted(uuid, true)) {
                    session.frames.offer(new Frame(presenter, packet, System.nanoTime()));
                }
                return true;
            }
            if (session.rules.muted(uuid, false)) {
                return true;
            }
        }
        var addon = instance;
        if (addon == null || addon.server != voiceServer.getServer() || state.hasGroup() || packet.isWhispering()) {
            return false;
        }
        Amplification amplifier = addon.amplifiers.get(uuid);
        if (amplifier == null || amplifier.player != speaker || speaker.isSpectator() || !speaker.isAlive()) {
            return false;
        }
        if (state.isDisabled() || state.isDisconnected()) {
            return true;
        }

        // Plugins may shorten the chosen radius. Keep the configured radius as a hard upper bound.
        float distance = PluginManager.instance().getDistance(speaker, state, packet, amplifier.range);
        if (Float.isFinite(distance) && distance > 0F) {
            distance = Math.min(distance, amplifier.range);
            var position = speaker.getEyePosition();
            float reach = distance;
            var audience = ServerPlayerManager.getPlayersInRange(speaker.level(), position, reach + 2D,
                    receiver -> withinReach(speaker, receiver, reach)
                            && CommonCompatibilityManager.INSTANCE.canSee(receiver, speaker));
            voiceServer.broadcast(audience, locational(uuid, speaker, packet, reach), speaker, state, null,
                    SoundPacketEvent.SOURCE_PROXIMITY);
        }
        return true;
    }

    static void use(ServerPlayer player, ItemStack item, boolean selectingRange) {
        var addon = instance;
        if (addon == null || addon.server != player.level().getServer() || player.isSpectator()) {
            return;
        }
        if (!MicrophoneConfig.get().handheldEnabled()) {
            player.sendOverlayMessage(Component.translatable("message.microphone.handheld_disabled"));
            return;
        }
        UUID uuid = player.getUUID();
        HeldMicrophone current = addon.heldMicrophones.get(uuid);
        if (selectingRange) {
            MicrophoneItem.nextTier(item);
            int range = MicrophoneConfig.get().blocks(MicrophoneItem.tier(item));
            if (current != null && current.item == item) {
                addon.amplifiers.put(uuid, new Amplification(player, range));
            }
            player.sendOverlayMessage(Component.translatable("message.microphone.range", range));
            return;
        }
        if (current != null && current.item == item) {
            addon.heldMicrophones.remove(uuid);
            addon.amplifiers.remove(uuid);
            player.sendOverlayMessage(Component.translatable("message.microphone.off"));
            return;
        }
        Server voiceServer = getVoiceServer(addon.server);
        PlayerState state = voiceServer == null ? null : voiceServer.getPlayerStateManager().getState(uuid);
        if (voiceServer == null || !canSpeak(voiceServer, player, state)) {
            player.sendOverlayMessage(Component.translatable("message.microphone.voice_unavailable"));
            return;
        }
        if (state.hasGroup()) {
            player.sendOverlayMessage(Component.translatable("message.microphone.leave_group"));
            return;
        }
        int range = MicrophoneConfig.get().blocks(MicrophoneItem.tier(item));
        addon.heldMicrophones.put(uuid, new HeldMicrophone(player, item));
        addon.amplifiers.put(uuid, new Amplification(player, range));
        player.sendOverlayMessage(Component.translatable("message.microphone.on", range));
    }

    private void verifyAmplifiers(MinecraftServer server) {
        if (this.server != server || heldMicrophones.isEmpty()) {
            return;
        }
        Server voice = getVoiceServer(server);
        var entries = heldMicrophones.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            UUID uuid = entry.getKey();
            HeldMicrophone held = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            PlayerState state = voice == null ? null : voice.getPlayerStateManager().getState(uuid);
            if (player == held.player && player.isAlive() && !player.isSpectator() && !held.item.isEmpty()
                    && (player.getMainHandItem() == held.item || player.getOffhandItem() == held.item)
                    && voice != null && canSpeak(voice, player, state) && !state.hasGroup()) {
                continue;
            }
            entries.remove();
            amplifiers.remove(uuid);
            if (player == held.player) {
                player.sendOverlayMessage(Component.translatable("message.microphone.off"));
            }
        }
    }

    private static boolean withinReach(ServerPlayer sender, ServerPlayer receiver, float reach) {
        return sender != receiver && sender.level() == receiver.level()
                && sender.getEyePosition().distanceToSqr(receiver.getEyePosition()) <= reach * reach;
    }

    private static LocationSoundPacket locational(UUID uuid, ServerPlayer sender, MicPacket packet, float reach) {
        // Using the actual position keeps playback functional beyond the client's entity-tracking range.
        return new LocationSoundPacket(uuid, uuid, sender.getEyePosition(), packet.getData(),
                packet.getSequenceNumber(), reach, null);
    }

    public static boolean suppressSound(Server voiceServer, ServerPlayer sender, PlayerState senderState,
                                        ServerPlayer receiver, String source) {
        Session session = running(voiceServer);
        if (session == null) {
            return BROADCAST_SOURCE.equals(source);
        }
        VoiceRules rules = session.rules;
        if (rules.deafened(receiver.getUUID())) {
            return true;
        }
        Presenter presenter = session.presenter;
        boolean broadcast = BROADCAST_SOURCE.equals(source);
        if (broadcast) {
            Delivery current = deliveringAs.get();
            if (sender == null || presenter == null || current == null || current.session != session
                    || current.presenter != presenter || current.reach != session.reach
                    || !presenter.owner.equals(sender.getUUID()) || senderState == null || senderState.hasGroup()
                    || senderState.isDisabled() || senderState.isDisconnected()
                    || (current.reach != 0 && !withinReach(sender, receiver, current.reach))) {
                return true;
            }
        }
        return sender != null && rules.muted(sender.getUUID(), broadcast);
    }

    boolean start() {
        if (session != null) {
            return false;
        }
        session = new Session();
        return true;
    }

    String reachLabel() {
        int reach = session.reach;
        return reach == 0 ? "global" : reach + " blocks";
    }

    void setReach(int blocks) {
        session.reach = blocks;
        session.frames.clear();
    }

    void reset() {
        session = null;
    }

    boolean active() {
        return session != null;
    }

    VoiceRules rules() {
        return session.rules;
    }

    void setRules(VoiceRules rules) {
        session.rules = rules;
        Presenter presenter = session.presenter;
        if (presenter != null && rules.muted(presenter.owner, true)) {
            session.frames.clear();
        }
    }

    UUID presenter() {
        Presenter presenter = session.presenter;
        return presenter == null ? null : presenter.owner;
    }

    boolean giveMic(ServerPlayer target) {
        var inventory = target.getInventory();
        if (inventory.getFreeSlot() < 0) {
            return false;
        }
        String ticket = UUID.randomUUID().toString();
        var item = new ItemStack(ITEM);
        item.set(DataComponents.CUSTOM_NAME, Component.literal("Event Microphone"));
        item.set(DataComponents.MAX_STACK_SIZE, 1);
        item.set(DataComponents.EQUIPPABLE, Equippable.builder(EquipmentSlot.HEAD).build());
        var tag = new CompoundTag();
        tag.putString(TICKET_TAG, ticket);
        item.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        if (!inventory.add(item)) {
            return false;
        }
        if (target.getUUID().equals(presenter())) {
            disarm();
        }
        session.micTickets.put(target.getUUID(), ticket);
        return true;
    }

    String arm(ServerPlayer player) {
        if (!EventVoiceCommands.canPresent(player) || player.isSpectator()) {
            return "You need presenter permission and must be outside spectator mode to enable the event microphone.";
        }
        UUID presenter = presenter();
        if (presenter != null && !presenter.equals(player.getUUID())) {
            return "Another presenter has the event microphone. Ask a manager to switch it off first.";
        }
        String ticket = session.micTickets.get(player.getUUID());
        if (ticket == null || !hasMic(player, ticket)) {
            return "Hold your current Event Microphone in either hand or wear it in the head slot.";
        }
        Server voiceServer = getVoiceServer(server);
        PlayerState state = voiceServer == null ? null : voiceServer.getPlayerStateManager().getState(player.getUUID());
        if (state != null && state.hasGroup()) {
            return "Leave your voice group before enabling the event microphone.";
        }
        if (voiceServer == null || !canSpeak(voiceServer, player, state)) {
            return "Connect and enable Simple Voice Chat with permission to speak before enabling the microphone.";
        }
        disarm();
        session.presenter = new Presenter(player.getUUID(), ticket);
        return null;
    }

    void disarm() {
        session.presenter = null;
        session.frames.clear();
    }

    private static boolean hasMic(ServerPlayer player, String ticket) {
        return micMatches(player.getMainHandItem(), ticket)
                || micMatches(player.getOffhandItem(), ticket)
                || micMatches(player.getItemBySlot(EquipmentSlot.HEAD), ticket);
    }

    private static boolean micMatches(ItemStack item, String ticket) {
        if (!item.is(ITEM)) {
            return false;
        }
        CustomData data = item.get(DataComponents.CUSTOM_DATA);
        return data != null && ticket.equals(data.copyTag().getStringOr(TICKET_TAG, ""));
    }

    private static Server getVoiceServer(MinecraftServer server) {
        Server voiceServer = Voicechat.SERVER == null ? null : Voicechat.SERVER.getServer();
        return voiceServer != null && voiceServer.getServer() == server && !voiceServer.isClosed() ? voiceServer : null;
    }

    private static boolean canSpeak(Server voiceServer, ServerPlayer player, PlayerState state) {
        return state != null && !state.isDisconnected() && !state.isDisabled()
                && voiceServer.getConnection(player.getUUID()) != null
                && PermissionManager.INSTANCE.SPEAK_PERMISSION.hasPermission(player);
    }

    private void deliver(MinecraftServer server) {
        var current = session;
        if (this.server != server || current == null || current.presenter == null) {
            return;
        }
        Presenter presenter = current.presenter;
        ServerPlayer player = server.getPlayerList().getPlayer(presenter.owner);
        Server voiceServer = getVoiceServer(server);
        PlayerState state = voiceServer == null ? null : voiceServer.getPlayerStateManager().getState(presenter.owner);
        if (player == null || voiceServer == null || !canSpeak(voiceServer, player, state) || state.hasGroup()
                || !EventVoiceCommands.canPresent(player) || (!player.isAlive() || player.isSpectator()) || !hasMic(player, presenter.ticket)) {
            disarm();
            if (player != null) {
                player.sendOverlayMessage(Component.literal("Event microphone switched off."));
            }
            return;
        }
        if (current.frames.isEmpty()) {
            return;
        }
        var frames = new ArrayList<Frame>(MAX_QUEUED_FRAMES);
        current.frames.drainTo(frames, MAX_QUEUED_FRAMES);
        int reach = current.reach;
        var candidates = reach == 0 ? server.getPlayerList().getPlayers() : player.level().players();
        var audience = candidates.stream()
                .filter(receiver -> receiver != player && (reach == 0 || withinReach(player, receiver, reach))
                        && CommonCompatibilityManager.INSTANCE.canSee(receiver, player))
                .toList();
        delivery.execute(() -> broadcast(voiceServer, current, presenter, player, audience, frames, reach));
    }

    private void broadcast(Server voiceServer, Session current, Presenter presenter, ServerPlayer player,
                           List<ServerPlayer> audience, List<Frame> frames, int reach) {
        deliveringAs.set(new Delivery(current, presenter, reach));
        try {
            for (Frame frame : frames) {
                if (session != current || current.presenter != presenter || current.reach != reach || voiceServer.isClosed()
                        || current.rules.muted(presenter.owner, true)) {
                    return;
                }
                if (frame.presenter != presenter || System.nanoTime() - frame.receivedAt > MAX_FRAME_AGE_NS) {
                    continue;
                }
                PlayerState state = voiceServer.getPlayerStateManager().getState(presenter.owner);
                if (state == null || state.hasGroup() || state.isDisabled() || state.isDisconnected()) {
                    return;
                }
                MicPacket packet = frame.packet;
                var audio = reach == 0
                        ? new GroupSoundPacket(presenter.owner, presenter.owner, packet.getData(), packet.getSequenceNumber(), null)
                        : locational(presenter.owner, player, packet, reach);
                voiceServer.broadcast(audience, audio, player, state, null, BROADCAST_SOURCE);
            }
        } finally {
            deliveringAs.remove();
        }
    }
}
