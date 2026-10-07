package gc.playerlogger;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

public final class ItemUtil {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private ItemUtil() {
    }

    /** Example: 24x EMERALD  /  ENCHANTED_BOOK [mending 1]  /  DIAMOND_SWORD "Name" [sharpness 5] */
    public static String describe(ItemStack item) {
        StringBuilder sb = new StringBuilder();
        if (item.getAmount() > 1) {
            sb.append(item.getAmount()).append("x ");
        }
        sb.append(item.getType().name());

        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            Component name = meta.displayName();
            if (name != null) {
                sb.append(" \"").append(PLAIN.serialize(name)).append('"');
            }
        }

        Map<Enchantment, Integer> ench = new LinkedHashMap<>(item.getEnchantments());
        if (meta instanceof EnchantmentStorageMeta storage) {
            ench.putAll(storage.getStoredEnchants());
        }
        if (!ench.isEmpty()) {
            sb.append(" [").append(joinEnchants(ench)).append(']');
        }
        return sb.toString();
    }

    public static String joinEnchants(Map<Enchantment, Integer> map) {
        return map.entrySet().stream()
                .map(en -> en.getKey().getKey().getKey() + " " + en.getValue())
                .collect(Collectors.joining(", "));
    }
}
