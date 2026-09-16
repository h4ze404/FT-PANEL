package ru.afbaritone;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.FishingRodItem;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Locale;

/**
 * Автопокупка удочки «Спиннинг кладоискателя» у ближайшего бота (NPC).
 * Покупает только если удочки нет в инвентаре или она сломана.
 * Имя ищет по частичному совпадению («спиннинг»), чтобы не зависеть
 * от декоративных символов в начале названия.
 */
public class RodBuyer {

    public enum State { IDLE, FIND, WALK, INTERACT, SCAN, CONFIRM, VERIFY, DONE }

    private static final RodBuyer INSTANCE = new RodBuyer();

    /** Ключевое слово для поиска удочки (без учёта регистра, частичное совпадение). */
    private static final String ROD_KEYWORD = "спиннинг";
    private static final long STEP_TIMEOUT_MS = 25_000;
    private static final long TOTAL_TIMEOUT_MS = 120_000;
    private static final double REACH_DIST = 3.5;
    private static final int MAX_BUY_ATTEMPTS = 5;

    private State state = State.IDLE;
    private boolean pendingAutofish;
    private long stateStart;
    private long totalStart;
    private Entity targetBot;
    private int buyAttempts;
    private boolean usedShift;
    private long waitStart;

    public static RodBuyer get() {
        return INSTANCE;
    }

    public boolean isActive() {
        return state != State.IDLE && state != State.DONE;
    }

