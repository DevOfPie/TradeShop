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
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.shanerx.tradeshop.TradeShop;
import org.shanerx.tradeshop.shop.Shop;
import org.shanerx.tradeshop.shop.ShopChest;
import org.shanerx.tradeshop.shop.ShopStatus;
import org.shanerx.tradeshop.shop.ShopType;
import org.shanerx.tradeshop.shop.listeners.ShopProtectionListener;
import org.shanerx.tradeshop.shoplocation.ShopLocation;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The in-server half of the tier-2 harness.
 *
 * <p>It runs once, from inside a real Paper server, after the server has finished
 * starting. Each scenario is a piece of behaviour a human would otherwise log in
 * to check; each writes one line to a result file that {@code ci/integration.sh}
 * reads after the process has exited. The plugin then shuts the server down
 * itself, so the runner never has to type {@code stop} into a stdin it has
 * deliberately closed.
 *
 * <h2>Why the result is a file and not a log line</h2>
 * The runner has to distinguish three states that all look alike from outside: a
 * run that passed, a run that failed, and a run that never happened. A log line
 * can be produced by a plugin that half-started; a file that declares how many
 * scenarios were registered and then lists which ones actually ran lets the
 * runner catch the third state, which is the one a green build hides.
 */
public final class IntegrationPlugin extends JavaPlugin implements Listener {

    /**
     * Deliberate sabotage, so the gate can be shown to fail rather than only
     * ever observed passing. Set by {@code ci/integration.sh} from
     * {@code TS_IT_INDUCE}. It lives in the harness permanently because a
     * failure mode you have to hand-edit into the code is one nobody re-checks.
     */
    private static final String INDUCE = System.getProperty("tradeshop.it.induce", "");

    /**
     * Tier 3: keep the server alive after the scenarios below, and let a real
     * client drive the rest. Set by {@code ci/integration.sh}. Off by default, so
     * that a tier-2 run on a machine with no Node toolchain is still exactly the
     * run it was.
     */
    private static final boolean CLIENT = Boolean.parseBoolean(
            System.getProperty("tradeshop.it.client", "false"));

    private final List<Scenario> scenarios = new ArrayList<>();

    /** The tier-3 half, or null when this is a tier-2 run. */
    private ClientPhase client;

    /**
     * The finished lines of the last sign edit, as every plugin left them.
     *
     * <p>Volatile because it is written on the server thread and read from the
     * scenario thread.
     */
    private volatile String[] lastSignEventLines = new String[0];

    @Override
    public void onEnable() {
        register();
        getServer().getPluginManager().registerEvents(this, this);
        if (CLIENT) {
            client = new ClientPhase(this);
            client.wire();
        }
        getLogger().info("integration harness armed with " + declaredScenarios() + " scenario(s)"
                + (CLIENT ? " (" + scenarios.size() + " in-server, " + ClientPhase.STEPS.size()
                        + " driven by a real client)" : "")
                + ", trade header is " + ShopType.TRADE.toHeader()
                + (INDUCE.isEmpty() ? "" : ", INDUCED FAILURE MODE: " + INDUCE));
    }

    /**
     * How many scenarios this run is on the hook for.
     *
     * <p>Written into the result file before any of them run, because "the suite
     * ran fewer scenarios than it has" is the one failure a report produced by the
     * run itself cannot catch.
     */
    private int declaredScenarios() {
        return scenarios.size() + (CLIENT ? ClientPhase.STEPS.size() : 0);
    }

