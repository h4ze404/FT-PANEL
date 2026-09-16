package ru.afbaritone;

import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.enchantment.Enchantment;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Отслеживание зачарованных книг: какие сейчас в инвентаре и
 * сколько выловлено за сессию по каждому зачарованию.
 */
public class BookTracker {

    private static final Map<String, Integer> caught = new LinkedHashMap<>();
    private static final Map<String, Integer> lastSnapshot = new LinkedHashMap<>();
    private static int tickCounter;

    public static void tick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) return;
        if (++tickCounter % 20 != 0) return; // раз в секунду достаточно

        Map<String, Integer> now = new LinkedHashMap<>();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getMainStacks().get(i);
            String key = bookKey(stack);
            if (key != null) now.merge(key, stack.getCount(), Integer::sum);
        }

        // прирост = выловил новую
        for (var e : now.entrySet()) {
            int prev = lastSnapshot.getOrDefault(e.getKey(), 0);
            if (e.getValue() > prev) {
                caught.merge(e.getKey(), e.getValue() - prev, Integer::sum);
            }
        }
        lastSnapshot.clear();
        lastSnapshot.putAll(now);
    }

    /** Ключ книги вида "mending:1" или "sharpness:5;mending:1", null если не зачарованная книга. */
    private static String bookKey(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        String item = stack.getItem().toString().toLowerCase(Locale.ROOT);
        if (!item.contains("enchanted_book")) return null;
        ItemEnchantmentsComponent comp = stack.get(DataComponentTypes.ENCHANTMENTS);
        if (comp == null) return null;
        StringBuilder sb = new StringBuilder();
        for (RegistryEntry<Enchantment> ench : comp.getEnchantments()) {
            if (sb.length() > 0) sb.append(';');
            String id = ench.getKey().map(k -> k.getValue().getPath()).orElse("unknown");
            sb.append(id).append(':').append(comp.getLevel(ench));
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    public static Map<String, Integer> getCaught() {
        return caught;
    }
}
