package com.aibot.ai;

import com.aibot.config.BotConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.world.ServerWorld;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Persistent memory system for the AI bot.
 * <p>
 * Memories are stored per-world and per-player as JSON files:
 * {@code config/aibot/memory/{world_name}/{player_uuid}/{id}.json}
 * <p>
 * Features:
 * <ul>
 *   <li>World isolation — different saves never mix memories</li>
 *   <li>Player isolation — each player has their own memory set</li>
 *   <li>Persists across game restarts (JSON on disk)</li>
 *   <li>Keyword-based relevance matching (no extra API calls)</li>
 *   <li>Token-efficient compression for prompt injection</li>
 *   <li>Auto-merge old/low-importance memories to save space</li>
 * </ul>
 */
public class MemoryManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path MEMORY_ROOT = FabricLoader.getInstance().getConfigDir().resolve("aibot").resolve("memory");

    /** Memory entry stored as a JSON file on disk. */
    public static class Memory {
        public String id;
        public String content;
        public long timestamp;
        public int importance;       // 1–5, higher = more important
        public int recallCount;      // how many times this memory has been recalled
        public List<String> keywords;
        public String category;      // "preference", "fact", "location", "event", "command", "general"
        public List<String> linkedIds; // IDs of related memories for graph-like connections

        public Memory() {}

        public Memory(String id, String content, int importance, List<String> keywords, String category) {
            this.id = id;
            this.content = content;
            this.timestamp = System.currentTimeMillis();
            this.importance = Math.max(1, Math.min(5, importance));
            this.recallCount = 0;
            this.keywords = keywords;
            this.category = category != null ? category : "general";
            this.linkedIds = new ArrayList<>();
        }
    }

    // ==================== Core API ====================

    /**
     * Adds a new memory for the given world and player.
     * Auto-extracts keywords, guesses importance and category based on content.
     *
     * @param content   the fact/preference/event to remember
     * @param category  optional explicit category; if null or "general", auto-detected
     * @return the created Memory
     */
    public static Memory addMemory(ServerWorld world, UUID playerUuid, String content, String category) {
        String worldName = getWorldName(world);
        if (category == null || category.isEmpty() || category.equals("general")) {
            category = guessCategory(content);
        }
        int importance = guessImportance(content);
        List<String> keywords = extractKeywords(content);
        String id = "mem_" + UUID.randomUUID().toString().substring(0, 8);

        Memory mem = new Memory(id, content, importance, keywords, category);
        saveMemory(worldName, playerUuid, mem);

        // Auto-compress if too many memories
        autoCompress(worldName, playerUuid);

        return mem;
    }

    /**
     * Updates an existing memory's content, keywords, importance, and category.
     * If the memory doesn't exist, creates a new one.
     *
     * @return the updated Memory, or null if not found
     */
    public static Memory updateMemory(ServerWorld world, UUID playerUuid, String memoryId,
                                       String newContent, Integer newImportance, String newCategory) {
        String worldName = getWorldName(world);
        List<Memory> all = loadAllMemories(worldName, playerUuid);
        Memory target = all.stream().filter(m -> m.id.equals(memoryId)).findFirst().orElse(null);
        if (target == null) return null;

        target.content = newContent;
        target.timestamp = System.currentTimeMillis();
        if (newImportance != null) {
            target.importance = Math.max(1, Math.min(5, newImportance));
        } else {
            target.importance = guessImportance(newContent);
        }
        target.keywords = extractKeywords(newContent);
        if (newCategory != null && !newCategory.isEmpty()) {
            target.category = newCategory;
        } else {
            target.category = guessCategory(newContent);
        }

        saveMemory(worldName, playerUuid, target);
        return target;
    }

    /**
     * Searches memories by keyword or content substring.
     *
     * @param query      search term (matched against keywords and content)
     * @param category   optional category filter; null or empty = any
     * @param maxResults max results to return
     * @return list of matching memories, sorted by relevance + recency
     */
    public static List<Memory> searchMemories(ServerWorld world, UUID playerUuid,
                                               String query, String category, int maxResults) {
        String worldName = getWorldName(world);
        List<Memory> all = loadAllMemories(worldName, playerUuid);
        if (all.isEmpty()) return List.of();

        String queryLower = query.toLowerCase();
        List<AbstractMap.SimpleEntry<Memory, Double>> scored = new ArrayList<>();

        for (Memory m : all) {
            if (category != null && !category.isEmpty() && !m.category.equalsIgnoreCase(category)) {
                continue;
            }
            double score = 0;
            // Exact keyword match
            for (String kw : m.keywords) {
                if (kw.toLowerCase().contains(queryLower) || queryLower.contains(kw.toLowerCase())) {
                    score += 5;
                }
            }
            // Content substring match
            if (m.content.toLowerCase().contains(queryLower)) {
                score += 3;
            }
            // ID match
            if (m.id.equalsIgnoreCase(queryLower)) {
                score += 20;
            }
            if (score > 0) {
                scored.add(new AbstractMap.SimpleEntry<>(m, score + relevanceScore(m, query)));
            }
        }

        scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return scored.stream()
            .limit(Math.max(1, maxResults))
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }

    /**
     * "Refreshes" a memory — bumps its timestamp and increments recall count.
     * Call this when a memory is recalled in context to keep it "alive."
     */
    public static void refreshMemory(ServerWorld world, UUID playerUuid, String memoryId) {
        String worldName = getWorldName(world);
        List<Memory> all = loadAllMemories(worldName, playerUuid);
        Memory target = all.stream().filter(m -> m.id.equals(memoryId)).findFirst().orElse(null);
        if (target == null) return;
        target.timestamp = System.currentTimeMillis();
        target.recallCount++;
        saveMemory(worldName, playerUuid, target);
    }

    /**
     * Links two memories together. Bidirectional by default.
     * Useful for connecting related facts (e.g., "home location" ↔ "home chest coords").
     */
    public static void linkMemories(ServerWorld world, UUID playerUuid,
                                     String memoryId1, String memoryId2) {
        String worldName = getWorldName(world);
        List<Memory> all = loadAllMemories(worldName, playerUuid);
        Memory m1 = all.stream().filter(m -> m.id.equals(memoryId1)).findFirst().orElse(null);
        Memory m2 = all.stream().filter(m -> m.id.equals(memoryId2)).findFirst().orElse(null);
        if (m1 == null || m2 == null) return;

        if (m1.linkedIds == null) m1.linkedIds = new ArrayList<>();
        if (m2.linkedIds == null) m2.linkedIds = new ArrayList<>();
        if (!m1.linkedIds.contains(memoryId2)) m1.linkedIds.add(memoryId2);
        if (!m2.linkedIds.contains(memoryId1)) m2.linkedIds.add(memoryId1);

        saveMemory(worldName, playerUuid, m1);
        saveMemory(worldName, playerUuid, m2);
    }

    /**
     * Removes a link between two memories.
     */
    public static void unlinkMemories(ServerWorld world, UUID playerUuid,
                                       String memoryId1, String memoryId2) {
        String worldName = getWorldName(world);
        List<Memory> all = loadAllMemories(worldName, playerUuid);
        Memory m1 = all.stream().filter(m -> m.id.equals(memoryId1)).findFirst().orElse(null);
        Memory m2 = all.stream().filter(m -> m.id.equals(memoryId2)).findFirst().orElse(null);
        if (m1 != null && m1.linkedIds != null) { m1.linkedIds.remove(memoryId2); saveMemory(worldName, playerUuid, m1); }
        if (m2 != null && m2.linkedIds != null) { m2.linkedIds.remove(memoryId1); saveMemory(worldName, playerUuid, m2); }
    }

    /**
     * Returns compressed memory text suitable for injecting into the system prompt.
     * Only returns the top N most relevant memories, sorted and truncated.
     * Recalled memories are automatically refreshed (timestamp bumped).
     *
     * @param world       the current world
     * @param playerUuid  the player's UUID
     * @param context     current command/context for relevance matching
     * @param maxTokens   maximum tokens to spend on memories in the prompt (~500 recommended)
     * @return formatted memory string, or empty if no memories
     */
    public static String getCompressedMemoryText(ServerWorld world, UUID playerUuid, String context, int maxTokens) {
        BotConfig config = BotConfig.load();
        if (!config.isMemoryEnabled()) return "";

        String worldName = getWorldName(world);
        List<Memory> all = loadAllMemories(worldName, playerUuid);
        if (all.isEmpty()) return "";

        int maxMemories = config.getMemoriesPerPrompt();

        // Score and sort by relevance (includes linked-memory bonus)
        List<Memory> scored = all.stream()
            .map(m -> new AbstractMap.SimpleEntry<>(m, relevanceScore(m, context)))
            .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
            .limit(maxMemories)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());

        if (scored.isEmpty()) return "";

        // Build compressed text; refresh each recalled memory
        StringBuilder sb = new StringBuilder();
        sb.append("Player memories (things you should remember):\n");
        int tokenBudget = maxTokens;
        int charsPerToken = 4;

        for (Memory m : scored) {
            String entry = formatMemoryEntry(m);
            int entryTokens = entry.length() / charsPerToken;
            if (tokenBudget - entryTokens < 0) break;
            sb.append(entry);
            tokenBudget -= entryTokens;

            // Include linked memories as context hints
            if (m.linkedIds != null && !m.linkedIds.isEmpty() && tokenBudget > 20) {
                List<Memory> linked = all.stream()
                    .filter(lm -> m.linkedIds.contains(lm.id))
                    .limit(3)
                    .collect(Collectors.toList());
                for (Memory lm : linked) {
                    String linkedEntry = "  ↳ 相关：" + (lm.content.length() > 60 ? lm.content.substring(0, 60) + "…" : lm.content) + "\n";
                    int linkedTokens = linkedEntry.length() / charsPerToken;
                    if (tokenBudget - linkedTokens >= 0) {
                        sb.append(linkedEntry);
                        tokenBudget -= linkedTokens;
                    }
                }
            }

            // Refresh: bump timestamp and recall count
            m.timestamp = System.currentTimeMillis();
            m.recallCount++;
            saveMemory(worldName, playerUuid, m);
        }

        return sb.toString();
    }

    /**
     * Deletes a specific memory by ID.
     * @return true if deleted, false if not found
     */
    public static boolean deleteMemory(ServerWorld world, UUID playerUuid, String memoryId) {
        String worldName = getWorldName(world);
        Path dir = getPlayerDir(worldName, playerUuid);
        Path file = dir.resolve(memoryId + ".json");
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            System.err.println("[AIBot Memory] Failed to delete: " + e.getMessage());
            return false;
        }
    }

    /**
     * Lists all memory IDs and summaries for a player in the given world.
     * @return list of formatted memory entries with category, importance, and preview
     */
    public static List<String> listMemories(ServerWorld world, UUID playerUuid) {
        String worldName = getWorldName(world);
        List<Memory> all = loadAllMemories(worldName, playerUuid);
        List<String> result = new ArrayList<>();
        for (Memory m : all) {
            String preview = m.content.length() > 60 ? m.content.substring(0, 60) + "…" : m.content;
            String stars = "⭐".repeat(m.importance);
            String categoryColor = getCategoryColor(m.category);
            String linked = (m.linkedIds != null && !m.linkedIds.isEmpty()) ? " §d🔗" + m.linkedIds.size() : "";
            String recall = m.recallCount > 0 ? " §7(R" + m.recallCount + ")" : "";
            result.add(String.format("§d%s §8| %s[%s]§8 | %s%s%s%s",
                m.id, categoryColor, m.category, stars, preview, linked, recall));
        }
        return result;
    }

    private static String getCategoryColor(String category) {
        return switch (category) {
            case "preference" -> "§d";  // pink
            case "location" -> "§b";    // aqua
            case "event" -> "§e";       // yellow
            case "command" -> "§5";     // dark purple
            case "fact" -> "§a";        // green
            default -> "§7";            // gray
        };
    }

    /**
     * Clears all memories for a player in a world.
     */
    public static void clearAll(ServerWorld world, UUID playerUuid) {
        String worldName = getWorldName(world);
        Path dir = getPlayerDir(worldName, playerUuid);
        try {
            if (Files.exists(dir)) {
                try (var stream = Files.list(dir)) {
                    stream.forEach(f -> { try { Files.delete(f); } catch (IOException ignored) {} });
                }
            }
        } catch (IOException e) {
            System.err.println("[AIBot Memory] Failed to clear: " + e.getMessage());
        }
    }

    /**
     * Gets a single memory by its exact ID.
     * @return the Memory, or null if not found
     */
    public static Memory getMemoryById(ServerWorld world, UUID playerUuid, String memoryId) {
        String worldName = getWorldName(world);
        List<Memory> all = loadAllMemories(worldName, playerUuid);
        return all.stream().filter(m -> m.id.equals(memoryId)).findFirst().orElse(null);
    }

    // ==================== Internal: File I/O ====================

    private static void saveMemory(String worldName, UUID playerUuid, Memory mem) {
        Path dir = getPlayerDir(worldName, playerUuid);
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(mem.id + ".json");
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(mem, writer);
            }
        } catch (IOException e) {
            System.err.println("[AIBot Memory] Failed to save: " + e.getMessage());
        }
    }

    private static List<Memory> loadAllMemories(String worldName, UUID playerUuid) {
        List<Memory> result = new ArrayList<>();
        Path dir = getPlayerDir(worldName, playerUuid);
        if (!Files.exists(dir)) return result;

        try (var stream = Files.list(dir)) {
            stream.filter(f -> f.toString().endsWith(".json")).forEach(f -> {
                try (Reader reader = Files.newBufferedReader(f)) {
                    Memory mem = GSON.fromJson(reader, Memory.class);
                    if (mem != null) result.add(mem);
                } catch (IOException e) {
                    System.err.println("[AIBot Memory] Failed to load: " + f + " — " + e.getMessage());
                }
            });
        } catch (IOException e) {
            System.err.println("[AIBot Memory] Failed to list: " + e.getMessage());
        }

        // Sort newest first
        result.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
        return result;
    }

    private static Path getPlayerDir(String worldName, UUID playerUuid) {
        return MEMORY_ROOT.resolve(sanitize(worldName)).resolve(playerUuid.toString());
    }

    // ==================== Internal: World Name ====================

    /**
     * Gets the world save folder name (not the dimension key).
     * Two different save files will have different names, ensuring isolation.
     */
    private static String getWorldName(ServerWorld world) {
        try {
            return world.getServer().getSaveProperties().getLevelName();
        } catch (Exception e) {
            // Fallback to dimension key
            return world.getRegistryKey().getValue().toString();
        }
    }

    /** Sanitize a string for use as a directory name. */
    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9_\\-\\u4e00-\\u9fff]", "_");
    }

    // ==================== Internal: Keyword Extraction ====================

    /** Common stop words to filter out. */
    private static final Set<String> STOP_WORDS = Set.of(
        "的", "了", "是", "在", "我", "你", "他", "她", "它", "们", "这", "那", "不", "也", "就", "都",
        "要", "会", "和", "与", "或", "但", "而", "及", "把", "被", "让", "给", "向", "从", "到",
        "a", "an", "the", "is", "are", "was", "were", "be", "been", "being",
        "have", "has", "had", "do", "does", "did", "will", "would", "could", "should",
        "may", "might", "can", "shall", "to", "of", "in", "for", "on", "with",
        "at", "by", "from", "as", "into", "about", "like", "this", "that",
        "it", "its", "and", "or", "but", "not", "no", "so", "if", "then",
        "just", "very", "too", "really", "also", "only", "now", "here", "there",
        "嗯", "啊", "吧", "呢", "哦", "哈", "嘛", "呀"
    );

    /**
     * Extracts meaningful keywords from content using simple word splitting.
     * No AI calls — pure local processing.
     */
    static List<String> extractKeywords(String content) {
        if (content == null || content.isEmpty()) return List.of();

        // Split on any non-letter/non-digit character (works for Chinese + English)
        String[] tokens = content.toLowerCase().split("[^\\w\\u4e00-\\u9fff]+");
        List<String> keywords = new ArrayList<>();

        for (String token : tokens) {
            String trimmed = token.trim();
            if (trimmed.isEmpty() || trimmed.length() == 1) continue;
            if (STOP_WORDS.contains(trimmed)) continue;
            if (trimmed.matches("^[0-9.,]+$")) continue; // pure numbers
            keywords.add(trimmed);
        }

        // Deduplicate
        return keywords.stream().distinct().limit(10).collect(Collectors.toList());
    }

    // ==================== Internal: Importance Guessing ====================

    private static int guessImportance(String content) {
        String lower = content.toLowerCase();
        // High importance signals
        if (lower.contains("喜欢") || lower.contains("不喜欢") || lower.contains("prefer")
            || lower.contains("重要") || lower.contains("important")
            || lower.contains("家") || lower.contains("home")
            || lower.contains("密码") || lower.contains("password")) {
            return 4;
        }
        // Medium
        if (lower.contains("坐标") || lower.contains("位置") || lower.contains("location")
            || lower.contains("记得") || lower.contains("记住") || lower.contains("remember")
            || lower.contains("矿") || lower.contains("mine")) {
            return 3;
        }
        // Default
        return 2;
    }

    // ==================== Internal: Category Guessing ====================

    /**
     * Auto-detects the best category for a memory based on content signals.
     * Categories: "preference", "fact", "location", "event", "command", "general"
     */
    static String guessCategory(String content) {
        if (content == null || content.isEmpty()) return "general";
        String lower = content.toLowerCase();

        // Location signals
        if (lower.contains("坐标") || lower.contains("位置") || lower.contains("location")
            || lower.contains("at") && (lower.contains("x=") || lower.contains("x:"))
            || lower.contains("基地") || lower.contains("base")
            || lower.contains("家") && (lower.contains("在") || lower.contains("at") || lower.contains("位置"))) {
            return "location";
        }

        // Preference signals
        if (lower.contains("喜欢") || lower.contains("不喜欢") || lower.contains("prefer")
            || lower.contains("讨厌") || lower.contains("hate") || lower.contains("dislike")
            || lower.contains("想要") || lower.contains("want")
            || lower.contains("最爱") || lower.contains("favorite")
            || lower.contains("常用") || lower.contains("usually")) {
            return "preference";
        }

        // Event signals
        if (lower.contains("发生了") || lower.contains("happened")
            || lower.contains("刚刚") || lower.contains("just")
            || lower.contains("杀死") || lower.contains("killed")
            || lower.contains("找到") || lower.contains("found")
            || lower.contains("建造了") || lower.contains("built")
            || lower.contains("挖到") || lower.contains("mined")) {
            return "event";
        }

        // Command signals
        if (lower.contains("指令") || lower.contains("command")
            || lower.contains("/") || lower.contains("设置")
            || lower.contains("切换") || lower.contains("switch")) {
            return "command";
        }

        return "fact";
    }

    // ==================== Internal: Relevance Scoring ====================

    static double relevanceScore(Memory mem, String context) {
        double score = 0;

        if (context != null && !context.isEmpty()) {
            String ctxLower = context.toLowerCase();
            for (String kw : mem.keywords) {
                if (ctxLower.contains(kw.toLowerCase())) {
                    score += 10;
                }
            }
        }

        // Importance bonus
        score += mem.importance * 3;

        // Recency bonus (stronger decay curve)
        long age = System.currentTimeMillis() - mem.timestamp;
        if (age < 3600_000) score += 8;        // < 1 hour
        else if (age < 86400_000) score += 6;   // < 1 day
        else if (age < 604800_000) score += 4;  // < 1 week
        else if (age < 2_592_000_000L) score += 2; // < 1 month
        else score += 0;                         // old, just importance matters

        // Recall count bonus (frequently recalled = important)
        if (mem.recallCount > 10) score += 4;
        else if (mem.recallCount > 5) score += 2;
        else if (mem.recallCount > 0) score += 1;

        return score;
    }

    // ==================== Internal: Formatting ====================

    private static String formatMemoryEntry(Memory mem) {
        String importanceStars = "⭐".repeat(mem.importance);
        String truncated = mem.content.length() > 150 ? mem.content.substring(0, 150) + "…" : mem.content;
        return String.format("- [%s] %s %s\n", mem.category, importanceStars, truncated);
    }

    // ==================== Internal: Auto-Compression ====================

    /**
     * When a player exceeds maxMemories, merge old low-importance, rarely-recalled
     * memories of the same category into a single summary memory.
     */
    static void autoCompress(String worldName, UUID playerUuid) {
        BotConfig config = BotConfig.load();
        List<Memory> all = loadAllMemories(worldName, playerUuid);
        if (all.size() <= config.getMaxMemories()) return;

        // Score each memory by "compressability" (lower = more compressable)
        // Old, low importance, rarely recalled = most compressable
        List<Memory> compressable = all.stream()
            .sorted(Comparator.comparingDouble((Memory m) ->
                m.importance * 0.3  // lower importance → more compressable
                + Math.min(m.recallCount, 10) * 0.2  // rarely recalled → more compressable
                - (System.currentTimeMillis() - m.timestamp) / 86_400_000.0 * 0.1  // older → more compressable
            ))
            .collect(Collectors.toList());

        // Take the bottom 30% most compressable memories
        int toCompressCount = Math.max(2, all.size() / 3);
        List<Memory> toCompress = compressable.subList(0, Math.min(toCompressCount, compressable.size()));

        if (toCompress.size() < 2) return;

        // Group by category for coherent summaries
        Map<String, List<Memory>> groups = new LinkedHashMap<>();
        for (Memory m : toCompress) {
            groups.computeIfAbsent(m.category, k -> new ArrayList<>()).add(m);
        }

        for (var entry : groups.entrySet()) {
            List<Memory> group = entry.getValue();
            if (group.size() < 2) continue;

            // Merge into a summary
            String joined = group.stream()
                .map(m -> m.content)
                .collect(Collectors.joining("；"));

            String catLabel = switch (entry.getKey()) {
                case "preference" -> "过往偏好";
                case "location" -> "过往位置";
                case "event" -> "过往事件";
                case "command" -> "过往指令";
                case "fact" -> "过往事实";
                default -> "过往摘要";
            };
            String summary = catLabel + "：" + joined.substring(0, Math.min(200, joined.length()));

            // Collect linked IDs from all compressed memories
            Set<String> allLinks = new LinkedHashSet<>();
            for (Memory m : group) {
                try {
                    Path file = getPlayerDir(worldName, playerUuid).resolve(m.id + ".json");
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {}
                if (m.linkedIds != null) allLinks.addAll(m.linkedIds);
            }

            // Save merged memory
            Memory merged = new Memory(
                "mem_" + UUID.randomUUID().toString().substring(0, 8),
                summary, 2,
                extractKeywords(summary),
                entry.getKey()
            );
            merged.linkedIds = new ArrayList<>(allLinks);
            saveMemory(worldName, playerUuid, merged);
        }
    }
}
