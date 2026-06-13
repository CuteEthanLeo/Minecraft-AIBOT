package com.aibot.chat;

import com.aibot.config.BotConfig;
import net.minecraft.text.Text;

/**
 * Premium chat formatter for AI-Bot.
 * Clean, modern, professional design with consistent styling.
 */
public class ChatFormatter {

    // ─── Tag ───
    // §8[ §d✦ §5AI-Bot §8]  — elegant purple-gold premium tag
    private static String tag(BotConfig config) {
        return "§8[§d✦ §5" + config.getModelDisplayName() + "§8]";
    }

    // ─── Prefix icons ───
    private static final String ARROW  = "§7▸ ";        // neutral arrow
    private static final String CHECK  = "§a✔ §7";     // success
    private static final String CROSS  = "§c✘ §7";     // error
    private static final String GEAR   = "§8⟳ §7";    // processing
    private static final String BULLET = "§7• ";        // list item

    // ─── Public API ───

    /** [Tag] message */
    public static Text msg(BotConfig config, String message) {
        return Text.literal(tag(config) + " " + message);
    }

    /** [Tag] ▸ message  (neutral info) */
    public static Text info(BotConfig config, String message) {
        return Text.literal(tag(config) + " " + ARROW + "§f" + message);
    }

    /** [Tag] ✔ message  (success) */
    public static Text success(BotConfig config, String message) {
        return Text.literal(tag(config) + " " + CHECK + "§f" + message);
    }

    /** [Tag] ✘ message  (error) */
    public static Text error(BotConfig config, String message) {
        return Text.literal(tag(config) + " " + CROSS + "§f" + message);
    }

    /** [Tag] ⟳ message  (processing/thinking) */
    public static Text processing(BotConfig config, String message) {
        return Text.literal(tag(config) + " " + GEAR + "§7" + message);
    }

    /** [Tag] §e➤ action  (action being taken) */
    public static Text action(BotConfig config, String actionDesc) {
        return Text.literal(tag(config) + " §e➤ §f" + actionDesc);
    }

    /** §8┃  (divider start) */
    public static Text divider() {
        return Text.literal("§8§m────────────────────────────────────────────");
    }

    /** §8┃   centered title */
    public static Text title(String title) {
        return Text.literal("§5   §l✦ " + title + " ✦");
    }

    /** Empty line */
    public static Text blank() {
        return Text.literal("");
    }

    /** §7• label: §fvalue */
    public static Text entry(String label, String value) {
        return Text.literal(BULLET + "§d" + label + ": §f" + value);
    }
}
