package com.aibot.chat;

import com.aibot.ai.CommandParser;
import com.aibot.ai.DeepSeekClient;
import com.aibot.ai.MemoryManager;
import com.aibot.config.BotConfig;
import com.aibot.entity.RobotAI;
import com.aibot.entity.RobotEntity;
import com.aibot.entity.RobotWorldData;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.Comparator;
import java.util.List;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * AI-Bot command handler — /bot command only.
 * Premium formatting with console-level command execution.
 */
public class ChatHandler {

    // ======================================================================
    //  Registration
    // ======================================================================

    public static void register() {
        // /bot command (silent — not broadcast to chat)
        CommandRegistrationCallback.EVENT.register(ChatHandler::registerCommand);
    }

    private static void registerCommand(CommandDispatcher<ServerCommandSource> dispatcher,
                                         CommandRegistryAccess access,
                                         CommandManager.RegistrationEnvironment env) {
        dispatcher.register(literal("bot")
            .then(argument("args", StringArgumentType.greedyString())
                .executes(ctx -> {
                    handleCommand(ctx.getSource().getPlayer(),
                        StringArgumentType.getString(ctx, "args"));
                    return 1;
                })
            )
            .executes(ctx -> {
                sendHelp(ctx.getSource().getPlayer(), BotConfig.load());
                return 1;
            })
        );
    }

    // ======================================================================
    //  Core handler
    // ======================================================================