    /**
     * The scenarios, in the order a person would perform them.
     */
    private void register() {
        scenarios.add(new Scenario("pluginEnabled", () -> {
            Plugin tradeShop = Bukkit.getPluginManager().getPlugin("TradeShop");
            Assert.that(tradeShop != null, "TradeShop is not installed on this server");
            Assert.that(tradeShop.isEnabled(),
                    "TradeShop is installed but not enabled - onEnable threw, and the server carried on");
        }));

        // Writing a shop sign on a chest: the first thing a human tester does,
        // and the gate everything else is behind.
        scenarios.add(new Scenario("signOnAChestCreatesAShop", () -> {
            RealShop scene = new RealShop(this, 1);
            scene.placeChestAndSign();
            scene.createShop(ShopType.TRADE.toHeader(), "1 DIAMOND", "1 EMERALD");

            Shop shop = scene.get(() -> Shop.loadShop(new ShopLocation(scene.signBlock().getLocation())));

            Assert.that(shop != null, "no shop was stored at the sign's location");
            Assert.equal(ShopType.TRADE, shop.getShopType(), "the shop should be a trade shop");
            Assert.equal(scene.owner().getUniqueId(), shop.getOwner().getUUID(),
                    "the signer should own the shop");
            Assert.equal(scene.get(() -> scene.signBlock().getRelative(0, -1, 0).getLocation()),
                    shop.getInventoryLocation(),
                    "the shop should be linked to the chest under the sign");

            // Unlike tier 1, the shop was written to a real data file on disk by
            // the real plugin, and read back out of it.
            Assert.equal(ShopStatus.OUT_OF_STOCK, shop.getStatus(),
                    "nothing has been put in the chest yet");

            // TradeShop decorated the EVENT's lines, which is all a plugin can
            // do during a sign edit...
            Assert.equal(strip(ShopStatus.OUT_OF_STOCK.getLine()), strip(lastSignEventLines[3]),
                    "line 3 of the finished sign edit is where a player reads the shop's status");

            // ...and the block is still blank, because writing those lines back
            // is done by the packet handler that fired the event, not by the
            // event bus. No client, no packet, no write. That is a property of
            // the server, not a gap in this harness, and it is asserted rather
            // than worked around: the sign assertions that matter are in the
            // stocking scenario, where TradeShop calls sign.update() itself.
            Assert.equal("", scene.signLines()[0],
                    "the server writes a sign edit back from the packet handler, so a synthetic "
                            + "event must leave the block untouched");
        }));

        // Stocking and trading, and the wall they hit.
        //
        // THIS SCENARIO ASSERTS A BLOCKER, NOT CORRECT BEHAVIOUR. It is green
        // because the situation it describes is real, and it must go red the
        // moment a real client drives the sign edit - at which point it is
        // deleted and replaced by the stock and trade flows it is standing in
        // for. Read the failure as "the blocker is gone", not as a regression.
        //
        // The chain: TradeShop's Shop.updateSign() starts at getShopSign(),
        // which returns null unless the sign BLOCK already reads as a shop sign.
        // The block only reads that way once a sign edit has been written back
        // onto it, and that write is done by the packet handler that fired
        // SignChangeEvent, not by the event bus. A harness that fires the event
        // itself is not a packet handler, so the block stays blank, the sign is
        // never updated, the status never leaves OUT_OF_STOCK, and a click on
        // the sign is not recognised as a click on a shop.
        //
        // Copying the event's finished lines onto the block is exactly the
        // workaround tier 1 carries, and reintroducing it here is the one thing
        // this unit says not to do quietly. So the chain is documented instead.
        scenarios.add(new Scenario("aSyntheticSignEditLeavesTheBlockBlankSoTheShopNeverOpens", () -> {
            RealShop scene = new RealShop(this, 2);
            scene.placeChestAndSign();
            scene.createShop(ShopType.TRADE.toHeader(), "1 DIAMOND", "1 EMERALD");

            scene.stockShop(new ItemStack(Material.DIAMOND, 10));
            Assert.equal(10, scene.countInChest(Material.DIAMOND),
                    "the diamonds should be in the chest before the lid closes");
            Assert.that(scene.get(() -> ShopChest.isShopChest(scene.chestBlock())),
                    "the chest under the sign should be linked to the shop");
            Assert.that(scene.get(() -> ShopChest.isShopChest(scene.chestInventory())),
                    "the chest's inventory is what the close event carries, and it is how "
                            + "TradeShop finds the shop again");

            scene.closeChestAsOwner();

            // TradeShop did react to the close: it recounted the stock, on the
            // real chest, and got the right answer. Everything up to the sign
            // works.
            Assert.eventually(15_000, "TradeShop to recount the stock in the chest",
                    scene.onServer(() -> Shop.loadShop(new ShopLocation(scene.signBlock().getLocation()))
                            .getAvailableTrades() == 10));

            Shop shop = scene.get(() -> Shop.loadShop(new ShopLocation(scene.signBlock().getLocation())));

            Assert.that(scene.get(() -> shop.getShopSign()) == null,
                    "getShopSign() is null while the block is blank, which is what stops the "
                            + "status from ever being recomputed");
            Assert.equal(ShopStatus.OUT_OF_STOCK, shop.getStatus(),
                    "ten trades are available and the shop still says out of stock, because "
                            + "updateStatus() is only reached through the sign");
            Assert.equal("", scene.signLines()[3],
                    "and the sign the server stored is still blank");

            // And so the trade cannot happen either: the click lands on a block
            // that does not read as a shop sign.
            Player buyer = scene.buyerHolding(new ItemStack(Material.EMERALD, 5));
            scene.rightClickSign(buyer);

            Assert.equal(0, scene.countOf(buyer, Material.DIAMOND),
                    "no product moves, because the clicked block is not a shop sign");
            Assert.equal(5, scene.countOf(buyer, Material.EMERALD), "and no cost is taken");
            Assert.equal(10, scene.countInChest(Material.DIAMOND), "and the shop keeps its stock");
            Assert.equal(0, scene.countInChest(Material.EMERALD), "and is paid nothing");
        }));

        // Creating a shop the other way a player can: from the chat bar.
        //
        // This path writes the sign itself rather than leaving it to a packet
        // handler, so it is the one that lets the rest of the flows run - and
        // the sign assertions below read the real block with nothing copied back
        // onto it by the harness.
        scenarios.add(new Scenario("commandCreatedShopIsCompleteAndTheServerWritesItsSign", () -> {
            RealShop scene = new RealShop(this, 3);
            scene.placeChestAndSign();
            scene.createShopByCommand("1 DIAMOND", "1 EMERALD");

            Shop shop = scene.get(() -> Shop.loadShop(new ShopLocation(scene.signBlock().getLocation())));

            Assert.that(shop != null, "no shop was stored at the sign's location");
            Assert.equal(ShopType.TRADE, shop.getShopType(), "the shop should be a trade shop");
            Assert.equal(scene.owner().getUniqueId(), shop.getOwner().getUUID(),
                    "the player who ran the command should own the shop");
            Assert.equal(scene.get(() -> scene.chestBlock().getLocation()), shop.getInventoryLocation(),
                    "the shop should be linked to the chest under the sign");

            // "Complete" is a property of the shop, not of a message: a shop is
            // incomplete while either side is empty. /tradeshop create alone
            // leaves it that way, which is why setProduct and setCost follow.
            Assert.that(!shop.isMissingItems(),
                    "both sides should be set, so the shop is no longer incomplete");
            Assert.equal(ShopStatus.OUT_OF_STOCK, shop.getStatus(),
                    "a complete shop with an empty chest is out of stock, not incomplete");

            // Read off the real block. TradeShop put every one of these there
            // through sign.update().
            Assert.equal(ShopType.TRADE.toHeader(), scene.signLines()[0],
                    "line 0 is the shop's header");
            Assert.equal("1 Diamond", scene.signLines()[1], "line 1 is what the shop gives");
            Assert.equal("1 Emerald", scene.signLines()[2], "line 2 is what the shop takes");
            Assert.equal(strip(ShopStatus.OUT_OF_STOCK.getLine()), scene.signLines()[3],
                    "line 3 is where a player reads the shop's status");
        }));

        // Stocking a shop: the owner puts the product in the chest and shuts the
        // lid, and the sign is supposed to notice.
        scenarios.add(new Scenario("closingTheChestAfterStockingItOpensTheShop", () -> {
            RealShop scene = new RealShop(this, 4);
            scene.placeChestAndSign();
            scene.createShopByCommand("1 DIAMOND", "1 EMERALD");

            Assert.equal(ShopStatus.OUT_OF_STOCK,
                    scene.get(() -> Shop.loadShop(new ShopLocation(scene.signBlock().getLocation())).getStatus()),
                    "precondition: the shop starts empty");

            scene.stockShop(new ItemStack(Material.DIAMOND, 10));
            scene.closeChestAsOwner();

            Assert.eventually(15_000, "a stocked shop to report itself open",
                    scene.onServer(() -> Shop.loadShop(new ShopLocation(scene.signBlock().getLocation()))
                            .getStatus() == ShopStatus.OPEN));

            Shop shop = scene.get(() -> Shop.loadShop(new ShopLocation(scene.signBlock().getLocation())));
            Assert.equal(10, shop.getAvailableTrades(), "ten diamonds at one per trade is ten trades");

            // Nothing in this harness ever writes a sign. Line 3 says what it
            // says because TradeShop called sign.update() and a real server
            // obeyed - the assertion the tier-1 suite cannot make.
            Assert.equal(strip(ShopStatus.OPEN.getLine()), scene.signLines()[3],
                    "line 3 should have been rewritten on the block the server stored");
        }));

        // The trade itself: the thing a human would otherwise log in, place a
        // chest, write a sign and click to check. Same four movements the tier-1
        // suite asserts, so a divergence between tiers is visible rather than
        // arguable.
        scenarios.add(new Scenario("buyerWithEnoughCostReceivesTheProduct", () -> {
            RealShop scene = new RealShop(this, 5);
            scene.placeChestAndSign();
            scene.createShopByCommand("1 DIAMOND", "1 EMERALD");
            scene.stockShop(new ItemStack(Material.DIAMOND, 10));
            scene.closeChestAsOwner();

            Assert.eventually(15_000, "the shop to be open before anyone trades with it",
                    scene.onServer(() -> Shop.loadShop(new ShopLocation(scene.signBlock().getLocation()))
                            .getStatus() == ShopStatus.OPEN));

            Player buyer = scene.buyerHolding(new ItemStack(Material.EMERALD, 5));
            scene.rightClickSign(buyer);

            Assert.eventually(15_000, "the buyer to be holding a diamond",
                    scene.onServer(() -> scene.rawCountOf(buyer, Material.DIAMOND) == 1));

            Assert.equal(1, scene.countOf(buyer, Material.DIAMOND), "buyer should have received one diamond");
            Assert.equal(4, scene.countOf(buyer, Material.EMERALD), "buyer should have paid one emerald");
            Assert.equal(1, scene.countInChest(Material.EMERALD), "the emerald should be in the shop chest");
            Assert.equal(9, scene.countInChest(Material.DIAMOND), "the shop should have one fewer diamond");
        }));

        // The signs a shop can go on. These three are here rather than at tier 1
        // because tier 1 cannot express them: MockBukkit's material set is its
        // 1.21.1 line, so pale oak - added in 1.21.2 - does not exist there at
        // all, and MockBukkit refuses to build a block state for a hanging sign
        // ("Cannot create a SignMock from OAK_HANGING_SIGN"), so the create flow
        // cannot be driven over one. This server is 1.21.11 and its signs are
        // real blocks.
        //
        // Each names a defect in the hand-maintained sign catalogue that used to
        // stand in ShopSign: a wood that was never added, a mounting whose
        // material name was misspelt so it matched nothing, and a reverse lookup
        // that threw on the names the catalogue did register.
        scenarios.add(new Scenario("paleOakSignCanCarryAShop",
                () -> shopOnSignMaterial(12, "PALE_OAK_SIGN",
                        "pale oak was never in the twelve-wood catalogue, so a pale oak sign "
                                + "could not be a shop")));

        scenarios.add(new Scenario("hangingSignCanCarryAShop",
                () -> shopOnSignMaterial(13, "OAK_HANGING_SIGN",
                        "a hanging sign was registered as a shop sign and then threw from the "
                                + "colour lookup, which called valueOf on a name that was never "
                                + "a constant")));

        scenarios.add(new Scenario("wallHangingSignCanCarryAShop",
                () -> shopOnSignMaterial(14, "OAK_WALL_HANGING_SIGN",
                        "the catalogue built <WOOD>_HANGING_WALL_SIGN, which matches no material, "
                                + "so no wall-hanging sign was ever recognised")));

        // Which block an explosion has to spare to leave a shop sign standing.
        // The wall-hanging mounting is the one that was wrong, and it is here
        // because MockBukkit cannot build a block state for it; the two
        // mountings the mock can represent are asserted at tier 1.
        scenarios.add(new Scenario("hangingSignSupportsAreTheBlocksHoldingItUp", () -> {
            Material wallHanging = Material.matchMaterial("OAK_WALL_HANGING_SIGN");
            Material ceilingHanging = Material.matchMaterial("OAK_HANGING_SIGN");
            Assert.that(wallHanging != null && ceilingHanging != null,
                    "hanging signs do not exist on this server (" + Bukkit.getBukkitVersion()
                            + "), so this scenario would have proved nothing");

            RealShop scene = new RealShop(this, 15);
            scene.placeChestAndSign(wallHanging);
            Block sign = scene.signBlock();

            List<Block> wallSupports = scene.get(() -> ShopProtectionListener.supportingBlocks(sign));

            // The defect: contains("WALL_SIGN") is false for OAK_WALL_HANGING_SIGN,
            // so this sign was treated as a standing sign and the block below it
            // was protected instead of the wall actually holding it up.
            Assert.equal(2, wallSupports.size(),
                    "a wall hanging sign survives on either of the two blocks it spans, so both "
                            + "have to be spared");
            for (Block support : wallSupports) {
                Assert.equal(scene.get(sign::getY), support.getY(),
                        "a wall hanging sign is held from the side, not from underneath - the "
                                + "block below it is what the old string test protected");
            }

            scene.placeChestAndSign(ceilingHanging);
            Block ceiling = scene.signBlock();
            List<Block> ceilingSupports = scene.get(() -> ShopProtectionListener.supportingBlocks(ceiling));

            Assert.equal(1, ceilingSupports.size(), "a ceiling hanging sign hangs from one block");
            Assert.equal(scene.get(() -> ceiling.getRelative(BlockFace.UP)), ceilingSupports.get(0),
                    "and that block is above it, which no branch of the old test could return");
        }));

        // Tab completion, built from the live item registry rather than from a
        // blocklist that stopped being updated. Here as well as at tier 1
        // because this is the only tier where the library's registry query - the
        // implementation an operator actually gets - can run at all.
        scenarios.add(new Scenario("tabCompleteOffersOnlyMaterialsWithAnItemForm", () -> {
            List<String> offered = ((TradeShop) Bukkit.getPluginManager().getPlugin("TradeShop"))
                    .getListManager().getGameMats();

            Assert.that(offered.contains("DIAMOND"), "a diamond should be offered for trade");
            Assert.that(offered.contains("PALE_OAK_SIGN"),
                    "a pale oak sign is an item on this server and should be offered");

            // Neither of these was in the sixty-nine constant blocklist, because
            // neither existed when it was last edited.
            Assert.that(!offered.contains("PALE_OAK_WALL_HANGING_SIGN"),
                    "a wall-hanging sign has no item form and cannot be traded");
            Assert.that(!offered.contains("PALE_OAK_WALL_SIGN"),
                    "and neither can a wall sign");
        }));

        // The item-metadata matrix. Kept in its own file because it is a suite
        // rather than a scenario, and because every row in it carries the reason a
        // cheaper tier would have lied about it. See ItemMatrix.
        scenarios.addAll(ItemMatrix.rows(this));

        // The two per-item comparison toggles a mock cannot answer honestly. The
        // other fourteen are switched off and back on in the tier-1 matrix; these
        // need a BlockStateMeta and a real shop file respectively. See
        // SettingToggleRows.
        scenarios.addAll(SettingToggleRows.rows(this));

        // The reported defects and the one found beside them, in their own file
        // for the same reason as
        // the matrix: a suite rather than a scenario, and every row carries the
        // file and line it pins. Last, because one of them stands an older
        // config.yml up on disk and reloads the plugin's settings from it -
        // recoverable, and restored in a finally, but not something to run in
        // front of rows that read the same settings.
        scenarios.addAll(DefectRows.rows(this));
    }

