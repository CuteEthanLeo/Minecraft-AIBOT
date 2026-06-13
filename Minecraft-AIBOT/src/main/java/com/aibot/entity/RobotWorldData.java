package com.aibot.entity;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * World-level robot tracking data stored as a JSON file per world.
 * Survives chunk unloads and server restarts.
 * <p>
 * Stored in the world save folder under {@code aibot/robot_data.json}.
 */
public class RobotWorldData {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** In-memory cache: world dimension key → RobotWorldData */
    private static final Map<String, RobotWorldData> CACHE = new ConcurrentHashMap<>();

    private String robotUuid = "";
    private double lastKnownX = 0;
    private double lastKnownY = 64;
    private double lastKnownZ = 0;
    private String lastKnownDim = "minecraft:overworld";

    // ==================== Singleton Factory ====================

    /**
     * Gets or creates the robot data for the given world.
     * Loads from disk if available, otherwise creates a new empty state.
     */
    public static RobotWorldData getOrCreate(ServerWorld world) {
        String dimKey = world.getRegistryKey().getValue().toString();
        return CACHE.computeIfAbsent(dimKey, k -> load(k, world));
    }

    private static RobotWorldData load(String dimKey, ServerWorld world) {
        Path file = getFilePath(world);
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file)) {
                RobotWorldData data = GSON.fromJson(reader, RobotWorldData.class);
                if (data != null) {
                    data.lastKnownDim = dimKey;
                    return data;
                }
            } catch (IOException e) {
                System.err.println("[AIBot] Failed to load robot world data: " + e.getMessage());
            }
        }
        RobotWorldData data = new RobotWorldData();
        data.lastKnownDim = dimKey;
        return data;
    }

    /**
     * Saves the current data to disk.
     */
    public void markDirty() {
        // Schedule a save (called from server thread)
        Path file = getFilePath(lastKnownDim);
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            System.err.println("[AIBot] Failed to save robot world data: " + e.getMessage());
        }
    }

    private static Path getFilePath(ServerWorld world) {
        return getFilePath(world.getRegistryKey().getValue().toString());
    }

    private static Path getFilePath(String dimKey) {
        // Save in the world directory: <world>/aibot/robot_data.json
        // Use FabricLoader game dir + saves + world name
        Path savesDir = FabricLoader.getInstance().getGameDir().resolve("saves");
        // Try to find the world directory; fall back to game dir
        return savesDir.resolve("aibot_robot_" + dimKey.replace(':', '_') + ".json");
    }

    // ==================== Accessors ====================

    /**
     * Updates the robot's last known position and UUID.
     * Called from RobotEntity.tick() every ~5 seconds.
     */
    public void updateRobot(UUID uuid, double x, double y, double z, String dimension) {
        this.robotUuid = uuid.toString();
        this.lastKnownX = x;
        this.lastKnownY = y;
        this.lastKnownZ = z;
        this.lastKnownDim = dimension;
    }

    /**
     * Returns the stored robot UUID, or null if no robot has been registered yet.
     */
    @Nullable
    public UUID getRobotUuid() {
        if (robotUuid == null || robotUuid.isEmpty()) return null;
        try {
            return UUID.fromString(robotUuid);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Returns the last known position of the robot, or null if never recorded.
     */
    @Nullable
    public BlockPos getLastKnownPos() {
        if (robotUuid == null || robotUuid.isEmpty()) return null;
        return new BlockPos((int) lastKnownX, (int) lastKnownY, (int) lastKnownZ);
    }

    /**
     * Returns the last known dimension of the robot (e.g. "minecraft:overworld").
     */
    public String getLastKnownDim() {
        return lastKnownDim;
    }

    /**
     * Clears the stored robot data (called when robot is removed).
     */
    public void clear() {
        this.robotUuid = "";
        this.lastKnownX = 0;
        this.lastKnownY = 64;
        this.lastKnownZ = 0;
        markDirty();
    }

    /**
     * Removes cached data for a world (cleanup).
     */
    public static void removeCache(String dimKey) {
        CACHE.remove(dimKey);
    }
}
