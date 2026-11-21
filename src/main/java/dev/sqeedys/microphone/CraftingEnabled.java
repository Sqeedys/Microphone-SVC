package dev.sqeedys.microphone;

import com.mojang.serialization.MapCodec;
import net.fabricmc.fabric.api.resource.conditions.v1.ResourceCondition;
import net.fabricmc.fabric.api.resource.conditions.v1.ResourceConditionType;
import net.minecraft.resources.RegistryOps;

enum CraftingEnabled implements ResourceCondition {
    INSTANCE;

    static final ResourceConditionType<CraftingEnabled> TYPE =
            ResourceConditionType.create(Microphone.id("crafting_enabled"), MapCodec.unit(INSTANCE));

    @Override
    public ResourceConditionType<CraftingEnabled> getType() {
        return TYPE;
    }

    @Override
    public boolean test(RegistryOps.RegistryInfoLookup registries) {
        return MicrophoneConfig.get().recipeEnabled();
    }
}
