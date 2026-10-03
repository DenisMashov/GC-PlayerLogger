package me.denis.playerlogger.listeners;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.denis.playerlogger.LogAction;
import me.denis.playerlogger.LogManager;
import me.denis.playerlogger.PlayerLoggerPlugin;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Locale;
import java.util.stream.Collectors;

public final class ChatListener implements Listener {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final PlayerLoggerPlugin plugin;
    private final LogManager logManager;

    public ChatListener(PlayerLoggerPlugin plugin, LogManager logManager) {
        this.plugin = plugin;
        this.logManager = logManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        if (!logManager.isEnabled(LogAction.CHAT)) return;

        Player player = e.getPlayer();
        String message = PLAIN.serialize(e.message());
        boolean sensitive = logManager.hasSensitiveWord(message);
        // chat is async -> hop back to the main thread
        Bukkit.getScheduler().runTask(plugin,
                () -> logManager.log(LogAction.CHAT, player, message, sensitive));
    }

    // ignoreCancelled = false: even commands blocked by other plugins (AuthMe etc.) are logged
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!logManager.isEnabled(LogAction.COMMAND)) return;

        String message = e.getMessage();
        String raw = message.startsWith("/") ? message.substring(1) : message;
        String[] parts = raw.split(" ", 2);
        String label = parts[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1); // minecraft:op -> op

        if (logManager.ignoredCommands().contains(label)) return;

        // hide passwords: "/login hunter2" -> "/login ********"
        if (logManager.maskedCommands().contains(label) && parts.length > 1 && !parts[1].isBlank()) {
            message = "/" + parts[0] + " ********";
        }

        boolean sensitive = logManager.sensitiveCommands().contains(label);
        String title = e.isCancelled() ? "Command (cancelled)" : "Command";
        logManager.log(LogAction.COMMAND, e.getPlayer(), title, message, sensitive);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSign(SignChangeEvent e) {
        if (!logManager.isEnabled(LogAction.SIGN)) return;

        String text = e.lines().stream()
                .map(PLAIN::serialize)
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining(" | "));
        if (text.isEmpty()) return;

        Block b = e.getBlock();
        logManager.log(LogAction.SIGN, e.getPlayer(),
                text + " @ " + b.getX() + ", " + b.getY() + ", " + b.getZ(),
                logManager.hasSensitiveWord(text));
    }
}
