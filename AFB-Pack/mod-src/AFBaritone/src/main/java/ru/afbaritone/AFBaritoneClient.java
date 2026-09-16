package ru.afbaritone;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public class AFBaritoneClient implements ClientModInitializer {

    public static final String MOD_ID = "afbaritone";

    public static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(Identifier.of("afbaritone", "main"));

    private static boolean wasFocused = true;

    private static KeyBinding toggleKey;
    private static KeyBinding settingsKey;

    @Override
    public void onInitializeClient() {
        FishingManager.get().loadConfig();
        WebApi.start();

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.afbaritone.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V, CATEGORY));
        settingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.afbaritone.settings", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_O, CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.wasPressed()) {
                FishingManager m = FishingManager.get();
                if (m.getState() == FishingManager.State.IDLE && !RodBuyer.get().isActive()) {
                    if (!RodBuyer.hasRod()) {
                        // удочки нет/сломана — сначала купить у бота
                        RodBuyer.get().start(true);
                    } else {
                        m.start(null, m.getSearchRadius());
                    }
                } else if (RodBuyer.get().isActive()) {
                    RodBuyer.get().cancel();
                } else {
                    m.stop(true);
                }
            }
            while (settingsKey.wasPressed()) {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc.player != null && mc.currentScreen == null) {
                    mc.setScreen(new SettingsScreen());
                }
            }
            FishingManager.get().tick();
            RodBuyer.get().tick();
            BookTracker.tick(client);
            AutoConnect.tick(client);

            // Ванилла сама открывает ESC-меню через 500мс без фокуса (GameRenderer,
            // опция pauseOnLostFocus) — отключаем, чтобы свёрнутая игра не «вставала на паузу»
            client.options.pauseOnLostFocus = false;

            // Свёрнутая игра не должна висеть в ESC-меню:
            // закрываем пока окно не в фокусе И при возврате фокуса
            boolean focused = client.isWindowFocused();
            boolean justRegained = focused && !wasFocused;
            if (client.currentScreen instanceof net.minecraft.client.gui.screen.GameMenuScreen
                    && (!focused || justRegained)) {
                client.setScreen(null);
            }
            wasFocused = focused;
        });
    }

    public static void msg(String text) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal("§6[AFB] §f" + text), false);
        }
    }
}
