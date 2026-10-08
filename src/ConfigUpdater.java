package gc.playerlogger;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Keeps the user's config.yml safe across plugin updates.
 *
 * Rules:
 *  - If config.yml is already at the latest config-version, the file is NOT touched at all.
 *  - If a newer plugin version adds options, only the MISSING options are added.
 *    Existing values are never changed or removed, comments are kept.
 *  - Before any change a backup (config-backup-*.yml) is saved.
 *  - If config.yml has a syntax error, it is left exactly as it is.
 *  - Settings from the first (older) config layout are copied to their new places.
 *    The old keys stay in the file untouched.
 */
public final class ConfigUpdater {

    private ConfigUpdater() {
    }

    public static void update(PlayerLoggerPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "config.yml");
        if (!file.isFile()) return;

        // the config.yml that ships inside the jar
        YamlConfiguration defaults = new YamlConfiguration();
        try (InputStream in = plugin.getResource("config.yml")) {
            if (in == null) return;
            defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().warning("Could not read the built-in config: " + e.getMessage());
            return;
        }
        int latest = defaults.getInt("config-version", 1);

        // the user's config.yml
        YamlConfiguration user = new YamlConfiguration();
        try {
            user.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().severe("config.yml has an error and was NOT touched: " + e.getMessage());
            return;
        }

        int current = user.getInt("config-version", 0);
        if (current >= latest) return;                      // up to date -> never touch the file

        // backup first
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path backup = file.toPath().resolveSibling("config-backup-" + stamp + ".yml");
        try {
            Files.copy(file.toPath(), backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not create a backup, config.yml was NOT touched: " + e.getMessage());
            return;
        }

        boolean migrated = migrateLegacy(user);
        List<String> added = addMissing(user, defaults);
        user.set("config-version", latest);

        try {
            user.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save config.yml: " + e.getMessage());
            return;
        }

        plugin.getLogger().info("config.yml updated to version " + latest + ": "
                + added.size() + " new option(s) added"
                + (migrated ? ", old settings were copied to their new places" : "")
                + ". Your existing values were not changed. Backup: " + backup.getFileName());
    }

    // ------------------------------------------------------------------ add only what is missing

    private static List<String> addMissing(YamlConfiguration user, YamlConfiguration def) {
        List<String> added = new ArrayList<>();
        for (String key : def.getKeys(true)) {
            if (def.isConfigurationSection(key)) continue;
            if (user.isSet(key)) continue;

            user.set(key, def.get(key));
            user.setComments(key, def.getComments(key));
            user.setInlineComments(key, def.getInlineComments(key));

            // comments of parent sections that were created just now
            int dot = key.indexOf('.');
            while (dot > 0) {
                String parent = key.substring(0, dot);
                if (user.getComments(parent).isEmpty()) {
                    List<String> comments = def.getComments(parent);
                    if (!comments.isEmpty()) {
                        user.setComments(parent, comments);
                    }
                }
                dot = key.indexOf('.', dot + 1);
            }
            added.add(key);
        }
        return added;
    }

    // ------------------------------------------------------------------ first config layout -> current layout

    private static boolean migrateLegacy(YamlConfiguration u) {
        boolean changed = false;

        // 1) "events.chat: true"  ->  "events.chat.enabled: true"
        ConfigurationSection events = u.getConfigurationSection("events");
        if (events != null) {
            for (String key : new ArrayList<>(events.getKeys(false))) {
                if (events.isBoolean(key)) {
                    boolean value = events.getBoolean(key);
                    events.set(key, null);
                    events.set(key + ".enabled", value);
                    changed = true;
                }
            }
        }

        // 2) moved options (only copied when the new place is still empty)
        String[][] moved = {
                {"discord.webhook-url", "discord.default-webhook-url"},
                {"log-to-file", "general.log-to-file"},
                {"commands.ignored", "filters.commands.ignored"},
                {"commands.mask-args", "filters.commands.mask-args"},
                {"commands.sensitive", "filters.commands.sensitive"},
                {"actions.log-ip-on-join", "filters.join.log-ip"},
                {"actions.teleport-causes", "filters.teleport.causes"},
                {"actions.log-all-blocks", "filters.blocks.log-all"},
                {"actions.watched-blocks", "filters.blocks.watched"},
                {"actions.sensitive-blocks", "filters.blocks.sensitive"},
                {"actions.watched-buckets", "filters.buckets.watched"},
        };
        for (String[] m : moved) {
            if (u.isSet(m[0]) && !u.isSet(m[1])) {
                u.set(m[1], u.get(m[0]));
                changed = true;
            }
        }

        // 3) category switches / webhooks -> per-action switches
        Map<String, List<String>> categories = Map.of(
                "command", List.of("command"),
                "chat", List.of("chat", "sign", "book"),
                "action", List.of("join", "quit", "kick", "death", "teleport", "gamemode",
                        "block-break", "block-place", "anvil", "enchant", "bucket"));

        for (Map.Entry<String, List<String>> category : categories.entrySet()) {
            for (String flag : new String[]{"discord", "ingame", "file"}) {
                String old = "destinations." + category.getKey() + "." + flag;
                if (!u.isSet(old)) continue;
                boolean value = u.getBoolean(old);
                for (String action : category.getValue()) {
                    String target = "events." + action + "." + flag;
                    if (!u.isSet(target)) {
                        u.set(target, value);
                        changed = true;
                    }
                }
            }

            String hook = u.getString("discord.webhooks." + category.getKey(), "");
            if (hook != null && !hook.isBlank()) {
                for (String action : category.getValue()) {
                    String target = "events." + action + ".webhook-url";
                    if (!u.isSet(target)) {
                        u.set(target, hook);
                        changed = true;
                    }
                }
            }
        }
        return changed;
    }
}
