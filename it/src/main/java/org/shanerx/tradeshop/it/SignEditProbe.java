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

import com.bergerkiller.bukkit.common.events.SignEditTextEvent;
import io.papermc.paper.event.player.PlayerOpenSignEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerSignOpenEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.shanerx.tradeshop.shop.Shop;
import org.shanerx.tradeshop.shop.ShopStatus;
import org.shanerx.tradeshop.shop.ShopType;
import org.shanerx.tradeshop.shoplocation.ShopLocation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Whether BKCommonLib's {@code SignEditTextEvent} can hold a shop sign shut.
 *
 * <h2>The question</h2>
 * TradeShop guards a shop sign from being rewritten with two listeners that are
 * unlike each other. One is Paper's {@code PlayerOpenSignEvent}, which refuses
 * the editor before it opens and does not exist on Spigot. The other is a
 * {@code PlayerInteractEvent} deny, which is on every server and denies the whole
 * block interaction rather than the edit. BKCommonLib is already a hard
 * {@code depend:}, and its {@code SignEditTextEvent} reads - from its signature
 * alone - as if it could be both: cancellable, cross-platform, and carrying an
 * {@code EditReason} that tells placing a sign apart from editing one.
 *
 * <p>A signature is not a behaviour. This class registers a listener for it,
 * records every one that fires with the reason, the player, the side and the
 * lines, and lets a real client drive the paths a person would: placing a sign
 * and typing it, right-clicking an existing shop sign to open the editor,
 * submitting the editor's text, putting dye and glow ink on the sign, and
 * writing the back. The steps then assert what was recorded.
 *
 * <h2>What the probe is allowed to do</h2>
 * It cancels exactly one thing: an edit whose incoming text carries
 * {@value #CANCEL_MARKER}. The client types that marker when it wants to see
 * whether cancelling holds, so the decision is the client's and the probe only
 * obeys it. Nothing else here changes what the server does - every other handler
 * is at {@code MONITOR} and only writes to the log.
 *
 * <h2>Why the assertions read blocks and not the event</h2>
 * The same reason the rest of this tier does. "The event was cancelled" is a
 * statement about a listener; "the sign still says what it said" is a statement
 * about the world, and only the second one is what a shop owner is entitled to.
 */
final class SignEditProbe implements Listener {

    /**
     * The text the client types when it wants this probe to cancel the edit.
     *
     * <p>On line 0, and matched anywhere in the four lines being submitted. It
     * has to be the <em>client's</em> choice rather than a coordinate this class
     * keeps, because the interesting case is an edit of a sign the probe is also
     * asserting about, and a location-keyed rule would make the cancel and the
     * assertion the same decision made twice.
     */
    static final String CANCEL_MARKER = "CANCELME";

    /** The control text the client leaves on a sign it expects to keep. */
    static final String KEEP_MARKER = "PROBEKEEP";

    /** The text the client submits into an editor it opened on the shop sign. */
    static final String EDIT_MARKER = "PROBEEDIT";

    /** The text baked into the sign item the client places without ever typing it. */
    static final String PICK_MARKER = "PROBEPICK";

    /**
     * Declared in the order the client must drive them, and appended to
     * {@link ClientPhase#STEPS}.
     */
    static final List<String> STEPS = List.of(
            "signEditTextEventFiresForAPlacedSignAndCallsItAnEdit",
            "openingTheEditorOnAShopSignFiresNoSignEditTextEvent",
            "theSubmittedEditorTextFiresSignEditTextEventAndCallsItAPlace",
            "cancellingSignEditTextEventOnAPlacedSignLeavesItBlank",
            "cancellingSignEditTextEventOnAnEditLeavesTheOldText",
            "dyeAndGlowInkOnAShopSignFireNoSignEditTextEvent",
            "anInteractDenyStopsTheDyeThatTheTextEventNeverSaw",
            "theBackOfASignFiresForTheBackSideAndCreatesAShopNoClickCanReach",
            "placingASignThatAlreadyCarriesTextFiresCtrlPickPlace");

    /**
     * Three sites, all in the chunk the tier-3 site already loads, and none of
     * them on a coordinate any other scenario touches.
     *
     * <p>{@code SHOP} is a chest with a shop sign over it, left unstocked on
     * purpose: {@code ShopTradeListener} returns before its own
     * {@code setCancelled(true)} when a shop is out of stock, so the interact is
     * not denied and the vanilla sign editor opens. That is the state issue #152
     * describes and the state this probe needs in order to watch the editor path
     * at all.
     *
     * <p>{@code PLAIN} is a sign on the ground that is never a shop, so that
     * cancelling can be measured with TradeShop's own sign guards out of the way.
     *
     * <p>{@code BACK} is a chest with a plain sign over it, which the client
     * writes on the front, edits, and finally writes on the back.
     */
    private static final int SHOP_X = 7004;
    private static final int SHOP_Z = 0;
    private static final int PLAIN_X = 7000;
    private static final int PLAIN_Z = 4;
    private static final int BACK_X = 7000;
    private static final int BACK_Z = 8;
    private static final int PICK_X = 7000;
    private static final int PICK_Z = 12;

    private final IntegrationPlugin plugin;

    /** Every {@code SignEditTextEvent} this run has seen, in order. */
    private final List<Seen> edits = new CopyOnWriteArrayList<>();

    /** Every sign editor the server opened, from either API's event. */
    private final List<Opened> opens = new CopyOnWriteArrayList<>();

    /** Every block interaction this probe denied, by block. */
    private final List<String> denied = new CopyOnWriteArrayList<>();

    /** The tier-3 shop sign, which was placed and typed before any probe step. */
    private Block mainSign;

    private Block shopChest;
    private Block shopSign;
    private Block plainSign;
    private Block backChest;
    private Block backSign;
    private Block pickSign;

    SignEditProbe(IntegrationPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // What was seen
    // ------------------------------------------------------------------

    /**
     * One {@code SignEditTextEvent}, flattened to strings at the moment it fired.
     *
     * <p>Flattened because the event's {@code getSign()} is a live block state and
     * its lines are a mutable accessor: keeping the event itself would mean
     * asserting later against something the server has since changed, which is the
     * difference between a record and a stale reference.
     */
    private record Seen(String reason, String player, String side, List<String> lines,
                        String block, boolean cancelledHere) {
    }

    private record Opened(String event, String player, String side, String cause, String block) {
    }

    /**
     * The listener the whole probe is about.
     *
     * <p>{@code NORMAL} rather than {@code MONITOR} because it has to be able to
     * cancel. BKCommonLib fires this from its own {@code SignChangeEvent} handler
     * at {@code HIGH}, and cancelling here is what makes BKCommonLib cancel that
     * {@code SignChangeEvent} in turn.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onSignEditText(SignEditTextEvent event) {
        String[] lines = event.getLines();
        boolean cancel = Arrays.stream(lines)
                .anyMatch(line -> line != null && line.contains(CANCEL_MARKER));
        if (cancel) {
            event.setCancelled(true);
        }

        Seen seen = new Seen(String.valueOf(event.getEditReason()),
                event.getPlayer() == null ? "nobody" : event.getPlayer().getName(),
                String.valueOf(event.getSide()),
                List.of(strip(lines)),
                key(event.getBlock()),
                cancel);
        edits.add(seen);

        plugin.getLogger().info("SignEditTextEvent reason=" + seen.reason()
                + " player=" + seen.player()
                + " side=" + seen.side()
                + " block=" + seen.block()
                + " lines=" + seen.lines()
                + " cancelledByTheProbe=" + cancel);
    }

    /**
     * Paper's editor-open event, watched and never cancelled.
     *
     * <p>It is here to prove that the editor genuinely opened, which is the whole
     * precondition of the question this probe asks. TradeShop does register a
     * listener for it - and on this branch that registration is keyed on
     * {@code getServer().getVersion()} containing "paper", which this build's
     * version string does not, so nothing of TradeShop's is on it here.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPaperOpenSign(PlayerOpenSignEvent event) {
        noteOpen("PlayerOpenSignEvent", event.getPlayer().getName(),
                String.valueOf(event.getSide()), String.valueOf(event.getCause()),
                event.getSign().getBlock());
    }

    /**
     * Spigot's editor-open event, which is plain Bukkit API and therefore the one
     * a cross-platform guard would have to use. Watched for the same reason and
     * never cancelled.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBukkitSignOpen(PlayerSignOpenEvent event) {
        noteOpen("PlayerSignOpenEvent", event.getPlayer().getName(),
                String.valueOf(event.getSide()), String.valueOf(event.getCause()),
                event.getSign().getBlock());
    }

    /**
     * The other guard, narrowed to one item, so the comparison is measured rather
     * than argued.
     *
     * <p>The claim this probe has to make is that {@code SignEditTextEvent} and a
     * {@code PlayerInteractEvent} deny cover different things. Half of that is
     * measured elsewhere - dye changes a shop sign and fires no text event. The
     * other half is whether the interact deny would have stopped it, and reasoning
     * about what {@code Event.Result.DENY} does to a block interaction is exactly
     * the kind of reading this probe exists to distrust.
     *
     * <p>So it denies, with the same two lines the interact guard is built from -
     * {@code ShopType.isShop(clicked)} then {@code setUseInteractedBlock(DENY)} -
     * and narrowed to a blue dye so that nothing else in the run is touched. Which
     * dye is in hand is the client's choice, the same way {@value #CANCEL_MARKER}
     * is; this only obeys it.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProbeInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getMaterial() != Material.BLUE_DYE || event.getClickedBlock() == null) {
            return;
        }
        if (!ShopType.isShop(event.getClickedBlock())) {
            return;
        }

        event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        denied.add(key(event.getClickedBlock()));
        plugin.getLogger().info("the probe denied the block interaction of a "
                + event.getMaterial() + " right-click on " + key(event.getClickedBlock()));
    }

    private void noteOpen(String eventName, String player, String side, String cause, Block block) {
        Opened opened = new Opened(eventName, player, side, cause, key(block));
        opens.add(opened);
        plugin.getLogger().info(eventName + " player=" + player + " side=" + side
                + " cause=" + cause + " block=" + opened.block());
    }

    // ------------------------------------------------------------------
    // The sites
    // ------------------------------------------------------------------

    /**
     * Clears the three probe sites and remembers where they are.
     *
     * <p>Places nothing: every block the steps assert about has to have been put
     * there by the client, or the step would be asserting the harness.
     */
    void prepare(Block tier3Sign) {
        this.mainSign = tier3Sign;
        World world = tier3Sign.getWorld();

        shopChest = clearColumn(world, SHOP_X, SHOP_Z, 2);
        shopSign = shopChest.getRelative(0, 1, 0);

        plainSign = clearColumn(world, PLAIN_X, PLAIN_Z, 2);

        backChest = clearColumn(world, BACK_X, BACK_Z, 2);
        backSign = backChest.getRelative(0, 1, 0);

        pickSign = clearColumn(world, PICK_X, PICK_Z, 2);
    }

    /**
     * A sign item that already carries its own text, which is what ctrl-picking a
     * written sign in creative puts in a player's hand.
     *
     * <p>Handed over as fixture, the same way the sword and the emeralds are. The
     * client's <em>action</em> is placing a sign, which is real; where the item
     * came from is not what is being tested. A different wood from the plain signs
     * so that the client can ask for this one by name and never pick up the wrong
     * stack.
     *
     * <p>Returns a plain sign if the server will not let this item be built, which
     * makes the step fail by name with the observations printed rather than making
     * a join handler throw.
     */
    static ItemStack pickedSign() {
        ItemStack item = new ItemStack(Material.SPRUCE_SIGN, 1);
        try {
            if (item.getItemMeta() instanceof BlockStateMeta meta
                    && meta.getBlockState() instanceof Sign sign) {
                sign.getSide(Side.FRONT).setLine(0, PICK_MARKER);
                sign.getSide(Side.FRONT).setLine(1, "already written");
                meta.setBlockState(sign);
                item.setItemMeta(meta);
            }
        } catch (RuntimeException refused) {
            Bukkit.getLogger().warning("[TradeShopIT] a sign item carrying block state could not be "
                    + "built on this server: " + refused);
        }

        // Read back, because "the fixture did not carry any text" and "the event
        // does not report the text" look identical from the assertions.
        if (item.getItemMeta() instanceof BlockStateMeta built && built.getBlockState() instanceof Sign sign) {
            Bukkit.getLogger().info("[TradeShopIT] the pre-written sign item carries hasBlockState="
                    + built.hasBlockState() + " frontLines="
                    + Arrays.toString(sign.getSide(Side.FRONT).getLines()));
        }
        return item;
    }

    /** Empties {@code height} blocks above the ground and returns the lowest one. */
    private Block clearColumn(World world, int x, int z, int height) {
        world.getChunkAt(x >> 4, z >> 4).load(true);
        int groundY = world.getHighestBlockYAt(x, z);
        Block lowest = world.getBlockAt(x, groundY + 1, z);
        for (int i = 0; i < height; i++) {
            lowest.getRelative(0, i, 0).setType(Material.AIR, false);
        }
        return lowest;
    }

    /**
     * Where the client has to build, told to it rather than agreed with it.
     *
     * <p>Same reason the tier-3 site is announced at join: a coordinate written
     * down in two languages is a coordinate that can disagree with itself. The
     * kind word says what the position means, because "chest" and "sign" want
     * different placements.
     */
    List<String> siteMessages() {
        return List.of(
                "TS-IT PROBE SHOP CHEST " + at(shopChest),
                "TS-IT PROBE PLAIN SIGN " + at(plainSign),
                "TS-IT PROBE BACK CHEST " + at(backChest),
                "TS-IT PROBE PICK SIGN " + at(pickSign));
    }

    private static String at(Block block) {
        return block.getX() + " " + block.getY() + " " + block.getZ();
    }

    // ------------------------------------------------------------------
    // The steps
    // ------------------------------------------------------------------

    void body(String name) {
        switch (name) {
            case "signEditTextEventFiresForAPlacedSignAndCallsItAnEdit" -> firesOnAPlacedSign();
            case "openingTheEditorOnAShopSignFiresNoSignEditTextEvent" -> editorOpenFiresNothing();
            case "theSubmittedEditorTextFiresSignEditTextEventAndCallsItAPlace" -> submitFiresPlace();
            case "cancellingSignEditTextEventOnAPlacedSignLeavesItBlank" -> cancelHoldsOnPlace();
            case "cancellingSignEditTextEventOnAnEditLeavesTheOldText" -> cancelHoldsOnEdit();
            case "dyeAndGlowInkOnAShopSignFireNoSignEditTextEvent" -> dyeFiresNothing();
            case "anInteractDenyStopsTheDyeThatTheTextEventNeverSaw" -> interactDenyStopsDye();
            case "theBackOfASignFiresForTheBackSideAndCreatesAShopNoClickCanReach" -> firesForTheBackSide();
            case "placingASignThatAlreadyCarriesTextFiresCtrlPickPlace" -> firesForACtrlPickPlace();
            default -> throw new AssertionError("no body is written for declared step " + name);
        }
    }

    /**
     * Question one, and the cheapest possible answer to it: does the event fire at
     * all on this server, with this BKCommonLib, from a real client's packet?
     *
     * <p>Asserted against the sign the tier-3 flow already typed, so that the
     * answer costs no extra actions and comes from a sign edit nothing in this
     * file arranged.
     *
     * <h2>It fires, and it calls a placement an EDIT</h2>
     * That is measured and it was not what the signature suggested. The console
     * line from the first run of this probe:
     *
     * <pre>
     * SignEditTextEvent reason=EDIT player=TSOwner side=FRONT block=world/7000/-59/0
     *     lines=[[Trade], 1 DIAMOND, 1 EMERALD, ] cancelledByTheProbe=false
     * </pre>
     *
     * <p>The mechanism is in BKCommonLib and it is inverted on the Paper path.
     * {@code CommonListener.onSignChange} calls the reason {@code EDIT} when the
     * block is in a map of signs the player was known to be editing, and
     * {@code CommonSignOpenListenerPaper.execute} fills that map
     * <em>when the open cause is {@code PLACE}</em> - {@code if_acmpeq} to the
     * constant named PLACE, then store. Its Spigot counterpart,
     * {@code CommonSignOpenListenerBukkit}, carries the opposite test in its
     * source: {@code if (event.getCause() != Cause.PLACE) storeEditedSign(...)}.
     * So the two platforms disagree and Paper gets the wrong one. The reason is
     * therefore not usable to tell placing a sign from editing one - which was one
     * of the three things this event was being considered for.
     */
    private void firesOnAPlacedSign() {
        Assert.that(!edits.isEmpty(),
                "SignEditTextEvent never fired at all. BKCommonLib build is on this server and "
                        + "the client typed a sign; if this is empty the event is not on the path "
                        + "a sign edit takes. Sign editors the server did open: " + opens);

        List<Seen> forSign = editsAt(mainSign);
        Assert.equal(1, forSign.size(),
                "the client typed the tier-3 sign exactly once, so exactly one SignEditTextEvent "
                        + "belongs to it; all edits seen were " + edits);

        Seen seen = forSign.get(0);
        Assert.equal("EDIT", seen.reason(),
                "a sign that was placed and then typed is reported as an EDIT on Paper, because "
                        + "BKCommonLib's Paper sign-open listener records the sign exactly when the "
                        + "editor opened with cause PLACE. Read the class javadoc before treating "
                        + "this as a regression: it is the inversion this probe found");
        Assert.equal(ClientPhase.OWNER_BOT, seen.player(), "the client that typed it is the player on the event");
        Assert.equal("FRONT", seen.side(), "a sign is placed facing its placer, so the typed side is the front");
        Assert.equal(ShopType.TRADE.toHeader(), seen.lines().get(0),
                "the event carries the lines the client typed, before TradeShop decorates them");
        Assert.equal("1 DIAMOND", seen.lines().get(1), "line 1 is what the client typed");
        Assert.equal("1 EMERALD", seen.lines().get(2), "line 2 is what the client typed");
    }

    /**
     * The question that decides the whole probe: a player right-clicks a shop sign,
     * the vanilla editor opens over it, and this asks whether
     * {@code SignEditTextEvent} is anywhere on that path.
     *
     * <p>The shop is deliberately out of stock. {@code ShopTradeListener} returns
     * before its own {@code setCancelled(true)} in that state, so nothing denies
     * the interaction and the editor opens - which is the defect this branch
     * still carries and the only reason there is something here to watch.
     */
    private void editorOpenFiresNothing() {
        Shop shop = Sync.get(plugin, () -> Shop.loadShop(new ShopLocation(shopSign.getLocation())));
        Assert.that(shop != null, "the client should have built a second shop at the probe site");
        Assert.equal(ShopStatus.OUT_OF_STOCK, shop.getStatus(),
                "the probe shop is left unstocked on purpose: a stocked shop has its interact "
                        + "cancelled by the trade listener and the editor never opens");

        List<Opened> opened = opensAt(shopSign);
        Assert.that(!opened.isEmpty(),
                "right-clicking a shop sign should have opened the vanilla sign editor on this "
                        + "branch - that is issue #152 - and no editor-open event fired. Without "
                        + "the editor opening there is nothing for SignEditTextEvent to be "
                        + "compared against. Editors opened anywhere: " + opens);
        Assert.that(opened.stream().anyMatch(o -> "INTERACT".equals(o.cause())),
                "the editor should have opened because the client right-clicked, not because it "
                        + "placed the sign; opens on this block were " + opened);

        // The whole finding. The sign edit dialog is open on the client, and the
        // only SignEditTextEvent this block has ever produced is the one from
        // typing it when it was placed.
        List<Seen> forSign = editsAt(shopSign);
        Assert.equal(1, forSign.size(),
                "opening the editor fired a SignEditTextEvent, which would mean it can be used to "
                        + "refuse the editor. Edits on this block: " + forSign);
        Assert.that(forSign.get(0).lines().get(0).contains(ShopType.TRADE.toHeader()),
                "the one edit this block has seen carries the text typed at placement, not "
                        + "anything from the editor that is open right now; it was " + forSign.get(0));
    }

    /**
     * Where the event actually comes from: the text the client submits out of the
     * editor. It is fired from {@code SignChangeEvent} and nothing earlier, so it
     * arrives after the screen a shop owner did not want opened has been open,
     * filled in and sent.
     *
     * <p>And the reason is {@code PLACE}, which is the other half of the inversion
     * described on {@link #firesOnAPlacedSign()}: the editor opened with cause
     * {@code INTERACT}, BKCommonLib's Paper listener only records the block when
     * the cause is {@code PLACE}, so nothing was recorded and the reason fell
     * through to its default.
     *
     * <p>The sign is unchanged afterwards, because TradeShop's own
     * {@code SignChangeEvent} guard refuses the finished edit of a shop sign. That
     * guard is what makes the open editor harmless to the stored text; it is not
     * what stops the editor opening.
     */
    private void submitFiresPlace() {
        Assert.eventually(15_000, "the submitted editor text to reach a SignEditTextEvent",
                () -> editsAt(shopSign).size() > 1);

        List<Seen> forSign = editsAt(shopSign);
        Assert.equal(2, forSign.size(),
                "the block has been typed once at placement and submitted once from the editor; "
                        + "edits on it were " + forSign);

        Seen seen = forSign.get(1);
        Assert.equal("PLACE", seen.reason(),
                "submitting text into an editor opened by right-clicking is reported as a PLACE, "
                        + "which is the inversion this probe found - see the class javadoc");
        Assert.equal(ClientPhase.OWNER_BOT, seen.player(), "the client that submitted it is the player on the event");
        Assert.equal("FRONT", seen.side(), "the client is standing in front of the sign");
        Assert.that(seen.lines().get(0).contains(EDIT_MARKER),
                "the event carries the text the client submitted, was " + seen.lines());

        // The block, not the event. TradeShop cancelled the SignChangeEvent under
        // it, so the stored sign is the shop's.
        String[] lines = frontLines(shopSign);
        Assert.equal(ShopType.TRADE.toHeader(), lines[0],
                "TradeShop refuses the finished edit of a shop sign, so line 0 is still the header");
        Assert.that(!lines[0].contains(EDIT_MARKER) && !lines[1].contains(EDIT_MARKER),
                "none of the submitted text should have landed on the block, was " + Arrays.toString(lines));
    }

    /**
     * Cancelling, on the reason a placement produces. The sign the client placed
     * and typed is left blank, which is what a refused edit looks like on a sign
     * that had nothing on it before.
     */
    private void cancelHoldsOnPlace() {
        List<Seen> forSign = editsAt(plainSign);
        Assert.equal(1, forSign.size(),
                "the client placed one plain sign and typed it once; edits on it were " + forSign);
        Assert.equal("EDIT", forSign.get(0).reason(),
                "it was typed as it was placed, which this build reports as an EDIT - see the "
                        + "class javadoc for the inversion");
        Assert.that(forSign.get(0).cancelledHere(),
                "the client typed " + CANCEL_MARKER + ", so the probe should have cancelled this one");

        Assert.that(Sync.get(plugin, () -> plainSign.getState() instanceof Sign),
                "the sign itself should still be standing - cancelling the text must not remove the block, was "
                        + Sync.get(plugin, () -> plainSign.getType()));

        String[] lines = frontLines(plainSign);
        for (int i = 0; i < lines.length; i++) {
            Assert.equal("", lines[i],
                    "line " + i + " of a cancelled placement should be blank, whole sign was "
                            + Arrays.toString(lines));
        }
    }

    /**
     * Cancelling on the {@code EDIT} reason, with a control beside it.
     *
     * <p>The control is the point. "The sign still says what it said" proves
     * nothing on its own unless the same client, on the same sign, can be shown to
     * change it when the probe does not cancel - otherwise an edit that never
     * arrived reads exactly like an edit that was refused.
     */
    private void cancelHoldsOnEdit() {
        List<Seen> forSign = editsAt(backSign);
        Assert.equal(2, forSign.size(),
                "the client typed this sign at placement and then edited it once; edits on it were " + forSign);

        Seen placed = forSign.get(0);
        Assert.equal("EDIT", placed.reason(),
                "the first was typing it as it was placed, which this build calls an EDIT");
        Assert.that(!placed.cancelledHere(), "the control edit is not cancelled, which is what makes it a control");

        Seen edited = forSign.get(1);
        Assert.equal("PLACE", edited.reason(),
                "the second was submitted out of an editor the client opened, which this build "
                        + "calls a PLACE - the two reasons are swapped, see the class javadoc");
        Assert.that(edited.cancelledHere(),
                "the client typed " + CANCEL_MARKER + " into the editor, so the probe cancelled it");

        String[] lines = frontLines(backSign);
        Assert.that(lines[0].contains(KEEP_MARKER),
                "the control text is what survived a cancelled edit, was " + Arrays.toString(lines));
        Assert.that(Arrays.stream(lines).noneMatch(line -> line.contains(CANCEL_MARKER)),
                "none of the cancelled text should have landed on the block, was " + Arrays.toString(lines));
    }

    /**
     * What the event does not cover.
     *
     * <p>Dye and a glow ink sac change a sign without ever changing its text, so
     * they are a different interaction with the same block. The current interact
     * deny stops them because it denies the block; a text event has nothing to say
     * about them. Asserted both ways: no {@code SignEditTextEvent} fired, and the
     * sign really did change, so the gap is a gap and not an action that never
     * happened.
     */
    private void dyeFiresNothing() {
        Assert.eventually(15_000, "the dye and the glow ink to land on the shop sign",
                () -> Sync.get(plugin, () -> {
                    if (!(shopSign.getState() instanceof Sign sign)) {
                        return false;
                    }
                    SignSide front = sign.getSide(Side.FRONT);
                    return front.getColor() == DyeColor.RED && front.isGlowingText();
                }));

        Assert.equal(DyeColor.RED, Sync.get(plugin, () ->
                        ((Sign) shopSign.getState()).getSide(Side.FRONT).getColor()),
                "a red dye used on a shop sign recoloured it, and nothing in TradeShop refused the "
                        + "interaction on this branch");
        Assert.that(Sync.get(plugin, () ->
                        ((Sign) shopSign.getState()).getSide(Side.FRONT).isGlowingText()),
                "a glow ink sac used on a shop sign made its text glow");

        // And the event said nothing about either. Two edits is what the block had
        // before the dye: the placement and the submitted editor text.
        List<Seen> forSign = editsAt(shopSign);
        Assert.equal(2, forSign.size(),
                "dye and glow ink changed the sign and fired no SignEditTextEvent, so a guard built "
                        + "only on that event does not cover them. Edits on this block were " + forSign);
    }

    /**
     * The other half of the same question: the interaction the text event never
     * saw is one the interact deny does stop.
     *
     * <p>The client came back to the same shop sign holding a blue dye. The probe
     * denied the block interaction on it - the two lines the interact guard is
     * built from and nothing else - and the sign is still the colour the red dye
     * made it. So the two guards are not alternatives: one refuses text, the other
     * refuses the interaction, and dye is only ever the second.
     */
    private void interactDenyStopsDye() {
        String block = key(shopSign);
        Assert.that(denied.contains(block),
                "the probe should have denied a blue dye right-click on the shop sign; it denied "
                        + denied);

        Assert.equal(DyeColor.RED, Sync.get(plugin, () ->
                        ((Sign) shopSign.getState()).getSide(Side.FRONT).getColor()),
                "the blue dye did not land, because the block's own interaction was denied - which "
                        + "is the guard TradeShop has and the text event does not");

        List<Seen> forSign = editsAt(shopSign);
        Assert.equal(2, forSign.size(),
                "and still no SignEditTextEvent has anything to say about any of it; edits on this "
                        + "block were " + forSign);
    }

    /**
     * The back of a sign, which the event does carry a side for - and what
     * TradeShop makes of one, which is worse than not caring.
     *
     * <h2>The event</h2>
     * It fires for the back, with {@code getSide()} reporting {@code BACK}, so
     * this is one thing the event genuinely offers that a
     * {@code PlayerInteractEvent} deny does not distinguish at all.
     *
     * <h2>What TradeShop does with it, measured</h2>
     * A shop header typed on the <em>back</em> of a sign standing on a chest
     * creates a real shop that is stored on disk, and no click will ever open it:
     *
     * <pre>
     * after a shop header was written on the BACK of a sign over a chest:
     *     ShopType.isShop(block)=false storedShop=Trade
     *     frontLines=[PROBEKEEP one, keep two, keep three, ]
     *     backLines=[[Trade], 1 Diamond, 1 Emerald, &lt;Out Of Stock&gt;]
     * </pre>
     *
     * <p>{@code ShopCreateListener.onSignChange} never asks
     * {@code SignChangeEvent.getSide()}. It copies the event's lines onto a
     * {@code Sign} snapshot with {@code setLine}, which is the front-side
     * accessor, and asks {@code ShopType.isShop} about that - so a back-side edit
     * reads as a shop header. The shop is then created and filed under the sign's
     * location, while the decoration it writes back goes to the side actually
     * being edited, the back. Afterwards {@code ShopType.isShop(block)} is false
     * because that reads the front, so {@code ShopTradeListener} will never match
     * a click on it.
     */
    private void firesForTheBackSide() {
        Assert.eventually(15_000, "the back-side edit to reach a SignEditTextEvent",
                () -> editsAt(backSign).stream().anyMatch(s -> "BACK".equals(s.side())));

        List<Seen> backEdits = editsAt(backSign).stream().filter(s -> "BACK".equals(s.side())).toList();
        Assert.equal(1, backEdits.size(),
                "the client wrote the back of the sign once; back-side edits on it were " + backEdits);
        Assert.equal(ClientPhase.OWNER_BOT, backEdits.get(0).player(),
                "the client that wrote it is the player on the event");
        Assert.that(backEdits.get(0).lines().get(0).contains(ShopType.TRADE.toHeader()),
                "the event carries the text the client wrote on the back, was " + backEdits.get(0).lines());

        boolean readsAsShop = Sync.get(plugin, () -> ShopType.isShop(backSign));
        Shop stored = Sync.get(plugin, () -> Shop.loadShop(new ShopLocation(backSign.getLocation())));
        String[] front = frontLines(backSign);
        String[] back = sideLines(backSign, Side.BACK);

        plugin.getLogger().info("after a shop header was written on the BACK of a sign over a chest: "
                + "ShopType.isShop(block)=" + readsAsShop
                + " storedShop=" + (stored == null ? "none" : stored.getShopType())
                + " frontLines=" + Arrays.toString(front)
                + " backLines=" + Arrays.toString(back));

        // TradeShop took the back-side edit for a shop header and created a shop
        // from it. That is not this probe's subject, but it is what this probe's
        // client did, and a measurement nobody pins is a measurement nobody keeps.
        Assert.that(stored != null,
                "a shop header typed on the BACK of a sign over a chest created no shop, which "
                        + "would be the correct outcome and is not the one this branch produces");
        Assert.equal(ShopType.TRADE, stored.getShopType(),
                "the shop TradeShop created from a back-side edit is a trade shop");
        Assert.equal(strip(new String[] {ShopStatus.OUT_OF_STOCK.getLine()})[0], back[3],
                "and it decorated the side that was edited, so the shop's status line is on the "
                        + "BACK; the back read " + Arrays.toString(back));

        // And it is unreachable. ShopType.isShop reads the front, which still says
        // what the client wrote there, so no click on this block is ever seen as a
        // click on a shop.
        Assert.that(!readsAsShop,
                "the block does not read as a shop sign, because ShopType.isShop reads the front "
                        + "and the front is untouched - front was " + Arrays.toString(front));
        Assert.that(front[0].contains(KEEP_MARKER),
                "the front still carries what the client wrote on it, was " + Arrays.toString(front));
    }

    /**
     * The third reason, and the only one this build gets right.
     *
     * <p>{@code CTRL_PICK_PLACE} does not come from {@code SignChangeEvent} at all:
     * {@code CommonListener.onBlockPlaceHandleSignEvents} fires it from
     * {@code BlockPlaceEvent} when the item being placed is a
     * {@code BlockStateMeta} that already has a block state, and the block placed
     * is a sign. That is a sign that arrives with its text already on it - what
     * ctrl-picking a written sign in creative hands a player - and no editor is
     * ever opened for it.
     *
     * <p>Worth one row because it is the one path where TradeShop would otherwise
     * see nothing: a placed sign that carries text fires no
     * {@code SignChangeEvent}, so neither of TradeShop's existing guards nor its
     * shop-creation listener is on it.
     */
    private void firesForACtrlPickPlace() {
        Assert.that(Sync.get(plugin, () -> pickSign.getState() instanceof Sign),
                "the client should have placed the pre-written sign, was "
                        + Sync.get(plugin, () -> pickSign.getType()));

        Assert.eventually(15_000, "the pre-written sign's placement to reach a SignEditTextEvent",
                () -> !editsAt(pickSign).isEmpty());

        List<Seen> forSign = editsAt(pickSign);
        Assert.that(forSign.stream().anyMatch(s -> "CTRL_PICK_PLACE".equals(s.reason())),
                "placing a sign that already carries its text is the one path that reports "
                        + "CTRL_PICK_PLACE; edits on this block were " + forSign);

        Seen seen = forSign.stream().filter(s -> "CTRL_PICK_PLACE".equals(s.reason())).findFirst().orElseThrow();
        Assert.equal(ClientPhase.OWNER_BOT, seen.player(), "the client that placed it is the player on the event");
        Assert.equal("FRONT", seen.side(), "the front of the placed sign is the side it is fired for");

        Assert.that(seen.lines().get(0).contains(PICK_MARKER),
                "the event carries the text the item arrived with, was " + seen.lines());

        // Nothing was typed, so no editor was opened over it. That is the shape of
        // the path: a sign can arrive complete, with no editor and no
        // SignChangeEvent anywhere near it.
        Assert.that(opensAt(pickSign).isEmpty(),
                "a sign placed with its text already on it opens no editor; opens on this block were "
                        + opensAt(pickSign));

        String[] lines = frontLines(pickSign);
        Assert.that(lines[0].contains(PICK_MARKER),
                "and the text really is on the block, was " + Arrays.toString(lines));

        plugin.getLogger().info("after placing a sign item that carries block state: "
                + "editorsOpened=" + opensAt(pickSign)
                + " blockFrontLines=" + Arrays.toString(lines));
    }

    // ------------------------------------------------------------------
    // Reading it back
    // ------------------------------------------------------------------

    private List<Seen> editsAt(Block block) {
        String key = key(block);
        List<Seen> found = new ArrayList<>();
        for (Seen seen : edits) {
            if (seen.block().equals(key)) {
                found.add(seen);
            }
        }
        return found;
    }

    private List<Opened> opensAt(Block block) {
        String key = key(block);
        List<Opened> found = new ArrayList<>();
        for (Opened opened : opens) {
            if (opened.block().equals(key)) {
                found.add(opened);
            }
        }
        return found;
    }

    private String[] frontLines(Block block) {
        return sideLines(block, Side.FRONT);
    }

    private String[] sideLines(Block block, Side side) {
        return Sync.get(plugin, () -> {
            if (!(block.getState() instanceof Sign sign)) {
                throw new AssertionError("there is no sign at " + key(block) + ", it is " + block.getType());
            }
            return strip(sign.getSide(side).getLines());
        });
    }

    private static String[] strip(String[] lines) {
        String[] clean = new String[lines.length];
        for (int i = 0; i < lines.length; i++) {
            clean[i] = lines[i] == null ? "" : ChatColor.stripColor(lines[i]);
        }
        return clean;
    }

    /** A block as one comparable string, so an observation outlives the block state. */
    private static String key(Block block) {
        return block.getWorld().getName().toLowerCase(Locale.ROOT)
                + "/" + block.getX() + "/" + block.getY() + "/" + block.getZ();
    }

    /**
     * What this server calls itself, printed once.
     *
     * <p>Not an assertion and not this probe's subject, but it is the value the
     * guard under discussion is keyed on, and a run that answers "which guard
     * should TradeShop keep" is the right place for it to be on the record.
     */
    void logServerIdentity() {
        plugin.getLogger().info("this server's getVersion() is \"" + Bukkit.getServer().getVersion()
                + "\", getName() is \"" + Bukkit.getServer().getName()
                + "\", and PlayerOpenSignEvent is "
                + (classPresent("io.papermc.paper.event.player.PlayerOpenSignEvent") ? "" : "not ")
                + "on the classpath");
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException absent) {
            return false;
        }
    }
}
