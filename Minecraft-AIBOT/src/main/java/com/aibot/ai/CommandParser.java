package com.aibot.ai;

import com.google.gson.*;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the JSON response from the DeepSeek API into a list of ParsedAction objects.
 * The expected response format is:
 * {
 *   "actions": [
 *     {"type": "follow", "target": "owner"},
 *     {"type": "chat", "message": "I'll follow you!"},
 *     ...
 *   ]
 * }
 */
public class CommandParser {

    private static final Gson GSON = new Gson();

    /**
     * Parses the raw API response text into a list of executable actions.
     * Handles various response formats:
     * - Pure JSON: {"actions": [...]}
     * - JSON array: [...]
     * - Markdown-wrapped JSON (```json ... ```)
     * - Plain text (treated as a chat response)
     */
    public static List<ParsedAction> parse(String rawResponse) {
        List<ParsedAction> actions = new ArrayList<>();

        if (rawResponse == null || rawResponse.isBlank()) {
            ParsedAction idle = new ParsedAction();
            idle.type = ActionTypes.IDLE;
            idle.message = "I didn't understand that.";
            actions.add(idle);
            return actions;
        }

        String json = extractJson(rawResponse);

        if (json == null) {
            // Treat as plain text chat response
            ParsedAction chat = new ParsedAction();
            chat.type = ActionTypes.CHAT;
            chat.message = rawResponse.trim();
            actions.add(chat);
            return actions;
        }

        try {
            JsonElement element = JsonParser.parseString(json);

            if (element.isJsonArray()) {
                // Direct array of actions
                JsonArray arr = element.getAsJsonArray();
                for (JsonElement e : arr) {
                    ParsedAction action = parseSingleAction(e.getAsJsonObject());
                    if (action != null) actions.add(action);
                }
            } else if (element.isJsonObject()) {
                JsonObject obj = element.getAsJsonObject();
                if (obj.has("actions")) {
                    // {"actions": [...]}
                    JsonArray arr = obj.getAsJsonArray("actions");
                    for (JsonElement e : arr) {
                        ParsedAction action = parseSingleAction(e.getAsJsonObject());
                        if (action != null) actions.add(action);
                    }
                } else {
                    // Single action object
                    ParsedAction action = parseSingleAction(obj);
                    if (action != null) actions.add(action);
                }
            }
        } catch (JsonSyntaxException e) {
            System.err.println("[AIBot] Failed to parse JSON response: " + e.getMessage());
            ParsedAction chat = new ParsedAction();
            chat.type = ActionTypes.CHAT;
            chat.message = rawResponse.trim();
            actions.add(chat);
        }

        if (actions.isEmpty()) {
            ParsedAction idle = new ParsedAction();
            idle.type = ActionTypes.IDLE;
            idle.message = "OK.";
            actions.add(idle);
        }

        return actions;
    }

    /**
     * Extracts JSON from a response that might be wrapped in markdown code fences.
     */
    private static String extractJson(String text) {
        String trimmed = text.trim();

        // Try to extract from ```json ... ``` block
        if (trimmed.contains("```json")) {
            int start = trimmed.indexOf("```json") + 7;
            int end = trimmed.indexOf("```", start);
            if (end > start) {
                return trimmed.substring(start, end).trim();
            }
        }
        if (trimmed.contains("```")) {
            int start = trimmed.indexOf("```") + 3;
            int end = trimmed.indexOf("```", start);
            if (end > start) {
                String inner = trimmed.substring(start, end).trim();
                // Remove optional language tag
                int newline = inner.indexOf('\n');
                if (newline > 0 && newline < 20) {
                    inner = inner.substring(newline + 1).trim();
                }
                return inner;
            }
        }

        // Check if it looks like JSON
        if ((trimmed.startsWith("{") || trimmed.startsWith("[")) &&
            (trimmed.endsWith("}") || trimmed.endsWith("]"))) {
            return trimmed;
        }

        return null;
    }

    private static ParsedAction parseSingleAction(JsonObject obj) {
        if (obj == null || !obj.has("type")) return null;

        ParsedAction action = new ParsedAction();

        String typeStr = obj.get("type").getAsString().toLowerCase();
        try {
            action.type = ActionTypes.valueOf(typeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            System.err.println("[AIBot] Unknown action type: " + typeStr);
            return null;
        }

        // Parse common fields
        if (obj.has("target")) {
            action.target = obj.get("target").getAsString();
        }
        if (obj.has("message")) {
            action.message = obj.get("message").getAsString();
        }
        if (obj.has("item") || obj.has("itemName")) {
            JsonElement item = obj.has("item") ? obj.get("item") : obj.get("itemName");
            action.itemName = item.getAsString();
        }
        if (obj.has("quantity")) {
            action.quantity = obj.get("quantity").getAsInt();
        }
        if (obj.has("command")) {
            action.command = obj.get("command").getAsString();
        }
        if (obj.has("structure")) {
            action.structure = obj.get("structure").getAsString();
        }
        if (obj.has("size")) {
            action.size = obj.get("size").getAsString();
        }
        if (obj.has("material")) {
            action.material = obj.get("material").getAsString();
        }
        if (obj.has("description")) {
            action.description = obj.get("description").getAsString();
        }

        // Parse commands array (multi-command sequences for custom builds)
        if (obj.has("commands")) {
            JsonArray cmds = obj.getAsJsonArray("commands");
            action.commands = new ArrayList<>();
            for (JsonElement e : cmds) {
                action.commands.add(e.getAsString());
            }
        } else if (obj.has("command") && action.command != null) {
            // Single command: wrap in list for uniform handling
            action.commands = new ArrayList<>();
            action.commands.add(action.command);
        }

        // Parse position if present
        if (obj.has("position")) {
            JsonObject pos = obj.getAsJsonObject("position");
            int x = pos.has("x") ? pos.get("x").getAsInt() : 0;
            int y = pos.has("y") ? pos.get("y").getAsInt() : 0;
            int z = pos.has("z") ? pos.get("z").getAsInt() : 0;
            action.position = new BlockPos(x, y, z);
        }
        // Alternatively, parse x, y, z directly
        if (action.position == null && obj.has("x") && obj.has("y") && obj.has("z")) {
            action.position = new BlockPos(
                obj.get("x").getAsInt(),
                obj.get("y").getAsInt(),
                obj.get("z").getAsInt()
            );
        }

        return action;
    }
}
