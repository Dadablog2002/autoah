package autoah;

import com.google.gson.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.LiteralText;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

public class AutoAh implements ClientModInitializer {

    static class Cfg {
        String price = "1000";
        int count = 64;
        int hotbarSlot = 1;
        int timerSeconds = 300;
        String openCommand = "ah";
        String sellCommand = "ah sell {price}";
        int myLotsSlot = 49;
        int[] cancelSlots = {0};
        int moveMs = 450;
    }

    static Cfg cfg = new Cfg();
    static boolean cfgLoaded = false;
    static boolean running = false;
    static final Deque<Step> queue = new ArrayDeque<>();
    static KeyBinding toggleKey;
    static KeyBinding guiKey;
    static final Random rnd = new Random();

    static boolean moving = false;
    static double sx, sy, tx, ty, bend;
    static long t0;
    static int dur;

    static void moveTo(double x, double y) {
        MinecraftClient mc = MinecraftClient.getInstance();
        sx = mc.mouse.getX(); sy = mc.mouse.getY();
        tx = x; ty = y;
        dur = cfg.moveMs + rnd.nextInt(200);
        bend = (rnd.nextDouble() - 0.5) * 60;
        t0 = System.currentTimeMillis();
        moving = true;
    }

    static void frame() {
        if (!moving) return;
        double t = Math.min(1.0, (System.currentTimeMillis() - t0) / (double) dur);
        double s = t * t * (3 - 2 * t);
        double dx = tx - sx, dy = ty - sy;
        double len = Math.max(1, Math.hypot(dx, dy));
        double off = Math.sin(Math.PI * t) * bend;
        double x = sx + dx * s + (-dy / len) * off;
        double y = sy + dy * s + (dx / len) * off;
        GLFW.glfwSetCursorPos(MinecraftClient.getInstance().getWindow().getHandle(), x, y);
        if (t >= 1.0) moving = false;
    }

    interface Step { boolean tick(MinecraftClient mc); }

    static class Wait implements Step {
        int left; Wait(int ticks) { left = ticks; }
        public boolean tick(MinecraftClient mc) { return --left <= 0; }
    }

    static class Cmd implements Step {
        String c; boolean sent; int w = 10;
        Cmd(String c) { this.c = c; }
        public boolean tick(MinecraftClient mc) {
            if (!sent) {
                String s = c.replace("{price}", cfg.price).replace("{count}", String.valueOf(cfg.count));
                mc.player.sendChatMessage("/" + s);
                sent = true;
            }
            return --w <= 0;
        }
    }

    static class WaitScreen implements Step {
        int timeout = 100;
        public boolean tick(MinecraftClient mc) {
            if (mc.currentScreen instanceof GenericContainerScreen) return true;
            if (--timeout <= 0) { fail(mc, "Menu ne vidkrylos"); }
            return false;
        }
    }

    static class Hotbar implements Step {
        public boolean tick(MinecraftClient mc) {
            mc.player.inventory.selectedSlot = Math.max(0, Math.min(8, cfg.hotbarSlot - 1));
            return true;
        }
    }

    static class Close implements Step {
        public boolean tick(MinecraftClient mc) { mc.player.closeHandledScreen(); return true; }
    }

    static class Click implements Step {
        int slot; int phase = 0; int w = 12;
        Click(int slot) { this.slot = slot; }
        public boolean tick(MinecraftClient mc) {
            if (!(mc.currentScreen instanceof GenericContainerScreen)) return false;
            GenericContainerScreen scr = (GenericContainerScreen) mc.currentScreen;
            GenericContainerScreenHandler h = scr.getScreenHandler();
            if (slot >= h.slots.size()) return true;
            if (phase == 0) {
                int bgH = 114 + h.getRows() * 18;
                int left = (mc.getWindow().getScaledWidth() - 176) / 2;
                int top = (mc.getWindow().getScaledHeight() - bgH) / 2;
                double scale = mc.getWindow().getScaleFactor();
                double px = (left + h.slots.get(slot).x + 8) * scale;
                double py = (top + h.slots.get(slot).y + 8) * scale;
                moveTo(px, py);
                phase = 1;
            } else if (phase == 1 && !moving) {
                mc.interactionManager.clickSlot(h.syncId, slot, 0, SlotActionType.PICKUP, mc.player);
                phase = 2;
            } else if (phase == 2) {
                return --w <= 0;
            }
            return false;
        }
    }