    private static void handleCommand(ServerPlayerEntity player, String rawArgs) {
        BotConfig config = BotConfig.load();
        String cmd = rawArgs.toLowerCase().trim();
        boolean zh = config.getCommandLanguage().equals("zh");

        // ── Empty ──
        if (cmd.isEmpty()) {
            player.sendMessage(ChatFormatter.info(config,
                zh ? "请输入指令。" : "Enter a command."), false);
            player.sendMessage(ChatFormatter.info(config,
                zh ? "使用 §d/bot help§7 查看帮助" : "Type §d/bot help§7 for help."), false);
            return;
        }

        // ── generate ──
        if (cmd.equals("generate") || cmd.equals("生成")) {
            handleGenerate(player, config);
            return;
        }

        // ── help ──
        if (cmd.equals("help") || cmd.equals("帮助")) {
            sendHelp(player, config);
            return;
        }

        // ── clear ──
        if (cmd.equals("clear") || cmd.equals("清除")) {
            DeepSeekClient.clearHistory(player.getUuid().toString());
            player.sendMessage(ChatFormatter.success(config,
                zh ? "对话上下文已清除 ✓" : "Conversation context cleared ✓"), false);
            return;
        }

        // ── remember ──
        if (cmd.startsWith("remember ") || cmd.startsWith("记忆 ")) {
            String fact = cmd.startsWith("remember ") ? cmd.substring(9) : cmd.substring(3);
            handleRemember(player, config, fact.trim());
            return;
        }

        // ── forget ──
        if (cmd.startsWith("forget ") || cmd.startsWith("忘记 ")) {
            String memId = cmd.startsWith("forget ") ? cmd.substring(7) : cmd.substring(3);
            handleForget(player, config, memId.trim());
            return;
        }

        // ── memories ──
        if (cmd.equals("memories") || cmd.equals("记忆列表")) {
            handleListMemories(player, config);
            return;
        }

        // ── memory search ──
        if (cmd.startsWith("memory search ") || cmd.startsWith("记忆搜索 ")) {
            String query = cmd.startsWith("memory search ") ? cmd.substring(14) : cmd.substring(5);
            handleMemorySearch(player, config, query.trim());
            return;
        }

        // ── memory update ──
        if (cmd.startsWith("memory update ") || cmd.startsWith("记忆更新 ")) {
            String args = cmd.startsWith("memory update ") ? cmd.substring(14) : cmd.substring(5);
            handleMemoryUpdate(player, config, args.trim());
            return;
        }

        // ── memory clear ──
        if (cmd.equals("memory clear") || cmd.equals("记忆清除")) {
            handleMemoryClear(player, config);
            return;
        }

        // ── memory link ──
        if (cmd.startsWith("memory link ") || cmd.startsWith("记忆链接 ")) {
            String args = cmd.startsWith("memory link ") ? cmd.substring(12) : cmd.substring(5);
            handleMemoryLink(player, config, args.trim());
            return;
        }

        // ── memory unlink ──
        if (cmd.startsWith("memory unlink ") || cmd.startsWith("记忆取消链接 ")) {
            String args = cmd.startsWith("memory unlink ") ? cmd.substring(14) : cmd.substring(7);
            handleMemoryUnlink(player, config, args.trim());
            return;
        }

        // ── memory importance ──
        if (cmd.startsWith("memory importance ") || cmd.startsWith("记忆重要性 ")) {
            String args = cmd.startsWith("memory importance ") ? cmd.substring(18) : cmd.substring(6);
            handleMemoryImportance(player, config, args.trim());
            return;
        }

        // ── provider ──
        if (cmd.startsWith("provider ")) {
            handleProviderSwitch(player, config, cmd.substring(9).trim());
            return;
        }
        if (cmd.equals("provider")) {
            player.sendMessage(ChatFormatter.entry(zh ? "当前提供商" : "Provider", config.getProviderDisplayName(config.getApiProvider())), false);
            if (zh) {
                player.sendMessage(ChatFormatter.info(config, "§d/bot provider <名称> §7切换AI提供商"), false);
                player.sendMessage(ChatFormatter.info(config, "§7可用: deepseek, openai, claude, gemini, moonshot, zhipu, qwen, siliconflow, groq, mistral, ollama..."), false);
            } else {
                player.sendMessage(ChatFormatter.info(config, "§d/bot provider <name> §7switch AI provider"), false);
                player.sendMessage(ChatFormatter.info(config, "§7Available: deepseek, openai, claude, gemini, moonshot, zhipu, qwen, siliconflow, groq, mistral, ollama..."), false);
            }
            return;
        }

        // ── model ──
        if (cmd.startsWith("model ")) {
            handleModelSwitch(player, config, cmd.substring(6).trim());
            return;
        }
        if (cmd.equals("model")) {
            player.sendMessage(ChatFormatter.entry(zh ? "当前模型" : "Model", config.getModel()), false);
            String prov = config.getApiProvider();
            if (zh) {
                player.sendMessage(ChatFormatter.info(config, "§d/bot model <名称> §7切换模型"), false);
                player.sendMessage(ChatFormatter.info(config, "§7当前提供商 (" + prov + ") 的可用模型请查看其API文档"), false);
            } else {
                player.sendMessage(ChatFormatter.info(config, "§d/bot model <name> §7switch model"), false);
                player.sendMessage(ChatFormatter.info(config, "§7Check API docs for " + prov + " available models"), false);
            }
            return;
        }

        // ── API Key required below ──
        if (!config.hasApiKey()) {
            player.sendMessage(ChatFormatter.error(config,
                zh ? "请在 config/robot-ai.json 中设置 API Key" : "Set API Key in config/robot-ai.json"), false);
            return;
        }

        // ── Find robot in world ──
        ServerWorld world = (ServerWorld) player.getEntityWorld();
        RobotEntity robot = findRobotInWorld(world);
        if (robot == null) {
            player.sendMessage(ChatFormatter.error(config,
                zh ? "没有机器人！使用 §d/bot generate §7生成一个" : "No robot! Use §d/bot generate §7to create one"), false);
            return;
        }

        // ── Thinking indicator ──
        player.sendMessage(ChatFormatter.processing(config,
            zh ? "思考中…" : "Thinking…"), false);

        DeepSeekClient.sendCommand(player, cmd, robot)
            .thenAcceptAsync(actions -> {
                if (actions.isEmpty()) {
                    player.sendMessage(ChatFormatter.error(config,
                        zh ? "指令处理失败。" : "Command failed."), false);
                    return;
                }

                StringBuilder chatReply = new StringBuilder();
                List<RobotAI.QueuedAction> queue = new java.util.ArrayList<>();

                for (var a : actions) {
                    switch (a.type) {
                        case CHAT -> {
                            if (a.message != null && !a.message.isBlank()) {
                                if (!chatReply.isEmpty()) chatReply.append("\n");
                                chatReply.append(a.message);
                            }
                        }
                        case SCAN -> {
                            String report = RobotAI.buildScanReport(robot);
                            if (!chatReply.isEmpty()) chatReply.append("\n");
                            chatReply.append("§7").append(report);
                        }
                        case COMMAND -> executeCommand(player, robot, a.command);
                        case TP -> {
                            // Teleport to requested coordinates if provided, otherwise to player
                            BlockPos tpPos = a.position;
                            if (tpPos != null) {
                                ServerWorld tpWorld = (ServerWorld) player.getEntityWorld();
                                robot.teleport(tpWorld, tpPos.getX() + 0.5, tpPos.getY() + 0.5, tpPos.getZ() + 0.5,
                                    java.util.Set.of(), robot.getYaw(), robot.getPitch(), true);
                                if (!chatReply.isEmpty()) chatReply.append("\n");
                                chatReply.append("§a已传送到 (" + tpPos.getX() + ", " + tpPos.getY() + ", " + tpPos.getZ() + ")");
                            } else {
                                // Fallback: teleport to player
                                ServerWorld targetWorld = (ServerWorld) player.getEntityWorld();
                                robot.teleport(targetWorld, player.getX(), player.getY(), player.getZ(),
                                    java.util.Set.of(), player.getYaw(), player.getPitch(), true);
                                if (!chatReply.isEmpty()) chatReply.append("\n");
                                chatReply.append("§a已传送到你身边");
                            }
                        }
                        default -> queue.add(new RobotAI.QueuedAction(
                            a.type, a.target, a.position, a.message,
                            a.itemName, a.quantity, a.command,
                            a.structure, a.size, a.material));
                    }
                }

                if (!queue.isEmpty()) robot.queueActions(queue);

                // ── Send chat reply ──
                if (!chatReply.isEmpty()) {
                    for (String line : chatReply.toString().split("\n")) {
                        player.sendMessage(ChatFormatter.msg(config, "§7" + line), false);
                    }
                } else if (!queue.isEmpty()) {
                    var first = queue.get(0);
                    String desc = switch (first.type()) {
                        case FOLLOW  -> "§a" + (zh ? "跟随" : "Follow");
                        case STAY    -> "§7" + (zh ? "停留" : "Stay");
                        case ATTACK  -> "§c" + (zh ? "攻击 " : "Attack ") + (first.target() != null ? first.target() : "");
                        case MINE    -> "§6" + (zh ? "挖掘" : "Mine");
                        case PLACE   -> "§6" + (zh ? "放置" : "Place");
                        case COLLECT -> "§e" + (zh ? "收集" : "Collect");
                        case EAT     -> "§6" + (zh ? "进食" : "Eat");
                        case GOTO    -> "§b" + (zh ? "移动" : "Go to");
                        case EQUIP   -> "§d" + (zh ? "装备" : "Equip") + " " + (first.itemName() != null ? first.itemName() : "");
                        case CRAFT   -> "§d" + (zh ? "合成" : "Craft") + " " + (first.itemName() != null ? first.itemName() : "");
                        case COMMAND -> "§5" + (zh ? "指令" : "Cmd");
                        case TP      -> "§5" + (zh ? "传送" : "TP");
                        case BUILD   -> "§6" + (zh ? "建造" : "Build") + " " + (first.structure() != null ? first.structure() : "");
                        default      -> "§7" + (zh ? "执行" : "Exec");
                    };
                    player.sendMessage(ChatFormatter.action(config, desc + (zh ? "中…" : "…")), false);
                }
            }, runnable -> {
                ServerWorld w = player.getEntityWorld() instanceof ServerWorld sw ? sw : null;
                if (w != null && w.getServer() != null) w.getServer().execute(runnable);
            })
            .exceptionally(ex -> {
                player.sendMessage(ChatFormatter.error(config, ex.getMessage()), false);
                return null;
            });
    }

