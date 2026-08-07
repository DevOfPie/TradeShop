package org.shanerx.tradeshop;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.Sign;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.shanerx.tradeshop.shop.ShopType;

/**
 * A chest with a TradeShop sign on it, and the steps a player would take to use
 * it, so that a test can say what it is checking instead of how to set it up.
 *
 * <h2>One scenario per subclass, on purpose</h2>
 * TradeShop keeps plugin state in static fields and in a data store that outlive
 * a {@code mock}/{@code unmock} cycle, so a second scenario in the same JVM
 * inherits the first one's state and fails while the plugin itself looks
 * healthy. Until that is addressed in the plugin, each scenario gets its own
 * class and {@code reuseForks=false} in the POM gives each class its own JVM.
 */
abstract class AbstractShopFlowTest {

    private static final int CHEST_Y = 64, CHEST_Z = 0;

    protected ServerMock server;
    protected TradeShop plugin;
    protected WorldMock world;

    protected Block chestBlock;
    protected Block signBlock;
    protected PlayerMock owner;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock(new TradeShopServerMock());
        plugin = MockBukkit.load(TradeShop.class);
        world = server.addSimpleWorld("world");

        chestBlock = world.getBlockAt(0, CHEST_Y, CHEST_Z);
        chestBlock.setType(Material.CHEST);

        signBlock = world.getBlockAt(0, CHEST_Y + 1, CHEST_Z);
        signBlock.setType(Material.OAK_SIGN);

        owner = server.addPlayer("owner");
        owner.setOp(true);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** Line 1 is the product the shop gives, line 2 the cost it takes. */
    protected void createShop(String product, String cost) {
        SignChangeEvent event = new SignChangeEvent(signBlock, owner,
                new String[]{ShopType.TRADE.toHeader(), product, cost, ""});
        server.getPluginManager().callEvent(event);
        applySignEdit(event);
    }

    /**
     * Copies the finished lines of a {@link SignChangeEvent} onto the sign block.
     *
     * <p>A real server does this itself once the event returns, which is how a
     * plugin's edits to a sign become visible to everything downstream. MockBukkit
     * does not, so without this the sign stays blank, nothing recognises it as a
     * shop, and the trade silently never happens while every call still succeeds.
     */
    private void applySignEdit(SignChangeEvent event) {
        Sign state = (Sign) signBlock.getState();
        for (int line = 0; line < 4; line++) {
            state.setLine(line, event.getLine(line));
        }
        state.update(true);
    }

    protected void stockShop(ItemStack stock) {
        Chest chest = (Chest) chestBlock.getState();
        chest.getInventory().addItem(stock);
        chest.update();
    }

    protected PlayerMock buyerHolding(ItemStack held) {
        PlayerMock buyer = server.addPlayer("buyer");
        buyer.setOp(true);
        buyer.getInventory().addItem(held);
        return buyer;
    }

    protected void rightClickSign(PlayerMock buyer) {
        server.getPluginManager().callEvent(new PlayerInteractEvent(buyer,
                Action.RIGHT_CLICK_BLOCK, null, signBlock, BlockFace.NORTH));
    }

    protected int countOf(PlayerMock player, Material material) {
        return count(player.getInventory().getContents(), material);
    }

    protected int countInChest(Material material) {
        return count(((Chest) chestBlock.getState()).getInventory().getContents(), material);
    }

    private int count(ItemStack[] contents, Material material) {
        int total = 0;
        for (ItemStack stack : contents) {
            if (stack != null && stack.getType() == material) {
                total += stack.getAmount();
            }
        }
        return total;
    }
}