    /**
     * A shop created on one named sign material, end to end.
     *
     * <p>The material is named as a string and resolved against the running
     * server on purpose. A constant would not compile against the 1.21.1 API
     * this plugin is built with, and a scenario that silently did nothing
     * because the material was absent is the vacuous pass this tier exists to
     * close - so an unresolvable name is a failure with the version in it.
     */
    private void shopOnSignMaterial(int site, String materialName, String defect) {
        Material signMaterial = Material.matchMaterial(materialName);
        Assert.that(signMaterial != null, materialName + " does not exist on this server ("
                + Bukkit.getBukkitVersion() + "), so this scenario would have proved nothing");

        RealShop scene = new RealShop(this, site);
        scene.placeChestAndSign(signMaterial);

        Assert.that(!scene.get(() -> ShopType.isShop(scene.signBlock())),
                "a blank sign is not a shop yet, which is the precondition the next line needs");

        scene.createShopByCommand("1 DIAMOND", "1 EMERALD");

        Shop shop = scene.get(() -> Shop.loadShop(new ShopLocation(scene.signBlock().getLocation())));
        Assert.that(shop != null, "no shop was stored at the " + materialName + ": " + defect);
        Assert.equal(ShopType.TRADE, shop.getShopType(), "the shop should be a trade shop");
        Assert.equal(scene.get(() -> scene.chestBlock().getLocation()), shop.getInventoryLocation(),
                "the shop should be linked to the chest under the sign");

        // The block, not the event: TradeShop wrote these lines through the
        // server, and writing them is the call that used to throw on a hanging
        // sign.
        Assert.that(scene.get(() -> ShopType.isShop(scene.signBlock())),
                "the finished " + materialName + " should read as a shop sign");
        Assert.equal(ShopType.TRADE.toHeader(), scene.signLines()[0], "line 0 is the shop's header");
        Assert.equal("1 Diamond", scene.signLines()[1], "line 1 is what the shop gives");
        Assert.equal("1 Emerald", scene.signLines()[2], "line 2 is what the shop takes");

        // A sign whose colour lookup fell through would write the four
        // characters "null" in front of the item line. It is worth one
        // assertion, because nothing else about the shop would look wrong.
        Assert.that(!scene.signLines()[1].contains("null"),
                "the default colour for this wood did not resolve, and the literal text was "
                        + "written onto the sign: " + scene.signLines()[1]);
    }

