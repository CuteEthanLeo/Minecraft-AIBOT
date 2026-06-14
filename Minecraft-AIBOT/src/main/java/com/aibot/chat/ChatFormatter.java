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
    private static final String WARN   = "§e⚠ §7";    // warning
    private static final String SWORD  = "§c⚔ §7";    // combat
    private static final String BUILD  = "§6🏗 §7";   // building
    private static final String MINE   = "§6⛏ §7";    // mining

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

    /** [Tag] ⚠ message  (warning) */
    public static Text warn(BotConfig config, String message) {
        return Text.literal(tag(config) + " " + WARN + "§f" + message);
    }

    /** [Tag] ⟳ message  (processing/thinking) */
    public static Text processing(BotConfig config, String message) {
        return Text.literal(tag(config) + " " + GEAR + "§7" + message);
    }

    /** [Tag] §e➤ action  (action being taken) */
    public static Text action(BotConfig config, String actionDesc) {
        return Text.literal(tag(config) + " §e➤ §f" + actionDesc);
    }

    /** [Tag] ⚔ combat action */
    public static Text combat(BotConfig config, String message) {
        return Text.literal(tag(config) + " " + SWORD + "§f" + message);
    }

    /** [Tag] 🏗 building action */
    public static Text building(BotConfig config, String message) {
        return Text.literal(tag(config) + " " + BUILD + "§f" + message);
    }

    /** [Tag] ⛏ mining action */
    public static Text mining(BotConfig config, String message) {
        return Text.literal(tag(config) + " " + MINE + "§f" + message);
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

    /**
     * Renders a progress bar.
     * e.g. §7[§a████████░░§7] §f80%
     */
    public static Text progressBar(BotConfig config, String label, int current, int total) {
        int barWidth = 10;
        int filled = total > 0 ? (int) Math.round((double) current / total * barWidth) : 0;
        filled = Math.max(0, Math.min(barWidth, filled));
        String bar = "§a" + "█".repeat(filled) + "§8" + "░".repeat(barWidth - filled);
        int pct = total > 0 ? current * 100 / total : 0;
        return Text.literal(tag(config) + " §7" + label + " [" + bar + "§7] §f" + pct + "%");
    }

    /**
     * Strips §-color codes from AI-generated text to prevent format injection.
     */
    public static String stripFormatCodes(String text) {
        if (text == null) return "";
        return text.replaceAll("§[0-9a-fk-or]", "");
    }
}
