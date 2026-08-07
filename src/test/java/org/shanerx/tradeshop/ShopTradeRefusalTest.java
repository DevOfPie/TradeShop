package org.shanerx.tradeshop;

import be.seeseemelk.mockbukkit.entity.PlayerMock;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The other half of the manual check: a buyer who cannot pay must leave with
 * nothing, and must not have drained the shop on the way out.
 */
class ShopTradeRefusalTest extends AbstractShopFlowTest {

    @Test
    void buyerWhoCannotPayGetsNothingAndTheShopKeepsItsStock() {
        createShop("1 DIAMOND", "1 EMERALD");
        stockShop(new ItemStack(Material.DIAMOND, 10));

        PlayerMock buyer = buyerHolding(new ItemStack(Material.DIRT, 1));
        rightClickSign(buyer);

        assertEquals(0, countOf(buyer, Material.DIAMOND), "buyer paid nothing so should receive nothing");
        assertEquals(10, countInChest(Material.DIAMOND), "the shop should still hold all ten diamonds");
    }
}
