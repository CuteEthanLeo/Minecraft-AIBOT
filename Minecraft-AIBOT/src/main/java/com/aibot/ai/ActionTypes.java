package com.aibot.ai;

import com.google.gson.annotations.SerializedName;

/**
 * Defines all possible actions the robot can perform,
 * as returned by the DeepSeek API in structured JSON format.
 */
public enum ActionTypes {

    /** Follow the specified entity/player. Target: "owner" or entity UUID string. */
    @SerializedName("follow")
    FOLLOW,

    /** Stop moving and stay at current position. */
    @SerializedName("stay")
    STAY,

    /** Attack the specified entity. Target: entity description or "nearest_hostile". */
    @SerializedName("attack")
    ATTACK,

    /** Break the block at the specified position. */
    @SerializedName("mine")
    MINE,

    /** Place a block from inventory at the specified position. */
    @SerializedName("place")
    PLACE,

    /** Collect nearby dropped items. */
    @SerializedName("collect")
    COLLECT,

    /** Equip an item from inventory (weapon, tool, armor). */
    @SerializedName("equip")
    EQUIP,

    /** Eat food from inventory. */
    @SerializedName("eat")
    EAT,

    /** Navigate to the specified coordinates. */
    @SerializedName("goto")
    GOTO,

    /** Send a chat message back to the owner. */
    @SerializedName("chat")
    CHAT,

    /** Scan the surroundings and report back entities/blocks. */
    @SerializedName("scan")
    SCAN,

    /** Attempt to craft an item (if materials are available). */
    @SerializedName("craft")
    CRAFT,

    /** Idle / do nothing. */
    @SerializedName("idle")
    IDLE,

    /** Execute a Minecraft command (e.g. /setblock, /tp). Runs as console. */
    @SerializedName("command")
    COMMAND,

    /** Teleport the robot to a target (owner or coordinates). */
    @SerializedName("tp")
    TP,

    /** Build a structure (house, wall, tower, etc.). Uses structure/size/material fields. */
    @SerializedName("build")
    BUILD
}