    // ======================================================================
    //  Console-level command execution  ✦  /setblock, /give, etc.
    //  ⚠️  SECURITY: AI output is not trusted — dangerous commands are blocked.
    // ======================================================================

    /** Commands the AI is NEVER allowed to execute (privilege escalation / server control). */
    private static final java.util.Set<String> DENIED_COMMANDS = java.util.Set.of(
        "op", "deop", "ban", "ban-ip", "banlist", "pardon", "pardon-ip",
        "kick", "stop", "restart", "reload",
        "save-all", "save-off", "save-on",
        "debug", "tellraw", "msg", "w", "say", "me", "tm", "teammsg",
        "execute", "function", "schedule",
        "setidletimeout", "whitelist", "perf",
        "publish", "transfer",
        "tp", "teleport"  // Use the 'tp' action type instead for robot teleport
    );

    private static void executeCommand(ServerPlayerEntity player, RobotEntity robot, String cmdLine) {
        if (cmdLine == null || cmdLine.isBlank()) return;
        ServerWorld world = (ServerWorld) robot.getEntityWorld();
        if (world == null || world.getServer() == null) return;

        BotConfig config = BotConfig.load();
        boolean zh = config.getCommandLanguage().equals("zh");

        // Placeholder replacements
        String ix = String.valueOf((int) robot.getX());
        String iy = String.valueOf((int) robot.getY());
        String iz = String.valueOf((int) robot.getZ());
        String playerName = player.getName().getString();
        String cmd = cmdLine
            .replace("@p", playerName)
            .replace("~x~", ix).replace("~y~", iy).replace("~z~", iz)
            .replace("~r~", String.valueOf((int)player.getX()))
            .replace("~ry~", String.valueOf((int)player.getY()))
            .replace("~rz~", String.valueOf((int)player.getZ()));

        // ── SECURITY: Block dangerous commands ──
        String cmdLower = cmd.toLowerCase().trim();
        String commandName = cmdLower.contains(" ") ? cmdLower.substring(0, cmdLower.indexOf(' ')) : cmdLower;
        if (DENIED_COMMANDS.contains(commandName)) {
            System.err.println("[AIBot] BLOCKED dangerous command from AI: /" + cmd);
            player.sendMessage(ChatFormatter.error(config,
                zh ? "§c指令被安全系统拦截（不允许: /" + commandName + "）"
                    : "§cCommand blocked by security (" + commandName + " not allowed)"), false);
            return;
        }

        // Execute as console (full permissions) — safe commands only reach here
        world.getServer().getCommandManager().parseAndExecute(
            world.getServer().getCommandSource(), cmd);

        player.sendMessage(ChatFormatter.action(config,
            "§7/" + cmd + "  " + (zh ? "§8✓" : "§8✓")), false);
    }

