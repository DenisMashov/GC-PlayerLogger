package gc.playerlogger;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Counters for the daily summary. Main thread only. Saved to stats.yml so restarts do not lose data. */
public final class StatsTracker {

    private static final int MAX_DISTINCT = 2000;

    public record Summary(int total, int sensitive, int uniquePlayers, int peakOnline, long periodStart,
                          List<Map.Entry<String, Integer>> actions,
                          List<Map.Entry<String, Integer>> players,
                          List<Map.Entry<String, Integer>> commandUsers,
                          List<Map.Entry<String, Integer>> commands) {}

    private final Map<String, Integer> byAction = new HashMap<>();
    private final Map<String, Integer> playerTotals = new HashMap<>();
    private final Map<String, Integer> playerCommands = new HashMap<>();
    private final Map<String, Integer> commandNames = new HashMap<>();
    private int total;
    private int sensitive;
    private int peakOnline;
    private long periodStart = System.currentTimeMillis();

    public void record(LogAction action, String player, String detail, boolean isSensitive) {
        total++;
        if (isSensitive) sensitive++;
        byAction.merge(action.key(), 1, Integer::sum);
        bump(playerTotals, player);
        if (action == LogAction.COMMAND) {
            bump(playerCommands, player);
            String label = commandLabel(detail);
            if (label != null) bump(commandNames, label);
        }
    }

    public void updatePeak(int online) {
        if (online > peakOnline) peakOnline = online;
    }

    public Summary summary(int topN) {
        return new Summary(total, sensitive, playerTotals.size(), peakOnline, periodStart,
                top(byAction, topN), top(playerTotals, topN), top(playerCommands, topN), top(commandNames, topN));
    }

    public void reset() {
        byAction.clear();
        playerTotals.clear();
        playerCommands.clear();
        commandNames.clear();
        total = 0;
        sensitive = 0;
        peakOnline = 0;
        periodStart = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ persistence

    public String serialize() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("period-start", periodStart);
        y.set("total", total);
        y.set("sensitive", sensitive);
        y.set("peak-online", peakOnline);
        y.set("by-action", toList(byAction));
        y.set("players-total", toList(playerTotals));
        y.set("players-commands", toList(playerCommands));
        y.set("command-names", toList(commandNames));
        return y.saveToString();
    }

    public void load(File file) {
        if (!file.isFile()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        periodStart = y.getLong("period-start", System.currentTimeMillis());
        total = y.getInt("total", 0);
        sensitive = y.getInt("sensitive", 0);
        peakOnline = y.getInt("peak-online", 0);
        fill(byAction, y.getStringList("by-action"));
        fill(playerTotals, y.getStringList("players-total"));
        fill(playerCommands, y.getStringList("players-commands"));
        fill(commandNames, y.getStringList("command-names"));
    }

    // ------------------------------------------------------------------ helpers

    private static void bump(Map<String, Integer> map, String key) {
        if (map.containsKey(key) || map.size() < MAX_DISTINCT) {
            map.merge(key, 1, Integer::sum);
        }
    }

    private static String commandLabel(String detail) {
        if (detail == null || detail.length() < 2 || detail.charAt(0) != '/') return null;
        String first = detail.substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        int colon = first.indexOf(':');
        if (colon >= 0) first = first.substring(colon + 1);
        if (first.isBlank() || first.length() > 32) return null;
        return first;
    }

    private static List<Map.Entry<String, Integer>> top(Map<String, Integer> map, int n) {
        return map.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(n)
                .map(en -> Map.entry(en.getKey(), en.getValue()))
                .toList();
    }

    private static List<String> toList(Map<String, Integer> map) {
        List<String> out = new ArrayList<>();
        map.forEach((k, v) -> out.add(k + "=" + v));
        return out;
    }

    private static void fill(Map<String, Integer> map, List<String> in) {
        map.clear();
        for (String s : in) {
            int i = s.lastIndexOf('=');
            if (i <= 0) continue;
            try {
                map.put(s.substring(0, i), Integer.parseInt(s.substring(i + 1)));
            } catch (NumberFormatException ignored) {
                // skip broken line
            }
        }
    }
}
