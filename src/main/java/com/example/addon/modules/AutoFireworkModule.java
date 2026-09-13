package com.example.addon.modules; // TODO: sostituisci con il package del tuo addon

import com.example.addon.AddonTemplate
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
 
import java.util.List;
 
/**
 * Modulo custom: compra materiali nello shop (/shop), trasforma glowstone -> polvere
 * e canna da zucchero -> carta, poi crafta in sequenza le 3 ricette dei fuochi
 * pirotecnici (stella base -> stella con fade -> razzo finito).
 *
 * NOTE IMPORTANTI PRIMA DI TESTARE:
 * - Mettiti in un punto con terreno PIATTO e libero, con almeno un banco da
 *   lavoro piazzato entro ~5 blocchi da te (come richiesto).
 * - La fase "glowstone" piazza il blocco 1 casella a NORD di te: assicurati
 *   che quella posizione sia libera e che sotto ci sia un blocco solido.
 * - Le ricette dei fuochi in Minecraft sono "shapeless" (non contano le
 *   posizioni nella griglia, solo quali oggetti ci sono), quindi il modulo
 *   non deve preoccuparsi di dove mettere esattamente ogni ingrediente.
 */
public class AutoFireworkModule extends Module {
 
    // ----------------------- SETTINGS -----------------------
 
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
 
    private final Setting<Integer> clickDelayTicks = sgGeneral.add(new IntSetting.Builder()
        .name("click-delay")
        .description("Tick di attesa tra un click e l'altro nello shop/crafting.")
        .defaultValue(4)
        .min(1)
        .sliderMax(20)
        .build()
    );
 
    private final Setting<Integer> afterCloseDelayTicks = sgGeneral.add(new IntSetting.Builder()
        .name("delay-dopo-chiusura")
        .description("Tick di attesa dopo aver chiuso lo shop prima di riaprirlo.")
        .defaultValue(10)
        .min(1)
        .sliderMax(40)
        .build()
    );
 
    private final Setting<Integer> screenTimeoutTicks = sgGeneral.add(new IntSetting.Builder()
        .name("timeout-apertura-schermata")
        .description("Dopo quanti tick considerare fallita l'apertura di una schermata.")
        .defaultValue(60)
        .min(20)
        .sliderMax(200)
        .build()
    );
 
    private final Setting<Integer> craftingSearchRadius = sgGeneral.add(new IntSetting.Builder()
        .name("raggio-ricerca-banco")
        .description("Raggio (in blocchi) in cui cercare un banco da lavoro vicino.")
        .defaultValue(5)
        .min(1)
        .sliderMax(10)
        .build()
    );
 
    private final Setting<Integer> maxBreakTicks = sgGeneral.add(new IntSetting.Builder()
        .name("max-tick-rottura-blocco")
        .description("Tick massimi spesi a colpire un blocco prima di considerarlo bloccato.")
        .defaultValue(30)
        .min(5)
        .sliderMax(100)
        .build()
    );
 
    public AutoFireworkModule() {
        super(Categories.Misc, "auto-firework", "Compra materiali e crafta i fuochi pirotecnici personalizzati.");
    }
 
    // ----------------------- DATA STRUCTURES -----------------------
 
    private record BuyStep(int slot, int clicks) {}
    private record BuyOrder(String name, List<BuyStep> steps) {}
    private record CraftIngredient(Item item, int gridSlot) {}
 
    private static final List<BuyOrder> BUY_ORDERS = List.of(
        new BuyOrder("Gunpowder", List.of(
            new BuyStep(21, 1), new BuyStep(8, 1), new BuyStep(31, 1), new BuyStep(23, 2)
        )),
        new BuyOrder("Cyan Dye", List.of(
            new BuyStep(23, 1), new BuyStep(3, 1), new BuyStep(31, 1), new BuyStep(13, 1)
        )),
        new BuyOrder("Purple Dye", List.of(
            new BuyStep(23, 1), new BuyStep(12, 1), new BuyStep(31, 1), new BuyStep(13, 1)
        )),
        new BuyOrder("Feather", List.of(
            new BuyStep(21, 1), new BuyStep(5, 1), new BuyStep(31, 1), new BuyStep(13, 1)
        )),
        new BuyOrder("Diamond", List.of(
            new BuyStep(13, 1), new BuyStep(7, 1), new BuyStep(31, 1), new BuyStep(13, 1)
        )),
        new BuyOrder("Glowstone Block", List.of(
            new BuyStep(19, 1), new BuyStep(14, 2), new BuyStep(6, 1), new BuyStep(31, 1), new BuyStep(13, 1)
        )),
        new BuyOrder("Black Dye", List.of(
            new BuyStep(23, 1), new BuyStep(0, 1), new BuyStep(31, 1), new BuyStep(13, 1)
        )),
        new BuyOrder("Gray Dye", List.of(
            new BuyStep(23, 1), new BuyStep(4, 1), new BuyStep(31, 1), new BuyStep(13, 1)
        )),
        new BuyOrder("Sugar Cane", List.of(
            new BuyStep(20, 1), new BuyStep(18, 1), new BuyStep(31, 1), new BuyStep(13, 1)
        ))
    );
 