    // ======================================================================
    //  Help menu
    // ======================================================================

    private static void sendHelp(ServerPlayerEntity player, BotConfig config) {
        boolean zh = config.getCommandLanguage().equals("zh");

        player.sendMessage(ChatFormatter.divider(), false);
        player.sendMessage(ChatFormatter.title(
            zh ? "AI-Bot 指令帮助" : "AI-Bot Commands"), false);
        player.sendMessage(ChatFormatter.divider(), false);
        player.sendMessage(ChatFormatter.blank(), false);

        String[][] en = {
            {"help",          "Show this help menu"},
            {"generate",      "Spawn an AI robot (one per world)"},
            {"clear",         "Clear conversation context"},
            {"provider [name]","Switch AI provider (deepseek, openai, claude, etc.)"},
            {"model [name]",  "Switch AI model for current provider"},
            {"remember <f>",  "Ask AI to remember something forever"},
            {"memories",      "List all memories"},
            {"memory search <q>", "Search memories by keyword"},
            {"memory update <id> <c>", "Update a memory"},
            {"memory importance <id> <1-5>", "Set memory importance"},
            {"memory link <id1> <id2>", "Link two related memories"},
            {"memory clear",  "Delete all memories"},
            {"forget <id>",   "Delete a specific memory"},
            {"follow",        "Robot follows you"},
            {"stay",          "Robot stays in place"},
            {"attack [tgt]",  "Attack hostiles or named target"},
            {"mine [x y z]",  "Mine blocks at coordinates"},
            {"place [x y z]", "Place a block from inventory"},
            {"collect",       "Pick up nearby items"},
            {"equip <item>",  "Equip tool / weapon / armor"},
            {"eat",           "Eat food from inventory"},
            {"goto <x y z>",  "Navigate to coordinates"},
            {"scan",          "Scan surroundings"},
            {"craft <item>",  "Try to craft an item"},
            {"<anything>",    "Natural language control"},
        };
        String[][] zhEntries = {
            {"help",            "显示此帮助菜单"},
            {"generate",        "生成 AI 机器人（每世界一个）"},
            {"clear",           "清除对话上下文"},
            {"provider [名称]",  "切换 AI 提供商 (deepseek, openai, claude, gemini 等)"},
            {"model [名称]",     "切换当前提供商的 AI 模型"},
            {"remember <内容>",  "让 AI 永久记住某件事"},
            {"memories",        "列出所有记忆"},
            {"memory search <关键词>", "按关键词搜索记忆"},
            {"memory update <id> <内容>", "更新一条记忆"},
            {"memory importance <id> <1-5>", "设置记忆重要性"},
            {"memory link <id1> <id2>", "链接两条相关记忆"},
            {"memory clear",    "删除所有记忆"},
            {"forget <id>",     "删除指定记忆"},
            {"follow",          "机器人跟随你"},
            {"stay",            "机器人保持停留"},
            {"attack [目标]",    "攻击敌对生物或指定目标"},
            {"mine [x y z]",    "挖掘方块"},
            {"place [x y z]",   "从背包放置方块"},
            {"collect",         "拾取附近掉落物"},
            {"equip <物品>",     "装备工具/武器/盔甲"},
            {"eat",             "从背包中进食"},
            {"goto <x y z>",    "导航至坐标"},
            {"scan",            "扫描周围环境"},
            {"craft <物品>",     "尝试合成物品"},
            {"<任意指令>",       "用自然语言控制机器人"},
        };

        String[][] entries = zh ? zhEntries : en;

        for (String[] e : entries) {
            player.sendMessage(Text.literal("  §d/bot " + e[0]), false);
            player.sendMessage(Text.literal("  §7" + e[1]), false);
            player.sendMessage(ChatFormatter.blank(), false);
        }

        player.sendMessage(ChatFormatter.divider(), false);
        player.sendMessage(ChatFormatter.blank(), false);
        // Inventory instructions
        player.sendMessage(Text.literal("  " + (zh ? "§8┃  §7物品栏操作" : "§8┃  §7Inventory")), false);
        player.sendMessage(Text.literal("  §7▸ §d" + (zh ? "右键" : "Right-click") + " §7"
            + (zh ? "打开机器人物品栏 GUI" : "to open robot inventory GUI")), false);
        player.sendMessage(Text.literal("  §7▸ §d" + (zh ? "手持物品右键" : "Right-click with item") + " §7"
            + (zh ? "存入物品到机器人" : "to deposit into robot")), false);
        player.sendMessage(Text.literal("  §7▸ §d" + (zh ? "潜行+右键" : "Shift+right-click") + " §7"
            + (zh ? "认领/解绑机器人" : "to claim/release robot")), false);
        player.sendMessage(ChatFormatter.blank(), false);
        player.sendMessage(ChatFormatter.divider(), false);
        player.sendMessage(ChatFormatter.entry(
            zh ? "当前提供商" : "Active Provider",
            config.getProviderDisplayName(config.getApiProvider()) + " §7· " + config.getModel()), false);
        player.sendMessage(ChatFormatter.entry(
            zh ? "API Key" : "API Key", "§7config/robot-ai.json"), false);
        player.sendMessage(ChatFormatter.entry(
            zh ? "切换提供商" : "Switch Provider", "§d/bot provider <name>"), false);
        player.sendMessage(ChatFormatter.divider(), false);
    }

