package dev.sqeedys.microphone.tests;

import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.StaticSoundPacketEvent;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.permission.v1.PermissionContext;
import net.fabricmc.fabric.api.permission.v1.PermissionEvents;
import net.fabricmc.fabric.api.permission.v1.PermissionNode;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

public final class VoiceTestFixture implements DedicatedServerModInitializer, VoicechatPlugin {
    private static final UUID FAR_RECEIVER = UUID.nameUUIDFromBytes("OfflinePlayer:ListenerFar".getBytes(StandardCharsets.UTF_8));
    private static volatile String closedGate = "none";

    @Override
    public void onInitializeServer() {
        PermissionEvents.ON_REQUEST.register(new PermissionEvents.OnRequest() {
            @Override
            public <T> T handlePermissionRequest(PermissionContext context, PermissionNode<T> permission) {
                if (!(context.get(PermissionContext.ENTITY) instanceof ServerPlayer player)) {
                    return null;
                }
                String name = player.getGameProfile().name();
                return switch (permission.key().toString()) {
                    case "microphone:present" ->
                            name.equals("Speaker") ? permission.cast(!closedGate.equals("present")) : null;
                    case "microphone:moderate" ->
                            name.equals("ListenerNear") ? permission.cast(true) : null;
                    case "voicechat:speak" ->
                            closedGate.equals("speak") && name.equals("Speaker") ? permission.cast(false) : null;
                    case "voicechat:listen" ->
                            closedGate.equals("listen") && name.equals("ListenerFar") ? permission.cast(false) : null;
                    default -> null;
                };
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> {
            var root = Commands.literal("microphonefixture").requires(Commands.hasPermission(Commands.LEVEL_OWNERS));
            for (String gate : new String[]{"none", "present", "speak", "listen", "outgoing"}) {
                root.then(Commands.literal(gate).executes(command -> {
                    closedGate = gate;
                    command.getSource().sendSuccess(() -> Component.literal("Voice fixture gate " + gate), false);
                    return 1;
                }));
            }
            root.then(Commands.literal("recipe").executes(command -> {
                var source = command.getSource();
                var manager = source.getServer().getRecipeManager();
                var key = ResourceKey.create(Registries.RECIPE, Identifier.fromNamespaceAndPath("microphone", "microphone"));
                var copper = new ItemStack(Items.COPPER_INGOT);
                var iron = new ItemStack(Items.IRON_INGOT);
                var redstone = new ItemStack(Items.REDSTONE);
                var empty = ItemStack.EMPTY;
                var input = CraftingInput.of(3, 3, List.of(empty, copper, empty, iron, redstone, iron, empty, iron, empty));
                var match = manager.getRecipeFor(RecipeType.CRAFTING, input, source.getServer().overworld());
                String item = match.map(result -> BuiltInRegistries.ITEM.getKey(result.value().assemble(input).getItem()).toString())
                        .orElse("none");
                var advancement = Identifier.fromNamespaceAndPath("microphone", "recipes/redstone/microphone");
                boolean unlock = source.getServer().getAdvancements().get(advancement) != null;
                boolean present = manager.byKey(key).isPresent();
                source.sendSuccess(() -> Component.literal("Recipe fixture: " + present + " " + item + " " + unlock), false);
                return 1;
            }));
            dispatcher.register(root);
        });
    }

    @Override
    public String getPluginId() {
        return "microphone_fixture";
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(StaticSoundPacketEvent.class, packet -> {
            if (closedGate.equals("outgoing") && packet.getSource().equals("microphone:broadcast")
                    && packet.getReceiverConnection().getPlayer().getUuid().equals(FAR_RECEIVER)) {
                packet.cancel();
            }
        });
    }
}
