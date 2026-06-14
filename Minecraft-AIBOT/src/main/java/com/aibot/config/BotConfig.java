package com.aibot.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Manages the mod configuration stored in config/robot-ai.json.
 * Auto-creates the config file with defaults on first run.
 */
public class BotConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("robot-ai.json");

    private static BotConfig INSTANCE;
    private static long instanceLoadTime = 0;
    // Re-check the config file at most once per 30 seconds to pick up hot-reloads
    private static final long CONFIG_CACHE_MS = 30_000;

    // ==================== API / AI Settings ====================

    /**
     * Per-provider configuration.
     */
    public static class ProviderConfig {
        @SerializedName("apiKey")
        public String apiKey = "";
        @SerializedName("apiEndpoint")
        public String apiEndpoint = "";
        @SerializedName("model")
        public String model = "";

        public ProviderConfig() {}
        public ProviderConfig(String apiKey, String apiEndpoint, String model) {
            this.apiKey = apiKey;
            this.apiEndpoint = apiEndpoint;
            this.model = model;
        }
    }

    @SerializedName("apiKey")
    private String apiKey = "";

    @SerializedName("apiProvider")
    private String apiProvider = "deepseek";

    @SerializedName("apiEndpoint")
    private String apiEndpoint = "";

    @SerializedName("model")
    private String model = "deepseek-v4-pro";

    /**
     * Per-provider API keys, endpoints, and models.
     * Keyed by provider name: "deepseek", "openai", "claude", "gemini", etc.
     */
    @SerializedName("providers")
    private java.util.Map<String, ProviderConfig> providers = null;

    @SerializedName("maxTokens")
    private int maxTokens = 4096;

    @SerializedName("temperature")
    private double temperature = 0.7;

    @SerializedName("language")
    private String language = "auto";
    // "auto" = AI decides, "en" = force English, "zh" = force Chinese

    @SerializedName("proactiveChat")
    private boolean proactiveChat = true;

    @SerializedName("proactiveChatInterval")
    private int proactiveChatInterval = 300; // seconds (5 min)

    // ==================== Command Settings ====================

    @SerializedName("commandPrefix")
    private String commandPrefix = "!bot";

    @SerializedName("commandLanguage")
    private String commandLanguage = "zh";
    // "zh" = Chinese help menu, "en" = English help menu

    // ==================== Movement & Behavior ====================

    @SerializedName("followDistance")
    private double followDistance = 20.0;

    @SerializedName("followSpeed")
    private double followSpeed = 1.3;

    @SerializedName("combatRange")
    private double combatRange = 16.0;

    @SerializedName("pickupRange")
    private double pickupRange = 8.0;

    // ==================== Combat Settings ====================

    @SerializedName("enableCombat")
    private boolean enableCombat = true;

    @SerializedName("robotAttackDamage")
    private double robotAttackDamage = 6.0;

    @SerializedName("robotMaxHealth")
    private double robotMaxHealth = 40.0;

    // ==================== Feature Toggles ====================

    @SerializedName("enableHunger")
    private boolean enableHunger = false;

    @SerializedName("autoCollect")
    private boolean autoCollect = true;

    @SerializedName("enableParticles")
    private boolean enableParticles = true;

    @SerializedName("ownerOnly")
    private boolean ownerOnly = true;

    // ==================== Auto Teleport Settings ====================

    @SerializedName("autoTeleportDistance")
    private double autoTeleportDistance = 64.0;

    @SerializedName("enableAutoTeleport")
    private boolean enableAutoTeleport = true;

    // ==================== Auto Tool Settings ====================

    @SerializedName("enableAutoTool")
    private boolean enableAutoTool = true;

    @SerializedName("autoCollectRange")
    private double autoCollectRange = 16.0;

    // ==================== World Settings ====================

    @SerializedName("maxRobotPerWorld")
    private int maxRobotPerWorld = 1;

    // ==================== AI Context Settings ====================

    @SerializedName("conversationContext")
    private boolean conversationContext = true;

    @SerializedName("maxContextTokens")
    private int maxContextTokens = 8192;

    // ==================== Memory Settings ====================

    @SerializedName("memoryEnabled")
    private boolean memoryEnabled = true;

    @SerializedName("maxMemories")
    private int maxMemories = 50;

    @SerializedName("memoriesPerPrompt")
    private int memoriesPerPrompt = 5;

    @SerializedName("memoryMaxTokens")
    private int memoryMaxTokens = 500;

    private BotConfig() {}

    /**
     * Initializes default provider entries with well-known endpoints and models.
     * Model names are verified against each provider's latest API documentation.
     */
    private void initProviders() {
        if (providers != null) return;
        providers = new java.util.LinkedHashMap<>();

        // ── Migration from old flat config ──
        // If the old-style apiKey/apiEndpoint/model fields are set but
        // the providers map is empty for the current provider, copy them in.
        String cur = getApiProvider();
        if (!apiKey.isEmpty() && !apiKey.equals("YOUR_DEEPSEEK_API_KEY_HERE")) {
            providers.put(cur, new ProviderConfig(apiKey, apiEndpoint, model));
        }

        // Fill defaults for providers that aren't configured yet
        // DeepSeek — deepseek-v4-pro (flagship, 1M ctx), deepseek-v4-flash (general, 1M ctx)
        addDefaultProvider("deepseek",  "", "https://api.deepseek.com", "deepseek-v4-pro");

        // OpenAI — gpt-4o (flagship), o3 (reasoning), gpt-4.1 (long ctx)
        addDefaultProvider("openai",    "", "https://api.openai.com", "gpt-4o");

        // Claude (Anthropic) — Opus 4.7 (most capable), Sonnet 4.6 (best balance)
        addDefaultProvider("claude",    "", "https://api.anthropic.com", "claude-sonnet-4-6");

        // Gemini (Google) — 3.5 Flash (latest GA), 2.5 Pro (stable)
        addDefaultProvider("gemini",    "", "https://generativelanguage.googleapis.com", "gemini-3.5-flash");

        // Moonshot / Kimi (月之暗面) — kimi-k2.6 (latest flagship), moonshot-v1-128k (legacy)
        addDefaultProvider("moonshot",  "", "https://api.moonshot.cn", "kimi-k2.6");

        // Zhipu / GLM (智谱) — glm-4-plus (flagship), glm-4.7-flash (free)
        addDefaultProvider("zhipu",     "", "https://open.bigmodel.cn/api/paas/v4", "glm-4-plus");

        // Qwen (通义千问, Alibaba) — qwen3-max (next-gen), qwen-plus (stable, 1M ctx)
        addDefaultProvider("qwen",      "", "https://dashscope.aliyuncs.com/compatible-mode", "qwen-plus");

        // SiliconFlow (硅基流动) — open-source model gateway
        addDefaultProvider("siliconflow", "", "https://api.siliconflow.cn", "deepseek-ai/DeepSeek-V2-Chat");

        // Groq — ultra-fast inference, Llama 4 / 3.3
        addDefaultProvider("groq",      "", "https://api.groq.com/openai", "llama-3.3-70b-versatile");

        // Mistral AI — mistral-large (flagship), codestral (coding)
        addDefaultProvider("mistral",   "", "https://api.mistral.ai", "mistral-large-latest");

        // Together AI — open-source model gateway
        addDefaultProvider("together",  "", "https://api.together.xyz", "meta-llama/Meta-Llama-3.1-70B-Instruct-Turbo");

        // Perplexity — online search-augmented models
        addDefaultProvider("perplexity", "", "https://api.perplexity.ai", "sonar-pro");

        // OpenRouter — unified API for 200+ models
        addDefaultProvider("openrouter", "", "https://openrouter.ai/api", "openai/gpt-4o");

        // Yi (零一万物) — yi-large (flagship), yi-large-turbo (fast)
        addDefaultProvider("yi",        "", "https://api.lingyiwanwu.com", "yi-large");

        // DeepInfra — serverless inference for open models
        addDefaultProvider("deepinfra", "", "https://api.deepinfra.com/v1/openai", "meta-llama/Meta-Llama-3-70B-Instruct");

        // Ollama — local models, OpenAI-compatible endpoint
        addDefaultProvider("ollama",    "", "http://localhost:11434", "llama3.2");

        // xAI (Grok) — grok-2 (latest), grok-beta
        addDefaultProvider("xai",       "", "https://api.x.ai", "grok-2-latest");

        // Custom — any OpenAI-compatible API
        addDefaultProvider("custom",    apiEndpoint.isEmpty() ? "" : apiEndpoint, "https://api.deepseek.com", "");
    }

    private void addDefaultProvider(String name, String apiKey, String endpoint, String model) {
        if (!providers.containsKey(name)) {
            providers.put(name, new ProviderConfig(apiKey, endpoint, model));
        } else {
            ProviderConfig pc = providers.get(name);
            if (pc.apiEndpoint == null || pc.apiEndpoint.isEmpty()) {
                pc.apiEndpoint = endpoint;
            }
            if (pc.model == null || pc.model.isEmpty()) {
                pc.model = model;
            }
        }
    }

    /**
     * Loads the config from disk, or creates a default one if it doesn't exist.
     * Uses a 30-second in-memory cache to avoid repeated file I/O each tick.
     */
    public static BotConfig load() {
        long now = System.currentTimeMillis();
        if (INSTANCE != null && (now - instanceLoadTime) < CONFIG_CACHE_MS) {
            return INSTANCE;
        }

        if (Files.exists(CONFIG_PATH)) {
            try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
                INSTANCE = GSON.fromJson(reader, BotConfig.class);
                if (INSTANCE == null) INSTANCE = new BotConfig();
            } catch (IOException e) {
                System.err.println("[AIBot] Failed to load config: " + e.getMessage());
                INSTANCE = new BotConfig();
            }
        } else {
            INSTANCE = new BotConfig();
        }

        INSTANCE.initProviders();

        // Sync root-level fields from the current provider's config
        INSTANCE.syncFromProvider();

        // Always save to ensure all fields exist
        INSTANCE.save();
        instanceLoadTime = System.currentTimeMillis();
        return INSTANCE;
    }

    /**
     * Synchronizes the root-level apiKey/apiEndpoint/model fields
     * from the current provider's config entry. This ensures
     * backward compatibility with code that reads the root fields.
     */
    public void syncFromProvider() {
        String cur = getApiProvider();
        if (providers != null && providers.containsKey(cur)) {
            ProviderConfig pc = providers.get(cur);
            if (pc.apiKey != null && !pc.apiKey.isEmpty()) {
                this.apiKey = pc.apiKey;
            }
            if (pc.apiEndpoint != null && !pc.apiEndpoint.isEmpty()) {
                this.apiEndpoint = pc.apiEndpoint;
            }
            if (pc.model != null && !pc.model.isEmpty()) {
                this.model = pc.model;
            }
        }
    }

    /**
     * Saves the current root-level fields back to the current provider's config entry,
     * then writes to disk.
     */
    public void saveToProvider() {
        String cur = getApiProvider();
        if (providers != null) {
            ProviderConfig pc = providers.computeIfAbsent(cur, k -> new ProviderConfig());
            pc.apiKey = this.apiKey;
            pc.apiEndpoint = this.apiEndpoint;
            pc.model = this.model;
        }
    }

    /**
     * Saves the current config to disk.
     * Syncs root fields back to the current provider before saving.
     */
    public void save() {
        saveToProvider();
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            System.err.println("[AIBot] Failed to save config: " + e.getMessage());
        }
    }

    /**
     * Reloads the config from disk immediately (bypasses cache).
     */
    public static void reload() {
        INSTANCE = null;
        instanceLoadTime = 0;
        load();
    }

    // ==================== Setters (for runtime changes) ====================

    public void setModel(String model) {
        this.model = model;
        save();
    }

    public void setApiProvider(String provider) {
        // Save current provider's state before switching
        saveToProvider();
        // Switch to new provider
        this.apiProvider = provider;
        // Load the new provider's config
        if (providers != null && providers.containsKey(provider)) {
            ProviderConfig pc = providers.get(provider);
            if (pc.apiKey != null && !pc.apiKey.isEmpty()) this.apiKey = pc.apiKey;
            if (pc.apiEndpoint != null && !pc.apiEndpoint.isEmpty()) this.apiEndpoint = pc.apiEndpoint;
            if (pc.model != null && !pc.model.isEmpty()) this.model = pc.model;
        }
        save();
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
        save();
    }

    public void setApiEndpoint(String endpoint) {
        this.apiEndpoint = endpoint;
        save();
    }

    public void setProviderConfig(String provider, String apiKey, String endpoint, String model) {
        if (providers == null) providers = new java.util.LinkedHashMap<>();
        providers.put(provider, new ProviderConfig(
            apiKey != null ? apiKey : "",
            endpoint != null ? endpoint : "",
            model != null ? model : ""
        ));
        // If this is the current provider, sync root fields
        if (provider.equals(this.apiProvider)) {
            if (apiKey != null && !apiKey.isEmpty()) this.apiKey = apiKey;
            if (endpoint != null && !endpoint.isEmpty()) this.apiEndpoint = endpoint;
            if (model != null && !model.isEmpty()) this.model = model;
        }
        save();
    }

    // ==================== Getters ====================

    public String getApiKey() {
        return apiKey;
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isEmpty() && !apiKey.equals("YOUR_DEEPSEEK_API_KEY_HERE")
            && !apiKey.equals("YOUR_API_KEY_HERE");
    }

    public String getApiProvider() {
        return apiProvider != null ? apiProvider : "deepseek";
    }

    public String getApiEndpoint() {
        return apiEndpoint;
    }

    public String getModel() {
        return model;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public double getTemperature() {
        return temperature;
    }

    public String getLanguage() {
        return language;
    }

    public String getCommandPrefix() {
        return commandPrefix;
    }

    public String getCommandLanguage() {
        return commandLanguage;
    }

    public java.util.Map<String, ProviderConfig> getProviders() {
        if (providers == null) {
            providers = new java.util.LinkedHashMap<>();
        }
        return providers;
    }

    /**
     * Returns the API format type for the current provider.
     * "openai"  → OpenAI-compatible /v1/chat/completions format (most providers)
     * "claude"  → Anthropic Claude format
     * "gemini"  → Google Gemini format
     */
    public String getApiFormat() {
        return getApiFormatFor(apiProvider);
    }

    /**
     * Returns the API format type for a given provider name.
     */
    public static String getApiFormatFor(String provider) {
        if (provider == null) return "openai";
        return switch (provider) {
            case "claude" -> "claude";
            case "gemini" -> "gemini";
            default -> "openai"; // deepseek, openai, moonshot, qwen, etc.
        };
    }

    /**
     * Returns the default base URL for a given provider name.
     */
    public static String getDefaultEndpoint(String provider) {
        if (provider == null) return "https://api.deepseek.com";
        return switch (provider) {
            case "deepseek"   -> "https://api.deepseek.com";
            case "openai"     -> "https://api.openai.com";
            case "claude"     -> "https://api.anthropic.com";
            case "gemini"     -> "https://generativelanguage.googleapis.com";
            case "moonshot"   -> "https://api.moonshot.cn";
            case "zhipu"      -> "https://open.bigmodel.cn/api/paas/v4";
            case "qwen"       -> "https://dashscope.aliyuncs.com/compatible-mode";
            case "siliconflow" -> "https://api.siliconflow.cn";
            case "groq"       -> "https://api.groq.com/openai";
            case "mistral"    -> "https://api.mistral.ai";
            case "together"   -> "https://api.together.xyz";
            case "perplexity" -> "https://api.perplexity.ai";
            case "openrouter" -> "https://openrouter.ai/api";
            case "yi"         -> "https://api.lingyiwanwu.com";
            case "deepinfra"  -> "https://api.deepinfra.com/v1/openai";
            case "ollama"     -> "http://localhost:11434";
            case "xai"        -> "https://api.x.ai";
            case "custom"     -> "https://api.deepseek.com";
            default -> "https://api.deepseek.com";
        };
    }

    /**
     * Returns a human-readable display name for the provider.
     */
    public static String getProviderDisplayName(String provider) {
        if (provider == null) return "DeepSeek";
        return switch (provider) {
            case "deepseek"   -> "DeepSeek";
            case "openai"     -> "OpenAI";
            case "claude"     -> "Claude (Anthropic)";
            case "gemini"     -> "Gemini (Google)";
            case "moonshot"   -> "Moonshot (月之暗面)";
            case "zhipu"      -> "Zhipu (智谱 GLM)";
            case "qwen"       -> "Qwen (通义千问)";
            case "siliconflow" -> "SiliconFlow (硅基流动)";
            case "groq"       -> "Groq";
            case "mistral"    -> "Mistral AI";
            case "together"   -> "Together AI";
            case "perplexity" -> "Perplexity";
            case "openrouter" -> "OpenRouter";
            case "yi"         -> "Yi (零一万物)";
            case "deepinfra"  -> "DeepInfra";
            case "ollama"     -> "Ollama (Local)";
            case "xai"        -> "xAI (Grok)";
            case "custom"     -> "Custom";
            default -> provider.substring(0, 1).toUpperCase() + provider.substring(1);
        };
    }

    public String getModelDisplayName() {
        return getProviderDisplayName(apiProvider) + " · " + model;
    }

    /**
     * Returns all supported provider names.
     */
    public static String[] getAllProviders() {
        return new String[]{
            "deepseek", "openai", "claude", "gemini",
            "moonshot", "zhipu", "qwen", "siliconflow",
            "groq", "mistral", "together", "perplexity",
            "openrouter", "yi", "deepinfra", "ollama", "xai",
            "custom"
        };
    }

    public boolean isProactiveChatEnabled() {
        return proactiveChat;
    }

    public int getProactiveChatInterval() {
        return proactiveChatInterval;
    }

    public double getFollowDistance() {
        return followDistance;
    }

    public double getFollowSpeed() {
        return followSpeed;
    }

    public double getCombatRange() {
        return combatRange;
    }

    public double getPickupRange() {
        return pickupRange;
    }

    public boolean isCombatEnabled() {
        return enableCombat;
    }

    public boolean isHungerEnabled() {
        return enableHunger;
    }

    public boolean isAutoCollectEnabled() {
        return autoCollect;
    }

    public boolean isParticlesEnabled() {
        return enableParticles;
    }

    public boolean isOwnerOnly() {
        return ownerOnly;
    }

    // ==================== New Getters ====================

    public double getAutoTeleportDistance() {
        return autoTeleportDistance;
    }

    public boolean isAutoTeleportEnabled() {
        return enableAutoTeleport;
    }

    public boolean isAutoToolEnabled() {
        return enableAutoTool;
    }

    public double getAutoCollectRange() {
        return autoCollectRange;
    }

    public int getMaxRobotPerWorld() {
        return maxRobotPerWorld;
    }

    public boolean isConversationContext() {
        return conversationContext;
    }

    public int getMaxContextTokens() {
        return maxContextTokens;
    }

    public boolean isMemoryEnabled() {
        return memoryEnabled;
    }

    public int getMaxMemories() {
        return maxMemories;
    }

    public int getMemoriesPerPrompt() {
        return memoriesPerPrompt;
    }

    public int getMemoryMaxTokens() {
        return memoryMaxTokens;
    }

    public double getRobotAttackDamage() {
        return robotAttackDamage;
    }

    public double getRobotMaxHealth() {
        return robotMaxHealth;
    }
}
