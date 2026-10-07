package gc.playerlogger;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/** TPS / lag alerts and the daily summary schedule. Runs on the main thread. */
public final class ServerMonitor {

    private static final double RECOVERY_MARGIN = 1.0;
    private static final long MB = 1024L * 1024L;

    private final PlayerLoggerPlugin plugin;
    private final LogManager logManager;
    private final long startedAt = System.currentTimeMillis();

    private BukkitTask tpsTask;
    private BukkitTask minuteTask;
    private boolean lagActive;
    private long lastLagAlert;
    private boolean initialized;
    private LocalDate lastSummaryDate;
    private int minutesSinceSave;

    public ServerMonitor(PlayerLoggerPlugin plugin, LogManager logManager) {
        this.plugin = plugin;
        this.logManager = logManager;
    }

    public void start() {
        stop();
        FileConfiguration c = plugin.getConfig();

        long seconds = Math.max(5L, c.getLong("monitoring.tps-alert.check-interval-seconds", 30L));
        tpsTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkTps, seconds * 20L, seconds * 20L);
        minuteTask = Bukkit.getScheduler().runTaskTimer(plugin, this::minuteTick, 1200L, 1200L);

        if (!initialized) {
            initialized = true;
            LocalDateTime now = LocalDateTime.now();
            // if today's summary time has already passed, the next summary is tomorrow
            lastSummaryDate = now.toLocalTime().isBefore(summaryTime()) ? null : now.toLocalDate();
        }
    }

    public void stop() {
        if (tpsTask != null) tpsTask.cancel();
        if (minuteTask != null) minuteTask.cancel();
        tpsTask = null;
        minuteTask = null;
    }

    // ------------------------------------------------------------------ tps

    private void checkTps() {
        if (!logManager.isEnabled(LogAction.TPS_ALERT)) return;

        FileConfiguration c = plugin.getConfig();
        long now = System.currentTimeMillis();
        long grace = Math.max(0L, c.getLong("monitoring.tps-alert.startup-grace-seconds", 120L)) * 1000L;
        if (now - startedAt < grace) return;

        double threshold = c.getDouble("monitoring.tps-alert.threshold", 15.0);
        double critical = c.getDouble("monitoring.tps-alert.critical-below", 10.0);
        long cooldown = Math.max(1L, c.getLong("monitoring.tps-alert.cooldown-minutes", 5L)) * 60_000L;
        boolean recovery = c.getBoolean("monitoring.tps-alert.send-recovery", true);

        double[] tps = tps();
        double current = tps[0];

        if (current < threshold) {
            if (!lagActive || now - lastLagAlert >= cooldown) {
                lagActive = true;
                lastLagAlert = now;
                sendTpsAlert("TPS Alert", "Server TPS dropped below **" + f(threshold) + "**.",
                        tps, current < critical, null);
            }
        } else if (lagActive && current >= Math.min(threshold + RECOVERY_MARGIN, 19.8)) {
            lagActive = false;
            if (recovery) {
                sendTpsAlert("TPS Recovered", "Server TPS is back to normal.", tps, false, 0x2ECC71);
            }
        }
    }

    /** Used by /plog test tps-alert */
    public void sendTestAlert() {
        sendTpsAlert("TPS Alert (test)", "This is a test alert. Current server stats:", tps(), false, null);
    }

    private void sendTpsAlert(String title, String description, double[] tps, boolean sensitive, Integer color) {
        double mspt = Bukkit.getServer().getAverageTickTime();
        int online = Bukkit.getOnlinePlayers().size();

        Runtime rt = Runtime.getRuntime();
        long maxMb = rt.maxMemory() / MB;
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / MB;

        int chunks = 0;
        int entities = 0;
        for (World w : Bukkit.getWorlds()) {
            chunks += w.getChunkCount();
            entities += w.getEntityCount();
        }

        String tpsText = f(tps[0]) + " / " + f(tps[1]) + " / " + f(tps[2]);
        List<LogEntry.Field> fields = List.of(
                new LogEntry.Field("TPS (1m / 5m / 15m)", "`" + tpsText + "`", false),
                new LogEntry.Field("MSPT", "`" + f(mspt) + " ms`", true),
                new LogEntry.Field("Players online", "`" + online + "`", true),
                new LogEntry.Field("Memory", "`" + usedMb + " / " + maxMb + " MB`", true),
                new LogEntry.Field("Chunks / Entities", "`" + chunks + " / " + entities + "`", true));

        String detail = "TPS " + tpsText + " | MSPT " + f(mspt) + " ms | " + online + " online | "
                + usedMb + "/" + maxMb + " MB";

        logManager.logSystem(LogAction.TPS_ALERT, title, detail, description, sensitive, color, fields);
    }

    private static double[] tps() {
        double[] raw = Bukkit.getServer().getTPS();
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) {
            out[i] = i < raw.length ? Math.min(20.0, raw[i]) : 20.0;
        }
        return out;
    }

    // ------------------------------------------------------------------ minute tick

    private void minuteTick() {
        logManager.stats().updatePeak(Bukkit.getOnlinePlayers().size());

        if (++minutesSinceSave >= 10) {
            minutesSinceSave = 0;
            logManager.saveStatsAsync();
        }

        checkSummary();
    }

    private void checkSummary() {
        if (!logManager.isEnabled(LogAction.DAILY_SUMMARY)) return;

        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();
        if (today.equals(lastSummaryDate)) return;
        if (now.toLocalTime().isBefore(summaryTime())) return;

        lastSummaryDate = today;
        logManager.sendDailySummary(true);
    }

    private LocalTime summaryTime() {
        String raw = plugin.getConfig().getString("monitoring.daily-summary.time", "00:00");
        try {
            return LocalTime.parse(raw == null ? "00:00" : raw.trim());
        } catch (DateTimeParseException e) {
            plugin.getLogger().warning("Invalid monitoring.daily-summary.time '" + raw + "' - use HH:mm (e.g. 00:00).");
            return LocalTime.MIDNIGHT;
        }
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
