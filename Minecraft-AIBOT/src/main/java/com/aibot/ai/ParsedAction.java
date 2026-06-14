package com.aibot.ai;

import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * Parsed action from the AI. Contains the action type and relevant parameters.
 */
public class ParsedAction {
    public ActionTypes type;
    public String target;      // entity name, UUID, "owner", "nearest_hostile", etc.
    public String message;     // for CHAT action
    public BlockPos position;  // for MINE, PLACE, GOTO actions
    public String itemName;    // for EQUIP, EAT, CRAFT actions
    public int quantity;       // for MINE, COLLECT actions
    public String command;     // for COMMAND action (e.g. "setblock ~ ~1 ~ grass_block")
    public String structure;   // for BUILD action ("house", "wall", "tower", "hut")
    public String size;        // for BUILD action ("small", "medium", "large")
    public String material;    // for BUILD action ("oak", "stone", "birch")
    public String description; // for custom BUILD — freeform description of what to build
    public List<String> commands; // for multi-command sequences (e.g. /fill for custom shapes)

    public ParsedAction() {
        this.type = ActionTypes.IDLE;
    }

    @Override
    public String toString() {
        return "ParsedAction{type=" + type + ", target='" + target + "', pos=" + position + "}";
    }
}
