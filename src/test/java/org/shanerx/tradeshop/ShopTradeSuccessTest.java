package org.shanerx.tradeshop;

import be.seeseemelk.mockbukkit.entity.PlayerMock;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The trade a human would otherwise log in to perform: stock a shop, click its
 * sign with the cost in hand, and check both sides of the exchange.
 */
class ShopTradeSuccessTest extends AbstractShopFlowTest {

    @Test
    void buyerWithEnoughCostReceivesTheProduct() {
        createShop("1 DIAMOND", "1 EMERALD");
        stockShop(new ItemStack(Material.DIAMOND, 10));

        PlayerMock buyer = buyerHolding(new ItemStack(Material.EMERALD, 5));
        rightClickSign(buyer);

        assertEquals(1, countOf(buyer, Material.DIAMOND), "buyer should have received one diamond");
        assertEquals(4, countOf(buyer, Material.EMERALD), "buyer should have paid one emerald");
        assertEquals(1, countInChest(Material.EMERALD), "the emerald should be in the shop chest");
        assertEquals(9, countInChest(Material.DIAMOND), "the shop should have one fewer diamond");
    }
}