    static void fail(MinecraftClient mc, String msg) {
        queue.clear();
        if (mc.player != null) {
            mc.player.closeHandledScreen();
            mc.player.sendMessage(new LiteralText("[AutoAH] " + msg + ", povtor cherez 5s"), false);
        }
        queue.add(new Wait(100));
        queue.add(mc2 -> { buildCycle(); return true; });
    }

    static void buildCycle() {
        queue.add(new Cmd(cfg.openCommand));
        queue.add(new WaitScreen());
        queue.add(new Wait(10));
        queue.add(new Click(cfg.myLotsSlot));
        queue.add(new Wait(15));
        for (int s : cfg.cancelSlots) {
            queue.add(new Click(s));
            queue.add(new Wait(10 + rnd.nextInt(10)));
        }
        queue.add(new Close());
        queue.add(new Wait(10));
        queue.add(new Hotbar());
        queue.add(new Cmd(cfg.sellCommand));
        queue.add(new Wait(Math.max(1, cfg.timerSeconds) * 20));
        queue.add(mc -> { buildCycle(); return true; });
    }

    static File cfgFile() {
        return new File(MinecraftClient.getInstance().runDirectory, "config/autoah.json");
    }

    static void loadCfg() {
        if (cfgLoaded) return;
        cfgLoaded = true;
        try {
            File f = cfgFile();
            if (f.exists()) {
                Cfg c = new Gson().fromJson(new String(Files.readAllBytes(f.toPath()), "UTF-8"), Cfg.class);
                if (c != null) cfg = c;
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    static void saveCfg() {
        try {
            File f = cfgFile();
            f.getParentFile().mkdirs();
            Files.write(f.toPath(), new GsonBuilder().setPrettyPrinting().create().toJson(cfg).getBytes("UTF-8"));
        } catch (Exception e) { e.printStackTrace(); }
    }

    static void setRunning(MinecraftClient mc, boolean on) {
        running = on;
        queue.clear();
        moving = false;
        if (on) buildCycle();
        if (mc.player != null)
            mc.player.sendMessage(new LiteralText("[AutoAH] " + (on ? "ON" : "OFF")), true);
    }

    public static class CfgScreen extends Screen {
        final Screen parent;
        TextFieldWidget fPrice, fCount, fTimer, fCmd, fLots, fCancel, fMove;
        ButtonWidget bSlot;
        int hotSlot;

        public CfgScreen(Screen parent) {
            super(new LiteralText("AutoAH"));
            this.parent = parent;
        }

        static int num(String s, int def) {
            try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
        }

        TextFieldWidget field(int y, String value, int max) {
            TextFieldWidget f = new TextFieldWidget(this.textRenderer, this.width / 2 - 20, y, 150, 18, new LiteralText(""));
            f.setMaxLength(max);
            f.setText(value);
            this.addButton(f);
            return f;
        }

        String slotLabel() {
            String name = "";
            if (this.client != null && this.client.player != null) {
                name = this.client.player.inventory.getStack(hotSlot - 1).getName().getString();
            }
            return "Slot " + hotSlot + ": " + name;
        }

        @Override
        protected void init() {
            loadCfg();
            hotSlot = Math.max(1, Math.min(9, cfg.hotbarSlot));
            int y = 30;
            fPrice = field(y, cfg.price, 20); y += 24;
            fCount = field(y, String.valueOf(cfg.count), 6); y += 24;
            fTimer = field(y, String.valueOf(cfg.timerSeconds), 6); y += 24;
            fCmd = field(y, cfg.sellCommand, 60); y += 24;
            fLots = field(y, String.valueOf(cfg.myLotsSlot), 4); y += 24;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cfg.cancelSlots.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(cfg.cancelSlots[i]);
            }
            fCancel = field(y, sb.toString(), 60); y += 24;
            fMove = field(y, String.valueOf(cfg.moveMs), 5); y += 24;

            bSlot = this.addButton(new ButtonWidget(this.width / 2 - 20, y, 150, 20, new LiteralText(slotLabel()), b -> {
                hotSlot = hotSlot % 9 + 1;
                b.setMessage(new LiteralText(slotLabel()));
            }));
            y += 30;

            this.addButton(new ButtonWidget(this.width / 2 - 155, y, 100, 20, new LiteralText("Zberehty"), b -> {
                apply();
                saveCfg();
            }));
            this.addButton(new ButtonWidget(this.width / 2 - 50, y, 100, 20,
                    new LiteralText(running ? "Zupynyty" : "Start"), b -> {
                apply();
                saveCfg();
                setRunning(this.client, !running);
                this.client.openScreen(null);
            }));
            this.addButton(new ButtonWidget(this.width / 2 + 55, y, 100, 20, new LiteralText("Zakryty"), b -> {
                apply();
                saveCfg();
                this.client.openScreen(parent);
            }));
        }

        void apply() {
            cfg.price = fPrice.getText().trim();
            cfg.count = Math.max(1, num(fCount.getText(), 64));
            cfg.timerSeconds = Math.max(1, num(fTimer.getText(), 300));
            cfg.sellCommand = fCmd.getText().trim();
            cfg.myLotsSlot = Math.max(0, num(fLots.getText(), 49));
            cfg.moveMs = Math.max(100, num(fMove.getText(), 450));
            cfg.hotbarSlot = hotSlot;
            List<Integer> list = new ArrayList<>();
            for (String p : fCancel.getText().split(",")) {
                try { list.add(Integer.parseInt(p.trim())); } catch (Exception ignored) { }
            }
            int[] arr = new int[list.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = list.get(i);
            cfg.cancelSlots = arr;
        }

        @Override
        public void onClose() {
            apply();
            saveCfg();
            this.client.openScreen(parent);
        }

        @Override
        public void tick() {
            fPrice.tick(); fCount.tick(); fTimer.tick(); fCmd.tick();
            fLots.tick(); fCancel.tick(); fMove.tick();
        }

        @Override
        public void render(MatrixStack m, int mx, int my, float d) {
            this.renderBackground(m);
            drawCenteredText(m, this.textRenderer, new LiteralText("AutoAH - nalashtuvannia"), this.width / 2, 10, 0xFFFFFF);
            int lx = this.width / 2 - 170;
            int y = 35;
            String[] labels = {"Tsina", "Kilkist", "Taimer (sek)", "Komanda prodazhu",
                    "Slot 'moi loty'", "Slotu na znyattia", "Shvydkist myshky (ms)", "Predmet (hotbar)"};
            for (int i = 0; i < labels.length; i++) {
                this.textRenderer.drawWithShadow(m, labels[i], lx, y + i * 24, 0xAAAAAA);
            }
            super.render(m, mx, my, d);
        }

        @Override
        public boolean isPauseScreen() { return false; }
    }

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("AutoAH start/stop", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, "AutoAH"));
        guiKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("AutoAH menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, "AutoAH"));

        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) ->
                ScreenEvents.afterRender(screen).register((s, m, mx, my, d) -> frame()));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (toggleKey.wasPressed()) {
                loadCfg();
                if (running) setRunning(mc, false);
                else if (mc.currentScreen == null) mc.openScreen(new CfgScreen(null));
            }
            while (guiKey.wasPressed()) {
                loadCfg();
                if (mc.currentScreen == null) mc.openScreen(new CfgScreen(null));
            }
            if (!running || mc.player == null) return;
            Step s = queue.peek();
            if (s != null && s.tick(mc)) queue.poll();
        });
    }
}
