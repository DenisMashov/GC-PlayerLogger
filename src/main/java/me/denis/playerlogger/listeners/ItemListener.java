package me.denis.playerlogger.listeners;

import me.denis.playerlogger.ItemUtil;
import me.denis.playerlogger.LogAction;
import me.denis.playerlogger.LogManager;
import me.denis.playerlogger.PlayerLoggerPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.view.AnvilView;

import java.util.stream.Collectors;

/** Anvil, enchanting table, books and buckets. */
public final class ItemListener implements Listener {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final LogManager logManager;

    public ItemListener(PlayerLoggerPlugin plugin, LogManager logManager) {
        this.logManager = logManager;
    }

    // ------------------------------------------------------------------ enchanting table

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent e) {
        if (!logManager.isEnabled(LogAction.ENCHANT)) return;

        String enchants = ItemUtil.joinEnchants(e.getEnchantsToAdd());
        logManager.log(LogAction.ENCHANT, e.getEnchanter(),
                e.getItem().getType().name() + " -> " + enchants
                        + " (cost: " + e.getExpLevelCost() + " levels)");
    }

    // ------------------------------------------------------------------ anvil

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnvil(InventoryClickEvent e) {
        if (!(e.getView() instanceof AnvilView view)) return;
        if (e.getRawSlot() != 2) return;                       // result slot
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!logManager.isEnabled(LogAction.ANVIL)) return;

        ItemStack result = e.getCurrentItem();
        if (result == null || result.getType().isAir()) return;

        // the click must actually be able to take the item
        ItemStack cursor = e.getCursor();
        if (!cursor.getType().isAir() && !e.isShiftClick()) return;
        int cost = view.getRepairCost();
        if (p.getGameMode() != GameMode.CREATIVE && p.getLevel() < cost) return;

        logManager.log(LogAction.ANVIL, p, ItemUtil.describe(result) + " | cost: " + cost + " levels");
    }

    // ------------------------------------------------------------------ books

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBook(PlayerEditBookEvent e) {
        if (!logManager.isEnabled(LogAction.BOOK)) return;

        BookMeta meta = e.getNewBookMeta();
        String text = meta.pages().stream()
                .map(PLAIN::serialize)
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining(" / "));

        if (e.isSigning()) {
            Component title = meta.title();
            String t = title != null ? PLAIN.serialize(title) : "?";
            logManager.log(LogAction.BOOK, e.getPlayer(), "Book Sign",
                    "Title: " + t + " | " + text, logManager.hasSensitiveWord(text));
        } else {
            if (text.isEmpty()) return;
            logManager.log(LogAction.BOOK, e.getPlayer(), "Book Edit",
                    text, logManager.hasSensitiveWord(text));
        }
    }

    // ------------------------------------------------------------------ buckets

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        if (!logManager.isEnabled(LogAction.BUCKET)) return;

        String name = e.getBucket().name();
        if (!logManager.watchedBuckets().contains(name)) return;

        Block b = e.getBlock();
        logManager.log(LogAction.BUCKET, e.getPlayer(),
                name + " @ " + b.getX() + ", " + b.getY() + ", " + b.getZ());
    }
}
