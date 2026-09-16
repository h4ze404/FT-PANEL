package ru.afbaritone;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.item.FishingRodItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import ru.afbaritone.mixin.FishingBobberEntityMixin;

import java.util.Properties;
import java.util.Set;

public class FishingManager {

    public enum State { IDLE, GOAL, FINDING_SPOT, CASTING, WAITING_BITE, REELING, COOLDOWN }

    private static final FishingManager INSTANCE = new FishingManager();

    // Подстройка чувствительности/задержек
    private static final double BITE_VELOCITY = -0.18; // рывок поплавка вниз (после устояния)
    private static final double BITE_DROP = 0.12;      // провал поплавка ниже точки поклёвки
    private static final long BITE_TIMEOUT_MS = 120_000;
    private static final long GOAL_TIMEOUT_MS = 300_000;
    private static final long SPOT_TIMEOUT_MS = 90_000;
    private static final double CAST_REACH = 14.0;

    private State state = State.IDLE;
    private BlockPos goal;
    private BlockPos waterTarget;
    private boolean baritoneSent;
    private boolean cooldownCasts;
    private long stateStart;
    private long cooldownEnd;
    private double floatY = Double.NaN;
    private long settledAt;
    private volatile static long biteSignalAt;
    private long notWaterSince;
    private boolean reelRetried;

    /** Вызывается из миксина: сервер сообщил о поклёвке (статус 31). */
    public static void onServerBiteSignal() {
        biteSignalAt = System.currentTimeMillis();
    }

    private int searchRadius = 32;
    private boolean dropJunk = true;

    /** Что не выкидывать: книги + удочка. */
    private static final Set<Item> KEEP_ITEMS = Set.of(
            Items.BOOK,
            Items.ENCHANTED_BOOK,
            Items.WRITABLE_BOOK,
            Items.WRITTEN_BOOK
    );

    public static FishingManager get() {
        return INSTANCE;
    }

    public State getState() {
        return state;
    }

    public int getSearchRadius() {
        return searchRadius;
    }

    public void setSearchRadius(int r) {
        this.searchRadius = Math.max(4, Math.min(r, 64));
    }

    public BlockPos getGoal() {
        return goal;
    }

