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
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.LiteralText;
import net.minecraft.util.registry.Registry;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

public class AutoAh implements ClientModInitializer {

    static class Cfg {
        String price = "1000";
        int perLot = 1;
        int lots = 5;
        int hotbarSlot = 1;
        int timerSeconds = 300;
        String openCommand = "ah";
        String sellCommand = "ah sell {price}";
        int myLotsSlot = 49;
        int moveMs = 450;
        boolean showSlots = true;
    }

    static Cfg cfg = new Cfg();
    static boolean cfgLoaded = false;
    static boolean running = false;
    static boolean noMore = false;
    static Item sellItem = null;
    static final Deque<Step> queue = new ArrayDeque<>();
    static KeyBinding toggleKey;
    static KeyBinding guiKey;
    static final Random rnd = new Random();

    // ---------- Smooth mouse ----------
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

    // ---------- Helpers ----------
    static int[] guiOrigin(MinecraftClient mc, GenericContainerScreenHandler h) {
        int bgH = 114 + h.getRows() * 18;
        int left = (mc.getWindow().getScaledWidth() - 176) / 2;
        int top = (mc.getWindow().getScaledHeight() - bgH) / 2;
        return new int[]{left, top};
    }

    static void drawSlotNumbers(MinecraftClient mc, MatrixStack m, GenericContainerScreen scr) {
        GenericContainerScreenHandler h = scr.getScreenHandler();
        int[] o = guiOrigin(mc, h);
        int n = h.getRows() * 9;
        for (int i = 0; i < n && i < h.slots.size(); i++) {
            mc.textRenderer.drawWithShadow(m, String.valueOf(i), o[0] + h.slots.get(i).x, o[1] + h.slots.get(i).y, 0xFFFF55);
        }
    }