    // ======================================================================
    //  Model / Provider switch
    // ======================================================================

    private static void handleProviderSwitch(ServerPlayerEntity player, BotConfig config, String name) {
        boolean zh = config.getCommandLanguage().equals("zh");
        if (name.isEmpty()) {
            player.sendMessage(ChatFormatter.entry(
                zh ? "当前提供商" : "Provider",
                config.getProviderDisplayName(config.getApiProvider()) + " · " + config.getModel()), false);
            return;
        }

        // Check if it's a valid provider
        String[] allProviders = BotConfig.getAllProviders();
        boolean found = false;
        for (String p : allProviders) {
            if (p.equals(name)) {
                found = true;
                break;
            }
        }
        if (!found) {
            player.sendMessage(ChatFormatter.error(config,
                zh ? "未知提供商: " + name : "Unknown provider: " + name), false);
            player.sendMessage(ChatFormatter.info(config,
                zh ? "可用: " + String.join(", ", allProviders)
                    : "Available: " + String.join(", ", allProviders)), false);
            return;
        }

        config.setApiProvider(name);
        player.sendMessage(ChatFormatter.success(config,
            "→ §d" + config.getModelDisplayName()), false);
    }

    private static void handleModelSwitch(ServerPlayerEntity player, BotConfig config, String name) {
        boolean zh = config.getCommandLanguage().equals("zh");
        if (name.isEmpty()) {
            player.sendMessage(ChatFormatter.entry(
                zh ? "当前模型" : "Model",
                config.getModel()), false);
            return;
        }
        config.setModel(name);
        player.sendMessage(ChatFormatter.success(config,
            "→ §d" + config.getModelDisplayName()), false);
    }

    // ======================================================================
    //  Memory handlers
    // ======================================================================

