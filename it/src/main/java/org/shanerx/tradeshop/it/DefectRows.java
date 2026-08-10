/*
 * Copyright (c) 2016-2026
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *                http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.shanerx.tradeshop.it;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.ItemStack;
import org.shanerx.tradeshop.TradeShop;
import org.shanerx.tradeshop.data.config.Setting;
import org.shanerx.tradeshop.item.ShopItemStack;
import org.shanerx.tradeshop.shop.Shop;
import org.shanerx.tradeshop.shop.ShopChest;
import org.shanerx.tradeshop.shoplocation.ShopLocation;
import org.shanerx.tradeshop.utils.Utils;
import org.shanerx.tradeshop.utils.objects.ObjectHolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The rows for the defects a code review reported and nobody ever ran.
 *
 * <h2>Why every one of them is here and not in {@code src/test}</h2>
 * Each turns on something MockBukkit does not have, rather than on something it
 * does differently. The first is a real {@code config.yml} on disk, read back
 * through {@code ConfigManager.reload()} - the same call {@code TradeShop.onEnable}
 * makes at {@code TradeShop.java:140}. The defect is what the repair does to a
 * file, so a test with no file proves nothing about it.
 *
 * <p>Every row asserts what an operator is entitled to, so a red row is the
 * defect and a green row is the fix. None of them is written to pass today.
 */
final class DefectRows {

    private DefectRows() {
    }

    /** These rows' patch of the world, clear of tier 2's 1..5, the matrix's 10..11 and tier 3's 7000. */
    private static final int FIRST_SITE = 20;

