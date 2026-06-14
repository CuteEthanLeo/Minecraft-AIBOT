package com.aibot.ai;

import java.util.*;

/**
 * Manages conversation history for AI context across commands.
 * Stores message turns, estimates token counts, and trims oldest
 * history when the budget is exceeded.
 *
 * Always preserves the system prompt (first message) when trimming.
 */
public class ConversationHistory {

    private final List<Map<String, String>> messages = new ArrayList<>();
    private int maxTokens = 4096;
    private static final int MAX_RAW_MESSAGES = 200; // hard cap to prevent unbounded growth

    /** Rough estimation: 1 token ≈ 4 characters (Chinese: ~2 chars/token) */
    private static final double CHARS_PER_TOKEN = 4.0;

    public ConversationHistory() {}

    public ConversationHistory(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    /**
     * Add a message with the given role and content.
     */
    public void addMessage(String role, String content) {
        Map<String, String> msg = new LinkedHashMap<>();
        msg.put("role", role);
        msg.put("content", content);
        messages.add(msg);
        // Trim raw list when it grows too large to prevent memory leak
        if (messages.size() > MAX_RAW_MESSAGES) {
            // Keep the first message (system) and trim oldest user/assistant turns
            messages.subList(1, messages.size() - MAX_RAW_MESSAGES / 2).clear();
        }
    }

    public void addUserMessage(String content) {
        addMessage("user", content);
    }

    public void addAssistantMessage(String content) {
        addMessage("assistant", content);
    }

    /**
     * Get all messages (untrimmed).
     */
    public List<Map<String, String>> getMessages() {
        return Collections.unmodifiableList(messages);
    }

    /**
     * Get messages with the system prompt (first entry) always preserved,
     * and the most recent user/assistant turns kept while trimming older ones
     * to stay within the token budget.
     *
     * @param systemTokenEstimate Pre-calculated token count for the system prompt
     * @return A new list with trimmed messages suitable for the API call
     */
    public List<Map<String, String>> getTrimmedMessages(int systemTokenEstimate) {
        int budget = maxTokens;
        List<Map<String, String>> result = new ArrayList<>();

        if (messages.isEmpty()) return result;

        // Always keep the first message (system prompt)
        Map<String, String> systemMsg = messages.get(0);
        result.add(systemMsg);
        budget -= systemTokenEstimate;

        if (budget <= 0) return result;

        // Walk from the end (newest) backward, collecting messages that fit
        List<Map<String, String>> tail = new ArrayList<>();
        for (int i = messages.size() - 1; i > 0; i--) {
            Map<String, String> msg = messages.get(i);
            int estimated = estimateTokens(msg.get("content"));
            if (estimated > budget) {
                continue; // Skip this single message and try older ones
            }
            tail.add(msg);
            budget -= estimated;
        }

        // Reverse to restore chronological order
        for (int i = tail.size() - 1; i >= 0; i--) {
            result.add(tail.get(i));
        }

        return result;
    }

    /**
     * Set the maximum token budget for context.
     */
    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    /**
     * Clear all conversation history.
     */
    public void clear() {
        messages.clear();
    }

    /**
     * Rough token estimation: count chars / CHARS_PER_TOKEN.
     * For mixed Chinese/English, this is a reasonable approximation.
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }
}