    private static void handleRemember(ServerPlayerEntity player, BotConfig config, String fact) {
        if (fact.isEmpty()) {
            player.sendMessage(ChatFormatter.error(config,
                zh(config) ? "请输入要记住的内容" : "Enter something to remember"), false);
            return;
        }
        ServerWorld world = (ServerWorld) player.getEntityWorld();
        MemoryManager.Memory mem = MemoryManager.addMemory(world, player.getUuid(), fact, null);
        String catLabel = switch (mem.category) {
            case "preference" -> zh(config) ? "偏好" : "preference";
            case "location" -> zh(config) ? "位置" : "location";
            case "event" -> zh(config) ? "事件" : "event";
            case "command" -> zh(config) ? "指令" : "command";
            case "fact" -> zh(config) ? "事实" : "fact";
            default -> mem.category;
        };
        player.sendMessage(ChatFormatter.success(config,
            (zh(config) ? "已记住 ✓  ID: §d" : "Remembered ✓  ID: §d")
            + mem.id + " §8[" + catLabel + "]"), false);
    }

    private static void handleForget(ServerPlayerEntity player, BotConfig config, String memId) {
        if (memId.isEmpty()) {
            player.sendMessage(ChatFormatter.error(config,
                zh(config) ? "请输入要删除的记忆 ID" : "Enter memory ID to forget"), false);
            return;
        }
        ServerWorld world = (ServerWorld) player.getEntityWorld();
        boolean deleted = MemoryManager.deleteMemory(world, player.getUuid(), memId);
        if (deleted) {
            player.sendMessage(ChatFormatter.success(config,
                zh(config) ? "已忘记 ✓" : "Forgotten ✓"), false);
        } else {
            player.sendMessage(ChatFormatter.error(config,
                zh(config) ? "找不到该记忆 ID: " + memId : "Memory not found: " + memId), false);
        }
    }

    private static void handleListMemories(ServerPlayerEntity player, BotConfig config) {
        ServerWorld world = (ServerWorld) player.getEntityWorld();
        List<String> memories = MemoryManager.listMemories(world, player.getUuid());
        if (memories.isEmpty()) {
            player.sendMessage(ChatFormatter.info(config,
                zh(config) ? "暂无记忆。用 §d/bot remember <内容> §7来添加" : "No memories yet. Use §d/bot remember <fact> §7to add"), false);
            return;
        }
        player.sendMessage(ChatFormatter.divider(), false);
        player.sendMessage(ChatFormatter.title(
            zh(config) ? "记忆列表 §7(" + memories.size() + ")" : "Memories §7(" + memories.size() + ")"), false);
        player.sendMessage(ChatFormatter.blank(), false);
        for (String m : memories) {
            player.sendMessage(Text.literal("  " + m), false);
        }
        player.sendMessage(ChatFormatter.blank(), false);
        player.sendMessage(ChatFormatter.divider(), false);
        player.sendMessage(ChatFormatter.info(config,
            zh(config) ? "§7提示: §d/bot memory search <关键词> §7搜索  §d/bot memory update <id> <内容> §7更新  §d/bot memory clear §7清除全部"
            : "§7Tip: §d/bot memory search <query> §7search  §d/bot memory update <id> <new> §7update  §d/bot memory clear §7clear all"), false);
    }

    private static void handleMemorySearch(ServerPlayerEntity player, BotConfig config, String query) {
        if (query.isEmpty()) {
            player.sendMessage(ChatFormatter.error(config,
                zh(config) ? "请输入搜索关键词" : "Enter a search query"), false);
            return;
        }
        ServerWorld world = (ServerWorld) player.getEntityWorld();
        List<MemoryManager.Memory> results = MemoryManager.searchMemories(world, player.getUuid(), query, null, 10);
        if (results.isEmpty()) {
            player.sendMessage(ChatFormatter.info(config,
                zh(config) ? "未找到匹配 「" + query + "」的记忆" : "No memories matching \"" + query + "\""), false);
            return;
        }
        player.sendMessage(ChatFormatter.divider(), false);
        player.sendMessage(ChatFormatter.title(
            zh(config) ? "搜索: " + query + " §7(" + results.size() + " 条)" : "Search: " + query + " §7(" + results.size() + " results)"), false);
        player.sendMessage(ChatFormatter.blank(), false);
        for (MemoryManager.Memory m : results) {
            String preview = m.content.length() > 60 ? m.content.substring(0, 60) + "…" : m.content;
            String stars = "⭐".repeat(m.importance);
            player.sendMessage(Text.literal(String.format("  §d%s §8| §7[%s] §8| %s§f%s",
                m.id, m.category, stars, preview)), false);
        }
        player.sendMessage(ChatFormatter.blank(), false);
        player.sendMessage(ChatFormatter.divider(), false);
    }

