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
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.LiteralText;
import net.minecraft.text.MutableText;
import net.minecraft.util.Formatting;
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
        int speed = 2; // 1 Slow, 2 Normal, 3 Fast, 4 Turbo, 5 Instant
        boolean showSlots = true;
    }

    static final String[] SPEED_NAMES = {"Slow", "Normal", "Fast", "Turbo", "Instant"};
    static final double[] DELAY = {2.0, 1.0, 0.6, 0.35, 0.2};
    static final int[] MOUSE_MS = {800, 450, 260, 140, 0};

    static Cfg cfg = new Cfg();
    static boolean cfgLoaded = false;
    static boolean running = false;
    static boolean noMore = false;
    static ItemStack sellStack = ItemStack.EMPTY;
    static final Deque<Step> queue = new ArrayDeque<>();
    static KeyBinding toggleKey;
    static KeyBinding guiKey;
    static final Random rnd = new Random();

    // ---------- Speed helpers ----------
    static int spd() { return Math.max(1, Math.min(5, cfg.speed)); }

    static int t(int ticks) {
        return Math.max(1, (int) Math.round(ticks * DELAY[spd() - 1]));
    }

    // ---------- Smooth mouse ----------
    static boolean moving = false;
    static double sx, sy, tx, ty, bend;
    static long t0;
    static int dur;

    static void moveTo(double x, double y) {
        MinecraftClient mc = MinecraftClient.getInstance();
        int base = MOUSE_MS[spd() - 1];
        if (base <= 0) {
            GLFW.glfwSetCursorPos(mc.getWindow().getHandle(), x, y);
            moving = false;
            return;
        }
        sx = mc.mouse.getX(); sy = mc.mouse.getY();
        tx = x; ty = y;
        dur = base + rnd.nextInt(base / 3 + 1);
        bend = (rnd.nextDouble() - 0.5) * 60;
        t0 = System.currentTimeMillis();
        moving = true;
    }

    static void frame() {
        if (!moving) return;
        double tt = Math.min(1.0, (System.currentTimeMillis() - t0) / (double) dur);
        double s = tt * tt * (3 - 2 * tt);
        double dx = tx - sx, dy = ty - sy;
        double len = Math.max(1, Math.hypot(dx, dy));
        double off = Math.sin(Math.PI * tt) * bend;
        double x = sx + dx * s + (-dy / len) * off;
        double y = sy + dy * s + (dx / len) * off;
        GLFW.glfwSetCursorPos(MinecraftClient.getInstance().getWindow().getHandle(), x, y);
        if (tt >= 1.0) moving = false;
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

    static boolean same(ItemStack a, ItemStack b) {
        return a.getItem() == b.getItem() && Objects.equals(a.getTag(), b.getTag());
    }

    // inventory index (0-8 hotbar, 9-35 main) -> slot id in the player screen handler
    static int sl(int i) { return i < 9 ? i + 36 : i; }

    static boolean needSplit() { return !cfg.sellCommand.contains("{count}"); }

    // best stack to sell from: the smallest one that has enough items, else the biggest one
    static int findSrc(MinecraftClient mc) {
        if (sellStack.isEmpty()) return -1;
        int best = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.inventory.getStack(i);
            if (s.isEmpty() || !same(s, sellStack)) continue;
            if (best < 0) { best = i; continue; }
            int cb = mc.player.inventory.getStack(best).getCount();
            int c = s.getCount();
            boolean bOk = cb >= cfg.perLot, cOk = c >= cfg.perLot;
            if (cOk && (!bOk || c < cb)) best = i;
            else if (!cOk && !bOk && c > cb) best = i;
        }
        return best;
    }

    static int findEmpty(MinecraftClient mc, int exclude) {
        for (int i = 0; i < 36; i++) {
            if (i != exclude && mc.player.inventory.getStack(i).isEmpty()) return i;
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
        String c; boolean sent; int w = t(10);
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

    // Prepares exactly "items per lot" in hand (splitting the stack without opening the inventory) and sells
    static class Sell implements Step {
        int phase = 0, w = 0, src = -1, dst = -1, need = 1, clicks = 0;

        public boolean tick(MinecraftClient mc) {
            if (noMore) return true;
            int hid = mc.player.playerScreenHandler.syncId;
            int target = Math.max(0, Math.min(8, cfg.hotbarSlot - 1));
            switch (phase) {
                case 0: {
                    src = findSrc(mc);
                    if (src < 0) { noMore = true; return true; }
                    if (!mc.player.inventory.getCursorStack().isEmpty()) {
                        finish(mc, "Your cursor holds an item. Stopped.");
                        return true;
                    }
                    ItemStack s = mc.player.inventory.getStack(src);
                    need = Math.min(cfg.perLot, s.getCount());
                    if (!needSplit() || need >= s.getCount()) {
                        dst = src;
                        phase = 4;
                        return false;
                    }
                    dst = findEmpty(mc, src);
                    if (dst < 0) {
                        finish(mc, "No free inventory slot to split the stack. Stopped.");
                        return true;
                    }
                    phase = 1;
                    return false;
                }
                case 1:
                    mc.interactionManager.clickSlot(hid, sl(src), 0, SlotActionType.PICKUP, mc.player);
                    clicks = need;
                    phase = 2;
                    return false;
                case 2:
                    mc.interactionManager.clickSlot(hid, sl(dst), 1, SlotActionType.PICKUP, mc.player);
                    if (--clicks <= 0) phase = 3;
                    return false;
                case 3:
                    mc.interactionManager.clickSlot(hid, sl(src), 0, SlotActionType.PICKUP, mc.player);
                    phase = 4;
                    return false;
                case 4:
                    if (dst < 9) {
                        mc.player.inventory.selectedSlot = dst;
                    } else {
                        mc.interactionManager.clickSlot(hid, sl(dst), target, SlotActionType.SWAP, mc.player);
                        mc.player.inventory.selectedSlot = target;
                    }
                    phase = 5;
                    w = t(6);
                    return false;
                case 5:
                    if (--w <= 0) {
                        String s = cfg.sellCommand.replace("{price}", cfg.price).replace("{count}", String.valueOf(cfg.perLot));
                        mc.player.sendChatMessage("/" + s);
                        phase = 6;
                        w = t(10);
                    }
                    return false;
                default:
                    return --w <= 0;
            }
        }
    }

    static class WaitScreen implements Step {
        int timeout = 120;
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
        int slot; int phase = 0; int w = t(10);
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
        int phase = 0, w = 10, slot = -1, done = 0;

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
                w = t(10) + rnd.nextInt(t(6) + 1);
                phase = 2;
            } else if (phase == 2) {
                if (--w <= 0) phase = 0;
            }
            return false;
        }
    }

    static void buildRound(MinecraftClient mc) {
        if (findSrc(mc) < 0) { finish(mc, "Done. No more items to sell."); return; }
        noMore = false;
        for (int i = 0; i < cfg.lots; i++) {
            queue.add(new Sell());
            queue.add(new Wait(t(8) + rnd.nextInt(t(8) + 1)));
        }
        queue.add(new Wait(Math.max(1, cfg.timerSeconds) * 20));
        queue.add(new Cmd(cfg.openCommand));
        queue.add(new WaitScreen());
        queue.add(new Wait(t(8)));
        queue.add(new Click(cfg.myLotsSlot));
        queue.add(new Wait(t(12)));
        queue.add(new CancelAll());
        queue.add(new Close());
        queue.add(new Wait(t(20)));
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
        sellStack = st.copy();
        running = true;
        message(mc, "Started. Selling: " + st.getName().getString());
        buildRound(mc);
    }

    // ---------- Settings screen ----------
    static MutableText bold(String s) { return new LiteralText(s).formatted(Formatting.BOLD); }

    public static class CfgScreen extends Screen {
        final Screen parent;
        TextFieldWidget fPrice, fPer, fLots, fTimer, fCmd, fMy;
        ButtonWidget bSpeed, bSlot, bShow;
        int hotSlot, speed;
        boolean show;
        int y0 = 44, rowH = 24;

        public CfgScreen(Screen parent) {
            super(new LiteralText("AutoAH"));
            this.parent = parent;
        }

        static int num(String s, int def) {
            try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
        }

        TextFieldWidget field(int row, String value, int max) {
            TextFieldWidget f = new TextFieldWidget(this.textRenderer, this.width / 2 + 10, y0 + row * rowH, 160, 18, new LiteralText(""));
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

        String speedLabel() { return "Speed: " + SPEED_NAMES[speed - 1]; }
        String showLabel() { return "Slot numbers in /ah: " + (show ? "ON" : "OFF"); }

        @Override
        protected void init() {
            loadCfg();
            hotSlot = Math.max(1, Math.min(9, cfg.hotbarSlot));
            speed = Math.max(1, Math.min(5, cfg.speed));
            show = cfg.showSlots;
            rowH = Math.max(21, Math.min(26, (this.height - 110) / 9));
            fPrice = field(0, cfg.price, 20);
            fPer = field(1, String.valueOf(cfg.perLot), 4);
            fLots = field(2, String.valueOf(cfg.lots), 3);
            fTimer = field(3, String.valueOf(cfg.timerSeconds), 6);
            fCmd = field(4, cfg.sellCommand, 60);
            fMy = field(5, String.valueOf(cfg.myLotsSlot), 4);

            bSpeed = this.addButton(new ButtonWidget(this.width / 2 + 10, y0 + 6 * rowH - 1, 160, 20, bold(speedLabel()), b -> {
                speed = speed % 5 + 1;
                b.setMessage(bold(speedLabel()));
            }));
            bSlot = this.addButton(new ButtonWidget(this.width / 2 + 10, y0 + 7 * rowH - 1, 160, 20, bold(slotLabel()), b -> {
                hotSlot = hotSlot % 9 + 1;
                b.setMessage(bold(slotLabel()));
            }));
            bShow = this.addButton(new ButtonWidget(this.width / 2 + 10, y0 + 8 * rowH - 1, 160, 20, bold(showLabel()), b -> {
                show = !show;
                b.setMessage(bold(showLabel()));
            }));

            int y = y0 + 9 * rowH + 8;
            this.addButton(new ButtonWidget(this.width / 2 - 175, y, 110, 20, bold("Save"), b -> {
                apply();
                saveCfg();
            }));
            this.addButton(new ButtonWidget(this.width / 2 - 55, y, 110, 20,
                    bold(running ? "Stop" : "Start"), b -> {
                apply();
                saveCfg();
                setRunning(this.client, !running);
                this.client.openScreen(null);
            }));
            this.addButton(new ButtonWidget(this.width / 2 + 65, y, 110, 20, bold("Close"), b -> {
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
            cfg.speed = speed;
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
            fCmd.tick(); fMy.tick();
        }

        @Override
        public void render(MatrixStack m, int mx, int my, float d) {
            this.renderBackground(m);
            int cx = this.width / 2;
            int left = cx - 185, right = cx + 185;
            int panelBottom = y0 + 9 * rowH + 34;

            // panel with border
            fill(m, left - 2, 28, right + 2, panelBottom + 2, 0xFF000000);
            fill(m, left, 30, right, panelBottom, 0xE6201A14);
            fill(m, left, 30, right, 32, 0xFFFFAA00);

            // big bold title
            m.push();
            m.scale(2.0F, 2.0F, 1.0F);
            drawCenteredText(m, this.textRenderer, bold("AutoAH"), cx / 2, 4, 0xFFAA00);
            m.pop();

            // striped rows with bold labels
            String[] labels = {"Price", "Items per lot", "Lots per round", "Wait before cancel (s)",
                    "Sell command", "My lots button slot", "Work speed", "Item to sell", "Debug"};
            for (int i = 0; i < labels.length; i++) {
                int ry = y0 + i * rowH;
                fill(m, left + 4, ry - 2, right - 4, ry + rowH - 3, (i % 2 == 0) ? 0x30FFFFFF : 0x14FFFFFF);
                this.textRenderer.drawWithShadow(m, bold(labels[i]), left + 10, ry + 5, 0xFFE08A);
            }

            // status + hint
            int sy2 = y0 + 9 * rowH + 34;
            String status = running ? "RUNNING" : "STOPPED";
            drawCenteredText(m, this.textRenderer, bold("Status: " + status), cx, sy2 + 2, running ? 0x55FF55 : 0xFF5555);
            drawCenteredText(m, this.textRenderer, new LiteralText("Add {count} to the command if the server supports an amount."), cx, sy2 + 14, 0xAAAAAA);
            drawCenteredText(m, this.textRenderer, new LiteralText("Without {count} stacks are split automatically."), cx, sy2 + 25, 0xAAAAAA);

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