    static int findItem(MinecraftClient mc) {
        if (sellItem == null) return -1;
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.inventory.getStack(i);
            if (!s.isEmpty() && s.getItem() == sellItem) return i;
        }
        return -1;
    }

    static void message(MinecraftClient mc, String text) {
        if (mc.player != null) mc.player.sendMessage(new LiteralText("[AutoAH] " + text), false);
    }

    static void finish(MinecraftClient mc, String text) {
        running = false;
        queue.clear();
        moving = false;
        message(mc, text);
    }

    // ---------- Steps ----------
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
                String s = c.replace("{price}", cfg.price).replace("{count}", String.valueOf(cfg.perLot));
                mc.player.sendChatMessage("/" + s);
                sent = true;
            }
            return --w <= 0;
        }
    }

    static class Sell implements Step {
        int phase = 0, w = 6;
        public boolean tick(MinecraftClient mc) {
            if (noMore) return true;
            if (phase == 0) {
                int idx = findItem(mc);
                if (idx < 0) { noMore = true; return true; }
                int target = Math.max(0, Math.min(8, cfg.hotbarSlot - 1));
                if (idx < 9) {
                    mc.player.inventory.selectedSlot = idx;
                } else {
                    mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, idx, target, SlotActionType.SWAP, mc.player);
                    mc.player.inventory.selectedSlot = target;
                }
                phase = 1;
            } else if (phase == 1) {
                if (--w <= 0) {
                    String s = cfg.sellCommand.replace("{price}", cfg.price).replace("{count}", String.valueOf(cfg.perLot));
                    mc.player.sendChatMessage("/" + s);
                    phase = 2; w = 10;
                }
            } else {
                return --w <= 0;
            }
            return false;
        }
    }

    static class WaitScreen implements Step {
        int timeout = 100;
        public boolean tick(MinecraftClient mc) {
            if (mc.currentScreen instanceof GenericContainerScreen) return true;
            if (--timeout <= 0) { finish(mc, "The /ah menu did not open. Stopped."); }
            return false;
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
            GenericContainerScreenHandler h = ((GenericContainerScreen) mc.currentScreen).getScreenHandler();
            if (slot >= h.slots.size()) return true;
            if (phase == 0) {
                int[] o = guiOrigin(mc, h);
                double scale = mc.getWindow().getScaleFactor();
                moveTo((o[0] + h.slots.get(slot).x + 8) * scale, (o[1] + h.slots.get(slot).y + 8) * scale);
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

    // Takes down only lots that really exist (non-empty cells in the "my lots" menu)
    static class CancelAll implements Step {
        int phase = 0, w = 15, slot = -1, done = 0;

        int findLot(GenericContainerScreenHandler h) {
            int limit = h.getRows() * 9 - 9;
            for (int i = 0; i < limit && i < h.slots.size(); i++) {
                ItemStack st = h.slots.get(i).getStack();
                if (st.isEmpty()) continue;
                String id = Registry.ITEM.getId(st.getItem()).getPath();
                if (id.contains("pane") || id.contains("barrier")) continue;
                return i;
            }
            return -1;
        }

        public boolean tick(MinecraftClient mc) {
            if (!(mc.currentScreen instanceof GenericContainerScreen)) return false;
            GenericContainerScreenHandler h = ((GenericContainerScreen) mc.currentScreen).getScreenHandler();
            if (phase == 0) {
                if (done >= cfg.lots) return true;
                slot = findLot(h);
                if (slot < 0) return true;
                int[] o = guiOrigin(mc, h);
                double scale = mc.getWindow().getScaleFactor();
                moveTo((o[0] + h.slots.get(slot).x + 8) * scale, (o[1] + h.slots.get(slot).y + 8) * scale);
                phase = 1;
            } else if (phase == 1 && !moving) {
                mc.interactionManager.clickSlot(h.syncId, slot, 0, SlotActionType.PICKUP, mc.player);
                done++;
                w = 15 + rnd.nextInt(10);
                phase = 2;
            } else if (phase == 2) {
                if (--w <= 0) phase = 0;
            }
            return false;
        }
    }

    static void buildRound(MinecraftClient mc) {
        if (findItem(mc) < 0) { finish(mc, "Done. No more items to sell."); return; }
        noMore = false;
        for (int i = 0; i < cfg.lots; i++) {
            queue.add(new Sell());
            queue.add(new Wait(10 + rnd.nextInt(15)));
        }
        queue.add(new Wait(Math.max(1, cfg.timerSeconds) * 20));
        queue.add(new Cmd(cfg.openCommand));
        queue.add(new WaitScreen());
        queue.add(new Wait(10));
        queue.add(new Click(cfg.myLotsSlot));
        queue.add(new Wait(15));
        queue.add(new CancelAll());
        queue.add(new Close());
        queue.add(new Wait(30));
        queue.add(m -> { buildRound(m); return true; });
    }

    // ---------- Config ----------
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
        queue.clear();
        moving = false;
        if (!on) { running = false; message(mc, "Stopped."); return; }
        ItemStack st = mc.player.inventory.getStack(Math.max(0, Math.min(8, cfg.hotbarSlot - 1)));
        if (st.isEmpty()) {
            running = false;
            message(mc, "The chosen hotbar slot is empty. Put the item there and pick that slot.");
            return;
        }
        sellItem = st.getItem();
        running = true;
        message(mc, "Started. Selling: " + st.getName().getString());
        buildRound(mc);
    }

    // ---------- Settings screen ----------
    public static class CfgScreen extends Screen {
        final Screen parent;
        TextFieldWidget fPrice, fPer, fLots, fTimer, fCmd, fMy, fMove;
        ButtonWidget bSlot, bShow;
        int hotSlot;
        boolean show;

        public CfgScreen(Screen parent) {
            super(new LiteralText("AutoAH"));
            this.parent = parent;
        }

        static int num(String s, int def) {
            try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
        }

        TextFieldWidget field(int row, String value, int max) {
            TextFieldWidget f = new TextFieldWidget(this.textRenderer, this.width / 2 - 20, 30 + row * 22, 150, 18, new LiteralText(""));
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

        String showLabel() { return "Show slot numbers in /ah: " + (show ? "ON" : "OFF"); }

        @Override
        protected void init() {
            loadCfg();
            hotSlot = Math.max(1, Math.min(9, cfg.hotbarSlot));
            show = cfg.showSlots;
            fPrice = field(0, cfg.price, 20);
            fPer = field(1, String.valueOf(cfg.perLot), 4);
            fLots = field(2, String.valueOf(cfg.lots), 3);
            fTimer = field(3, String.valueOf(cfg.timerSeconds), 6);
            fCmd = field(4, cfg.sellCommand, 60);
            fMy = field(5, String.valueOf(cfg.myLotsSlot), 4);
            fMove = field(6, String.valueOf(cfg.moveMs), 5);

            bSlot = this.addButton(new ButtonWidget(this.width / 2 - 20, 30 + 7 * 22, 150, 20, new LiteralText(slotLabel()), b -> {
                hotSlot = hotSlot % 9 + 1;
                b.setMessage(new LiteralText(slotLabel()));
            }));
            bShow = this.addButton(new ButtonWidget(this.width / 2 - 20, 30 + 8 * 22, 150, 20, new LiteralText(showLabel()), b -> {
                show = !show;
                b.setMessage(new LiteralText(showLabel()));
            }));

            int y = 30 + 9 * 22 + 6;
            this.addButton(new ButtonWidget(this.width / 2 - 155, y, 100, 20, new LiteralText("Save"), b -> {
                apply();
                saveCfg();
            }));
            this.addButton(new ButtonWidget(this.width / 2 - 50, y, 100, 20,
                    new LiteralText(running ? "Stop" : "Start"), b -> {
                apply();
                saveCfg();
                setRunning(this.client, !running);
                this.client.openScreen(null);
            }));
            this.addButton(new ButtonWidget(this.width / 2 + 55, y, 100, 20, new LiteralText("Close"), b -> {
                apply();
                saveCfg();
                this.client.openScreen(parent);
            }));
        }

        void apply() {
            cfg.price = fPrice.getText().trim();
            cfg.perLot = Math.max(1, num(fPer.getText(), 1));
            cfg.lots = Math.max(1, num(fLots.getText(), 5));
            cfg.timerSeconds = Math.max(1, num(fTimer.getText(), 300));
            cfg.sellCommand = fCmd.getText().trim();
            cfg.myLotsSlot = Math.max(0, num(fMy.getText(), 49));
            cfg.moveMs = Math.max(100, num(fMove.getText(), 450));
            cfg.hotbarSlot = hotSlot;
            cfg.showSlots = show;
        }

        @Override
        public void onClose() {
            apply();
            saveCfg();
            this.client.openScreen(parent);
        }

        @Override
        public void tick() {
            fPrice.tick(); fPer.tick(); fLots.tick(); fTimer.tick();
            fCmd.tick(); fMy.tick(); fMove.tick();
        }

        @Override
        public void render(MatrixStack m, int mx, int my, float d) {
            this.renderBackground(m);
            drawCenteredText(m, this.textRenderer, new LiteralText("AutoAH - settings"), this.width / 2, 10, 0xFFFFFF);
            int lx = this.width / 2 - 170;
            String[] labels = {"Price", "Items per lot", "Lots per round", "Wait before cancel (sec)",
                    "Sell command", "My lots button slot", "Mouse speed (ms)", "Item to sell", "Debug"};
            for (int i = 0; i < labels.length; i++) {
                this.textRenderer.drawWithShadow(m, labels[i], lx, 35 + i * 22, 0xAAAAAA);
            }
            super.render(m, mx, my, d);
        }

        @Override
        public boolean isPauseScreen() { return false; }
    }

    // ---------- Init ----------
    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("AutoAH start/stop", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, "AutoAH"));
        guiKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("AutoAH menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, "AutoAH"));

        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) ->
                ScreenEvents.afterRender(screen).register((s, m, mx, my, d) -> {
                    frame();
                    loadCfg();
                    if (cfg.showSlots && s instanceof GenericContainerScreen) {
                        drawSlotNumbers(mc, m, (GenericContainerScreen) s);
                    }
                }));

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