    private static String strip(String coloured) {
        return ChatColor.stripColor(coloured);
    }

    /**
     * Scenarios run a second after the server reports itself loaded, and off the
     * server thread.
     *
     * <p>{@link ServerLoadEvent} fires once the worlds and every plugin are up,
     * which is the earliest moment a scenario can touch a real block and get a
     * real answer. Off-thread because a scenario's job is partly to wait -
     * TradeShop reads stock when a chest closes and does disk work on the main
     * thread - and waiting on the main thread is waiting for yourself. Every
     * call that touches the world is marshalled back; see {@link RealShop}.
     */
    /**
     * What every plugin, TradeShop included, left on a sign edit.
     *
     * <p>Recorded at {@code MONITOR}, after TradeShop's {@code HIGHEST} handler.
     * These are the lines a real server would then write onto the block - the
     * write is done by the packet handler that fired the event, not by the event
     * bus, so a harness that fires the event itself never sees them land. Logging
     * them makes the difference between "TradeShop did not decorate the sign" and
     * "TradeShop decorated the sign and nothing wrote it back" readable from the
     * console instead of arguable.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void afterSignChange(SignChangeEvent event) {
        lastSignEventLines = event.getLines().clone();
        getLogger().info("sign event lines after every plugin ran: " + Arrays.toString(event.getLines())
                + " cancelled=" + event.isCancelled());
    }

    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        getServer().getScheduler().runTaskLaterAsynchronously(this, this::runEverything, 20L);
    }

    private void runEverything() {
        List<String> lines = new ArrayList<>();
        lines.add("SCENARIOS " + declaredScenarios());

        for (int i = 0; i < scenarios.size(); i++) {
            Scenario scenario = scenarios.get(i);

            // "missing" is the failure this harness is least likely to notice on
            // its own: a scenario that silently does not run while the report
            // still says the suite finished.
            if ("missing".equals(INDUCE) && i == scenarios.size() - 1) {
                getLogger().info("INDUCED: skipping scenario " + scenario.name());
                continue;
            }

            lines.add("SCENARIO " + scenario.name() + " " + run(scenario));
        }

        if ("severe".equals(INDUCE)) {
            // Every assertion passed and the run is still not trustworthy. This
            // is the shape of the failure that started this tier.
            getLogger().severe("INDUCED: a severe line with every scenario green");
        }

        if ("hang".equals(INDUCE)) {
            // Deliberately before the client phase is armed, so that "hang" stays
            // the failure it already was: nothing writes the result, nothing stops
            // the server, and the runner's timeout is the verdict. The runner does
            // not launch a bot in this mode either - there would be nothing armed
            // for it to report to.
            getLogger().info("INDUCED: not writing the result file, the runner must time out");
            return;
        }

        if (CLIENT) {
            // The server stays up from here, and that is the whole sequencing
            // problem of tier 3 solved in one place: the client phase writes the
            // result and stops the server itself, once the bot has finished or once
            // its deadline has expired. See ClientPhase.
            client.begin(lines);
            return;
        }

        writeResult(lines);
        // Back onto the server thread to stop it: the runner boots this server
        // with stdin closed, so nothing can type "stop" at it.
        getServer().getScheduler().runTask(this, Bukkit::shutdown);
    }

    private String run(Scenario scenario) {
        try {
            induceAssertionFailure(scenario.name());
            scenario.body().run();
            getLogger().info("PASS " + scenario.name());
            return "PASS";
        } catch (Throwable t) {
            // Reported rather than thrown: one broken scenario must not stop the
            // rest from running, and the runner needs the result file to exist
            // in order to tell a failure from a run that never happened.
            getLogger().warning("FAIL " + scenario.name() + ": " + describe(t));
            return "FAIL " + describe(t);
        }
    }

    /**
     * The sabotage hook, shared with the tier-3 steps so that both halves of the
     * suite can be shown to fail rather than only ever observed passing.
     */
    void induceAssertionFailure(String scenarioName) {
        if ("assert".equals(INDUCE)) {
            Assert.that(false, "INDUCED: " + scenarioName + " was told to assert something false");
        }
    }

    String describe(Throwable t) {
        String message = t.getMessage();
        String detail = (message == null || message.isEmpty()) ? t.toString() : message;
        return detail.replace('\n', ' ').replace('\r', ' ');
    }

    void writeResult(List<String> lines) {
        File marker = new File(System.getProperty("tradeshop.it.marker",
                new File(getDataFolder().getParentFile().getParentFile(), "it-result.txt").getPath()));

        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(marker.toPath(), StandardCharsets.UTF_8))) {
            for (String line : lines) {
                out.println(line);
            }
        } catch (IOException e) {
            // Nothing useful left to do: without the file the runner reports a
            // run that never happened, which is the right verdict anyway.
            getLogger().severe("could not write the result file at " + marker + ": " + e);
        }
    }

    /**
     * A named piece of behaviour and the moves that check it.
     *
     * <p>Package-visible so that {@link ItemMatrix} can build its own without this
     * file growing a second suite inside it.
     */
    record Scenario(String name, Runnable body) {
    }
}