    // Ricetta 1: stella pirotecnica base
    private static final List<CraftIngredient> RECIPE_1 = List.of(
        new CraftIngredient(Items.GUNPOWDER, 1),
        new CraftIngredient(Items.CYAN_DYE, 2),
        new CraftIngredient(Items.PURPLE_DYE, 3),
        new CraftIngredient(Items.FEATHER, 4),
        new CraftIngredient(Items.DIAMOND, 5),
        new CraftIngredient(Items.GLOWSTONE_DUST, 6)
    );
 
    // Ricetta 2: aggiunge il fade (nero + grigio) alla stella
    private static final List<CraftIngredient> RECIPE_2 = List.of(
        new CraftIngredient(Items.FIREWORK_STAR, 1),
        new CraftIngredient(Items.BLACK_DYE, 2),
        new CraftIngredient(Items.GRAY_DYE, 3)
    );
 
    // Ricetta 3: razzo finito
    private static final List<CraftIngredient> RECIPE_3 = List.of(
        new CraftIngredient(Items.FIREWORK_STAR, 1),
        new CraftIngredient(Items.PAPER, 2),
        new CraftIngredient(Items.GUNPOWDER, 3),
        new CraftIngredient(Items.GUNPOWDER, 4)
    );
 
    // Slot della griglia di crafting nel banco da lavoro (0 = output, 1-9 = griglia 3x3)
    private static final int CRAFTING_OUTPUT_SLOT = 0;
    private static final int CRAFTING_PLAYER_INV_START = 10; // dopo output+griglia iniziano inventario+hotbar
 
    // ----------------------- STATE MACHINE -----------------------
 
    private enum Stage {
        IDLE, BUYING, PROCESS_GLOWSTONE, CRAFT_PAPER, CRAFT_RECIPE_1, CRAFT_RECIPE_2, CRAFT_RECIPE_3, DONE
    }
 
    private enum BuyPhase { SEND_COMMAND, WAIT_OPEN, CLICKING, WAIT_AFTER_CLICK, CLOSE, WAIT_CLOSE }
 
    private Stage stage = Stage.IDLE;
    private int ticksWaited = 0;
 
    // stato fase acquisti
    private int buyOrderIndex = 0;
    private int buyStepIndex = 0;
    private int buyClicksLeft = 0;
    private BuyPhase buyPhase = BuyPhase.SEND_COMMAND;
 
    // stato fase glowstone
    private enum GlowstonePhase { CHECK, SWAP_HOTBAR, PLACE, BREAK, WAIT }
    private GlowstonePhase glowstonePhase = GlowstonePhase.CHECK;
    private BlockPos glowstoneTargetPos = null;
 
    @Override
    public void onActivate() {
        stage = Stage.BUYING;
        buyOrderIndex = 0;
        buyStepIndex = 0;
        buyPhase = BuyPhase.SEND_COMMAND;
        glowstonePhase = GlowstonePhase.CHECK;
        ticksWaited = 0;
        log("Avviato. Inizio acquisti...");
    }
 
    @Override
    public void onDeactivate() {
        stage = Stage.IDLE;
    }
 
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
 
