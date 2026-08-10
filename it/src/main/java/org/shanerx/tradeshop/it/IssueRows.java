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
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.shanerx.tradeshop.item.ShopItemSide;
import org.shanerx.tradeshop.shop.Shop;
import org.shanerx.tradeshop.shop.ShopStatus;
import org.shanerx.tradeshop.shoplocation.ShopLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * The rows for defects reported on the upstream tracker.
 *
 * <h2>Why they are here and not in {@code src/test}</h2>
 * Each turns on something a mock does not have. See the comment above each row
 * for the specific reason; the shared one is that these are reports about what a
 * player did to a real world, and the harness that answers them has to be able
 * to break a block and put it back.
 */
final class IssueRows {

    private IssueRows() {
    }

    /**
     * These rows' patch of the world, clear of tier 2's 1..5 and 12..15, the
     * matrix's 10..11, DefectRows' 20..27 and tier 3's 7000.
     */
    private static final int FIRST_SITE = 30;

    static List<IntegrationPlugin.Scenario> rows(IntegrationPlugin plugin) {
        List<IntegrationPlugin.Scenario> rows = new ArrayList<>();

        // ------------------------------------------------------------------
        // #160, "Error After Removing a Linked Chest".
        //
        // Reported verbatim: create a shop, add cost, add product, break the
        // chest. The next thing that touches the shop throws
        //
        //   NullPointerException: Cannot invoke
        //   "org.bukkit.inventory.Inventory.getStorageContents()"
        //   because "shopInventory" is null
        //
        // Shop.hasStorage:646 is `getStorage() != null` and Shop.getStorage:624
        // is `getInventoryLocation().getBlock().getState()`. AN AIR BLOCK STILL
        // HAS A BLOCKSTATE, so the guard answers true for a chest that is gone,
        // Shop.updateFullTradeCount:741 walks past it, and :746 asks
        // getChestAsSC().getInventory() - which is null, because
        // ShopChest.getBlock:142 assigns its block only when the location holds
        // an inventory, and ShopChest.getInventory:165-172 catches the resulting
        // NPE and answers null. :748 dereferences it.
        //
        // Only reachable with a real world. The whole defect is that
        // getBlock().getState() answers a live object for a block that is not
        // there, so a mock that answered null - or threw - would hide it, and
        // the repair a shop owner takes runs inside a real BlockPlaceEvent.
        //
        // WHAT A SHOP WITH NO STORAGE SHOULD DO is a choice, not a lookup, and
        // the choice asserted below is: it becomes INCOMPLETE, keeps both sides
        // of its trade, and waits for its owner to put a storage block back.
        // Two reasons, both already in the plugin rather than invented here.
        // ShopStatus.INCOMPLETE already means "this shop is missing something it
        // needs", ShopTradeListener:129 already answers it with SHOP_EMPTY and
        // :108-111 already answers a null storage with MISSING_CHEST - none of
        // which is reachable while the guard lies. And
        // ShopProtectionListener.onBlockPlace:409 already holds the repair: an
        // owner placing a storage block under the sign is re-linked to it IF AND
        // ONLY IF !shop.hasStorage(). With the guard always true that branch is
        // dead code, so fixing the guard is what turns the repair back on.
        //
        // Not CLOSED: CLOSED is a thing an owner chooses, updateStatus:683
        // deliberately refuses to move a shop out of it, and a shop that had
        // been repaired would stay shut. Not removed: the shop's item lists are
        // the owner's work, and a vanished block is not consent to destroy them.
        // ------------------------------------------------------------------
        rows.add(new IntegrationPlugin.Scenario("aShopWhoseStorageBlockIsGoneAsksForRepairRatherThanThrowing", () -> {
            RealShop scene = new RealShop(plugin, FIRST_SITE);
            scene.placeChestAndSign();
            scene.createShopByCommand("1 DIAMOND", "1 EMERALD");
            scene.stockShop(new ItemStack(Material.DIAMOND, 10));
            scene.closeChestAsOwner();

            ShopLocation where = scene.get(() -> new ShopLocation(scene.signBlock().getLocation()));

            Assert.eventually(15_000, "precondition: the shop is open before its chest goes",
                    scene.onServer(() -> Shop.loadShop(where).getStatus() == ShopStatus.OPEN));

            // The reporter's fourth step, through the server's own listeners.
            // The event fires while the chest is still a chest, which is why the
            // break does not throw; the block is set to air afterwards because
            // that is what the server does next, and it is the state the shop is
            // left pointing at.
            scene.run(() -> {
                Bukkit.getPluginManager().callEvent(new BlockBreakEvent(scene.chestBlock(), scene.owner()));
                scene.chestBlock().setType(Material.AIR, false);
            });

            Shop shop = scene.get(() -> Shop.loadShop(where));
            Assert.that(shop != null, "breaking the chest must not take the shop record with it");

            // The reported exception, at the two calls its stack names. Called
            // rather than driven through an event on purpose: Bukkit's event bus
            // catches what a listener throws and logs it, so an NPE reached that
            // way would fail this run as an unexplained severe console line
            // instead of as this row.
            try {
                scene.run(shop::updateFullTradeCount);
            } catch (Throwable t) {
                throw new AssertionError("counting the trades of a shop whose storage block is gone "
                        + "must not throw, and it threw " + rootCause(t) + " - "
                        + "Shop.updateFullTradeCount:741 asks hasStorage(), which is "
                        + "getStorage():624 != null, and getBlock().getState() answers a BlockState "
                        + "for air as readily as for a chest", t);
            }

            try {
                scene.run(shop::saveShop);
            } catch (Throwable t) {
                throw new AssertionError("and neither must saving it - Shop.saveShop:488 calls "
                        + "updateFullTradeCount:494, which is how every mutation in the plugin "
                        + "reaches it: " + rootCause(t), t);
            }

            Assert.that(!scene.get(shop::hasStorage),
                    "a shop whose storage block has been removed must report that it has none");

            scene.run(shop::updateStatus);
            Assert.equal(ShopStatus.INCOMPLETE, shop.getStatus(),
                    "a shop with nothing to trade out of is incomplete, not out of stock - "
                            + "updateStatus:684 asks only whether chestLoc was ever set");
            Assert.equal(0, shop.getAvailableTrades(), "and it can make no trades at all");

            // The owner's work is not collateral. Both sides survive, so putting
            // a chest back is a repair rather than a rebuild.
            Assert.equal(1, shop.getSideList(ShopItemSide.PRODUCT).size(),
                    "the product side must survive the chest");
            Assert.equal(1, shop.getSideList(ShopItemSide.COST).size(),
                    "and so must the cost side");

            // The repair, taken the way a player takes it: place a chest under
            // the sign.
            scene.run(() -> {
                Block block = scene.chestBlock();
                BlockState replaced = block.getState();
                block.setType(Material.CHEST, false);
                Bukkit.getPluginManager().callEvent(new BlockPlaceEvent(block, replaced,
                        block.getRelative(BlockFace.DOWN), new ItemStack(Material.CHEST),
                        scene.owner(), true, EquipmentSlot.HAND));
            });

            Shop repaired = scene.get(() -> Shop.loadShop(where));
            Assert.that(scene.get(repaired::hasStorage),
                    "an owner who puts a storage block back under the sign must get their shop back "
                            + "- ShopProtectionListener.onBlockPlace:409 re-links it only when the "
                            + "shop says it has no storage, so a guard that always says it has one "
                            + "leaves the shop unrepairable");

            scene.stockShop(new ItemStack(Material.DIAMOND, 10));
            scene.closeChestAsOwner();

            Assert.eventually(15_000, "a repaired and restocked shop to open again",
                    scene.onServer(() -> Shop.loadShop(where).getStatus() == ShopStatus.OPEN));
        }));

        return rows;
    }

    /**
     * The exception a wrapper was hiding.
     *
     * <p>{@link Sync} reports what the server thread threw as an
     * {@code AssertionError} whose message is the cause's {@code toString()}, so
     * a row that reported what it caught would name the marshalling rather than
     * the defect.
     */
    private static String rootCause(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.toString();
    }
}
