package ru.afbaritone;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Executors;

/**
 * Внутренний HTTP API мода (только 127.0.0.1, порты 8765-8774).
 * Панель управления получает состояние и управляет игрой.
 */
public class WebApi {

    private static int port = -1;

    public static void start() {
        HttpServer server = null;
        for (int p = 8765; p <= 8774; p++) {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", p), 0);
                port = p;
                break;
            } catch (IOException ignored) {
            }
        }
        if (server == null) {
            AFBaritoneClient.msg("§cНе удалось открыть порт для панели (8765-8774 заняты)");
            return;
        }

        server.createContext("/info", ex -> respond(ex, 200, infoJson()));

        server.createContext("/cmd", ex -> {
            String body = readBody(ex);
            String command = jsonStr(body, "command");
            if (command == null || command.isBlank()) {
                respond(ex, 400, "{\"error\":\"command required\"}");
                return;
            }
            MinecraftClient mc = MinecraftClient.getInstance();
            String c = command.trim();
            mc.execute(() -> {
                if (c.startsWith("#")) {
                    if (BaritoneBridge.execute(c.substring(1)) == null) return;
                }
                ClientPlayerEntity player = mc.player;
                if (player == null || player.networkHandler == null) return;
                if (c.startsWith("/")) {
                    player.networkHandler.sendChatCommand(c.substring(1));
                } else {
                    player.networkHandler.sendChatMessage(c);
                }
            });
            respond(ex, 200, "{\"ok\":true}");
        });