    /** pendingAutofish=true — после покупки автоматически начать рыбалку. */
    public void start(boolean pendingAutofish) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        if (hasRod()) {
            AFBaritoneClient.msg("Удочка уже есть" + (isRodBroken(mc) ? "" : " и не сломана") + " — покупка не нужна");
            if (pendingAutofish) FishingManager.get().start(null, FishingManager.get().getSearchRadius());
            return;
        }
        this.pendingAutofish = pendingAutofish;
        this.targetBot = null;
        this.buyAttempts = 0;
        this.usedShift = false;
        this.totalStart = System.currentTimeMillis();
        FishingManager.get().stop(false); // рыбалку останавливаем на время покупки
        setState(State.FIND);
        AFBaritoneClient.msg("Ищу ближайшего бота для покупки удочки...");
    }

    public void cancel() {
        if (state == State.IDLE) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && mc.currentScreen != null) mc.player.closeHandledScreen();
        BaritoneBridge.stop();
        finish(false, "Покупка отменена");
    }

    private void setState(State s) {
        state = s;
        stateStart = System.currentTimeMillis();
        if (s != State.IDLE && s != State.DONE) {
            if (System.currentTimeMillis() - totalStart > TOTAL_TIMEOUT_MS) {
                finish(false, "Не успел за отведённое время");
            }
        }
    }

    private void finish(boolean success, String message) {
        state = State.IDLE;
        AFBaritoneClient.msg((success ? "§a" : "§c") + message);
        if (success && pendingAutofish) {
            pendingAutofish = false;
            FishingManager.get().start(null, FishingManager.get().getSearchRadius());
        } else if (!success) {
            pendingAutofish = false;
        }
    }

    public void tick() {
        if (state == State.IDLE || state == State.DONE) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            state = State.IDLE;
            return;
        }
        if (System.currentTimeMillis() - totalStart > TOTAL_TIMEOUT_MS) {
            finish(false, "Не успел купить удочку за отведённое время");
            return;
        }
        long stepElapsed = System.currentTimeMillis() - stateStart;
        if (stepElapsed > STEP_TIMEOUT_MS && state != State.INTERACT) {
            finish(false, "Шаг " + state + " завис — прерываю");
            return;
        }

        switch (state) {
            case FIND -> tickFind(mc);
            case WALK -> tickWalk(mc);
            case INTERACT -> tickInteract(mc);
            case SCAN -> tickScan(mc);
            case CONFIRM -> tickConfirm(mc);
            case VERIFY -> tickVerify(mc);
            default -> {}
        }
    }

    // ---------- Поиск бота ----------

    private void tickFind(MinecraftClient mc) {
        Entity best = null;
        double bestDist = Double.MAX_VALUE;

        for (Entity e : mc.world.getEntities()) {
            if (e == mc.player || !e.isAlive()) continue;
            if (!(e instanceof LivingEntity)) continue;
            Text name = e.getCustomName();
            if (name == null) continue; // боты всегда с никнеймом над головой
            double d = mc.player.distanceTo(e);
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }

        if (best == null) {
            if (stepElapsed() > 5000) {
                finish(false, "Рядом (в радиусе прогрузки) не найдено ни одного бота с ником");
            }
            return;
        }

        targetBot = best;
        AFBaritoneClient.msg("Найден бот: " + stripColor(best.getCustomName().getString())
                + " (" + (int) bestDist + " бл). Подхожу...");
        setState(State.WALK);
    }

    private void tickWalk(MinecraftClient mc) {
        if (targetBot == null || !targetBot.isAlive() || mc.world.getEntity(targetBot.getUuid()) == null) {
            // бот исчез — ищем заново
            setState(State.FIND);
            return;
        }
        double dist = mc.player.distanceTo(targetBot);
        if (dist <= REACH_DIST) {
            mc.options.forwardKey.setPressed(false);
            BaritoneBridge.stop();
            setState(State.INTERACT);
            waitStart = System.currentTimeMillis();
            return;
        }
        if (stepElapsed() % 4000 < 50) {
            // переобновляем путь раз в ~4 сек (бот может стоять где угодно)
            String err = BaritoneBridge.execute("goto " + targetBot.getBlockX() + " " + targetBot.getBlockY() + " " + targetBot.getBlockZ());
            if (err != null) {
                // Baritone недоступен — идём по прямой
                simpleWalk(mc, targetBot.getBlockPos());
            }
        }
        if (!BaritoneBridge.isAvailable()) simpleWalk(mc, targetBot.getBlockPos());
    }

    private void tickInteract(MinecraftClient mc) {
        if (mc.currentScreen instanceof HandledScreen<?> hs && hs.getScreenHandler() != mc.player.playerScreenHandler) {
            AFBaritoneClient.msg("Открыл меню бота, ищу удочку...");
            buyAttempts = 0;
            usedShift = false;
            setState(State.SCAN);
            return;
        }
        if (targetBot == null || !targetBot.isAlive()) {
            setState(State.FIND);
            return;
        }
        if (System.currentTimeMillis() - waitStart > 800) {
            waitStart = System.currentTimeMillis();
            double dist = mc.player.distanceTo(targetBot);
            if (dist > REACH_DIST + 1.5) {
                setState(State.WALK);
                return;
            }
            // смотрим на бота и жмём ПКМ
            lookAt(mc, targetBot);
            mc.interactionManager.interactEntity(mc.player, targetBot, Hand.MAIN_HAND);
        }
        if (stepElapsed() > 15_000) {
            finish(false, "Бот не открыл меню — возможно, это не торговец");
        }
    }

    // ---------- Поиск и покупка удочки в GUI ----------

    private void tickScan(MinecraftClient mc) {
        if (!(mc.currentScreen instanceof HandledScreen<?> hs)) {
            // меню закрылось — проверяем, купилось ли
            setState(State.VERIFY);
            return;
        }
        // Небольшая пауза после открытия, чтобы предметы прогрузились
        if (stepElapsed() < 700) return;

        var handler = hs.getScreenHandler();
        Slot found = null;
        for (Slot slot : handler.slots) {
            ItemStack stack = slot.getStack();
            if (stack == null || stack.isEmpty()) continue;
            String name = stripColor(stack.getName().getString()).toLowerCase(Locale.ROOT);
            if (name.contains(ROD_KEYWORD)) {
                found = slot;
                break;
            }
        }

        if (found == null) {
            if (stepElapsed() > 6000) {
                mc.player.closeHandledScreen();
                finish(false, "В меню бота нет «Спиннинг кладоискателя»");
            }
            return;
        }

        if (buyAttempts >= MAX_BUY_ATTEMPTS) {
            mc.player.closeHandledScreen();
            finish(false, "Не удалось купить удочку за " + MAX_BUY_ATTEMPTS + " попытки (нет монет?)");
            return;
        }

        if (stepElapsed() % 1200 < 50) return; // клик не чаще раза в ~1.2 сек

        buyAttempts++;
        int button = 0;
        SlotActionType type = usedShift ? SlotActionType.QUICK_MOVE : SlotActionType.PICKUP;
        AFBaritoneClient.msg("Купляю удочку (попытка " + buyAttempts + (usedShift ? ", shift" : "") + ")...");
        mc.interactionManager.clickSlot(handler.syncId, found.id, button, type, mc.player);

        // Чередуем обычный клик и shift-клик (на разных серверах покупка работает по-разному)
        if (buyAttempts % 2 == 0) usedShift = !usedShift;

        // На многих серверах после клика открывается окно «Подтверждение покупки»
        waitStart = System.currentTimeMillis();
        setState(State.CONFIRM);
    }

    /**
     * Шаг подтверждения покупки: окно «Подтверждение покупки» с зелёными
     * слотами [Купить] и красными [Отменить]. Ищем слот по названию
     * («купить»/«confirm»), фолбэк — зелёное стекло.
     */
    private void tickConfirm(MinecraftClient mc) {
        if (!(mc.currentScreen instanceof HandledScreen<?> hs)) {
            // окно закрылось — проверяем результат
            setState(State.VERIFY);
            return;
        }

        String title = stripColor(hs.getTitle().getString()).toLowerCase(Locale.ROOT);
        boolean isConfirm = title.contains("подтвержд") || title.contains("confirm") || title.contains("покупк");
        if (!isConfirm) {
            // это всё ещё меню бота — клик по удочке не сработал, пробуем снова
            if (System.currentTimeMillis() - waitStart > 1500) {
                setState(State.SCAN);
            }
            return;
        }

        if (System.currentTimeMillis() - waitStart < 600) return; // даём GUI прогрузиться

        var handler = hs.getScreenHandler();
        Slot buySlot = null;
        Slot greenPane = null;

        for (Slot slot : handler.slots) {
            ItemStack stack = slot.getStack();
            if (stack == null || stack.isEmpty()) continue;
            String name = stripColor(stack.getName().getString()).toLowerCase(Locale.ROOT);
            if (name.contains("купить") || name.contains("confirm") || name.contains("подтверд")) {
                buySlot = slot;
                break;
            }
            if (greenPane == null && isGreenGlass(stack)) {
                greenPane = slot;
            }
        }
        if (buySlot == null) buySlot = greenPane;

        if (buySlot == null) {
            if (System.currentTimeMillis() - waitStart > 5000) {
                mc.player.closeHandledScreen();
                finish(false, "В окне подтверждения не нашёл слот [Купить]");
            }
            return;
        }

        AFBaritoneClient.msg("Подтверждаю покупку...");
        mc.interactionManager.clickSlot(handler.syncId, buySlot.id, 0, SlotActionType.PICKUP, mc.player);
        setState(State.VERIFY);
    }

    private static boolean isGreenGlass(ItemStack stack) {
        String item = stack.getItem().toString().toLowerCase(Locale.ROOT);
        return item.contains("lime_stained_glass") || item.contains("green_stained_glass")
                || item.contains("lime") && item.contains("glass");
    }

    private void tickVerify(MinecraftClient mc) {
        if (hasRod()) {
            setState(State.DONE);
            finish(true, "Удочка куплена и в инвентаре!");
            return;
        }
        // Если экран ещё открыт — продолжаем сканировать
        if (mc.currentScreen instanceof HandledScreen<?> hs && hs.getScreenHandler() != mc.player.playerScreenHandler) {
            setState(State.SCAN);
            return;
        }
        if (stepElapsed() > 4000) {
            // экран закрыт, удочки нет — пробуем снова найти бота (например, у нас не хватило монет, но вдруг GUI закрылся сам)
            if (buyAttempts >= MAX_BUY_ATTEMPTS) {
                finish(false, "Удочка не куплена (проверь монеты)");
            } else {
                setState(State.FIND);
            }
        }
    }

    private long stepElapsed() {
        return System.currentTimeMillis() - stateStart;
    }

    // ---------- Проверка удочки ----------

    /** Есть ли рабочая (не сломанная) удочка в инвентаре. */
    public static boolean hasRod() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return false;
        boolean anyRod = false;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getMainStacks().get(i);
            if (stack == null || stack.isEmpty()) continue;
            String name = stripColor(stack.getName().getString()).toLowerCase(Locale.ROOT);
            if (name.contains(ROD_KEYWORD)) {
                if (isBroken(stack)) {
                    anyRod = true; // сломанная — считаем "есть, но сломана"
                } else {
                    return true;
                }
            } else if (stack.getItem() instanceof FishingRodItem) {
                if (isBroken(stack)) anyRod = true; else return true;
            }
        }
        return false;
    }

    private static boolean isRodBroken(MinecraftClient mc) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getMainStacks().get(i);
            if (stack != null && !stack.isEmpty() && isBroken(stack)
                    && (stripColor(stack.getName().getString()).toLowerCase(Locale.ROOT).contains(ROD_KEYWORD)
                        || stack.getItem() instanceof FishingRodItem)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBroken(ItemStack stack) {
        int max = stack.getMaxDamage();
        if (max <= 1) return false; // неразрушимое
        return stack.getDamage() >= max - 2; // почти сломано — тоже меняем
    }

    // ---------- Монеты (сайдбар) ----------

    /**
     * Читает количество монет из сайдбара (справа в игре, строка «Монет»).
     * @return число или -1, если не найдено.
     */
    public static int readCoinsSidebar() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return -1;
        try {
            var scoreboard = mc.world.getScoreboard();
            var sidebar = scoreboard.getObjectiveForSlot(net.minecraft.scoreboard.ScoreboardDisplaySlot.SIDEBAR);
            if (sidebar == null) return -1;

            for (var entry : scoreboard.getScoreboardEntries(sidebar)) {
                String owner = stripColor(entry.owner()).toLowerCase(Locale.ROOT);
                if (owner.contains("монет")) {
                    int v = entry.value();
                    if (v > 0) return v;
                    // иногда число вписано в саму строку
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(owner);
                    if (m.find()) return Integer.parseInt(m.group());
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    // ---------- Утилиты ----------

    private static String stripColor(String s) {
        return s == null ? "" : s.replaceAll("[§&][0-9a-fk-orA-FK-OR]", "");
    }

    private void simpleWalk(MinecraftClient mc, BlockPos target) {
        Vec3d to = Vec3d.ofCenter(target);
        Vec3d delta = to.subtract(mc.player.getEntityPos());
        float yaw = (float) (MathHelper.atan2(delta.z, delta.x) * 180 / Math.PI) - 90;
        mc.player.setYaw(yaw);
        mc.options.forwardKey.setPressed(true);
    }

    private void lookAt(MinecraftClient mc, Entity e) {
        Vec3d from = mc.player.getEyePos();
        Vec3d to = e.getEyePos();
        Vec3d delta = to.subtract(from);
        double horiz = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        mc.player.setYaw((float) (MathHelper.atan2(delta.z, delta.x) * 180 / Math.PI) - 90);
        mc.player.setPitch((float) (-(MathHelper.atan2(delta.y, horiz) * 180 / Math.PI)));
    }
}
