package gc.playerlogger;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class PlayerLoggerPlugin extends JavaPlugin {

    private LogManager logManager;
    private ServerMonitor monitor;

    @Override
    public void onEnable() {
        saveDefaultConfig();          // only creates config.yml if it does not exist
        ConfigUpdater.update(this);   // adds missing options after updates, never overwrites
        reloadConfig();

        logManager = new LogManager(this);
        logManager.loadStats();
        logManager.reload();

        monitor = new ServerMonitor(this, logManager);
        monitor.start();

        getServer().getPluginManager().registerEvents(new ChatListener(this, logManager), this);
        getServer().getPluginManager().registerEvents(new ActionListener(this, logManager), this);
        getServer().getPluginManager().registerEvents(new ItemListener(this, logManager), this);
        getServer().getPluginManager().registerEvents(new VillagerListener(this, logManager), this);

        PluginCommand cmd = getCommand("playerlogger");
        if (cmd != null) {
            PlayerLoggerCommand handler = new PlayerLoggerCommand(this, logManager);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }

        getLogger().info("GC-PlayerLogger enabled.");
    }

    @Override
    public void onDisable() {
        if (monitor != null) {
            monitor.stop();
        }
        if (logManager != null) {
            logManager.shutdown();
        }
    }

    public void reloadAll() {
        reloadConfig();
        logManager.reload();
        monitor.start();
    }

    public ServerMonitor monitor() {
        return monitor;
    }
}