    private static void handleMemoryUpdate(ServerPlayerEntity player, BotConfig config, String args) {
        String[] parts = args.split("\\s+", 2);
        if (parts.length < 2) {
            player.sendMessage(ChatFormatter.error(config,
                zh(config) ? "用法: /bot memory update <id> <新内容>" : "Usage: /bot memory update <id> <new content>"), false);
            return;
        }
        String memId = parts[0];
        String newContent = parts[1];
        ServerWorld world = (ServerWorld) player.getEntityWorld();
        MemoryManager.Memory updated = MemoryManager.updateMemory(world, player.getUuid(), memId, newContent, null, null);
        if (updated != null) {
            player.sendMessage(ChatFormatter.success(config,
                zh(config) ? "记忆 " + memId + " 已更新 ✓" : "Memory " + memId + " updated ✓"), false);
        } else {
            player.sendMessage(ChatFormatter.error(config,
                zh(config) ? "找不到该记忆 ID: " + memId : "Memory not found: " + memId), false);
        }
    }

    private static void handleMemoryClear(ServerPlayerEntity player, BotConfig config) {
        ServerWorld world = (ServerWorld) player.getEntityWorld();
        MemoryManager.clearAll(world, player.getUuid());
        player.sendMessage(ChatFormatter.success(config,
            zh(config) ? "所有记忆已清除 ✓" : "All memories cleared ✓"), false);
    }

    private static void handleMemoryLink(ServerPlayerEntity player, BotConfig config, String args) {
        String[] parts = args.split("\\s+", 2);
        if (parts.length < 2) {
            player.sendMessage(ChatFormatter.error(config,
                zh(config) ? "用法: /bot memory link <id1> <id2>" : "Usage: /bot memory link <id1> <id2>"), false);
            return;
        }
        ServerWorld world = (ServerWorld) player.getEntityWorld();
        MemoryManager.linkMemories(world, player.getUuid(), parts[0], parts[1]);
        player.sendMessage(ChatFormatter.success(config,
            zh(config) ? "已将 " + parts[0] + " ↔ " + parts[1] + " 链接 ✓" : "Linked " + parts[0] + " ↔ " + parts[1] + " ✓"), false);
    }

    private static void handleMemoryUnlink(ServerPlayerEntity player, BotConfig config, String args) {
        String[] parts = args.split("\\s+", 2);
        if (parts.length < 2) {
            player.sendMessage(ChatFormatter.error(config,
                zh(config) ? "用法: /bot memory unlink <id1> <id2>" : "Usage: /bot memory unlink <id1> <id2>"), false);
            return;
        }
        ServerWorld world = (ServerWorld) player.getEntityWorld();
        MemoryManager.unlinkMemories(world, player.getUuid(), parts[0], parts[1]);
        player.sendMessage(ChatFormatter.success(config,
            zh(config) ? "已取消 " + parts[0] + " ↔ " + parts[1] + " 的链接 ✓" : "Unlinked " + parts[0] + " ↔ " + parts[1] + " ✓"), false);
    }

    private static void handleMemoryImportance(ServerPlayerEntity player, BotConfig config, String args) {
        String[] parts = args.split("\\s+", 2);
        if (parts.length < 2) {
            player.sendMessage(ChatFormatter.error(config,
                zh(config) ? "用法: /bot memory importance <id> <1-5>" : "Usage: /bot memory importance <id> <1-5>"), false);
            return;
        }
        try {
            int importance = Integer.parseInt(parts[1]);
            if (importance < 1 || importance > 5) {
                player.sendMessage(ChatFormatter.error(config,
                    zh(config) ? "重要性范围 1-5" : "Importance must be 1-5"), false);
                return;
            }
            ServerWorld world = (ServerWorld) player.getEntityWorld();
            MemoryManager.Memory mem = MemoryManager.getMemoryById(world, player.getUuid(), parts[0]);
            if (mem == null) {
                player.sendMessage(ChatFormatter.error(config,
                    zh(config) ? "找不到该记忆 ID: " + parts[0] : "Memory not found: " + parts[0]), false);
                return;
            }
            MemoryManager.updateMemory(world, player.getUuid(), parts[0], mem.content, importance, mem.category);
            player.sendMessage(ChatFormatter.success(config,
                zh(config) ? "记忆 " + parts[0] + " 重要性设为 " + "⭐".repeat(importance) + " ✓"
                : "Memory " + parts[0] + " importance set to " + "⭐".repeat(importance) + " ✓"), false);
        } catch (NumberFormatException e) {
            player.sendMessage(ChatFormatter.error(config,
                zh(config) ? "请输入数字 1-5" : "Enter a number 1-5"), false);
        }
    }

