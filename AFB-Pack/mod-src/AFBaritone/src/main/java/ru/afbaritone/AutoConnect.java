package ru.afbaritone;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;

/**
 * Автоподключение к mc.funtime.su сразу при запуске игры.
 * Прямые вызовы (Loom remap-ит их при сборке) — никаких Class.forName.
 * Последняя ошибка хранится в lastError и видна в панели.
 */
public class AutoConnect {

    public static final String SERVER_HOST = "mc.funtime.su";
    public static boolean enabled = true;
    public static String lastError = null;
    private static boolean tried;
    private static long readyAt;

    public static void tick(MinecraftClient mc) {
        if (!enabled || tried) return;
        if (mc.world != null) {
            tried = true; // уже в игре
            return;
        }
        Screen s = mc.currentScreen;
        if (s instanceof TitleScreen || s.getClass().getSimpleName().contains("MultiplayerScreen")) {
            if (readyAt == 0) readyAt = System.currentTimeMillis();
            if (System.currentTimeMillis() - readyAt > 2500) {
                connectNow(mc);
            }
        }
    }

    /** Подключиться прямо сейчас (кнопка в панели / автостарт). */
    public static void connectNow(MinecraftClient mc) {
        tried = true;
        try {
            ServerAddress address = ServerAddress.parse(SERVER_HOST);
            ServerInfo info = new ServerInfo("FunTime", SERVER_HOST, ServerInfo.ServerType.OTHER);

            ConnectScreen.connect(mc.currentScreen, mc, address, info, false, null);
            lastError = null;
            AFBaritoneClient.msg("Подключаюсь к " + SERVER_HOST + "...");
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            lastError = cause.getClass().getSimpleName() + ": " + cause.getMessage();
            AFBaritoneClient.msg("§cАвтоконнект не сработал: " + lastError);
        }
    }
}
