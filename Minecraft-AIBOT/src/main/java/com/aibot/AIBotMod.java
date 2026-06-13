package com.aibot;

import com.aibot.chat.ChatHandler;
import com.aibot.config.BotConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main mod entry point for the AI Robot Companion mod.
 * <p>
 * This mod adds an AI-powered robot entity to Minecraft that can:
 * <ul>
 *   <li>Follow the player as a companion</li>
 *   <li>Attack hostile mobs</li>
 *   <li>Mine blocks and collect items</li>
 *   <li>Place blocks and help build</li>
 *   <li>Respond to natural language commands via DeepSeek API</li>
 *   <li>Support both English and Chinese commands</li>
 * </ul>
 * <p>
 * To use: Place a spawn egg, right-click the robot to bond with it,
 * then type {@code !bot <command>} in chat to control it.
 * You must set your DeepSeek API key in {@code config/robot-ai.json}.
 */
public class AIBotMod implements ModInitializer, ClientModInitializer {

    public static final String MOD_ID = "aibot";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[AI Robot] Initializing AI Robot Companion mod...");

        // Load config (creates default if not present)
        BotConfig config = BotConfig.load();
        if (config.hasApiKey()) {
            LOGGER.info("[AI Robot] DeepSeek API key loaded successfully.");
        } else {
            LOGGER.warn("[AI Robot] No DeepSeek API key found! Set your key in config/robot-ai.json");
        }

        // Register entities, items, etc.
        Registry.registerAll();
        LOGGER.info("[AI Robot] Entities and items registered.");

        // Register chat handler
        ChatHandler.register();
        LOGGER.info("[AI Robot] Chat handler registered. Use '{} <command>' to control your robot.", config.getCommandPrefix());

        // Listen for config reload
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            BotConfig.reload();
            LOGGER.info("[AI Robot] Server started. AI Robot Companion is ready!");
        });

        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
            if (success) {
                BotConfig.reload();
                LOGGER.info("[AI Robot] Config reloaded.");
            }
        });

        LOGGER.info("[AI Robot] AI Robot Companion mod initialized successfully!");
    }

    @Override
    public void onInitializeClient() {
        LOGGER.info("[AI Robot] Initializing client-side renderers...");

        // Register entity renderers and model layers
        Registry.registerClient();

        LOGGER.info("[AI Robot] Client renderers registered.");
    }
}
