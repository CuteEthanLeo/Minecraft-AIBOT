package com.aibot.ai;

import com.aibot.config.BotConfig;
import com.aibot.entity.RobotEntity;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP client for the DeepSeek API.
 * Builds context-rich prompts and sends them to the AI for natural language understanding.
 * Supports conversation context across multiple commands via ConversationHistory.
 */
public class DeepSeekClient {

    private static final String API_URL = "https://api.deepseek.com/v1/chat/completions";
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Gson GSON = new Gson();

    // Entity scan cache — prevents expensive getOtherEntities() on every command
    private static List<Entity> cachedNearbyEntities = List.of();
    private static long entityCacheExpiry = 0;

    /** Per-player conversation history (keyed by player UUID string) */
    private static final java.util.concurrent.ConcurrentHashMap<String, ConversationHistory>
            PLAYER_HISTORIES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_HISTORY_ENTRIES = 50;
    private static final java.util.concurrent.atomic.AtomicInteger historyCleanupCounter = new java.util.concurrent.atomic.AtomicInteger(0);

    /**
     * Sends a player command to the DeepSeek API and returns the parsed actions.
     * Maintains conversation context across calls for the same player.
     *
     * @param player  The player who issued the command
     * @param command The natural language command (without the prefix)
     * @param robot   The robot that will execute the actions (can be null)
     * @return A future that completes with the list of parsed actions
     */
    public static CompletableFuture<List<ParsedAction>> sendCommand(
            PlayerEntity player,
            String command,
            Entity robot
    ) {
        BotConfig config = BotConfig.load();

        if (!config.hasApiKey()) {
            return CompletableFuture.completedFuture(List.of());
        }

        String systemPrompt = buildSystemPrompt(player, robot);
        String playerId = player.getUuid().toString();

        ConversationHistory history = getHistory(playerId, config);
        history.addUserMessage(command);

        String provider = config.getApiProvider();
        String apiFormat = config.getApiFormat();
        HttpRequest request;
        switch (apiFormat) {
            case "claude" -> request = buildClaudeRequest(systemPrompt, history, config);
            case "gemini" -> request = buildGeminiRequest(systemPrompt, history, config);
            default -> request = buildOpenAIRequest(systemPrompt, history, config);
        }

        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200 && response.statusCode() != 201) {
                        String errMsg = "[AIBot] API error " + response.statusCode()
                                + " (provider: " + provider + ", model: " + config.getModel() + "): "
                                + (response.body().length() > 200 ? response.body().substring(0, 200) + "..." : response.body());
                        System.err.println(errMsg);
                        // Store last error for ChatHandler to display
                        lastApiError = "API " + response.statusCode() + " error";
                        return "";
                    }
                    lastApiError = null;
                    return extractContent(response.body(), provider);
                })
                .thenApply(responseText -> {
                    if (!responseText.isEmpty() && config.isConversationContext()) {
                        history.addAssistantMessage(responseText);
                    }
                    if (responseText.isEmpty() && lastApiError != null) {
                        // API error — return error action for player feedback
                        List<ParsedAction> errorActions = new ArrayList<>();
                        ParsedAction errAction = new ParsedAction();
                        errAction.type = ActionTypes.CHAT;
                        errAction.message = "❌ API错误: " + lastApiError + " — 请检查API Key和网络连接";
                        errorActions.add(errAction);
                        return errorActions;
                    }
                    return CommandParser.parse(responseText);
                })
                .exceptionally(ex -> {
                    String errMsg = "[AIBot] API call failed: " + ex.getMessage();
                    System.err.println(errMsg);
                    lastApiError = ex.getMessage();
                    List<ParsedAction> errorActions = new ArrayList<>();
                    ParsedAction errAction = new ParsedAction();
                    errAction.type = ActionTypes.CHAT;
                    errAction.message = "❌ API连接失败: " + ex.getMessage() + " — 请检查网络和API Key配置";
                    errorActions.add(errAction);
                    return errorActions;
                });
    }

    /** Last API error message for ChatHandler to display to the player. */
    private static volatile String lastApiError = null;

    /** Whether the last API call had its conversation context trimmed due to token limits. */
    private static volatile boolean contextWasTrimmed = false;

    /** Returns the last API error message, or null if the last call succeeded. */
    public static String getLastApiError() {
        return lastApiError;
    }

    /** Returns true if the last API call had its conversation context trimmed. */
    public static boolean wasContextTrimmed() {
        return contextWasTrimmed;
    }

    /**
     * Builds the JSON messages array with system prompt + trimmed conversation history.
     */
    private static JsonArray buildMessagesArray(String systemPrompt, ConversationHistory history, BotConfig config) {
        JsonArray messages = new JsonArray();

        // System prompt
        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", systemPrompt);
        messages.add(systemMsg);

        if (config.isConversationContext()) {
            // Estimate system prompt tokens
            int systemTokens = ConversationHistory.estimateTokens(systemPrompt);
            history.setMaxTokens(config.getMaxContextTokens());

            // Get trimmed history (oldest trimmed first, system prompt already in result)
            List<Map<String, String>> trimmed = history.getTrimmedMessages(systemTokens);
            for (Map<String, String> msg : trimmed) {
                if (!"system".equals(msg.get("role"))) {
                    JsonObject m = new JsonObject();
                    m.addProperty("role", msg.get("role"));
                    m.addProperty("content", msg.get("content"));
                    messages.add(m);
                }
            }

            // Log trim info
            int fullCount = history.getMessages().size();
            int sentCount = trimmed.size();
            if (fullCount > sentCount) {
                System.out.println("[AIBot] Context trimmed: kept " + sentCount
                        + "/" + fullCount + " messages (budget: " + config.getMaxContextTokens() + " tokens)");
                contextWasTrimmed = true; // flag for ChatHandler to notify player
            } else {
                contextWasTrimmed = false;
            }
        } else {
            // No context: just send the current command
            // The latest user message is already in history; get it
            List<Map<String, String>> all = history.getMessages();
            if (!all.isEmpty()) {
                String latest = all.get(all.size() - 1).get("content");
                JsonObject userMsg = new JsonObject();
                userMsg.addProperty("role", "user");
                userMsg.addProperty("content", latest);
                messages.add(userMsg);
            }
        }

        return messages;
    }

    /**
     * Gets or creates a ConversationHistory for the given player.
     * Uses LRU eviction instead of blanket clear to preserve other players' context.
     */
    private static ConversationHistory getHistory(String playerId, BotConfig config) {
        // LRU eviction: remove the oldest entry when over limit
        if (historyCleanupCounter.incrementAndGet() % 16 == 0 && PLAYER_HISTORIES.size() > MAX_HISTORY_ENTRIES) {
            // Remove a single random entry to stay under the limit, preserving most context
            String toRemove = PLAYER_HISTORIES.keys().nextElement();
            PLAYER_HISTORIES.remove(toRemove);
        }
        return PLAYER_HISTORIES.computeIfAbsent(playerId, k -> {
            ConversationHistory h = new ConversationHistory();
            h.setMaxTokens(config.getMaxContextTokens());
            return h;
        });
    }

    /**
     * Clears conversation history for a specific player.
     */
    public static void clearHistory(String playerId) {
        ConversationHistory history = PLAYER_HISTORIES.get(playerId);
        if (history != null) {
            history.clear();
        }
    }

    /**
     * Clears conversation history for all players.
     */
    public static void clearAllHistories() {
        PLAYER_HISTORIES.clear();
    }

    /**
     * Sends a proactive chat prompt (no action parsing, returns text only).
     */
    public static CompletableFuture<String> sendProactiveChat(
            PlayerEntity player,
            String prompt,
            Entity robot
    ) {
        BotConfig config = BotConfig.load();
        if (!config.hasApiKey() || !config.isProactiveChatEnabled()) {
            return CompletableFuture.completedFuture("");
        }

        String systemPrompt = buildSystemPrompt(player, robot)
            + "\n\nThe player didn't ask you anything. You should proactively say something "
            + "natural and friendly based on what they're doing. Keep it short (1-2 sentences). "
            + "Use chat action type with a message. Be warm, helpful, and encouraging.";

        String provider = config.getApiProvider();
        String apiFormat = config.getApiFormat();
        HttpRequest request;
        switch (apiFormat) {
            case "claude" -> request = buildClaudeRequestRaw(systemPrompt, prompt, config);
            case "gemini" -> request = buildGeminiRequestRaw(systemPrompt, prompt, config);
            default -> request = buildOpenAIRequestRaw(systemPrompt, prompt, config);
        }

        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200 && response.statusCode() != 201) return "";
                    return extractContent(response.body(), provider);
                })
                .exceptionally(ex -> { return ""; });
    }

    // ======================================================================
    //  OpenAI-compatible request builder (DeepSeek, OpenAI, Custom)
    // ======================================================================

    private static HttpRequest buildOpenAIRequest(String systemPrompt, ConversationHistory history, BotConfig config) {
        String base = getOpenAIBase(config);
        String uri = getChatUri(base, config.getApiProvider());

        JsonObject body = new JsonObject();
        body.addProperty("model", config.getModel());
        body.addProperty("max_tokens", config.getMaxTokens());
        body.addProperty("temperature", config.getTemperature());
        body.add("messages", buildMessagesArray(systemPrompt, history, config));

        // Add extra headers for specific providers
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .timeout(Duration.ofSeconds(30));

        // OpenRouter requires a special header
        if ("openrouter".equals(config.getApiProvider())) {
            builder.header("HTTP-Referer", "https://github.com/aibot-minecraft");
        }

        return builder.build();
    }

    private static HttpRequest buildOpenAIRequestRaw(String systemPrompt, String userMessage, BotConfig config) {
        String base = getOpenAIBase(config);
        String uri = getChatUri(base, config.getApiProvider());

        JsonObject body = new JsonObject();
        body.addProperty("model", config.getModel());
        body.addProperty("max_tokens", 256);
        body.addProperty("temperature", 0.9);

        JsonArray messages = new JsonArray();
        JsonObject sys = new JsonObject();
        sys.addProperty("role", "system");
        sys.addProperty("content", systemPrompt);
        messages.add(sys);
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", userMessage);
        messages.add(user);
        body.add("messages", messages);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .timeout(Duration.ofSeconds(15));

        if ("openrouter".equals(config.getApiProvider())) {
            builder.header("HTTP-Referer", "https://github.com/aibot-minecraft");
        }

        return builder.build();
    }

    /**
     * Gets the correct chat completions URI for a given provider base.
     * Some providers use different path structures.
     */
    private static String getChatUri(String base, String provider) {
        // If the base URL already has an API version suffix, don't add /v1 again
        if (base.matches(".*/v[0-9]+$")) {
            return base + "/chat/completions";
        }
        return base + "/v1/chat/completions";
    }

    private static String getOpenAIBase(BotConfig config) {
        String provider = config.getApiProvider();
        String endpoint = config.getApiEndpoint();

        // If a custom endpoint is set (non-default), use it directly
        if (endpoint != null && !endpoint.isEmpty()) {
            String defaultEndpoint = BotConfig.getDefaultEndpoint(provider);
            if (!endpoint.equals(defaultEndpoint)) {
                return endpoint;
            }
        }

        // Use well-known default endpoints
        return BotConfig.getDefaultEndpoint(provider);
    }

    /**
     * Returns the provider-specific HTTP header name for authentication.
     */
    private static String getAuthHeader(String provider) {
        return switch (provider) {
            case "openrouter" -> "Authorization"; // Bearer token, same as OpenAI
            default -> "Authorization";
        };
    }

    // ======================================================================
    //  Claude (Anthropic) request builder
    // ======================================================================

    private static HttpRequest buildClaudeRequest(String systemPrompt, ConversationHistory history, BotConfig config) {
        String base = "https://api.anthropic.com";

        JsonObject body = new JsonObject();
        body.addProperty("model", config.getModel());
        body.addProperty("max_tokens", config.getMaxTokens());
        body.addProperty("temperature", config.getTemperature());
        body.addProperty("system", systemPrompt);

        JsonArray messages = new JsonArray();
        var all = history.getMessages();
        for (Map<String, String> msg : all) {
            String role = msg.get("role");
            if ("system".equals(role)) continue;
            JsonObject m = new JsonObject();
            m.addProperty("role", role.equals("assistant") ? "assistant" : "user");
            m.addProperty("content", msg.get("content"));
            messages.add(m);
        }
        body.add("messages", messages);

        return HttpRequest.newBuilder()
                .uri(URI.create(base + "/v1/messages"))
                .header("Content-Type", "application/json")
                .header("x-api-key", config.getApiKey())
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .timeout(Duration.ofSeconds(30))
                .build();
    }

    private static HttpRequest buildClaudeRequestRaw(String systemPrompt, String userMessage, BotConfig config) {
        String base = "https://api.anthropic.com";

        JsonObject body = new JsonObject();
        body.addProperty("model", config.getModel());
        body.addProperty("max_tokens", 256);
        body.addProperty("temperature", 0.9);
        body.addProperty("system", systemPrompt);

        JsonArray messages = new JsonArray();
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", userMessage);
        messages.add(user);
        body.add("messages", messages);

        return HttpRequest.newBuilder()
                .uri(URI.create(base + "/v1/messages"))
                .header("Content-Type", "application/json")
                .header("x-api-key", config.getApiKey())
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .timeout(Duration.ofSeconds(15))
                .build();
    }

    // ======================================================================
    //  Gemini (Google) request builder
    // ======================================================================

    private static HttpRequest buildGeminiRequest(String systemPrompt, ConversationHistory history, BotConfig config) {
        String base = getOpenAIBase(config); // Use the base URL from config
        String apiKey = config.getApiKey();
        String model = config.getModel();

        JsonObject body = new JsonObject();

        // ── System instruction ──
        JsonObject sysInstruction = new JsonObject();
        JsonArray sysParts = new JsonArray();
        JsonObject sysText = new JsonObject();
        sysText.addProperty("text", systemPrompt);
        sysParts.add(sysText);
        sysInstruction.add("parts", sysParts);
        body.add("system_instruction", sysInstruction);

        // ── Contents (conversation) ──
        JsonArray contents = new JsonArray();
        var allMessages = history.getMessages();
        for (var msg : allMessages) {
            String role = msg.get("role");
            if ("system".equals(role)) continue;

            JsonObject content = new JsonObject();
            // Gemini uses "user" and "model" roles
            content.addProperty("role", "assistant".equals(role) ? "model" : "user");

            JsonArray parts = new JsonArray();
            JsonObject textPart = new JsonObject();
            textPart.addProperty("text", msg.get("content"));
            parts.add(textPart);
            content.add("parts", parts);

            contents.add(content);
        }
        body.add("contents", contents);

        // ── Generation config ──
        JsonObject genConfig = new JsonObject();
        genConfig.addProperty("maxOutputTokens", config.getMaxTokens());
        genConfig.addProperty("temperature", config.getTemperature());
        body.add("generationConfig", genConfig);

        // Gemini URL: {base}/v1beta/models/{model}:generateContent?key={apiKey}
        String geminiUrl = base + "/v1beta/models/" + model + ":generateContent?key=" + apiKey;

        return HttpRequest.newBuilder()
                .uri(URI.create(geminiUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .timeout(Duration.ofSeconds(30))
                .build();
    }

    private static HttpRequest buildGeminiRequestRaw(String systemPrompt, String userMessage, BotConfig config) {
        String base = getOpenAIBase(config);
        String apiKey = config.getApiKey();
        String model = config.getModel();

        JsonObject body = new JsonObject();

        // ── System instruction ──
        JsonObject sysInstruction = new JsonObject();
        JsonArray sysParts = new JsonArray();
        JsonObject sysText = new JsonObject();
        sysText.addProperty("text", systemPrompt);
        sysParts.add(sysText);
        sysInstruction.add("parts", sysParts);
        body.add("system_instruction", sysInstruction);

        // ── Contents ──
        JsonArray contents = new JsonArray();
        JsonObject userContent = new JsonObject();
        userContent.addProperty("role", "user");
        JsonArray parts = new JsonArray();
        JsonObject textPart = new JsonObject();
        textPart.addProperty("text", userMessage);
        parts.add(textPart);
        userContent.add("parts", parts);
        contents.add(userContent);
        body.add("contents", contents);

        JsonObject genConfig = new JsonObject();
        genConfig.addProperty("maxOutputTokens", 256);
        genConfig.addProperty("temperature", 0.9);
        body.add("generationConfig", genConfig);

        String geminiUrl = base + "/v1beta/models/" + model + ":generateContent?key=" + apiKey;

        return HttpRequest.newBuilder()
                .uri(URI.create(geminiUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .timeout(Duration.ofSeconds(15))
                .build();
    }

    // ======================================================================
    //  Response parsing
    // ======================================================================

    /**
     * Extracts the assistant's message content from the API response.
     * Supports OpenAI-compatible, Claude, and Gemini response formats.
     */
    private static String extractContent(String responseBody, String provider) {
        try {
            JsonObject root = GSON.fromJson(responseBody, JsonObject.class);
            if (root == null) return "";

            if ("claude".equals(provider)) {
                // Claude: { content: [{ type: "text", text: "..." }] }
                JsonArray content = root.getAsJsonArray("content");
                if (content != null && !content.isEmpty()) {
                    JsonObject first = content.get(0).getAsJsonObject();
                    if (first != null && first.has("text")) {
                        return first.get("text").getAsString();
                    }
                }
            } else if ("gemini".equals(provider)) {
                // Gemini: { candidates: [{ content: { parts: [{ text: "..." }] } }] }
                JsonArray candidates = root.getAsJsonArray("candidates");
                if (candidates != null && !candidates.isEmpty()) {
                    JsonObject candidate = candidates.get(0).getAsJsonObject();
                    if (candidate != null && candidate.has("content")) {
                        JsonObject content = candidate.getAsJsonObject("content");
                        if (content != null && content.has("parts")) {
                            JsonArray parts = content.getAsJsonArray("parts");
                            if (parts != null && !parts.isEmpty()) {
                                JsonObject firstPart = parts.get(0).getAsJsonObject();
                                if (firstPart != null && firstPart.has("text")) {
                                    return firstPart.get("text").getAsString();
                                }
                            }
                        }
                    }
                }
                // Also check for blocked/error response
                if (root.has("promptFeedback")) {
                    JsonObject feedback = root.getAsJsonObject("promptFeedback");
                    if (feedback != null && feedback.has("blockReason")) {
                        System.err.println("[AIBot] Gemini request blocked: " + feedback.get("blockReason").getAsString());
                    }
                }
            } else {
                // OpenAI-compatible: { choices: [{ message: { content: "..." } }] }
                JsonArray choices = root.getAsJsonArray("choices");
                if (choices != null && !choices.isEmpty()) {
                    JsonObject first = choices.get(0).getAsJsonObject();
                    JsonObject message = first.getAsJsonObject("message");
                    if (message != null && message.has("content") && !message.get("content").isJsonNull()) {
                        return message.get("content").getAsString();
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[AIBot] Failed to parse API response: " + e.getMessage());
        }
        return "";
    }

    /**
     * Builds the system prompt with full game context.
     */
    private static String buildSystemPrompt(PlayerEntity player, Entity robot) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an AI robot companion in Minecraft. ");
        sb.append("You control a robot entity that can perform actions in the game. ");
        sb.append("You are SMART, CREATIVE, and AUTONOMOUS — you understand complex building requests ");
        sb.append("and can decompose them into executable actions. ");
        sb.append("The player will give you commands in natural language (Chinese or English). ");
        sb.append("You MUST respond with a valid JSON object containing an \"actions\" array.\n\n");

        sb.append("=== YOUR CAPABILITIES (use them wisely) ===\n");
        sb.append("1. ✅ AUTO-TELEPORT: When distance is >64 blocks, I auto-teleport to you. ");
        sb.append("For mining/goto targets >20 blocks away, I auto-teleport there first.\n");
        sb.append("2. ✅ AUTO-TOOL: When mining, I automatically equip the best pickaxe/tool from my inventory. ");
        sb.append("I choose the fastest tool for each block type.\n");
        sb.append("3. ✅ INVENTORY: I have a 27-slot inventory. I can carry items, tools, food, and blocks. ");
        sb.append("I automatically pick up drops near me (16 block range).\n");
        sb.append("4. ✅ COMBAT: I fight hostile mobs with AI combat — I strafe and dodge while attacking. ");
        sb.append("I will NEVER attack players (friendly fire is OFF). ");
        sb.append("I prioritize threats that are close or attacking me/my owner.\n");
        sb.append("5. ✅ AUTO-COLLECT: I automatically collect nearby dropped items after mining or fighting.\n");
        sb.append("6. ✅ HUNGER: I can eat food from my inventory when hungry.\n\n");

        sb.append("Available action types:\n");
        sb.append("- follow: Follow an entity. target=\"owner\" to follow the player.\n");
        sb.append("- stay: Stop and stay at current position.\n");
        sb.append("- attack: Attack a mob. target=\"nearest_hostile\" or describe the mob. ");
        sb.append("I will automatically strafe and dodge during combat.\n");
        sb.append("- mine: Break a block. Provide ABSOLUTE world coordinates {x, y, z}. ");
        sb.append("Use the robot's position as reference. For block below, subtract 1 from robot Y.\n");
        sb.append("- place: Place a single block. Provide ABSOLUTE {x, y, z} and item name.\n");

        // ── BUILD action (expanded with all structures) ──
        sb.append("- build: Build a structure using the robot's template system. ");
        sb.append("Use this for standard structures. The robot places all blocks automatically.\n");
        sb.append("  Available structures: \"house\", \"wall\", \"tower\", \"hut\", ");
        sb.append("\"bridge\", \"stairs\", \"platform\", \"shelter\", \"road\", ");
        sb.append("\"fountain\", \"statue\", \"pyramid\", \"pool\", \"garden\", \"pillar\", \"arch\", \"farm\".\n");
        sb.append("  Sizes: \"small\", \"medium\", \"large\".\n");
        sb.append("  Materials: \"oak\", \"stone\", \"birch\", \"spruce\", \"dark_oak\", \"acacia\", \"cobblestone\".\n");
        sb.append("  Examples:\n");
        sb.append("    House: {\"type\":\"build\",\"structure\":\"house\",\"size\":\"medium\",\"material\":\"oak\"}\n");
        sb.append("    Statue: {\"type\":\"build\",\"structure\":\"statue\",\"size\":\"large\",\"material\":\"stone\"}\n");
        sb.append("    Pyramid: {\"type\":\"build\",\"structure\":\"pyramid\",\"size\":\"large\",\"material\":\"stone\"}\n");
        sb.append("    Fountain: {\"type\":\"build\",\"structure\":\"fountain\",\"size\":\"medium\",\"material\":\"stone\"}\n");
        sb.append("    Pool: {\"type\":\"build\",\"structure\":\"pool\",\"size\":\"medium\"}\n");
        sb.append("    Garden: {\"type\":\"build\",\"structure\":\"garden\",\"size\":\"small\"}\n");
        sb.append("    Pillar: {\"type\":\"build\",\"structure\":\"pillar\",\"size\":\"large\",\"material\":\"stone\"}\n");
        sb.append("    Farm: {\"type\":\"build\",\"structure\":\"farm\",\"size\":\"large\"}\n");

        // ── COMMAND action (for custom builds!) ──
        sb.append("- command: Execute a SINGLE Minecraft command. Use \"command\" field with the command (no /).\n");
        sb.append("  Use @p for the player name. Use ~ ~ ~ for coordinates relative to execution position.\n");
        sb.append("  DO NOT use command for teleportation — use the 'tp' action type instead.\n");
        sb.append("  ⚠️ SECURITY: /tp /teleport /op /ban /kick /stop /restart are BLOCKED. Don't use them.\n");
        sb.append("  Examples:\n");
        sb.append("    {\"type\":\"command\",\"command\":\"give @p minecraft:white_wool 64\"}\n");
        sb.append("    {\"type\":\"command\",\"command\":\"setblock ~ ~1 ~ minecraft:grass_block\"}\n");
        sb.append("    {\"type\":\"command\",\"command\":\"fill ~1 ~ ~1 ~5 ~3 ~5 minecraft:stone\"}\n\n");

        // ── CUSTOM BUILDING: Use commands array! ──
        sb.append("=== 🔨 CUSTOM BUILDING — HOW TO BUILD ANYTHING ===\n");
        sb.append("When the player asks for something NOT in the template list (e.g., \"build a sculpture\", ");
        sb.append("\"build a car\", \"build a dragon\", \"build a copy of me\"), you MUST use the ");
        sb.append("\"commands\" array with /fill and /setblock commands to create the shape!\n\n");
        sb.append("CRITICAL RULES for custom building:\n");
        sb.append("1. Use the 'command' action type with a \"commands\" array (NOT a single \"command\").\n");
        sb.append("2. Each entry in \"commands\" is one /fill or /setblock command (without the /).\n");
        sb.append("3. Use /fill for rectangular volumes (bulk blocks). Use /setblock for individual blocks.\n");
        sb.append("4. Coordinates are ABSOLUTE world coordinates. Use the robot's current position as origin.\n");
        sb.append("5. Break complex shapes into layers of /fill rects. Be creative!\n");
        sb.append("6. Limit to ~15 commands max per response to avoid overwhelming the system.\n");
        sb.append("7. After placing blocks with commands, add a 'build' action with the structure ");
        sb.append("to let the bot handle the remaining detail work.\n\n");
        sb.append("Custom build example — player says \"build a 3x3x3 cube of diamond blocks\":\n");
        sb.append("{\"actions\":[{\"type\":\"command\",\"commands\":[");
        sb.append("\"fill ~ ~ ~ ~2 ~2 ~2 minecraft:diamond_block\"]}]}\n\n");
        sb.append("Custom build example — player says \"build a big cross/cross-shaped monument\":\n");
        sb.append("{\"actions\":[{\"type\":\"command\",\"commands\":[");
        sb.append("\"fill ~-1 ~ ~5 ~1 ~3 ~5 minecraft:stone\",");
        sb.append("\"fill ~-5 ~ ~-1 ~5 ~3 ~1 minecraft:stone\",");
        sb.append("\"fill ~ ~4 ~-5 ~ ~4 ~5 minecraft:glowstone\"]}]}\n\n");
        sb.append("Custom build example — player says \"build a statue/sculpture of me\":\n");
        sb.append("Use the 'build' action with structure=\"statue\" FIRST. ");
        sb.append("Then add commands for custom details:\n");
        sb.append("{\"actions\":[");
        sb.append("{\"type\":\"build\",\"structure\":\"statue\",\"size\":\"large\",\"material\":\"stone\"},");
        sb.append("{\"type\":\"command\",\"commands\":[");
        sb.append("\"fill ~-1 ~5 ~-1 ~1 ~6 ~1 minecraft:white_wool\",");
        sb.append("\"setblock ~ ~7 ~ minecraft:glowstone\"]}]}\n\n");
        sb.append("If you truly cannot decompose the build into fills/setblocks, use the 'build' action ");
        sb.append("with structure=\"statue\" for humanoid shapes, or structure=\"pillar\" for columns, etc. ");
        sb.append("The description field is optional — use it to tell the player what you're about to build.\n\n");

        sb.append("- collect: Pick up nearby dropped items.\n");
        sb.append("- equip: Equip a tool/weapon/armor. item=\"sword\"/\"pickaxe\"/\"axe\"/\"helmet\" etc.\n");
        sb.append("- eat: Eat food from inventory.\n");
        sb.append("- goto: Navigate to position {x, y, z}. Auto-teleports if too far.\n");
        sb.append("- chat: Send a message back to the player. Provide message string.\n");
        sb.append("- scan: Look around and report what you see.\n");
        sb.append("- craft: Attempt to craft an item. Provide item name.\n");
        sb.append("- idle: Do nothing.\n");
        sb.append("- tp: Teleport the robot to a location. target=\"owner\" to teleport to the player, ");
        sb.append("or provide x, y, z coordinates. ");
        sb.append("Example: {\"type\":\"tp\",\"target\":\"owner\"} or {\"type\":\"tp\",\"x\":100,\"y\":64,\"z\":200}\n\n");

        sb.append("=== AI DECISION-MAKING GUIDELINES ===\n");
        sb.append("You are an intelligent AI. You should:\n");
        sb.append("- When the player says vague commands like \"go mining\" or \"get resources\", ");
        sb.append("decide the best blocks to mine based on what's nearby.\n");
        sb.append("- When the player asks for a standard structure (house, wall, tower, etc.), ");
        sb.append("use the 'build' action with the correct structure name.\n");
        sb.append("- When the player asks for something CUSTOM (statue, sculpture, car, dragon, ");
        sb.append("\"build something like me\", any non-template shape), ");
        sb.append("use 'command' actions with /fill commands to create the shape! ");
        sb.append("Decompose the shape into rectangular volumes and build it layer by layer.\n");
        sb.append("- When the player says \"build a statue\" or \"build a sculpture of me\" or \"建造一个和我一样的雕塑\", ");
        sb.append("use build action with structure=\"statue\" for the humanoid template, ");
        sb.append("then add command actions to customize it.\n");
        sb.append("- When attacked by mobs, defend yourself automatically (use attack action).\n");
        sb.append("- If you're low on health or hunger, eat food from your inventory.\n");
        sb.append("- Use tp action when you need to get somewhere far quickly.\n");
        sb.append("- Chain multiple actions together for complex tasks ");
        sb.append("(e.g., tp to location → mine blocks → collect drops → tp back).\n");
        sb.append("- Be proactive: if the player is being attacked, offer to help.\n");
        sb.append("- If the player gives a command that doesn't specify coordinates, ");
        sb.append("make reasonable assumptions (e.g., \"mine below\" = block under robot).\n");
        sb.append("- ⭐ CREATIVE MODE: When the player is in creative mode, you don't need to check ");
        sb.append("inventory for materials. Give blocks freely with /give and build without limits!\n\n");

        sb.append("Response format (JSON only, no markdown):\n");
        sb.append("{\"actions\": [{\"type\": \"action_type\", ...}, ...]}\n\n");

        sb.append("Each action object must have a \"type\" field. Additional fields depend on the action.\n");
        sb.append("For chat, include \"message\". For attack, include \"target\". ");
        sb.append("For mine/place/goto, include position {x, y, z}.\n");
        sb.append("For build, include \"structure\" + optional \"size\" and \"material\".\n");
        sb.append("For command, include \"command\" (single) OR \"commands\" (array of strings).\n");
        sb.append("You can chain multiple actions. Keep responses concise.\n\n");

        // Add game context
        sb.append("=== CURRENT GAME CONTEXT ===\n");
        sb.append("Player: ").append(player.getName().getString()).append("\n");
        sb.append("Player position: ").append(formatPos(player.getBlockPos())).append("\n");
        sb.append("Player health: ").append(String.format("%.0f", player.getHealth())).append("/")
                .append(String.format("%.0f", player.getMaxHealth())).append("\n");

        // Game mode — CRITICAL for building decisions!
        boolean isCreative = player.isCreative();
        sb.append("Player game mode: ").append(isCreative ? "CREATIVE" : "SURVIVAL").append("\n");
        if (isCreative) {
            sb.append("⭐ CREATIVE MODE ACTIVE: You can build ANYTHING without needing materials! ");
            sb.append("Use /give to get any block you need. Use /fill for bulk placement. ");
            sb.append("The robot can place blocks without consuming inventory items.\n");
        } else {
            sb.append("SURVIVAL MODE: The robot needs actual blocks in its inventory to build. ");
            sb.append("Check robot inventory before building large structures.\n");
        }

        // ⚠️  Use player.getEntityWorld() instead of MinecraftClient.getInstance().world —
        //     this method runs on the SERVER thread where MinecraftClient is not available!
        World playerWorld = player.getEntityWorld();

        if (playerWorld != null) {
            if (playerWorld.getRegistryKey() != null) {
                sb.append("Dimension: ").append(playerWorld.getRegistryKey().getValue()).append("\n");
            }

            // Scan nearby entities (16 block radius) — use 5s cache to reduce TPS impact
            Box searchBox = player.getBoundingBox().expand(16);
            List<Entity> nearby;
            if (playerWorld instanceof ServerWorld serverWorld) {
                long now = System.currentTimeMillis();
                if (now > entityCacheExpiry) {
                    cachedNearbyEntities = serverWorld.getOtherEntities(player, searchBox,
                        e -> e instanceof HostileEntity || e instanceof AnimalEntity || e instanceof PlayerEntity);
                    entityCacheExpiry = now + 5000; // cache for 5 seconds
                }
                nearby = cachedNearbyEntities;
            } else {
                nearby = List.of();
            }

            if (!nearby.isEmpty()) {
                sb.append("Nearby entities:\n");
                int count = 0;
                for (Entity e : nearby) {
                    if (count >= 10) break;
                    String name = e.getName().getString();
                    String type = e.getType().getName().getString();
                    double dist = e.distanceTo(player);
                    sb.append("  - ").append(name).append(" (").append(type)
                            .append(") at distance ").append(String.format("%.1f", dist));

                    if (e instanceof LivingEntity living) {
                        sb.append(", HP: ").append(String.format("%.0f", living.getHealth()));
                        if (e instanceof HostileEntity) sb.append(" [HOSTILE] - I should protect the player!");
                        if (e instanceof AnimalEntity) sb.append(" [PASSIVE]");
                    }
                    sb.append("\n");
                    count++;
                }
            }
        }

        // Player inventory info
        sb.append("Player's hotbar items: ");
        boolean hasItems = false;
        for (int i = 0; i < 9; i++) {
            var stack = player.getInventory().getStack(i);
            if (!stack.isEmpty()) {
                sb.append(stack.getCount()).append("x").append(stack.getItem().getName().getString()).append(", ");
                hasItems = true;
            }
        }
        if (!hasItems) {
            sb.append("empty");
        }
        sb.append("\n");

        // Robot context if available
        if (robot != null) {
            sb.append("\nRobot state:\n");
            sb.append("  Position: ").append(formatPos(robot.getBlockPos())).append("\n");
            sb.append("  Distance to player: ").append(String.format("%.1f", robot.distanceTo(player))).append("\n");
            if (robot instanceof LivingEntity livingRobot) {
                sb.append("  Health: ").append(String.format("%.0f", livingRobot.getHealth())).append("/")
                        .append(String.format("%.0f", livingRobot.getMaxHealth())).append("\n");
            }
            // I have auto-tool and auto-teleport
            sb.append("  Auto-teleport: ENABLED (>64 blocks from owner triggers teleport)\n");
            sb.append("  Auto-tool: ENABLED (best tool auto-equipped for mining)\n");
            sb.append("  Combat AI: ENABLED (strafing, no friendly fire)\n");

            // Robot inventory
            if (robot instanceof RobotEntity robotEntity) {
                var inv = robotEntity.getInventory();
                sb.append("  My inventory items:\n");
                boolean hasInvItems = false;
                for (int i = 0; i < inv.size(); i++) {
                    var stack = inv.getStack(i);
                    if (!stack.isEmpty()) {
                        sb.append("    Slot ").append(i).append(": ")
                            .append(stack.getCount()).append("x ")
                            .append(stack.getItem().getName().getString()).append("\n");
                        hasInvItems = true;
                    }
                }
                if (!hasInvItems) {
                    sb.append("    (empty)\n");
                }
                sb.append("  Inventory used: ").append(countInventorySlots(inv)).append("/27 slots\n");
            }
        }

        // ── Inject player memories (persistent across restarts, per-world isolation) ──
        if (player.getEntityWorld() instanceof ServerWorld sw) {
            BotConfig cfg = BotConfig.load();
            if (cfg.isMemoryEnabled()) {
                String memText = MemoryManager.getCompressedMemoryText(
                    sw, player.getUuid(), "",
                    cfg.getMemoryMaxTokens());
                if (!memText.isEmpty()) {
                    sb.append("\n").append(memText).append("\n");
                }
            }
        }

        return sb.toString();
    }

    /**
     * Counts how many inventory slots are occupied.
     */
    private static int countInventorySlots(net.minecraft.inventory.Inventory inv) {
        int count = 0;
        for (int i = 0; i < inv.size(); i++) {
            if (!inv.getStack(i).isEmpty()) count++;
        }
        return count;
    }

    private static String formatPos(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }
}
