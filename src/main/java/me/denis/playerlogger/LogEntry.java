package me.denis.playerlogger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One log line. Player entries have a uuid; system entries (TPS alert, daily summary)
 * have uuid == null and may carry a description and Discord embed fields instead.
 */
public record LogEntry(
        LogAction action,
        String player,
        UUID uuid,
        String world,
        int x, int y, int z,
        String gamemode,
        int ping,
        String title,
        String detail,
        String description,
        boolean sensitive,
        Integer colorOverride,
        List<Field> fields,
        Instant time) {

    public record Field(String name, String value, boolean inline) {}

    public boolean isSystem() {
        return uuid == null;
    }
}
