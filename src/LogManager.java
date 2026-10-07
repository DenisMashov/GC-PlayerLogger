package gc.playerlogger;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Central place that every listener sends its logs to.
 * NOTE: log() / logSystem() must be called from the main server thread.
 */
public final class LogManager {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter PERIOD =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Pattern MC_NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private static final String DEFAULT_FORMAT =
            "<dark_gray>[<gold>PLog</gold>]</dark_gray> <c><bold><title></bold></c> <dark_gray>|</dark_gray> "
                    + "<hover:show_text:'<gray>World: <white><world></white><newline>Location: <white><x>, <y>, <z></white>"
                    + "<newline>Gamemode: <white><gamemode></white><newline>Ping: <white><ping> ms</white>"
                    + "<newline><yellow>Click to fill /tp</yellow></gray>'>"
                    + "<click:suggest_command:'/tp <x> <y> <z>'><yellow><player></yellow></click></hover>"
                    + "<dark_gray> » </dark_gray><white><detail></white>";
    private static final String DEFAULT_SYSTEM_FORMAT =
            "<dark_gray>[<gold>PLog</gold>]</dark_gray> <c><bold><title></bold></c><dark_gray> » </dark_gray><white><detail></white>";

    private final PlayerLoggerPlugin plugin;
    private final StatsTracker stats = new StatsTracker();
    private final Set<UUID> mutedStaff = ConcurrentHashMap.newKeySet();
    private final ExecutorService fileExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "gc-playerlogger-File");
        t.setDaemon(true);
        return t;
    });

    private volatile Set<String> ignoredPlayers = Set.of();
    private volatile Set<String> ignoredCommands = Set.of();
    private volatile Set<String> sensitiveCommands = Set.of();
    private volatile Set<String> maskedCommands = Set.of();
    private volatile Set<String> sensitiveWords = Set.of();
    private volatile Set<String> watchedBlocks = Set.of();
    private volatile Set<String> sensitiveBlocks = Set.of();
    private volatile Set<String> teleportCauses = Set.of();
    private volatile Set<String> watchedBuckets = Set.of();
    private volatile Set<String> tradeOnlyItems = Set.of();

    private volatile DiscordWebhook discord;
    private BukkitTask discordTask;

    public LogManager(PlayerLoggerPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ lifecycle

    public void loadStats() {
        stats.load(statsFile());
    }

    public void reload() {
        FileConfiguration c = plugin.getConfig();

        ignoredPlayers = lower(c.getStringList("general.ignored-players"));
        ignoredCommands = lower(c.getStringList("filters.commands.ignored"));
        sensitiveCommands = lower(c.getStringList("filters.commands.sensitive"));
        maskedCommands = lower(c.getStringList("filters.commands.mask-args"));
        sensitiveWords = lower(c.getStringList("filters.chat.sensitive-words"));
        watchedBlocks = upper(c.getStringList("filters.blocks.watched"));
        sensitiveBlocks = upper(c.getStringList("filters.blocks.sensitive"));
        teleportCauses = upper(c.getStringList("filters.teleport.causes"));
        watchedBuckets = upper(c.getStringList("filters.buckets.watched"));
        tradeOnlyItems = upper(c.getStringList("filters.villager.trade-only-items"));

        if (discordTask != null) {
            discordTask.cancel();
            discordTask = null;
        }
        discord = null;

        if (c.getBoolean("discord.enabled", false)) {
            discord = new DiscordWebhook(
                    plugin,
                    c.getString("discord.username", "gc-playerlogger"),
                    c.getString("discord.avatar-url", ""),
                    c.getString("discord.mention", ""),
                    c.getString("discord.bot-token", ""));
            long interval = Math.max(20L, c.getLong("discord.send-interval-ticks", 40L));
            discordTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
                DiscordWebhook d = discord;
                if (d != null) d.flush(false);
            }, interval, interval);

            boolean anyDestination = false;
            for (LogAction a : LogAction.values()) {
                if (resolveDestination(a) != null) {
                    anyDestination = true;
                    break;
                }
            }
            if (!anyDestination) {
                plugin.getLogger().warning("Discord is enabled but no webhook-url / channel-id is set - nothing will be sent to Discord.");
            }
        }

        cleanupOldLogs();
    }

    public void shutdown() {
        if (discordTask != null) {
            discordTask.cancel();
        }
        DiscordWebhook d = discord;
        if (d != null) {
            d.flush(true);
        }
        try {
            Files.writeString(statsFile().toPath(), stats.serialize(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save stats.yml: " + ex.getMessage());
        }
        fileExecutor.shutdown();
        try {
            fileExecutor.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void saveStatsAsync() {
        String data = stats.serialize();
        Path file = statsFile().toPath();
        fileExecutor.execute(() -> {
            try {
                Files.writeString(file, data, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                plugin.getLogger().warning("Could not save stats.yml: " + ex.getMessage());
            }
        });
    }

    private File statsFile() {
        return new File(plugin.getDataFolder(), "stats.yml");
    }

    // ------------------------------------------------------------------ getters

    public StatsTracker stats() { return stats; }
    public Set<String> ignoredCommands() { return ignoredCommands; }
    public Set<String> sensitiveCommands() { return sensitiveCommands; }
    public Set<String> maskedCommands() { return maskedCommands; }
    public Set<String> watchedBlocks() { return watchedBlocks; }
    public Set<String> sensitiveBlocks() { return sensitiveBlocks; }
    public Set<String> teleportCauses() { return teleportCauses; }
    public Set<String> watchedBuckets() { return watchedBuckets; }
    public Set<String> tradeOnlyItems() { return tradeOnlyItems; }

    public boolean hasSensitiveWord(String message) {
        Set<String> words = sensitiveWords;
        if (words.isEmpty()) return false;
        String m = message.toLowerCase(Locale.ROOT);
        for (String w : words) {
            if (!w.isBlank() && m.contains(w)) return true;
        }
        return false;
    }

    /** @return true if alerts are now enabled for this staff member */
    public boolean toggleAlerts(UUID uuid) {
        if (mutedStaff.remove(uuid)) {
            return true;
        }
        mutedStaff.add(uuid);
        return false;
    }

    public boolean isEnabled(LogAction action) {
        return plugin.getConfig().getBoolean(path(action, "enabled"), action.defaultEnabled());
    }

    // ------------------------------------------------------------------ player logging

    public void log(LogAction action, Player player, String detail) {
        log(action, player, action.display(), detail, false, true);
    }

    public void log(LogAction action, Player player, String detail, boolean sensitive) {
        log(action, player, action.display(), detail, sensitive, true);
    }

    public void log(LogAction action, Player player, String title, String detail, boolean sensitive) {
        log(action, player, title, detail, sensitive, true);
    }

    public void log(LogAction action, Player player, String title, String detail,
                    boolean sensitive, boolean respectExempt) {
        if (!isEnabled(action)) return;
        if (respectExempt && (player.hasPermission("playerlogger.exempt")
                || ignoredPlayers.contains(player.getName().toLowerCase(Locale.ROOT)))) {
            return;
        }

        FileConfiguration cfg = plugin.getConfig();
        boolean sens = sensitive || cfg.getBoolean(path(action, "sensitive"), false);

        Location l = player.getLocation();
        LogEntry e = new LogEntry(
                action,
                player.getName(),
                player.getUniqueId(),
                l.getWorld() != null ? l.getWorld().getName() : "unknown",
                l.getBlockX(), l.getBlockY(), l.getBlockZ(),
                player.getGameMode().name().toLowerCase(Locale.ROOT),
                player.getPing(),
                title,
                clean(detail),
                null,
                sens,
                null,
                null,
                Instant.now());

        stats.record(action, e.player(), e.detail(), sens);
        dispatch(e, cfg);
    }

    // ------------------------------------------------------------------ system logging

    public void logSystem(LogAction action, String title, String detail, String description,
                          boolean sensitive, Integer colorOverride, List<LogEntry.Field> fields) {
        if (!isEnabled(action)) return;

        FileConfiguration cfg = plugin.getConfig();
        boolean sens = sensitive || cfg.getBoolean(path(action, "sensitive"), false);

        LogEntry e = new LogEntry(
                action, "Server", null, "-", 0, 0, 0, "-", 0,
                title, clean(detail), description, sens, colorOverride, fields, Instant.now());
        dispatch(e, cfg);
    }

    /** Builds and sends the summary. reset = true starts a new period (scheduled run). */
    public void sendDailySummary(boolean reset) {
        FileConfiguration c = plugin.getConfig();
        int topN = Math.max(1, Math.min(10, c.getInt("monitoring.daily-summary.top-count", 5)));

        StatsTracker.Summary s = stats.summary(topN);
        if (reset) {
            stats.reset();
            if (s.total() == 0 && c.getBoolean("monitoring.daily-summary.skip-if-empty", true)) {
                return;
            }
        }

        LocalDateTime from = LocalDateTime.ofInstant(Instant.ofEpochMilli(s.periodStart()), ZoneId.systemDefault());
        LocalDateTime to = LocalDateTime.now();
        String description = "**Report period**\n`" + PERIOD.format(from) + "` \u2192 `" + PERIOD.format(to) + "`";

        List<LogEntry.Field> fields = new ArrayList<>();
        fields.add(new LogEntry.Field("Total alerts", "`" + s.total() + "`", true));
        fields.add(new LogEntry.Field("Sensitive", "`" + s.sensitive() + "`", true));
        fields.add(new LogEntry.Field("Unique players", "`" + s.uniquePlayers() + "`", true));
        fields.add(new LogEntry.Field("Peak online", "`" + s.peakOnline() + "`", true));
        fields.add(new LogEntry.Field("Top actions", rank(s.actions(), key -> {
            LogAction a = LogAction.byKey(key);
            return a != null ? a.emoji() + " " + a.display() : key;
        }), false));
        fields.add(new LogEntry.Field("Most active players", rank(s.players(), Function.identity()), true));
        fields.add(new LogEntry.Field("Top command users", rank(s.commandUsers(), Function.identity()), true));
        fields.add(new LogEntry.Field("Top commands", rank(s.commands(), k -> "/" + k), false));

        String detail = "Total " + s.total() + " | sensitive " + s.sensitive()
                + " | players " + s.uniquePlayers() + " | peak " + s.peakOnline() + " online";
        if (!s.players().isEmpty()) {
            Map.Entry<String, Integer> first = s.players().get(0);
            detail += " | most active: " + first.getKey() + " (" + first.getValue() + ")";
        }

        logSystem(LogAction.DAILY_SUMMARY, reset ? "Daily Summary" : "Daily Summary (preview)",
                detail, description, false, null, fields);
    }

    private static String rank(List<Map.Entry<String, Integer>> list, Function<String, String> label) {
        if (list.isEmpty()) return "-";
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (Map.Entry<String, Integer> en : list) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(i++).append(". ").append(label.apply(en.getKey())).append(" \u2014 `").append(en.getValue()).append('`');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ dispatch

    private void dispatch(LogEntry e, FileConfiguration cfg) {
        LogAction action = e.action();

        if (cfg.getBoolean("general.log-to-file", true) && cfg.getBoolean(path(action, "file"), true)) {
            writeFile(e);
        }

        if (cfg.getBoolean("ingame.enabled", true) && cfg.getBoolean(path(action, "ingame"), true)) {
            sendIngame(e, cfg);
        }

        DiscordWebhook hook = discord;
        if (hook != null && cfg.getBoolean(path(action, "discord"), true)) {
            String dest = resolveDestination(action);
            if (dest != null) {
                hook.queue(dest, new DiscordWebhook.Item(buildEmbed(e, cfg), e.sensitive()));
            }
        }
    }

    // ------------------------------------------------------------------ destinations

    /** Priority: action webhook > action channel > default webhook > default channel. */
    private String resolveDestination(LogAction action) {
        FileConfiguration c = plugin.getConfig();

        String url = str(c, path(action, "webhook-url"));
        if (!url.isBlank()) return "webhook:" + url;

        String channel = str(c, path(action, "channel-id"));
        if (!channel.isBlank()) return "channel:" + channel;

        url = str(c, "discord.default-webhook-url");
        if (!url.isBlank()) return "webhook:" + url;

        channel = str(c, "discord.default-channel-id");
        if (!channel.isBlank()) return "channel:" + channel;

        return null;
    }

    public List<String> statusLines() {
        FileConfiguration c = plugin.getConfig();
        List<String> out = new ArrayList<>();

        boolean hasToken = !str(c, "discord.bot-token").isBlank();
        out.add("Discord: " + (discord != null ? "enabled" : "disabled")
                + " | bot token: " + (hasToken ? "set" : "not set")
                + " | in-game: " + (c.getBoolean("ingame.enabled", true) ? "enabled" : "disabled"));

        for (LogAction a : LogAction.values()) {
            String dest = resolveDestination(a);
            boolean own = !str(c, path(a, "webhook-url")).isBlank() || !str(c, path(a, "channel-id")).isBlank();
            String target;
            if (dest == null) {
                target = "none";
            } else {
                target = (own ? "own " : "default ") + (dest.startsWith("webhook:") ? "webhook" : "channel");
            }
            out.add(a.key() + ": " + (isEnabled(a) ? "ON" : "OFF")
                    + " | game:" + onOff(c.getBoolean(path(a, "ingame"), true))
                    + " discord:" + onOff(c.getBoolean(path(a, "discord"), true))
                    + " file:" + onOff(c.getBoolean(path(a, "file"), true))
                    + " -> " + target);
        }
        return out;
    }

    // ------------------------------------------------------------------ in-game

    private void sendIngame(LogEntry e, FileConfiguration cfg) {
        boolean system = e.isSystem();
        String format = system
                ? cfg.getString("ingame.system-format", DEFAULT_SYSTEM_FORMAT)
                : cfg.getString("ingame.format", DEFAULT_FORMAT);
        String prefix = e.sensitive() ? cfg.getString("ingame.sensitive-prefix", "") : "";
        boolean hideOwn = cfg.getBoolean("ingame.hide-own", false);

        Component msg;
        try {
            msg = MM.deserialize(prefix + format,
                    Placeholder.styling("c", TextColor.color(colorOf(e))),
                    Placeholder.parsed("action", safe(e.action().display())),
                    Placeholder.unparsed("title", e.title()),
                    Placeholder.parsed("player", safe(e.player())),
                    Placeholder.unparsed("detail", e.detail()),
                    Placeholder.parsed("world", safe(e.world())),
                    Placeholder.parsed("x", String.valueOf(e.x())),
                    Placeholder.parsed("y", String.valueOf(e.y())),
                    Placeholder.parsed("z", String.valueOf(e.z())),
                    Placeholder.parsed("gamemode", safe(e.gamemode())),
                    Placeholder.parsed("ping", String.valueOf(e.ping())));
        } catch (Exception ex) {
            plugin.getLogger().warning("Invalid ingame format: " + ex.getMessage());
            return;
        }

        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (!staff.hasPermission("playerlogger.alert")) continue;
            if (mutedStaff.contains(staff.getUniqueId())) continue;
            if (hideOwn && e.uuid() != null && staff.getUniqueId().equals(e.uuid())) continue;
            staff.sendMessage(msg);
        }
    }

    // ------------------------------------------------------------------ file

    private void writeFile(LogEntry e) {
        String who = e.isSystem()
                ? e.player()
                : e.player() + " (" + e.world() + " " + e.x() + "," + e.y() + "," + e.z() + ")";
        String line = "[" + TIME.format(e.time()) + "] [" + e.action().display() + "] "
                + who + ": " + e.title() + " | " + e.detail()
                + (e.sensitive() ? " [SENSITIVE]" : "")
                + System.lineSeparator();
        Path file = plugin.getDataFolder().toPath().resolve("logs").resolve(DAY.format(e.time()) + ".log");

        fileExecutor.execute(() -> {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ex) {
                plugin.getLogger().warning("Could not write log file: " + ex.getMessage());
            }
        });
    }

    private void cleanupOldLogs() {
        int days = plugin.getConfig().getInt("general.log-retention-days", 30);
        if (days <= 0) return;

        Path dir = plugin.getDataFolder().toPath().resolve("logs");
        fileExecutor.execute(() -> {
            if (!Files.isDirectory(dir)) return;
            LocalDate cutoff = LocalDate.now().minusDays(days);
            try (Stream<Path> files = Files.list(dir)) {
                files.forEach(f -> {
                    String n = f.getFileName().toString();
                    if (!n.endsWith(".log") || n.length() != 14) return;
                    try {
                        if (LocalDate.parse(n.substring(0, 10)).isBefore(cutoff)) {
                            Files.deleteIfExists(f);
                        }
                    } catch (DateTimeParseException | IOException ignored) {
                        // not one of our files, or could not delete - skip
                    }
                });
            } catch (IOException ex) {
                plugin.getLogger().warning("Could not clean old logs: " + ex.getMessage());
            }
        });
    }

    // ------------------------------------------------------------------ discord embed

    private String buildEmbed(LogEntry e, FileConfiguration cfg) {
        boolean system = e.isSystem();
        boolean showAvatars = cfg.getBoolean("discord.show-avatars", true);
        String prefix = e.sensitive() ? "\u26A0\uFE0F " : e.action().emoji() + " ";

        StringBuilder sb = new StringBuilder("{");
        sb.append("\"title\":").append(q(truncate(prefix + e.title(), 250)));

        if (e.description() != null && !e.description().isBlank()) {
            sb.append(",\"description\":").append(q(truncate(e.description(), 3500)));
        } else if (!system) {
            String detail = truncate(e.detail().replace("```", "'''"), 1500);
            sb.append(",\"description\":").append(q("```\n" + detail + "\n```"));
        }

        sb.append(",\"color\":").append(colorOf(e));
        sb.append(",\"timestamp\":").append(q(e.time().toString()));

        if (!system) {
            sb.append(",\"author\":{\"name\":").append(q(e.player()));
            if (MC_NAME.matcher(e.player()).matches()) {
                sb.append(",\"url\":").append(q("https://namemc.com/profile/" + e.player()));
            }
            if (showAvatars) {
                sb.append(",\"icon_url\":").append(q("https://mc-heads.net/avatar/" + e.uuid() + "/64"));
            }
            sb.append("}");
        }

        List<LogEntry.Field> fields = new ArrayList<>();
        if (system) {
            if (e.fields() != null) fields.addAll(e.fields());
        } else if (cfg.getBoolean("discord.embed-fields", true)) {
            fields.add(new LogEntry.Field("World", "`" + e.world() + "`", true));
            fields.add(new LogEntry.Field("Location", "`" + e.x() + ", " + e.y() + ", " + e.z() + "`", true));
            fields.add(new LogEntry.Field("Gamemode", "`" + e.gamemode() + "`", true));
            fields.add(new LogEntry.Field("Ping", "`" + e.ping() + " ms`", true));
        }
        if (!fields.isEmpty()) {
            sb.append(",\"fields\":[");
            int count = 0;
            for (LogEntry.Field f : fields) {
                if (count >= 25) break;
                if (count > 0) sb.append(',');
                String value = f.value() == null || f.value().isBlank() ? "-" : truncate(f.value(), 1000);
                sb.append("{\"name\":").append(q(truncate(f.name(), 250)))
                  .append(",\"value\":").append(q(value))
                  .append(",\"inline\":").append(f.inline()).append('}');
                count++;
            }
            sb.append(']');
        }

        String server = str(cfg, "discord.server-name");
        sb.append(",\"footer\":{\"text\":")
          .append(q(server.isBlank() ? "gc-playerlogger" : server + " \u2022 gc-playerlogger"))
          .append("}}");
        return sb.toString();
    }

    private static int colorOf(LogEntry e) {
        if (e.sensitive()) return 0xE74C3C;
        if (e.colorOverride() != null) return e.colorOverride();
        return e.action().color();
    }

    // ------------------------------------------------------------------ helpers

    private static String path(LogAction a, String key) {
        return "events." + a.key() + "." + key;
    }

    private static String str(FileConfiguration c, String path) {
        String s = c.getString(path, "");
        return s == null ? "" : s.trim();
    }

    private static String onOff(boolean b) {
        return b ? "on" : "off";
    }

    /** Removes characters that could break MiniMessage tags/arguments. */
    private static String safe(String s) {
        return s.replaceAll("[<>'\"\\\\]", "");
    }

    static String q(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    private static String clean(String s) {
        if (s == null || s.isEmpty()) return "-";
        return s.replace("\r", " ").replace("\n", " ");
    }

    private static Set<String> lower(List<String> in) {
        Set<String> out = new HashSet<>();
        for (String s : in) out.add(s.toLowerCase(Locale.ROOT));
        return Set.copyOf(out);
    }

    private static Set<String> upper(List<String> in) {
        Set<String> out = new HashSet<>();
        for (String s : in) out.add(s.toUpperCase(Locale.ROOT));
        return Set.copyOf(out);
    }
}
