package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.meteorclient.events.world.TickEvent;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.stream.Collectors;

/**
 * MobAutoFarm
 *
 * Modulo di farm: si muove verso i mob bersaglio (movimento manuale, senza
 * Baritone), li attacca (multi-target, boost di velocità con la spada), e
 * vende automaticamente nella GUI "/shop Mobs" quando l'inventario è pieno
 * o su richiesta manuale.
 *
 * NOTA: non richiede Baritone come dipendenza. Se in futuro vuoi un
 * pathfinding più intelligente (che scavalca ostacoli, ecc.) va aggiunta
 * la dipendenza Baritone al build.gradle e si può reintrodurre.
 */
public class MobAutoFarm extends Module {

    // ================== SETTINGS ==================
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgMovement = settings.createGroup("Movimento");
    private final SettingGroup sgSelling = settings.createGroup("Vendita");

    private final Setting<List<String>> mobWhitelist = sgGeneral.add(new StringListSetting.Builder()
        .name("mob-target")
        .description("Nomi dei mob da farmare (es. zombie, skeleton). Vuoto = tutti gli ostili.")
        .defaultValue(Arrays.asList("zombie", "skeleton"))
        .build()
    );

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("raggio-ricerca")
        .description("Raggio entro cui cercare mob da farmare.")
        .defaultValue(16.0)
        .min(2.0)
        .sliderMax(32.0)
        .build()
    );

    private final Setting<Integer> maxTargets = sgGeneral.add(new IntSetting.Builder()
        .name("mob-per-giro")
        .description("Quanti mob attaccare nello stesso giro se sono già a portata.")
        .defaultValue(3)
        .min(1)
        .sliderMax(8)
        .build()
    );

    private final Setting<Boolean> requireSword = sgGeneral.add(new BoolSetting.Builder()
        .name("solo-con-spada")
        .description("Attacca (e applica il boost velocità) solo con una spada in mano.")
        .defaultValue(true)
        .build()
    );

    // --- Movimento verso il mob ---
    private final Setting<Boolean> moveToTarget = sgMovement.add(new BoolSetting.Builder()
        .name("muovi-verso-mob")
        .description("Cammina verso il mob più vicino se non è già a portata d'attacco.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> speedMultiplier = sgMovement.add(new DoubleSetting.Builder()
        .name("moltiplicatore-velocità")
        .description("Boost di velocità mentre insegui un mob con la spada in mano.")
        .defaultValue(1.3)
        .min(1.0)
        .sliderMax(2.0)
        .build()
    );

    // --- Vendita ---
    private final Setting<String> shopCommand = sgSelling.add(new StringSetting.Builder()
        .name("comando-shop")
        .description("Comando per aprire la GUI del negozio mob.")
        .defaultValue("shop Mobs")
        .build()
    );

    private final Setting<Integer> freeSlotsBeforeSell = sgSelling.add(new IntSetting.Builder()
        .name("slot-liberi-minimi")
        .description("Avvia la vendita automatica quando gli slot liberi scendono sotto questo valore.")
        .defaultValue(3)
        .min(0)
        .sliderMax(10)
        .build()
    );

    private final Setting<Keybind> manualSellKeybind = sgSelling.add(new KeybindSetting.Builder()
        .name("tasto-vendi-ora")
        .description("Premi questo tasto per forzare subito la vendita, indipendentemente da quanto è pieno l'inventario.")
        .defaultValue(Keybind.none())
        .build()
    );

    // ================== STATO INTERNO ==================
    private int attackCooldownTicks = 0;
    private boolean sellingInProgress = false;
    private boolean manualSellRequested = false;
    private boolean movingToTarget = false;

    public MobAutoFarm() {
        super(null, "mob-auto-farm", "Farm mob con movimento e vendita automatica nella GUI dello shop.");
    }

    @Override
    public void onActivate() {
        attackCooldownTicks = 0;
        sellingInProgress = false;
        manualSellRequested = false;
        movingToTarget = false;
    }

    @Override
    public void onDeactivate() {
        stopMovement();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (manualSellKeybind.get().wasPressed()) {
            manualSellRequested = true;
        }

        // 1) Vendita in corso
        if (sellingInProgress) {
            handleSellingRoutine();
            return;
        }

        // 2) Serve vendere? (pieno o richiesto manualmente)
        if (manualSellRequested || shouldStartSelling()) {
            manualSellRequested = false;
            stopMovement();
            startSelling();
            return;
        }

        // 3) Farm vero e proprio
        farmTick();
    }

    // ================== FARM ==================

    private void farmTick() {
        List<LivingEntity> targets = findTargets();
        if (targets.isEmpty()) {
            stopMovement();
            return;
        }

        LivingEntity nearest = targets.get(0);
        boolean holdingSword = mc.player.getMainHandStack().getItem() instanceof SwordItem;
        if (requireSword.get() && !holdingSword) {
            stopMovement();
            return;
        }

        boolean inRange = isInAttackRange(nearest);

        if (!inRange && moveToTarget.get()) {
            moveTowards(nearest.getPos());
            applySpeedBoost(holdingSword);
            return; // aspetta di arrivare a portata prima di attaccare
        }

        stopMovement();

        if (attackCooldownTicks > 0) {
            attackCooldownTicks--;
            return;
        }

        int attacked = 0;
        for (LivingEntity target : targets) {
            if (attacked >= maxTargets.get()) break;
            if (!isInAttackRange(target)) continue;

            PlayerUtils.faceEntityClient(target);
            mc.interactionManager.attackEntity(mc.player, target);
            mc.player.swingHand(Hand.MAIN_HAND);
            attacked++;
        }

        if (attacked > 0) attackCooldownTicks = 1;
    }

    /**
     * Movimento manuale verso un punto: ruota il giocatore verso il bersaglio
     * e tiene premuto "avanti". Semplice ed efficace per farm in aree aperte,
     * ma non evita ostacoli/buche come farebbe un vero pathfinder (Baritone).
     */
    private void moveTowards(Vec3d targetPos) {
        double dx = targetPos.x - mc.player.getX();
        double dz = targetPos.z - mc.player.getZ();

        float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        mc.player.setYaw(yaw);

        mc.options.forwardKey.setPressed(true);
        movingToTarget = true;
    }

    private void stopMovement() {
        if (movingToTarget) {
            mc.options.forwardKey.setPressed(false);
            movingToTarget = false;
        }
    }

    private void applySpeedBoost(boolean holdingSword) {
        if (!holdingSword) return;
        if (!mc.player.isSprinting()) mc.player.setSprinting(true);

        // Boost "grezzo" lato client: aumenta la velocità orizzontale attuale.
        // Su server con controlli di movimento severi (Grim, Vulcan, ecc.)
        // rischi di essere rilevato/bloccato: usalo solo su server permissivi.
        Vec3d v = mc.player.getVelocity();
        double mult = speedMultiplier.get();
        mc.player.setVelocity(v.x * mult, v.y, v.z * mult);
    }

    private List<LivingEntity> findTargets() {
        Box searchBox = mc.player.getBoundingBox().expand(range.get());
        List<String> whitelist = mobWhitelist.get().stream().map(String::toLowerCase).collect(Collectors.toList());

        return mc.world.getEntitiesByClass(MobEntity.class, searchBox, e -> {
            if (!e.isAlive()) return false;
            if (whitelist.isEmpty()) return true;
            String id = net.minecraft.registry.Registries.ENTITY_TYPE.getId(e.getType()).getPath().toLowerCase();
            return whitelist.contains(id);
        }).stream()
          .sorted(Comparator.comparingDouble(e -> e.squaredDistanceTo(mc.player)))
          .collect(Collectors.toList());
    }

    private boolean isInAttackRange(LivingEntity target) {
        double reach = 3.0;
        return mc.player.squaredDistanceTo(target) <= reach * reach;
    }

    // ================== VENDITA (GUI /shop Mobs) ==================

    private boolean shouldStartSelling() {
        int freeSlots = 0;
        for (ItemStack stack : mc.player.getInventory().main) {
            if (stack.isEmpty()) freeSlots++;
        }
        return freeSlots <= freeSlotsBeforeSell.get();
    }

    private void startSelling() {
        sellingInProgress = true;
        mc.player.networkHandler.sendChatCommand(shopCommand.get());
    }

    private void handleSellingRoutine() {
        if (!(mc.currentScreen instanceof HandledScreen<?> screen)) {
            return; // aspetta che la GUI si apra
        }

        var handler = screen.getScreenHandler();

        Set<net.minecraft.item.Item> ownedItems = new HashSet<>();
        for (ItemStack stack : mc.player.getInventory().main) {
            if (!stack.isEmpty()) ownedItems.add(stack.getItem());
        }

        // Scansiona SOLO gli slot del container (non i 36 della player inventory in basso).
        int containerSlots = handler.slots.size() - 36;
        for (int i = 0; i < containerSlots; i++) {
            ItemStack shopStack = handler.getSlot(i).getStack();
            if (shopStack.isEmpty()) continue;
            if (ownedItems.contains(shopStack.getItem())) {
                // Shift + click destro ("vendi tutto") non ha un SlotActionType diretto e
                // dipende da come il plugin dello shop legge il pacchetto. Prova prima questa:
                mc.player.setSneaking(true);
                mc.interactionManager.clickSlot(handler.syncId, i, 1, SlotActionType.PICKUP, mc.player);
                mc.player.setSneaking(false);
                // Se il tuo shop non reagisce, sostituisci la riga sopra con:
                // mc.interactionManager.clickSlot(handler.syncId, i, 1, SlotActionType.THROW, mc.player);
                return; // un'azione per tick, poi ricontrolliamo al tick successivo
            }
        }

        mc.player.closeHandledScreen();
        sellingInProgress = false;
    }
}