        switch (stage) {
            case BUYING -> tickBuying();
            case PROCESS_GLOWSTONE -> tickGlowstone();
            case CRAFT_PAPER -> tickCraftPaper();
            case CRAFT_RECIPE_1 -> tickCraft(RECIPE_1, Stage.CRAFT_RECIPE_2, "Ricetta 1 (stella base)");
            case CRAFT_RECIPE_2 -> tickCraft(RECIPE_2, Stage.CRAFT_RECIPE_3, "Ricetta 2 (fade)");
            case CRAFT_RECIPE_3 -> tickCraft(RECIPE_3, Stage.DONE, "Ricetta 3 (razzo finale)");
            case DONE -> {
                log("Fatto! Tutti i fuochi disponibili sono stati craftati.");
                toggle();
            }
            default -> {}
        }
    }
 
    // ----------------------- FASE ACQUISTI -----------------------
 
    private void tickBuying() {
        if (buyOrderIndex >= BUY_ORDERS.size()) {
            log("Acquisti completati. Passo alla lavorazione della glowstone.");
            stage = Stage.PROCESS_GLOWSTONE;
            glowstonePhase = GlowstonePhase.CHECK;
            return;
        }
 
        BuyOrder order = BUY_ORDERS.get(buyOrderIndex);
 
        switch (buyPhase) {
            case SEND_COMMAND -> {
                mc.player.networkHandler.sendChatCommand("shop");
                log("Apro shop per: " + order.name());
                ticksWaited = 0;
                buyStepIndex = 0;
                buyPhase = BuyPhase.WAIT_OPEN;
            }
            case WAIT_OPEN -> {
                ticksWaited++;
                if (isContainerScreenOpen()) {
                    buyPhase = BuyPhase.CLICKING;
                    buyStepIndex = 0;
                    buyClicksLeft = order.steps().get(0).clicks();
                    ticksWaited = 0;
                } else if (ticksWaited > screenTimeoutTicks.get()) {
                    log("Timeout apertura shop per " + order.name() + ", riprovo.");
                    buyPhase = BuyPhase.SEND_COMMAND;
                }
            }
            case CLICKING -> {
                if (!isContainerScreenOpen()) {
                    // schermata chiusa inaspettatamente, riprova da capo questo materiale
                    buyPhase = BuyPhase.SEND_COMMAND;
                    return;
                }
                HandledScreen<?> screen = (HandledScreen<?>) mc.currentScreen;
                ScreenHandler handler = screen.getScreenHandler();
                BuyStep step = order.steps().get(buyStepIndex);
                mc.interactionManager.clickSlot(handler.syncId, step.slot(), 0, SlotActionType.PICKUP, mc.player);
                buyClicksLeft--;
                if (buyClicksLeft <= 0) {
                    buyStepIndex++;
                    if (buyStepIndex >= order.steps().size()) {
                        buyPhase = BuyPhase.CLOSE;
                    } else {
                        buyClicksLeft = order.steps().get(buyStepIndex).clicks();
                        ticksWaited = 0;
                        buyPhase = BuyPhase.WAIT_AFTER_CLICK;
                    }
                } else {
                    ticksWaited = 0;
                    buyPhase = BuyPhase.WAIT_AFTER_CLICK;
                }
            }
            case WAIT_AFTER_CLICK -> {
                ticksWaited++;
                if (ticksWaited >= clickDelayTicks.get()) {
                    buyPhase = BuyPhase.CLICKING;
                }
            }
            case CLOSE -> {
                mc.player.closeHandledScreen();
                ticksWaited = 0;
                buyPhase = BuyPhase.WAIT_CLOSE;
            }
            case WAIT_CLOSE -> {
                ticksWaited++;
                if (ticksWaited >= afterCloseDelayTicks.get()) {
                    buyOrderIndex++;
                    buyPhase = BuyPhase.SEND_COMMAND;
                }
            }
        }
    }
 
    // ----------------------- FASE GLOWSTONE -----------------------
 
    private void tickGlowstone() {
        switch (glowstonePhase) {
            case CHECK -> {
                int slot = findItemInPlayerInventory(Blocks.GLOWSTONE.asItem());
                if (slot == -1) {
                    log("Glowstone dust pronta. Passo al crafting della carta.");
                    stage = Stage.CRAFT_PAPER;
                    return;
                }
                glowstonePhase = GlowstonePhase.SWAP_HOTBAR;
            }
            case SWAP_HOTBAR -> {
                int slot = findItemInPlayerInventory(Blocks.GLOWSTONE.asItem());
                if (slot == -1) {
                    glowstonePhase = GlowstonePhase.CHECK;
                    return;
                }
                // sposta il blocco nello slot 0 della hotbar (bottone SWAP con indice hotbar 0)
                mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, slot, 0, SlotActionType.SWAP, mc.player);
                mc.player.getInventory().selectedSlot = 0;
                glowstoneTargetPos = mc.player.getBlockPos().offset(Direction.NORTH);
                glowstonePhase = GlowstonePhase.PLACE;
                ticksWaited = 0;
            }
            case PLACE -> {
                BlockPos below = glowstoneTargetPos.down();
                if (!mc.world.getBlockState(glowstoneTargetPos).isAir()) {
                    // posizione occupata: prova comunque a rompere quello che c'è (nel caso sia rimasto un blocco da un ciclo precedente fallito)
                    glowstonePhase = GlowstonePhase.BREAK;
                    ticksWaited = 0;
                    return;
                }
                Vec3d hitPos = Vec3d.ofCenter(below).add(0, 0.5, 0);
                BlockHitResult hit = new BlockHitResult(hitPos, Direction.UP, below, false);
                ActionResult result = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
                if (result.isAccepted()) {
                    glowstonePhase = GlowstonePhase.BREAK;
                } else {
                    log("Impossibile piazzare glowstone (terreno non adatto?). Fermo il modulo.");
                    toggle();
                }
                ticksWaited = 0;
            }
            case BREAK -> {
                ticksWaited++;
                if (mc.world.getBlockState(glowstoneTargetPos).isAir()) {
                    glowstonePhase = GlowstonePhase.WAIT;
                    ticksWaited = 0;
                    return;
                }
                mc.interactionManager.attackBlock(glowstoneTargetPos, Direction.UP);
                mc.player.swingHand(Hand.MAIN_HAND);
                if (ticksWaited > maxBreakTicks.get()) {
                    log("Rottura glowstone troppo lenta, salto e riprovo.");
                    glowstonePhase = GlowstonePhase.WAIT;
                    ticksWaited = 0;
                }
            }
            case WAIT -> {
                ticksWaited++;
                if (ticksWaited >= clickDelayTicks.get()) {
                    glowstonePhase = GlowstonePhase.SWAP_HOTBAR;
                }
            }
        }
    }
 
    // ----------------------- FASE CARTA -----------------------
 
    private enum CraftPaperPhase { OPEN_TABLE, FILL, COLLECT, WAIT, CHECK_DONE }
    private CraftPaperPhase paperPhase = CraftPaperPhase.OPEN_TABLE;
 
    private void tickCraftPaper() {
        switch (paperPhase) {
            case OPEN_TABLE -> {
                if (isContainerScreenOpen()) {
                    paperPhase = CraftPaperPhase.CHECK_DONE;
                    return;
                }
                if (!openNearbyCraftingTable()) {
                    log("Nessun banco da lavoro trovato entro il raggio impostato. Fermo il modulo.");
                    toggle();
                }
                ticksWaited = 0;
            }
            case CHECK_DONE -> {
                int slot = findItemInScreen(Items.SUGAR_CANE);
                if (slot == -1) {
                    log("Carta pronta. Passo alla ricetta 1.");
                    mc.player.closeHandledScreen();
                    paperPhase = CraftPaperPhase.OPEN_TABLE;
                    stage = Stage.CRAFT_RECIPE_1;
                    return;
                }
                paperPhase = CraftPaperPhase.FILL;
            }
            case FILL -> {
                HandledScreen<?> screen = (HandledScreen<?>) mc.currentScreen;
                ScreenHandler handler = screen.getScreenHandler();
                // 3 canne da zucchero in fila (slot griglia 1, 2, 3) = 3 carta
                boolean ok = true;
                for (int gridSlot : new int[]{1, 2, 3}) {
                    int source = findItemInScreen(Items.SUGAR_CANE);
                    if (source == -1) { ok = false; break; }
                    transferOneItem(handler, source, gridSlot);
                }
                paperPhase = ok ? CraftPaperPhase.COLLECT : CraftPaperPhase.CHECK_DONE;
                ticksWaited = 0;
            }
            case COLLECT -> {
                HandledScreen<?> screen = (HandledScreen<?>) mc.currentScreen;
                ScreenHandler handler = screen.getScreenHandler();
                mc.interactionManager.clickSlot(handler.syncId, CRAFTING_OUTPUT_SLOT, 0, SlotActionType.QUICK_MOVE, mc.player);
                paperPhase = CraftPaperPhase.WAIT;
                ticksWaited = 0;
            }
            case WAIT -> {
                ticksWaited++;
                if (ticksWaited >= clickDelayTicks.get()) {
                    paperPhase = CraftPaperPhase.CHECK_DONE;
                }
            }
        }
    }
 
    // ----------------------- FASI CRAFT RICETTE (generico) -----------------------
 
    private enum GenericCraftPhase { OPEN_TABLE, CHECK, FILL, COLLECT, WAIT }
    private GenericCraftPhase genericPhase = GenericCraftPhase.OPEN_TABLE;
 
    private void tickCraft(List<CraftIngredient> recipe, Stage nextStage, String label) {
        switch (genericPhase) {
            case OPEN_TABLE -> {
                if (isContainerScreenOpen()) {
                    genericPhase = GenericCraftPhase.CHECK;
                    return;
                }
                if (!openNearbyCraftingTable()) {
                    log("Nessun banco da lavoro trovato. Fermo il modulo.");
                    toggle();
                }
            }
            case CHECK -> {
                boolean haveAll = true;
                for (CraftIngredient ci : recipe) {
                    if (findItemInScreen(ci.item()) == -1) { haveAll = false; break; }
                }
                if (!haveAll) {
                    log(label + " completata (ingredienti esauriti). Vado avanti.");
                    mc.player.closeHandledScreen();
                    genericPhase = GenericCraftPhase.OPEN_TABLE;
                    stage = nextStage;
                    return;
                }
                genericPhase = GenericCraftPhase.FILL;
            }
            case FILL -> {
                HandledScreen<?> screen = (HandledScreen<?>) mc.currentScreen;
                ScreenHandler handler = screen.getScreenHandler();
                for (CraftIngredient ci : recipe) {
                    int source = findItemInScreen(ci.item());
                    if (source == -1) {
                        // sparito nel frattempo, esci e ricontrolla
                        genericPhase = GenericCraftPhase.CHECK;
                        return;
                    }
                    transferOneItem(handler, source, ci.gridSlot());
                }
                genericPhase = GenericCraftPhase.COLLECT;
                ticksWaited = 0;
            }
            case COLLECT -> {
                HandledScreen<?> screen = (HandledScreen<?>) mc.currentScreen;
                ScreenHandler handler = screen.getScreenHandler();
                mc.interactionManager.clickSlot(handler.syncId, CRAFTING_OUTPUT_SLOT, 0, SlotActionType.QUICK_MOVE, mc.player);
                genericPhase = GenericCraftPhase.WAIT;
                ticksWaited = 0;
            }
            case WAIT -> {
                ticksWaited++;
                if (ticksWaited >= clickDelayTicks.get()) {
                    genericPhase = GenericCraftPhase.CHECK;
                }
            }
        }
    }
 
    // ----------------------- HELPER -----------------------
 
    private boolean isContainerScreenOpen() {
        return mc.currentScreen instanceof HandledScreen<?>;
    }
 
    /** Cerca un banco da lavoro vicino e ci interagisce. Ritorna true se ha inviato l'interazione. */
    private boolean openNearbyCraftingTable() {
        BlockPos playerPos = mc.player.getBlockPos();
        int r = craftingSearchRadius.get();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
 
        for (BlockPos pos : BlockPos.iterate(playerPos.add(-r, -r, -r), playerPos.add(r, r, r))) {
            if (mc.world.getBlockState(pos).getBlock() == Blocks.CRAFTING_TABLE) {
                double dist = pos.getSquaredDistance(playerPos);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = pos.toImmutable();
                }
            }
        }
 
        if (best == null) return false;
 
        Vec3d hitPos = Vec3d.ofCenter(best).add(0, 0.5, 0);
        BlockHitResult hit = new BlockHitResult(hitPos, Direction.UP, best, false);
        ActionResult result = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        return result.isAccepted();
    }
 
    /** Cerca un item nell'inventario del giocatore usando il suo playerScreenHandler (nessuna GUI aperta). */
    private int findItemInPlayerInventory(Item item) {
        ScreenHandler handler = mc.player.playerScreenHandler;
        for (int i = 9; i < handler.slots.size(); i++) { // salta output+griglia 2x2+armatura del player screen
            if (handler.getSlot(i).hasStack() && handler.getSlot(i).getStack().getItem() == item) {
                return i;
            }
        }
        return -1;
    }
 
    /** Cerca un item nella parte "inventario del giocatore" della schermata attualmente aperta (shop o banco). */
    private int findItemInScreen(Item item) {
        if (!isContainerScreenOpen()) return -1;
        ScreenHandler handler = ((HandledScreen<?>) mc.currentScreen).getScreenHandler();
        for (int i = CRAFTING_PLAYER_INV_START; i < handler.slots.size(); i++) {
            if (handler.getSlot(i).hasStack() && handler.getSlot(i).getStack().getItem() == item) {
                return i;
            }
        }
        return -1;
    }
 
    /**
     * Trasferisce esattamente 1 unità dallo slot sorgente allo slot destinazione,
     * tramite: preleva tutto lo stack -> rilascia 1 con click destro nella destinazione
     * -> rimetti il resto nella sorgente.
     */
    private void transferOneItem(ScreenHandler handler, int sourceSlot, int destSlot) {
        mc.interactionManager.clickSlot(handler.syncId, sourceSlot, 0, SlotActionType.PICKUP, mc.player);
        mc.interactionManager.clickSlot(handler.syncId, destSlot, 1, SlotActionType.PICKUP, mc.player);
        mc.interactionManager.clickSlot(handler.syncId, sourceSlot, 0, SlotActionType.PICKUP, mc.player);
    }
 
    private void log(String msg) {
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal("§b[AutoFirework] §f" + msg), false);
        }
    }
}
 
