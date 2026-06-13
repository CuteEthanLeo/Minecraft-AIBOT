package com.aibot;

import com.aibot.client.RobotScreen;
import com.aibot.entity.RobotEntity;
import com.aibot.entity.RobotModel;
import com.aibot.entity.RobotRenderer;
import com.aibot.entity.RobotScreenHandler;
import com.aibot.entity.RobotSpawnEggItem;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.gui.screen.ingame.HandledScreens;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.util.Identifier;

/**
 * Centralized registration helper for all mod content.
 */
public class Registry {

    // --- Identifiers ---
    public static final Identifier ROBOT_ID = Identifier.of("aibot", "robot");
    public static final Identifier ROBOT_SPAWN_EGG_ID = Identifier.of("aibot", "robot_spawn_egg");

    // --- Entity Type ---
    public static final EntityType<RobotEntity> ROBOT_ENTITY = FabricEntityTypeBuilder
        .<RobotEntity>create(SpawnGroup.MISC, RobotEntity::new)
        .dimensions(EntityDimensions.fixed(0.8F, 1.8F))
        .trackRangeChunks(8)
        .trackedUpdateRate(3)
        .build(RegistryKey.of(Registries.ENTITY_TYPE.getKey(), ROBOT_ID));

    // --- Items ---
    // In 1.21.2+, spawn egg is created with Settings.spawnEgg() and requires registryKey
    private static final RegistryKey<Item> ROBOT_SPAWN_EGG_KEY = RegistryKey.of(Registries.ITEM.getKey(), ROBOT_SPAWN_EGG_ID);
    public static final Item ROBOT_SPAWN_EGG = new RobotSpawnEggItem(
        new Item.Settings().registryKey(ROBOT_SPAWN_EGG_KEY).spawnEgg(ROBOT_ENTITY)
    );

    // --- Screen Handler ---
    public static final ScreenHandlerType<RobotScreenHandler> ROBOT_SCREEN_HANDLER =
        new ScreenHandlerType<>(
            (syncId, playerInventory) -> new RobotScreenHandler(syncId, playerInventory, new SimpleInventory(27), null),
            FeatureFlags.VANILLA_FEATURES
        );

    /**
     * Registers all server-side content.
     */
    public static void registerAll() {
        // Entity type
        net.minecraft.registry.Registry.register(Registries.ENTITY_TYPE, ROBOT_ID, ROBOT_ENTITY);

        // Entity attributes
        FabricDefaultAttributeRegistry.register(ROBOT_ENTITY, RobotEntity.createRobotAttributes());

        // Spawn egg item
        net.minecraft.registry.Registry.register(Registries.ITEM, ROBOT_SPAWN_EGG_ID, ROBOT_SPAWN_EGG);

        // Screen handler
        net.minecraft.registry.Registry.register(Registries.SCREEN_HANDLER, Identifier.of("aibot", "robot_inventory"), ROBOT_SCREEN_HANDLER);

        // Add spawn egg to spawn eggs item group
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.SPAWN_EGGS).register(entries -> {
            entries.add(ROBOT_SPAWN_EGG);
        });
    }

    /**
     * Registers all client-side content (renderers, model layers).
     */
    public static void registerClient() {
        // Entity renderer
        EntityRendererRegistry.register(ROBOT_ENTITY, RobotRenderer::new);

        // Model layer
        EntityModelLayerRegistry.registerModelLayer(RobotModel.LAYER, RobotModel::getTexturedModelData);

        // Robot inventory GUI screen
        HandledScreens.register(ROBOT_SCREEN_HANDLER, RobotScreen::new);
    }
}
