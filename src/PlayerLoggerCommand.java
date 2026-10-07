package gc.playerlogger;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class PlayerLoggerCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of("reload", "alerts", "status", "summary", "test");

    private final PlayerLoggerPlugin plugin;
    private final LogManager logManager;

    public PlayerLoggerCommand(PlayerLoggerPlugin plugin, LogManager logManager) {
        this.plugin = plugin;
        this.logManager = logManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            usage(sender, label);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                if (!sender.hasPermission("playerlogger.admin")) {
                    noPerm(sender);
                    return true;
                }
                plugin.reloadAll();
                sender.sendMessage(Component.text("gc-playerlogger config reloaded.", NamedTextColor.GREEN));
            }
            case "alerts" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(Component.text("Players only.", NamedTextColor.RED));
                    return true;
                }
                if (!p.hasPermission("playerlogger.alert")) {
                    noPerm(sender);
                    return true;
                }
                boolean on = logManager.toggleAlerts(p.getUniqueId());
                p.sendMessage(Component.text("In-game alerts: " + (on ? "ON" : "OFF"),
                        on ? NamedTextColor.GREEN : NamedTextColor.RED));
            }
            case "status" -> {
                if (!sender.hasPermission("playerlogger.admin")) {
                    noPerm(sender);
                    return true;
                }
                sender.sendMessage(Component.text("gc-playerlogger status", NamedTextColor.GOLD));
                for (String line : logManager.statusLines()) {
                    sender.sendMessage(Component.text(line, NamedTextColor.GRAY));
                }
            }
            case "summary" -> {
                if (!sender.hasPermission("playerlogger.admin")) {
                    noPerm(sender);
                    return true;
                }
                if (!logManager.isEnabled(LogAction.DAILY_SUMMARY)) {
                    sender.sendMessage(Component.text("daily-summary is disabled in the config.", NamedTextColor.RED));
                    return true;
                }
                logManager.sendDailySummary(false);
                sender.sendMessage(Component.text("Summary preview sent (stats were not reset).", NamedTextColor.GREEN));
            }
            case "test" -> {
                if (!sender.hasPermission("playerlogger.admin")) {
                    noPerm(sender);
                    return true;
                }
                LogAction action = args.length > 1 ? LogAction.byKey(args[1]) : LogAction.CHAT;
                if (action == null) {
                    sender.sendMessage(Component.text("Unknown action. Use /" + label + " status to see all actions.",
                            NamedTextColor.RED));
                    return true;
                }
                if (!logManager.isEnabled(action)) {
                    sender.sendMessage(Component.text("Action '" + action.key() + "' is disabled in the config.",
                            NamedTextColor.RED));
                    return true;
                }
                if (action == LogAction.TPS_ALERT) {
                    plugin.monitor().sendTestAlert();
                    sender.sendMessage(Component.text("Test TPS alert sent.", NamedTextColor.GREEN));
                    return true;
                }
                if (action == LogAction.DAILY_SUMMARY) {
                    logManager.sendDailySummary(false);
                    sender.sendMessage(Component.text("Summary preview sent (stats were not reset).", NamedTextColor.GREEN));
                    return true;
                }
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(Component.text("Players only for this action.", NamedTextColor.RED));
                    return true;
                }
                logManager.log(action, p, action.display() + " (test)", "This is a test alert.", false, false);
                p.sendMessage(Component.text("Test alert sent for '" + action.key() + "'.", NamedTextColor.GREEN));
            }
            default -> usage(sender, label);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd,
                                      @NotNull String label, @NotNull String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : SUBCOMMANDS) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("test")) {
            for (LogAction a : LogAction.values()) {
                if (a.key().startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(a.key());
            }
        }
        return out;
    }

    private void usage(CommandSender sender, String label) {
        sender.sendMessage(Component.text("/" + label + " <reload|alerts|status|summary|test [action]>", NamedTextColor.GRAY));
    }

    private void noPerm(CommandSender sender) {
        sender.sendMessage(Component.text("You don't have permission.", NamedTextColor.RED));
    }
}
