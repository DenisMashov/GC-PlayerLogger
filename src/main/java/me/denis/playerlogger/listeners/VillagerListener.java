package me.denis.playerlogger.listeners;

import me.denis.playerlogger.ItemUtil;
import me.denis.playerlogger.LogAction;
import me.denis.playerlogger.LogManager;
import me.denis.playerlogger.PlayerLoggerPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Villager;
import org.bukkit.entity.ZombieVillager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Locale;
import java.util.Set;

/** Villager / wandering trader related logs. */
public final class VillagerListener implements Listener {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final LogManager logManager;

    public VillagerListener(PlayerLoggerPlugin plugin, LogManager logManager) {
        this.logManager = logManager;
    }

    // ------------------------------------------------------------------ kill

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVillagerDeath(EntityDeathEvent e) {
        if (!(e.getEntity() instanceof AbstractVillager villager)) return;
        if (!logManager.isEnabled(LogAction.VILLAGER_KILL)) return;

        Player killer = villager.getKiller();
        if (killer == null) return;

        ItemStack weapon = killer.getInventory().getItemInMainHand();
        String with = weapon.getType().isAir() ? "bare hands" : weapon.getType().name();

        logManager.log(LogAction.VILLAGER_KILL, killer,
                "Killed " + describe(villager) + " with " + with + " @ " + at(villager.getLocation()));
    }

    // ------------------------------------------------------------------ damage (disabled by default)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVillagerDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof AbstractVillager villager)) return;
        if (!logManager.isEnabled(LogAction.VILLAGER_DAMAGE)) return;

        Player attacker = null;
        Entity damager = e.getDamager();
        if (damager instanceof Player p) {
            attacker = p;
        } else if (damager instanceof Projectile proj && proj.getShooter() instanceof Player shooter) {
            attacker = shooter;
        }
        if (attacker == null) return;

        logManager.log(LogAction.VILLAGER_DAMAGE, attacker,
                "Hit " + describe(villager) + " for " + String.format(Locale.ROOT, "%.1f", e.getFinalDamage())
                        + " damage @ " + at(villager.getLocation()));
    }

    // ------------------------------------------------------------------ trade

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrade(InventoryClickEvent e) {
        if (!(e.getInventory() instanceof MerchantInventory inv)) return;
        if (e.getRawSlot() != 2) return;                       // result slot
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!logManager.isEnabled(LogAction.VILLAGER_TRADE)) return;

        ItemStack result = e.getCurrentItem();
        if (result == null || result.getType().isAir()) return;

        ItemStack cursor = e.getCursor();
        if (!cursor.getType().isAir() && !e.isShiftClick()) return;

        MerchantRecipe recipe = inv.getSelectedRecipe();
        if (recipe == null || recipe.getUses() >= recipe.getMaxUses()) return;

        Set<String> only = logManager.tradeOnlyItems();
        if (!only.isEmpty() && !only.contains(result.getType().name())) return;

        StringBuilder paid = new StringBuilder();
        for (ItemStack ingredient : recipe.getIngredients()) {
            if (ingredient == null || ingredient.getType().isAir()) continue;
            if (paid.length() > 0) paid.append(" + ");
            paid.append(ItemUtil.describe(ingredient));
        }

        String who = "villager";
        if (inv.getMerchant() instanceof AbstractVillager villager) {
            who = describe(villager) + " @ " + at(villager.getLocation());
        }

        logManager.log(LogAction.VILLAGER_TRADE, p,
                "Traded with " + who + " | paid: " + paid + " | got: " + ItemUtil.describe(result));
    }

    // ------------------------------------------------------------------ cure

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCure(EntityTransformEvent e) {
        if (e.getTransformReason() != EntityTransformEvent.TransformReason.CURED) return;
        if (!(e.getEntity() instanceof ZombieVillager zombie)) return;
        if (!logManager.isEnabled(LogAction.VILLAGER_CURE)) return;

        OfflinePlayer cured = zombie.getConversionPlayer();
        Player p = cured != null ? cured.getPlayer() : null;
        if (p == null) return;

        logManager.log(LogAction.VILLAGER_CURE, p,
                "Cured a zombie villager @ " + at(zombie.getLocation()));
    }

    // ------------------------------------------------------------------ nametag / leash

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof AbstractVillager villager)) return;
        if (!logManager.isEnabled(LogAction.VILLAGER_NAME)) return;

        Player p = e.getPlayer();
        ItemStack held = p.getInventory().getItem(e.getHand());
        if (held == null) return;

        String detail;
        if (held.getType() == Material.NAME_TAG) {
            ItemMeta meta = held.getItemMeta();
            if (meta == null || !meta.hasDisplayName()) return;
            Component name = meta.displayName();
            if (name == null) return;
            detail = "Used a name tag \"" + PLAIN.serialize(name) + "\" on ";
        } else if (held.getType() == Material.LEAD) {
            detail = "Used a lead on ";
        } else {
            return;
        }

        logManager.log(LogAction.VILLAGER_NAME, p,
                detail + describe(villager) + " @ " + at(villager.getLocation()));
    }

    // ------------------------------------------------------------------ helpers

    private static String describe(AbstractVillager v) {
        if (v instanceof Villager villager) {
            return "Villager (" + villager.getProfession().getKey().getKey()
                    + ", level " + villager.getVillagerLevel() + ")";
        }
        return v.getType().name();
    }

    private static String at(Location l) {
        return l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ();
    }
}
