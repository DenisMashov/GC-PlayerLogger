package me.denis.playerlogger.listeners;

import me.denis.playerlogger.LogAction;
import me.denis.playerlogger.LogManager;
import me.denis.playerlogger.PlayerLoggerPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

public final class ActionListener implements Listener {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final PlayerLoggerPlugin plugin;
    private final LogManager logManager;

    public ActionListener(PlayerLoggerPlugin plugin, LogManager logManager) {
        this.plugin = plugin;
        this.logManager = logManager;
    }

    // ------------------------------------------------------------------ join / quit / kick / death

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        if (!logManager.isEnabled(LogAction.JOIN)) return;
        Player p = e.getPlayer();
        String detail = "Joined the server";
        if (plugin.getConfig().getBoolean("filters.join.log-ip", false)
                && p.getAddress() != null && p.getAddress().getAddress() != null) {
            detail += " (IP: " + p.getAddress().getAddress().getHostAddress() + ")";
        }
        logManager.log(LogAction.JOIN, p, detail);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        if (!logManager.isEnabled(LogAction.QUIT)) return;
        logManager.log(LogAction.QUIT, e.getPlayer(),
                "Left the server (" + e.getReason().name() + ")");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKick(PlayerKickEvent e) {
        if (!logManager.isEnabled(LogAction.KICK)) return;
        logManager.log(LogAction.KICK, e.getPlayer(), "Kicked: " + PLAIN.serialize(e.reason()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        if (!logManager.isEnabled(LogAction.DEATH)) return;
        Component msg = e.deathMessage();
        String text = msg != null ? PLAIN.serialize(msg) : "Died";
        logManager.log(LogAction.DEATH, e.getEntity(), text);
    }

    // ------------------------------------------------------------------ teleport / gamemode

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (!logManager.isEnabled(LogAction.TELEPORT)) return;
        if (!logManager.teleportCauses().contains(e.getCause().name())) return;

        logManager.log(LogAction.TELEPORT, e.getPlayer(),
                fmt(e.getFrom()) + " -> " + fmt(e.getTo()) + " (" + e.getCause().name() + ")");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent e) {
        if (!logManager.isEnabled(LogAction.GAMEMODE)) return;
        logManager.log(LogAction.GAMEMODE, e.getPlayer(),
                e.getPlayer().getGameMode().name() + " -> " + e.getNewGameMode().name());
    }

    // ------------------------------------------------------------------ blocks

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!logManager.isEnabled(LogAction.BLOCK_BREAK)) return;
        handleBlock(LogAction.BLOCK_BREAK, e.getPlayer(), e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!logManager.isEnabled(LogAction.BLOCK_PLACE)) return;
        handleBlock(LogAction.BLOCK_PLACE, e.getPlayer(), e.getBlock());
    }

    private void handleBlock(LogAction action, Player p, Block block) {
        Material type = block.getType();
        String name = type.name();

        boolean sensitive = logManager.sensitiveBlocks().contains(name);
        boolean watched = logManager.watchedBlocks().contains(name)
                || plugin.getConfig().getBoolean("filters.blocks.log-all", false);
        if (!sensitive && !watched) return;

        logManager.log(action, p,
                name + " @ " + block.getX() + ", " + block.getY() + ", " + block.getZ(), sensitive);
    }

    // ------------------------------------------------------------------ helpers

    private static String fmt(Location l) {
        String world = l.getWorld() != null ? l.getWorld().getName() : "?";
        return world + " " + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
    }
}