    static List<IntegrationPlugin.Scenario> rows(IntegrationPlugin plugin) {
        List<IntegrationPlugin.Scenario> rows = new ArrayList<>();

        // ------------------------------------------------------------------
        // Config repair, first half: an older config.yml that has lost part of a per-item
        // setting is not repaired by a boot.
        //
        // ConfigManager.addKeyValue:207-220 is the repair. For a Map setting it
        // walks the map's OWN keys - compare-durability, compare-name - and asks
        // whether each is present. It never looks inside one. A node that exists
        // but has lost its `default` child is therefore seen as present, nothing
        // is written, addKeyValue answers false, ConfigManager.reload:116 does
        // not save, and the hole survives the boot.
        //
        // It also returns as soon as it writes ONE key, so even the shape it can
        // repair is repaired one key per pass.
        //
        // MEASURED, so that the fix is aimed at the shape that actually breaks: a
        // per-item key that is absent ALTOGETHER is repaired. addKeyValue writes
        // it - as a Map.toString(), at :213, which is its own bug - returns true,
        // and reload() then rewrites the whole file from the enum defaults, which
        // covers the damage. Only the partly-present shape below survives a boot.
        // ------------------------------------------------------------------
        rows.add(new IntegrationPlugin.Scenario("aPerItemSettingThatLostAChildIsRepairedAtBoot", () -> {
            TradeShop tradeShop = tradeShop();
            File config = tradeShop.getSettingManager().getFile();
            File backup = new File(config.getParentFile(), config.getName() + ".w9-backup");
            String settingPath = Setting.SHOP_PER_ITEM_SETTINGS.getPath();

            // A scene only so that the config edits are marshalled onto the
            // server thread the same way every other touch in this tier is.
            RealShop scene = new RealShop(plugin, FIRST_SITE);
            scene.placeChestAndSign();

            try {
                Files.copy(config.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);

                // The shape an older file actually has: the setting is there and
                // one of its two children is not, because the child was added
                // after the file was written.
                scene.run(() -> {
                    YamlConfiguration old = YamlConfiguration.loadConfiguration(config);
                    old.set(settingPath + ".compare-durability.default", null);
                    old.set(settingPath + ".compare-name.default", null);
                    save(old, config);
                });

                // Exactly what TradeShop.java:140 does on every boot.
                scene.run(() -> tradeShop.getSettingManager().reload());

                Assert.that(Setting.SHOP_PER_ITEM_SETTINGS.getMappedObject("compare-durability.default") != null,
                        "a boot must repair a per-item setting that has lost a child, or every later read "
                                + "of it answers null - ConfigManager.addKeyValue:207-220 only asks whether "
                                + "the setting's own key is present and never looks inside it");
                Assert.that(Setting.SHOP_PER_ITEM_SETTINGS.getMappedObject("compare-name.default") != null,
                        "and it must repair every one of them rather than stopping at the first - "
                                + "addKeyValue:214 returns as soon as one key is written");
            } catch (IOException e) {
                throw new AssertionError("could not stand up an older config.yml: " + e);
            } finally {
                restore(backup, config);
                scene.run(() -> tradeShop.getSettingManager().reload());
            }
        }));

        // ------------------------------------------------------------------
        // Config repair, second half: whatever the config does, a setting that reads back
        // as null must not take the trade gate down with it.
        //
        // Setting.getMappedObject:299 answers null for a hole,
        // ShopItemStackSettingKeys.getDefaultValue:107 wraps that null in an
        // ObjectHolder, and then:
        //   ObjectHolder.asBoolean:107  -> canBeBoolean:70-71 calls obj.toString()
        //                                  on it, with no null guard at all;
        //   ObjectHolder.asInteger:112  -> answers null, which
        //   ShopItemStack.java:439      -> unboxes into an int.
        //
        // The hole is punched in memory here rather than through a file: this row
        // is about the guard, not about the repair, and the two have to be able to
        // fail and be fixed independently or a fix to one turns the other green
        // without ever having been written.
        // ------------------------------------------------------------------
        rows.add(new IntegrationPlugin.Scenario("aMissingPerItemSettingDoesNotThrowInsideTheTradeGate", () -> {
            RealShop scene = new RealShop(plugin, FIRST_SITE + 1);
            scene.placeChestAndSign();
            scene.createShopByCommand("1 DIAMOND", "1 EMERALD");
            scene.stockShop(new ItemStack(Material.DIAMOND, 10));

            Shop shop = scene.get(() -> Shop.loadShop(new ShopLocation(scene.signBlock().getLocation())));
            Assert.that(shop != null, "precondition: a shop to trade with once a setting has gone missing");

            Player buyer = scene.buyerHolding(new ItemStack(Material.EMERALD, 5));

            Object durability = Setting.SHOP_PER_ITEM_SETTINGS.getMappedObject("compare-durability.default");
            Object name = Setting.SHOP_PER_ITEM_SETTINGS.getMappedObject("compare-name.default");

            try {
                // The holder a missing setting produces, on its own, before any
                // shop is involved.
                try {
                    new ObjectHolder<Object>(null).asBoolean();
                } catch (Throwable t) {
                    throw new AssertionError("ObjectHolder.asBoolean() must answer for a setting that is "
                            + "not in the file, and it threw " + rootCause(t) + " - :107 hands straight to "
                            + "canBeBoolean:70-71, which calls obj.toString() with no null guard", t);
                }

                scene.run(() -> {
                    Setting.SHOP_PER_ITEM_SETTINGS.setMappedValue("compare-durability.default", null);
                    Setting.SHOP_PER_ITEM_SETTINGS.setMappedValue("compare-name.default", null);
                });

                // The item comparison, which is what a trade is made of.
                try {
                    scene.run(() -> new ShopItemStack(new ItemStack(Material.DIAMOND))
                            .isSimilar(new ItemStack(Material.DIAMOND)));
                } catch (Throwable t) {
                    throw new AssertionError("comparing two items must survive a per-item setting that is "
                            + "missing from the config, and it threw " + rootCause(t)
                            + " - ShopItemStack.java:439 unboxes ObjectHolder.asInteger():112, which "
                            + "answers null for a setting that is not there", t);
                }

                // And the gate itself, called the way ShopTradeListener calls it,
                // so that the failure is where a shop owner meets it.
                try {
                    scene.run(() -> new Utils().canExchangeAll(shop, buyer.getInventory(), 1,
                            Action.RIGHT_CLICK_BLOCK));
                } catch (Throwable t) {
                    throw new AssertionError("a trade must still be possible when the config is missing a "
                            + "per-item setting, and the gate threw " + rootCause(t), t);
                }
            } finally {
                Sync.run(plugin, () -> {
                    Setting.SHOP_PER_ITEM_SETTINGS.setMappedValue("compare-durability.default", durability);
                    Setting.SHOP_PER_ITEM_SETTINGS.setMappedValue("compare-name.default", name);
                });
            }
        }));

        // ------------------------------------------------------------------
        // The chest-linkage defect. Unlinking a chest never unlinks it.
        //
        // LinkageConfiguration.removeChest:70 calls
        // Map<String, Object>.remove(chestLocation) with a ShopLocation.
        // Map.remove takes Object, so it compiles; no String key is ever equal to
        // a ShopLocation, so it is always a miss. Every other method on that
        // interface keys by chestLocation.toString() - addLinkage:53-56,
        // getLinkedShop:44-45 - and this one does not.
        //
        // The entry therefore outlives the chest. DataStorage.removeChestLinkage:317
        // is the route a player takes by breaking half of a shop's storage
        // (ShopProtectionListener.java:317), and Shop.removeStorage:617 the route
        // taken by unlinking the whole of it.
        // ------------------------------------------------------------------
        rows.add(new IntegrationPlugin.Scenario("unlinkingAChestRemovesItsLinkageEntry", () -> {
            RealShop scene = new RealShop(plugin, FIRST_SITE + 2);
            scene.placeChestAndSign();
            scene.createShopByCommand("1 DIAMOND", "1 EMERALD");

            ShopLocation chest = scene.get(() -> new ShopLocation(scene.chestBlock().getLocation()));

            Assert.that(scene.get(() -> tradeShop().getDataStorage().getChestLinkage(chest)) != null,
                    "precondition: creating the shop linked the chest under the sign to it");

            scene.run(() -> tradeShop().getDataStorage().removeChestLinkage(chest));

            Assert.that(scene.get(() -> tradeShop().getDataStorage().getChestLinkage(chest)) == null,
                    "a chest that has been unlinked must stop being linked - "
                            + "LinkageConfiguration.removeChest:70 passes a ShopLocation to a "
                            + "Map<String,String>.remove, which is a legal call that can never match the "
                            + "String key addLinkage:56 wrote");

            // And the block goes on reading as a shop chest for exactly as long as
            // the entry does, which is what the hopper and protection paths key on
            // (ShopChest.isShopChest:75, ShopProtectionListener.java:117).
            Assert.that(!scene.get(() -> ShopChest.isShopChest(scene.chestBlock())),
                    "and an unlinked chest must stop reading as a shop chest to every path that asks");
        }));

        return rows;
    }

    private static TradeShop tradeShop() {
        return (TradeShop) Bukkit.getPluginManager().getPlugin("TradeShop");
    }

    /**
     * The exception a wrapper was hiding.
     *
     * <p>Bukkit's {@code PluginCommand.execute} rethrows whatever a command threw
     * as a {@code CommandException} whose own message names only the command, so a
     * row that drives a command and reports what it caught would otherwise say
     * nothing at all about the defect.
     */
    private static String rootCause(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.toString();
    }

    private static void save(YamlConfiguration config, File file) {
        try {
            config.save(file);
        } catch (IOException e) {
            throw new AssertionError("could not write " + file + ": " + e);
        }
    }

    /**
     * Puts the real config.yml back.
     *
     * <p>Silent on failure on purpose: this runs in a finally, and an exception
     * thrown here would replace the row's own verdict with a housekeeping error.
     * The restore is asserted by the rows that come after it rather than by this
     * method - a run where it did not happen goes red somewhere downstream.
     */
    private static void restore(File backup, File config) {
        try {
            if (backup.isFile()) {
                Files.copy(backup.toPath(), config.toPath(), StandardCopyOption.REPLACE_EXISTING);
                backup.delete();
            }
        } catch (IOException ignored) {
        }
    }
}