    /** Старт. goal == null -> просто искать воду вокруг игрока. */
    public void start(BlockPos goal, int radius) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;
        this.goal = goal;
        if (radius > 0) setSearchRadius(radius);
        this.baritoneSent = false;
        setState(State.GOAL);
        AFBaritoneClient.msg("Старт! Цель: " + (goal == null ? "текущая позиция" : goal.toShortString())
                + ", радиус поиска воды: " + searchRadius);
        if (goal != null) {
            AFBaritoneClient.msg("Baritone: " + (BaritoneBridge.isAvailable() ? "обнаружен" : "НЕ найден (установи Baritone для авто-ходьбы)"));
        }
    }

    public void stop(boolean announce) {
        if (state == State.IDLE) return;
        if (baritoneSent) BaritoneBridge.stop();
        if (MinecraftClient.getInstance().player != null) {
            MinecraftClient.getInstance().options.forwardKey.setPressed(false);
        }
        goal = null;
        waterTarget = null;
        floatY = Double.NaN;
        notWaterSince = 0;
        reelRetried = false;
        biteSignalAt = 0;
        setState(State.IDLE);
        if (announce) AFBaritoneClient.msg("Стоп.");
    }

    private void setState(State s) {
        state = s;
        stateStart = System.currentTimeMillis();
    }

    public void tick() {
        if (state == State.IDLE) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) {
            state = State.IDLE;
            return;
        }

        switch (state) {
            case GOAL -> tickGoal(mc);
            case FINDING_SPOT -> tickFindingSpot(mc);
            case CASTING -> tickCasting(mc);
            case WAITING_BITE -> tickWaitingBite(mc);
            case REELING -> tickReeling(mc);
            case COOLDOWN -> tickCooldown(mc);
            default -> {}
        }
    }

    // ---------- Этапы ----------

    private void tickGoal(MinecraftClient mc) {
        if (goal == null) {
            setState(State.FINDING_SPOT);
            return;
        }
        double dist = Math.sqrt(mc.player.squaredDistanceTo(Vec3d.ofCenter(goal)));
        if (dist <= 2.5) {
            AFBaritoneClient.msg("Пришли к цели. Ищу воду...");
            setState(State.FINDING_SPOT);
            return;
        }
        if (!baritoneSent) {
            baritoneSent = true;
            if (BaritoneBridge.isAvailable()) {
                BaritoneBridge.gotoPos(goal);
                AFBaritoneClient.msg("Baritone идёт к " + goal.toShortString() + " (idём, ждём прибытия)");
            } else {
                AFBaritoneClient.msg("Baritone не найден — иду по прямой сами (лучше установи Baritone)");
            }
        }
        if (!BaritoneBridge.isAvailable()) {
            simpleWalk(mc, goal);
        }
        if (System.currentTimeMillis() - stateStart > GOAL_TIMEOUT_MS) {
            BaritoneBridge.stop();
            AFBaritoneClient.msg("Не удалось дойти до цели за отведённое время. Стоп.");
            stop(false);
        }
    }

    private void tickFindingSpot(MinecraftClient mc) {
        if (waterTarget == null) {
            waterTarget = WaterFinder.find(mc.world, mc.player.getBlockPos(), searchRadius);
            if (waterTarget == null) {
                if (System.currentTimeMillis() - stateStart > 20_000) {
                    AFBaritoneClient.msg("Вода не найдена в радиусе " + searchRadius + " блоков. Стоп.");
                    stop(false);
                }
                return;
            }
            AFBaritoneClient.msg("Найдена вода: " + waterTarget.toShortString());
        }

        double dist = WaterFinder.horizontalDist(mc.player.getEyePos(), waterTarget);
        double vdist = Math.abs(mc.player.getBlockPos().getY() - waterTarget.getY());

        if (dist <= CAST_REACH && vdist <= 6) {
            baritoneSent = false;
            setState(State.CASTING);
            return;
        }

        // Вода далеко — просим Baritone подойти
        if (!baritoneSent && BaritoneBridge.isAvailable()) {
            BlockPos stand = WaterFinder.findStandSpot(mc.world, waterTarget);
            BlockPos target = stand != null ? stand : waterTarget;
            BaritoneBridge.gotoPos(target);
            baritoneSent = true;
            AFBaritoneClient.msg("Вода далеко (" + (int) dist + " бл). Baritone подходит к " + target.toShortString());
        } else if (!baritoneSent) {
            simpleWalk(mc, waterTarget);
        }

        // Периодически перепроверяем
        if (System.currentTimeMillis() - stateStart > SPOT_TIMEOUT_MS) {
            if (BaritoneBridge.isAvailable()) BaritoneBridge.stop();
            AFBaritoneClient.msg("Не подобрался к воде. Стоп.");
            stop(false);
        }
    }

    private void tickCasting(MinecraftClient mc) {
        mc.options.forwardKey.setPressed(false); // больше не идём
        int rodSlot = findRodSlot(mc);
        if (rodSlot < 0) {
            // Удочки нет/сломана — не останавливаемся, а идём покупать.
            // RodBuyer.start(true) сам перезапустит рыбалку после покупки.
            AFBaritoneClient.msg("Удочки нет — иду покупать у бота...");
            RodBuyer.get().start(true);
            return;
        }
        if (mc.player.getInventory().getSelectedSlot() != rodSlot) {
            mc.player.getInventory().setSelectedSlot(rodSlot);
        }

        waterTarget = WaterFinder.find(mc.world, mc.player.getBlockPos(), searchRadius);
        if (waterTarget == null) {
            AFBaritoneClient.msg("Вода пропала. Стоп.");
            stop(false);
            return;
        }

        double dist = WaterFinder.horizontalDist(mc.player.getEyePos(), waterTarget);
        if (dist > CAST_REACH) {
            waterTarget = null;
            setState(State.FINDING_SPOT);
            return;
        }

        aimAt(mc, waterTarget);
        AFBaritoneClient.msg("Закидываю удочку...");
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        floatY = Double.NaN;
        settledAt = 0;
        biteSignalAt = 0;      // старые сигналы поклёвки не должны триггерить новый заброс
        notWaterSince = 0;
        reelRetried = false;
        setState(State.WAITING_BITE);
    }

    private void tickWaitingBite(MinecraftClient mc) {
        FishingBobberEntity bobber = findBobber(mc);
        long elapsed = System.currentTimeMillis() - stateStart;

        if (bobber == null) {
            if (elapsed > 2500) {
                // Поплавок исчез (упал на землю / вытащен) — перезакидываем
                AFBaritoneClient.msg("Поплавок пропал, перезакидываю...");
                setState(State.COOLDOWN);
                cooldownEnd = System.currentTimeMillis() + 600 + mc.player.getRandom().nextInt(500);
            }
            return;
        }

        boolean bite = false;
        String biteSource = null;
        boolean inWater = bobber.isTouchingWater();

        // Поплавок 3+ секунды не в воде (залип на берегу / в блоке) — тянем и перезакидываем
        if (!inWater) {
            long now = System.currentTimeMillis();
            if (notWaterSince == 0) {
                notWaterSince = now;
            } else if (now - notWaterSince > 3000) {
                AFBaritoneClient.msg("Поплавок не в воде — перезакидываю...");
                mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                waterTarget = null; // при следующем забросе ищем воду заново
                setState(State.COOLDOWN);
                cooldownEnd = now + 600 + mc.player.getRandom().nextInt(500);
                return;
            }
        } else {
            notWaterSince = 0;
        }

        // 1) Самый надёжный сигнал: сервер сам сообщил о поклёвке (статус 31).
        //    Принимаем только свежий сигнал (<1.5с), чтобы хвост старого
        //    пакета не «вылавливал» пустой заброс.
        long nowMs = System.currentTimeMillis();
        if (biteSignalAt > 0) {
            if (nowMs - biteSignalAt < 1500) {
                biteSignalAt = 0;
                bite = true;
                biteSource = "сервер";
            } else {
                biteSignalAt = 0; // устарел — игнорируем
            }
        }

        // 2) Поле caughtFish (клиентское), которое устанавливается тем же статусом
        if (!bite) {
            try {
                if (((FishingBobberEntityMixin) bobber).afb$getCaughtFish()) {
                    bite = true;
                    biteSource = "caughtFish";
                }
            } catch (ClassCastException ignored) {
            }
        }

        // 3) Резервная эвристика — только когда поплавок уже устоялся на воде,
        //    чтобы плеск при падении в воду не считался поклёвкой
        if (!bite && inWater) {
            long now = System.currentTimeMillis();
            if (Double.isNaN(floatY)) {
                floatY = bobber.getY();
                settledAt = now;
            }
            if (bobber.getY() > floatY + 0.05) {
                floatY = bobber.getY(); // всплыл выше — пересчитываем базу
            }
            if (now - settledAt > 2000) {
                if (bobber.getVelocity().y < BITE_VELOCITY) {
                    bite = true;
                    biteSource = "рывок";
                }
                if (!bite && bobber.getY() < floatY - BITE_DROP) {
                    bite = true;
                    biteSource = "провал";
                }
            }
        }

        if (bite) {
            AFBaritoneClient.msg("Поклёвка! (" + biteSource + ") Вытаскиваю...");
            setState(State.REELING);
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            return;
        }

        if (elapsed > BITE_TIMEOUT_MS) {
            setState(State.REELING);
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        }
    }

    private void tickReeling(MinecraftClient mc) {
        FishingBobberEntity bobber = findBobber(mc);
        long elapsed = System.currentTimeMillis() - stateStart;
        if (bobber == null) {
            // Поплавок вытащен — улов в инвентаре, отдыхаем и закидываем снова
            cooldownCasts = true;
            setState(State.COOLDOWN);
            cooldownEnd = System.currentTimeMillis() + 800 + mc.player.getRandom().nextInt(900);
            return;
        }
        // Поплавок всё ещё в воде: либо сервер не принял первый рывок (лаг/задержка),
        // либо рыба сорвалась. Пробуем ещё раз быстро — рыба может быть ещё на крючке.
        if (!reelRetried && elapsed > 1200) {
            reelRetried = true;
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            return;
        }
        // Совсем застряли — принудительно тянем и перезакидываем
        if (elapsed > 4000) {
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            setState(State.COOLDOWN);
            cooldownEnd = System.currentTimeMillis() + 800 + mc.player.getRandom().nextInt(900);
        }
    }

    private void tickCooldown(MinecraftClient mc) {
        if (System.currentTimeMillis() >= cooldownEnd) {
            if (dropJunk) {
                dropItems(mc, true); // авто-выброс: книги оставляем
            }
            if (waterTarget == null) {
                setState(State.FINDING_SPOT);
            } else {
                setState(State.CASTING);
            }
            if (cooldownCasts) {
                cooldownCasts = false;
                // После вылова проверяем, не ушла ли вода далеко (переместили игрока и т.п.)
            }
        }
    }

    // ---------- Вспомогательное ----------

    private void simpleWalk(MinecraftClient mc, BlockPos target) {
        Vec3d to = Vec3d.ofCenter(target);
        Vec3d delta = to.subtract(mc.player.getEntityPos());
        float yaw = (float) (MathHelper.atan2(delta.z, delta.x) * 180 / Math.PI) - 90;
        mc.player.setYaw(yaw);
        mc.options.forwardKey.setPressed(true);
    }

    private void aimAt(MinecraftClient mc, BlockPos target) {
        Vec3d from = mc.player.getEyePos();
        Vec3d to = Vec3d.ofCenter(target).add(0, 0.1, 0);
        Vec3d delta = to.subtract(from);
        double horiz = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float) (MathHelper.atan2(delta.z, delta.x) * 180 / Math.PI) - 90;
        float pitch = (float) (-(MathHelper.atan2(delta.y, horiz) * 180 / Math.PI)) - 12.0f; // чуть ниже цели, чтобы дугой упал в воду
        mc.player.setYaw(yaw);
        mc.player.setPitch(MathHelper.clamp(pitch, -90, 90));
    }

    private int findRodSlot(MinecraftClient mc) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getMainStacks().get(i);
            if (stack != null && !stack.isEmpty() && stack.getItem() instanceof FishingRodItem) {
                return i;
            }
        }
        return -1;
    }

    private FishingBobberEntity findBobber(MinecraftClient mc) {
        if (mc.world == null || mc.player == null) return null;
        for (Entity e : mc.world.getEntities()) {
            if (e instanceof FishingBobberEntity bobber && bobber.getPlayerOwner() == mc.player) {
                return bobber;
            }
        }
        return null;
    }

    // ---------- Выброс лишнего лута ----------

    public boolean isDropJunk() {
        return dropJunk;
    }

    public void setDropJunk(boolean dropJunk) {
        this.dropJunk = dropJunk;
    }

    /**
     * Выкидывает весь лут из инвентаря, кроме удочки.
     * keepBooks=true — книги тоже остаются (авто-рыбалка),
     * keepBooks=false — книги выкидываются (кнопка в панели).
     */
    private void dropItems(MinecraftClient mc, boolean keepBooks) {
        if (mc.player == null || mc.interactionManager == null) return;
        // Не трогаем инвентарь, пока открыт какой-то экран (сундук и т.п.)
        if (mc.currentScreen != null) return;

        var handler = mc.player.playerScreenHandler;
        var inventory = mc.player.getInventory();

        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getMainStacks().get(i);
            if (stack == null || stack.isEmpty()) continue;
            if (isKept(stack, keepBooks)) continue;

            // Индекс инвентаря -> слот playerScreenHandler:
            // хотбар (0-8) -> слоты 36-44, основной инвентарь (9-35) -> слоты 9-35
            int slot = i < 9 ? i + 36 : i + 9;
            mc.interactionManager.clickSlot(handler.syncId, slot, 1, SlotActionType.THROW, mc.player);
        }
    }

    public void dropAllNow(boolean keepBooks) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            dropItems(mc, keepBooks);
        }
    }

    private boolean isKept(ItemStack stack, boolean keepBooks) {
        return stack.getItem() instanceof FishingRodItem
                || (keepBooks && KEEP_ITEMS.contains(stack.getItem()));
    }

    // ---------- Конфиг ----------

    public void saveConfig() {
        try {
            var cfgDir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
            var file = cfgDir.resolve("afbaritone.properties").toFile();
            Properties props = new Properties();
            props.setProperty("searchRadius", String.valueOf(searchRadius));
            props.setProperty("dropJunk", String.valueOf(dropJunk));
            if (goal != null) {
                props.setProperty("goal", goal.getX() + " " + goal.getY() + " " + goal.getZ());
            }
            try (var out = new java.io.FileOutputStream(file)) {
                props.store(out, "AFBaritone config");
            }
        } catch (Exception ignored) {
        }
    }

    public void loadConfig() {
        try {
            var cfgDir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
            var file = cfgDir.resolve("afbaritone.properties").toFile();
            if (!file.exists()) return;
            Properties props = new Properties();
            try (var in = new java.io.FileInputStream(file)) {
                props.load(in);
            }
            String r = props.getProperty("searchRadius");
            if (r != null) setSearchRadius(Integer.parseInt(r.trim()));
            String d = props.getProperty("dropJunk");
            if (d != null) dropJunk = Boolean.parseBoolean(d.trim());
            String g = props.getProperty("goal");
            if (g != null) {
                String[] parts = g.trim().split("\\s+");
                if (parts.length == 3) {
                    goal = new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                }
            }
        } catch (Exception ignored) {
        }
    }

    // Suppress unused warnings
    @SuppressWarnings("unused")
    private World unusedWorldRef;
}
