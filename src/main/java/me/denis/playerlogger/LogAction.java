package me.denis.playerlogger;

import java.util.Locale;

/** Every loggable action. The key is also the config section name under "events". */
public enum LogAction {
    COMMAND("command", "Command", "⌨️", 0x3498DB, true),
    CHAT("chat", "Chat", "💬", 0x2ECC71, true),
    SIGN("sign", "Sign", "📝", 0x2ECC71, true),
    BOOK("book", "Book", "📖", 0x2ECC71, true),
    JOIN("join", "Join", "📥", 0x95A5A6, true),
    QUIT("quit", "Quit", "📤", 0x95A5A6, true),
    KICK("kick", "Kick", "👢", 0xE67E22, true),
    DEATH("death", "Death", "💀", 0x7F8C8D, true),
    TELEPORT("teleport", "Teleport", "🌀", 0x9B59B6, true),
    GAMEMODE("gamemode", "Gamemode", "🎮", 0x9B59B6, true),
    BLOCK_BREAK("block-break", "Block Break", "⛏️", 0xF1C40F, true),
    BLOCK_PLACE("block-place", "Block Place", "🧱", 0xF1C40F, true),
    ANVIL("anvil", "Anvil", "🔨", 0xE67E22, true),
    ENCHANT("enchant", "Enchant", "✨", 0x1ABC9C, true),
    BUCKET("bucket", "Bucket", "🪣", 0xE74C3C, true),
    VILLAGER_KILL("villager-kill", "Villager Kill", "☠️", 0xC0392B, true),
    VILLAGER_DAMAGE("villager-damage", "Villager Damage", "🗡️", 0xC0392B, false),
    VILLAGER_TRADE("villager-trade", "Villager Trade", "💎", 0x2ECC71, true),
    VILLAGER_CURE("villager-cure", "Villager Cure", "🍎", 0x2ECC71, true),
    VILLAGER_NAME("villager-name", "Villager Nametag", "🏷️", 0x2ECC71, true),
    TPS_ALERT("tps-alert", "TPS Alert", "📉", 0xE67E22, true),
    DAILY_SUMMARY("daily-summary", "Daily Summary", "📊", 0x3498DB, true);

    private final String key;
    private final String display;
    private final String emoji;
    private final int color;
    private final boolean defaultEnabled;

    LogAction(String key, String display, String emoji, int color, boolean defaultEnabled) {
        this.key = key;
        this.display = display;
        this.emoji = emoji;
        this.color = color;
        this.defaultEnabled = defaultEnabled;
    }

    public String key() { return key; }
    public String display() { return display; }
    public String emoji() { return emoji; }
    public int color() { return color; }
    public boolean defaultEnabled() { return defaultEnabled; }

    public static LogAction byKey(String key) {
        if (key == null) return null;
        String k = key.toLowerCase(Locale.ROOT);
        for (LogAction a : values()) {
            if (a.key.equals(k)) return a;
        }
        return null;
    }
}
