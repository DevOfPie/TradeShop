package org.shanerx.tradeshop.harness;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Items with metadata on them, built the same way in every row of the matrix.
 *
 * <h2>There is deliberately no builder for a "plain" item here</h2>
 * A bare {@code new ItemStack(Material.X)} is the one item this tier cannot say
 * anything true about. {@code ShopItemStack.isSimilar} decides whether to run
 * seven of its fifteen checks from {@code hasItemMeta()}
 * ({@code ShopItemStack.java:304}), and MockBukkit answers that question the other
 * way round from a real server: {@code ItemStackMock.hasItemMeta()} is
 * {@code meta != null && !ItemFactoryMock.equals(meta, null)} with
 * {@code ItemFactoryMock.equals} being {@code Objects.equals}, so an untouched
 * stack reports {@code true} here and {@code false} on Paper. Every row about a
 * plain item therefore lives in {@code it/ItemMatrix.java}, where it fails; here
 * it would pass without the comparator ever taking the branch it is about.
 *
 * <p>Every builder returns a fresh stack. {@code isSimilar} returns {@code false}
 * for two references to the same object ({@code ShopItemStack.java:288}), so a row
 * that compared an item with itself would read as a non-match and prove nothing.
 */
public final class Items {

    private Items() {
    }

    /**
     * Applies {@code edit} to a meta for {@code material} and writes it onto a
     * fresh stack.
     *
     * <p>The meta comes from {@link org.bukkit.inventory.ItemFactory#getItemMeta(Material)}
     * rather than from the stack, and that is a measured workaround for one
     * MockBukkit divergence: {@code ItemType.ENCHANTED_BOOK.getItemMetaClass()}
     * answers {@code ItemMetaMock} while the factory answers
     * {@code EnchantedBookMetaMock}, and {@code ItemStackMock}'s constructor asks
     * the former. So {@code new ItemStack(Material.ENCHANTED_BOOK).getItemMeta()}
     * is not an {@link EnchantmentStorageMeta} here, where on a real server it
     * always is. The factory is the API a real server implements identically, so
     * the item this builds is the item the comparator would see on Paper; asking
     * the stack would have made the enchanted-book branch untestable rather than
     * wrong.
     */
    public static ItemStack with(Material material, Consumer<ItemMeta> edit) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = Bukkit.getItemFactory().getItemMeta(material);
        edit.accept(meta);
        stack.setItemMeta(meta);
        return stack;
    }

    public static ItemStack book(String title, String author, String... pages) {
        return with(Material.WRITTEN_BOOK, meta -> {
            BookMeta book = (BookMeta) meta;
            // The legacy String API on purpose. BookMetaMock aborts every Adventure
            // Component accessor with UnimplementedOperationException, and this build
            // counts an abort as a failure - and the comparator calls the String API
            // anyway, so nothing is being avoided that the code under test uses.
            book.setTitle(title);
            book.setAuthor(author);
            book.setPages(Arrays.asList(pages));
        });
    }

    public static ItemStack potion(PotionType base) {
        return with(Material.POTION, meta -> ((PotionMeta) meta).setBasePotionType(base));
    }

    public static ItemStack potionWithCustomEffect(PotionType base, PotionEffectType effect) {
        return with(Material.POTION, meta -> {
            PotionMeta potion = (PotionMeta) meta;
            potion.setBasePotionType(base);
            potion.addCustomEffect(new PotionEffect(effect, 600, 1), true);
        });
    }

    public static ItemStack rocket(int power, FireworkEffect... effects) {
        return with(Material.FIREWORK_ROCKET, meta -> {
            FireworkMeta firework = (FireworkMeta) meta;
            firework.setPower(power);
            for (FireworkEffect effect : effects) {
                firework.addEffect(effect);
            }
        });
    }

    public static FireworkEffect burst(Color colour) {
        return FireworkEffect.builder().with(FireworkEffect.Type.BALL).withColor(colour).build();
    }

    public static ItemStack enchanted(Material material, Enchantment enchantment, int level) {
        return with(material, meta -> meta.addEnchant(enchantment, level, true));
    }

    public static ItemStack enchantedBook(Enchantment enchantment, int level) {
        return with(Material.ENCHANTED_BOOK,
                meta -> ((EnchantmentStorageMeta) meta).addStoredEnchant(enchantment, level, true));
    }

    public static ItemStack damaged(Material material, int damage) {
        return with(material, meta -> ((Damageable) meta).setDamage(damage));
    }
}