    private static boolean zh(BotConfig config) {
        return config.getCommandLanguage().equals("zh");
    }

    // ======================================================================
    //  Generate robot
    // ======================================================================

    private static void handleGenerate(ServerPlayerEntity player, BotConfig config) {
        ServerWorld world = (ServerWorld) player.getEntityWorld();
        boolean zh = config.getCommandLanguage().equals("zh");

        // Check if robot already exists in this world
        if (findRobotInWorld(world) != null) {
            player.sendMessage(ChatFormatter.error(config,
                zh ? "这个世界已经有一个机器人了！" : "A robot already exists in this world!"), false);
            return;
        }

        // Spawn robot at player's position
        BlockPos spawnPos = player.getBlockPos();
        RobotEntity robot = com.aibot.Registry.ROBOT_ENTITY.spawn(
            world,
            spawnPos,
            net.minecraft.entity.SpawnReason.COMMAND
        );

        if (robot != null) {
            // Set player as owner
            robot.setOwner(player);
            // Register in persistent state so robot can be found even when chunk unloads
            robot.registerInWorld();
            player.sendMessage(ChatFormatter.success(config,
                zh ? "机器人已生成！使用 /bot 指令控制它" : "Robot spawned! Use /bot to control it"), false);
        } else {
            player.sendMessage(ChatFormatter.error(config,
                zh ? "生成机器人失败" : "Failed to spawn robot"), false);
        }
    }

    // ======================================================================
    //  Robot finder — world-wide singleton lookup
    // ======================================================================

    /**
     * Find the single robot in the world. Uses multiple fallback strategies:
     * 1. Search loaded entities (fast path)
     * 2. Check static cached reference
     * 3. Check persistent world data + force-load chunk
     * Returns null only if no robot has ever existed in this world.
     */
    private static RobotEntity findRobotInWorld(ServerWorld world) {
        if (world == null) return null;

        // Path 1: Search loaded entities (works most of the time)
        var loaded = world.getEntitiesByType(
            net.minecraft.util.TypeFilter.instanceOf(RobotEntity.class),
            r -> r.isAlive());
        List<RobotEntity> robots = new java.util.ArrayList<>();
        for (RobotEntity r : loaded) robots.add(r);
        if (!robots.isEmpty()) return robots.get(0);

        // Path 2: Check static cached reference (may still be alive in loaded chunk)
        RobotEntity cached = RobotEntity.getCachedInstance();
        if (cached != null && cached.isAlive()) {
            // Verify it's in the same world
            if (cached.getEntityWorld() == world) {
                return cached;
            }
        }

        // Path 3: Check persistent world data for robot UUID + position
        RobotWorldData data = RobotWorldData.getOrCreate(world);
        java.util.UUID savedUuid = data.getRobotUuid();
        if (savedUuid == null) return null;

        // Try world.getEntity() — sometimes works for tracked entities
        net.minecraft.entity.Entity entity = world.getEntity(savedUuid);
        if (entity instanceof RobotEntity robot && robot.isAlive()) return robot;

        // Path 4: Force-load the chunk at the last known position and retry
        BlockPos lastPos = data.getLastKnownPos();
        if (lastPos != null) {
            try {
                // Synchronously load the chunk
                world.getChunk(lastPos);
                // Retry entity search now that chunk is loaded
                var retry = world.getEntitiesByType(
                    net.minecraft.util.TypeFilter.instanceOf(RobotEntity.class),
                    r -> r.isAlive());
                List<RobotEntity> retryRobots = new java.util.ArrayList<>();
                for (RobotEntity r : retry) retryRobots.add(r);
                if (!retryRobots.isEmpty()) return retryRobots.get(0);
            } catch (Exception e) {
                System.err.println("[AIBot] Failed to load chunk for robot: " + e.getMessage());
            }
        }

        return null;
    }
}
