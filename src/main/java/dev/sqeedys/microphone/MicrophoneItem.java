package dev.sqeedys.microphone;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

final class MicrophoneItem extends Item {
    private static final String TIER_TAG = "microphone_range_tier";

    MicrophoneItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer) {
            Microphone.use(serverPlayer, player.getItemInHand(hand), player.isShiftKeyDown());
        }
        return InteractionResult.SUCCESS_SERVER;
    }

    static int tier(ItemStack stack) {
        int saved = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                .copyTag().getIntOr(TIER_TAG, 0);
        return saved >= 0 && saved < MicrophoneConfig.TIER_COUNT ? saved : 0;
    }

    static void nextTier(ItemStack stack) {
        int next = (tier(stack) + 1) % MicrophoneConfig.TIER_COUNT;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, data -> data.putInt(TIER_TAG, next));
    }
}
