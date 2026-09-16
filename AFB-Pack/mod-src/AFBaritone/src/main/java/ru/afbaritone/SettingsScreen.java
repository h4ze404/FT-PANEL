package ru.afbaritone;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

public class SettingsScreen extends Screen {

    private TextFieldWidget xField, yField, zField, rField;
    private ButtonWidget dropToggle;
    private Text status = Text.literal("");

    public SettingsScreen() {
        super(Text.literal("AFBaritone — настройки"));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int w = 70;
        int fieldW = 70;
        int y = 60;

        BlockPos cur = null;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) cur = mc.player.getBlockPos();
        FishingManager m = FishingManager.get();

        xField = new TextFieldWidget(this.textRenderer, cx - fieldW - 45, y, fieldW, 20, Text.literal("X"));
        yField = new TextFieldWidget(this.textRenderer, cx - fieldW / 2 + 5, y, fieldW, 20, Text.literal("Y"));
        zField = new TextFieldWidget(this.textRenderer, cx + 45, y, fieldW, 20, Text.literal("Z"));

        if (m.getGoal() != null) {
            xField.setText(String.valueOf(m.getGoal().getX()));
            yField.setText(String.valueOf(m.getGoal().getY()));
            zField.setText(String.valueOf(m.getGoal().getZ()));
        }

        rField = new TextFieldWidget(this.textRenderer, cx + 50, y + 30, fieldW, 20, Text.literal("R"));
        rField.setText(String.valueOf(m.getSearchRadius()));

        for (TextFieldWidget f : new TextFieldWidget[]{xField, yField, zField, rField}) {
            f.setTextPredicate(s -> s.isEmpty() || s.matches("-?\\d*"));
            addDrawableChild(f);
        }

        dropToggle = ButtonWidget.builder(dropToggleLabel(), b -> {
            m.setDropJunk(!m.isDropJunk());
            b.setMessage(dropToggleLabel());
        }).dimensions(cx - 105, y + 70, 210, 20).build();
        addDrawableChild(dropToggle);

        addDrawableChild(ButtonWidget.builder(Text.literal("Пойти и рыбачить"), b -> start())
                .dimensions(cx - 105, y + 100, 100, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Стоп"), b -> stopAll())
                .dimensions(cx + 5, y + 100, 100, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Готово"), b -> close())
                .dimensions(cx - 50, y + 130, 100, 20).build());
    }

    private Text dropToggleLabel() {
        FishingManager m = FishingManager.get();
        return m.isDropJunk()
                ? Text.literal("Выкидывать всё кроме книг: ВКЛ")
                : Text.literal("Выкидывать всё кроме книг: ВЫКЛ");
    }

    private void start() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        BlockPos goal = null;
        try {
            if (!xField.getText().isBlank() && !zField.getText().isBlank()) {
                int x = Integer.parseInt(xField.getText().trim());
                int z = Integer.parseInt(zField.getText().trim());
                int y = yField.getText().isBlank() ? mc.player.getBlockPos().getY() : Integer.parseInt(yField.getText().trim());
                goal = new BlockPos(x, y, z);
            }
        } catch (NumberFormatException e) {
            status = Text.literal("§cКоординаты введены неверно");
            return;
        }

        int radius;
        try {
            radius = rField.getText().isBlank() ? 32 : Integer.parseInt(rField.getText().trim());
        } catch (NumberFormatException e) {
            radius = 32;
        }

        FishingManager.get().saveConfig();
        FishingManager.get().start(goal, radius);
        status = Text.literal("§aЗапущено!");
    }

    private void stopAll() {
        FishingManager.get().stop(true);
        status = Text.literal("§7Остановлено");
    }

    @Override
    public void close() {
        FishingManager.get().saveConfig();
        super.close();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        int cx = this.width / 2;
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, cx, 25, 0xFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Цель для Baritone (пусто = текущая позиция)"), cx, 45, 0xA0A0A0);
        context.drawText(this.textRenderer, "X", xField.getX() - 12, xField.getY() + 6, 0xA0A0A0, false);
        context.drawText(this.textRenderer, "Y", yField.getX() - 12, yField.getY() + 6, 0xA0A0A0, false);
        context.drawText(this.textRenderer, "Z", zField.getX() - 12, zField.getY() + 6, 0xA0A0A0, false);
        context.drawText(this.textRenderer, "Радиус:", rField.getX() - 55, rField.getY() + 6, 0xA0A0A0, false);
        context.drawCenteredTextWithShadow(this.textRenderer, status, cx, this.height - 30, 0xFFFFFF);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
