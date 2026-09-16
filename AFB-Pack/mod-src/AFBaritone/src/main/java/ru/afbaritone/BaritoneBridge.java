package ru.afbaritone;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;

/**
 * Мост к Baritone: вызовы через публичные интерфейсы baritone.api
 * (рефлексия, чтобы не компилироваться против артефакта).
 * Возвращает текст ошибки, чтобы панель/чат видели причину сбоя.
 */
public final class BaritoneBridge {

    private static Boolean present = null;

    private BaritoneBridge() {
    }

    public static boolean isAvailable() {
        if (present == null) {
            try {
                Class.forName("baritone.api.BaritoneAPI");
                present = true;
            } catch (Throwable t) {
                present = false;
            }
        }
        return present;
    }

    /**
     * Прямой вызов команды Baritone. @return null — успех, иначе текст ошибки.
     */
    public static String execute(String command) {
        try {
            Class<?> api = Class.forName("baritone.api.BaritoneAPI");
            Object provider = api.getMethod("getProvider").invoke(null);

            // ВАЖНО: методы берём у публичных интерфейсов baritone.api,
            // а не у impl-классов (иначе IllegalAccessException)
            Class<?> providerIface = Class.forName("baritone.api.IBaritoneProvider");
            Object baritone = providerIface.getMethod("getPrimaryBaritone").invoke(provider);

            Class<?> baritoneIface = Class.forName("baritone.api.IBaritone");
            Object cm = baritoneIface.getMethod("getCommandManager").invoke(baritone);

            Class<?> cmIface = Class.forName("baritone.api.command.manager.ICommandManager");
            try {
                cmIface.getMethod("executeAndSilent", String.class).invoke(cm, command);
            } catch (NoSuchMethodException nsme) {
                cmIface.getMethod("execute", String.class).invoke(cm, command);
            }
            return null;
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    public static boolean gotoPos(BlockPos pos) {
        String cmd = "goto " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
        String err = execute(cmd);
        if (err == null) return true;
        AFBaritoneClient.msg("§cBaritone API: " + err + " — пробую через чат");
        return sendCommand("#" + cmd);
    }

    public static boolean stop() {
        return execute("stop") == null || sendCommand("#stop");
    }

    private static boolean sendCommand(String cmd) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || player.networkHandler == null) return false;
        player.networkHandler.sendChatMessage(cmd);
        return true;
    }
}