        server.createContext("/drop", ex -> {
            String body = readBody(ex);
            boolean keepBooks = !"false".equals(jsonStr(body, "keepBooks"));
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                stopFishing();
                FishingManager.get().dropAllNow(keepBooks);
            });
            respond(ex, 200, "{\"ok\":true}");
        });

        server.createContext("/goto", ex -> {
            String body = readBody(ex);
            Long x = jsonNum(body, "x"), y = jsonNum(body, "y"), z = jsonNum(body, "z");
            if (x == null || y == null || z == null) {
                respond(ex, 400, "{\"error\":\"x,y,z required\"}");
                return;
            }
            if (!BaritoneBridge.isAvailable()) {
                respond(ex, 409, "{\"error\":\"Baritone не установлен/не загрузился\"}");
                return;
            }
            String err = BaritoneBridge.execute("goto " + x + " " + y + " " + z);
            if (err != null) {
                respond(ex, 500, "{\"error\":\"Baritone: " + jsonEscape(err) + "\"}");
                return;
            }
            AFBaritoneClient.msg("Baritone идёт к " + x + " " + y + " " + z);
            respond(ex, 200, "{\"ok\":true}");
        });

        server.createContext("/buyrod", ex -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.execute(() -> RodBuyer.get().start(false));
            respond(ex, 200, "{\"ok\":true}");
        });

        server.createContext("/stop", ex -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                stopFishing();
                BaritoneBridge.stop();
            });
            respond(ex, 200, "{\"ok\":true}");
        });

        server.createContext("/autofish", ex -> {
            String body = readBody(ex);
            boolean enable = !"false".equals(jsonStr(body, "enabled"));
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                FishingManager m = FishingManager.get();
                if (enable) {
                    if (m.getState() != FishingManager.State.IDLE || RodBuyer.get().isActive()) return;
                    if (!RodBuyer.hasRod()) {
                        RodBuyer.get().start(true);
                    } else {
                        m.start(null, m.getSearchRadius());
                    }
                } else {
                    if (RodBuyer.get().isActive()) RodBuyer.get().cancel();
                    if (m.getState() != FishingManager.State.IDLE) m.stop(true);
                }
            });
            respond(ex, 200, "{\"ok\":true}");
        });

        // Выбор активного слота хотбара (0-8)
        server.createContext("/slot", ex -> {
            String body = readBody(ex);
            Long slot = jsonNum(body, "slot");
            if (slot == null || slot < 0 || slot > 8) {
                respond(ex, 400, "{\"error\":\"slot 0-8 required\"}");
                return;
            }
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                stopFishing(); // работа с инвентарём отключает авто-рыбалку
                if (mc.player != null) {
                    mc.player.getInventory().setSelectedSlot(slot.intValue());
                }
            });
            respond(ex, 200, "{\"ok\":true}");
        });

        // Переместить предмет между хотбаром и инвентарём (slot 0-35)
        server.createContext("/invmove", ex -> {
            String body = readBody(ex);
            Long slot = jsonNum(body, "slot");
            if (slot == null || slot < 0 || slot > 35) {
                respond(ex, 400, "{\"error\":\"slot 0-35 required\"}");
                return;
            }
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                stopFishing();
                if (mc.player != null && mc.interactionManager != null) {
                    int handlerSlot = slot < 9 ? slot.intValue() + 36 : slot.intValue();
                    mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, handlerSlot,
                            0, SlotActionType.QUICK_MOVE, mc.player);
                }
            });
            respond(ex, 200, "{\"ok\":true}");
        });

        // Выбросить предмет из слота (0-35)
        server.createContext("/invdrop", ex -> {
            String body = readBody(ex);
            Long slot = jsonNum(body, "slot");
            if (slot == null || slot < 0 || slot > 35) {
                respond(ex, 400, "{\"error\":\"slot 0-35 required\"}");
                return;
            }
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                stopFishing();
                if (mc.player != null && mc.interactionManager != null) {
                    int handlerSlot = slot < 9 ? slot.intValue() + 36 : slot.intValue();
                    mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, handlerSlot,
                            1, SlotActionType.THROW, mc.player);
                }
            });
            respond(ex, 200, "{\"ok\":true}");
        });

        // Открыть/закрыть инвентарь в игре
        server.createContext("/openinv", ex -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                stopFishing();
                if (mc.player == null) return;
                if (mc.currentScreen == null) {
                    mc.setScreen(new net.minecraft.client.gui.screen.ingame.InventoryScreen(mc.player));
                } else {
                    mc.player.closeHandledScreen();
                }
            });
            respond(ex, 200, "{\"ok\":true}");
        });

        // Клик по слоту любого открытого экрана (аукцион, бот, инвентарь...)
        server.createContext("/gclick", ex -> {
            String body = readBody(ex);
            Long slot = jsonNum(body, "slot");
            if (slot == null) {
                respond(ex, 400, "{\"error\":\"slot required\"}");
                return;
            }
            boolean shift = "true".equals(jsonStr(body, "shift"));
            boolean right = "true".equals(jsonStr(body, "right"));
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                if (!(mc.currentScreen instanceof HandledScreen<?> hs)) return;
                stopFishing();
                SlotActionType type = shift ? SlotActionType.QUICK_MOVE
                        : right ? SlotActionType.PICKUP : SlotActionType.PICKUP;
                int button = right ? 1 : 0;
                mc.interactionManager.clickSlot(hs.getScreenHandler().syncId, slot.intValue(), button, type, mc.player);
            });
            respond(ex, 200, "{\"ok\":true}");
        });

        // Автоконнект FunTime вкл/выкл
        server.createContext("/autoconnect", ex -> {
            String body = readBody(ex);
            boolean enable = "true".equals(jsonStr(body, "enabled"));
            AutoConnect.enabled = enable;
            FishingManager.get().saveConfig();
            respond(ex, 200, "{\"ok\":true}");
        });

        // Подключиться к серверу сейчас (кнопка в панели)
        server.createContext("/connect", ex -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.execute(() -> AutoConnect.connectNow(mc));
            respond(ex, 200, "{\"ok\":true}");
        });

        server.setExecutor(Executors.newFixedThreadPool(2));
        server.start();
        AFBaritoneClient.msg("API панели: порт " + port);
    }

    private static void stopFishing() {
        FishingManager m = FishingManager.get();
        if (m.getState() != FishingManager.State.IDLE) m.stop(false);
    }

    public static int getPort() {
        return port;
    }

    // ---------- /info ----------

    private static String infoJson() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity p = mc.player;
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"port\":").append(port);
        sb.append(",\"baritone\":").append(BaritoneBridge.isAvailable());
        sb.append(",\"hasRod\":").append(RodBuyer.hasRod());
        sb.append(",\"buying\":").append(RodBuyer.get().isActive());
        sb.append(",\"coins\":").append(readCoinsSidebar());
        sb.append(",\"autoconnect\":").append(AutoConnect.enabled);
        sb.append(",\"acErr\":").append(AutoConnect.lastError == null ? "null" : "\"" + jsonEscape(AutoConnect.lastError) + "\"");
        sb.append(",\"sidebar\":").append(jsonArray(readSidebarLines()));

        // Зачарованные книги за сессию
        sb.append(",\"books\":[");
        boolean first = true;
        for (var e : BookTracker.getCaught().entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"ench\":\"").append(jsonEscape(e.getKey())).append("\",\"count\":").append(e.getValue()).append("}");
        }
        sb.append(']');

        if (p != null) {
            sb.append(",\"player\":\"").append(jsonEscape(p.getName().getString())).append("\"");
            sb.append(",\"x\":").append(fmt(p.getX()));
            sb.append(",\"y\":").append(fmt(p.getY()));
            sb.append(",\"z\":").append(fmt(p.getZ()));
            sb.append(",\"health\":").append(fmt(p.getHealth()));
            sb.append(",\"world\":\"").append(jsonEscape(p.getEntityWorld().getRegistryKey().getValue().getPath())).append("\"");
            sb.append(",\"autofish\":").append(FishingManager.get().getState() != FishingManager.State.IDLE);
            sb.append(",\"selected\":").append(p.getInventory().getSelectedSlot());

            // Хотбар и инвентарь
            sb.append(",\"hotbar\":[");
            for (int i = 0; i < 9; i++) sb.append(i > 0 ? "," : "").append(stackJson(p.getInventory().getMainStacks().get(i)));
            sb.append(']');
            sb.append(",\"inv\":[");
            for (int i = 9; i < 36; i++) sb.append(i > 9 ? "," : "").append(stackJson(p.getInventory().getMainStacks().get(i)));
            sb.append(']');

            // Открытый экран (аукцион, бот, инвентарь...)
            if (mc.currentScreen instanceof HandledScreen<?> hs) {
                sb.append(",\"screen\":{\"title\":\"").append(jsonEscape(hs.getTitle().getString())).append("\",\"slots\":[");
                boolean f2 = true;
                for (Slot slot : hs.getScreenHandler().slots) {
                    ItemStack st = slot.getStack();
                    if (st == null || st.isEmpty()) continue;
                    if (!f2) sb.append(',');
                    f2 = false;
                    sb.append("{\"id\":").append(slot.id)
                      .append(",\"name\":\"").append(jsonEscape(stripColor(st.getName().getString()))).append("\"")
                      .append(",\"count\":").append(st.getCount())
                      .append(",\"lore\":").append(loreJson(st)).append("}");
                }
                sb.append("]}");
            } else {
                sb.append(",\"screen\":null");
            }
        } else {
            sb.append(",\"player\":null");
        }
        sb.append("}");
        return sb.toString();
    }

    private static String stackJson(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "null";
        return "{\"name\":\"" + jsonEscape(stripColor(stack.getName().getString())) + "\",\"count\":" + stack.getCount() + "}";
    }

    private static String loreJson(ItemStack stack) {
        StringBuilder sb = new StringBuilder("[");
        try {
            var lore = stack.get(net.minecraft.component.DataComponentTypes.LORE);
            if (lore != null) {
                boolean first = true;
                for (Text line : lore.lines()) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append('"').append(jsonEscape(stripColor(line.getString()))).append('"');
                }
            }
        } catch (Throwable ignored) {
        }
        return sb.append(']').toString();
    }

    // ---------- Монеты: читаем ВСЕ сайдбары (основной + цветные варианты) ----------

    public static int readCoinsSidebar() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return -1;
        try {
            var scoreboard = mc.world.getScoreboard();
            for (ScoreboardDisplaySlot slot : ScoreboardDisplaySlot.values()) {
                if (!slot.name().startsWith("SIDEBAR")) continue;
                var objective = scoreboard.getObjectiveForSlot(slot);
                if (objective == null) continue;
                for (ScoreboardEntry entry : scoreboard.getScoreboardEntries(objective)) {
                    String line = stripColor(decoratedOwner(scoreboard, entry).getString()).toLowerCase(Locale.ROOT);
                    if (line.contains("монет") || line.contains("coin")) {
                        // Число обычно вписано в саму строку («Монет: 35,060»),
                        // а в score-значении лежит служебное число для рендера
                        String cleaned = line.replace(",", "").replace(" ", "")
                                .replace("\u00a0", "").replace("'", "");
                        var m = java.util.regex.Pattern.compile("\\d+").matcher(cleaned);
                        if (m.find()) return Integer.parseInt(m.group());
                        if (entry.value() > 0) return entry.value();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /** Строки основного сайдбара — для отладки отображения монет. */
    public static java.util.List<String> readSidebarLines() {
        MinecraftClient mc = MinecraftClient.getInstance();
        java.util.List<String> out = new java.util.ArrayList<>();
        if (mc.player == null || mc.world == null) return out;
        try {
            var scoreboard = mc.world.getScoreboard();
            var objective = scoreboard.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
            if (objective == null) return out;
            for (ScoreboardEntry entry : scoreboard.getScoreboardEntries(objective)) {
                out.add(stripColor(decoratedOwner(scoreboard, entry).getString()) + " = " + entry.value());
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static Text decoratedOwner(net.minecraft.scoreboard.Scoreboard scoreboard, ScoreboardEntry entry) {
        try {
            var team = scoreboard.getScoreHolderTeam(entry.owner());
            if (team != null) return team.decorateName(Text.literal(entry.owner()));
        } catch (Throwable ignored) {
        }
        return Text.literal(entry.owner());
    }

    private static String stripColor(String s) {
        return s == null ? "" : s.replaceAll("[§&][0-9a-fk-orA-FK-OR]", "");
    }

    // ---------- Утилиты ----------

    private static String readBody(HttpExchange ex) {
        try (InputStream is = ex.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String jsonStr(String json, String key) {
        if (json == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*(\"((?:\\\\.|[^\"\\\\])*)\"|[^,}\\s]+)")
                .matcher(json);
        if (!m.find()) return null;
        String v = m.group(2) != null ? m.group(2) : m.group(1);
        return v == null ? null : v.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static Long jsonNum(String json, String key) {
        String v = jsonStr(json, key);
        if (v == null) return null;
        try {
            return Math.round(Double.parseDouble(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void respond(HttpExchange ex, int code, String body) {
        try {
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(code, body.getBytes(StandardCharsets.UTF_8).length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
        }
    }

    private static String fmt(double d) {
        return String.format(java.util.Locale.ROOT, "%.2f", d);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String jsonEscape(String s) {
        return esc(s).replace("\n", " ").replace("\r", "");
    }

    private static String jsonArray(java.util.List<String> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(jsonEscape(list.get(i))).append('"');
        }
        return sb.append(']').toString();
    }
}
