package autoah;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.LiteralText;
import net.minecraft.util.Formatting;
import net.minecraft.util.registry.Registry;
import org.lwjgl.glfw.GLFW;

public class AutoAh implements ClientModInitializer {

    // ---------------------------------------------------------------- швидкість
    static final String[] SPEED_NAMES = {"Повільно", "Нормально", "Швидко", "Турбо", "Миттєво", "Максимум"};
    // множник пауз між діями
    static final double[] DELAY = {2.0, 1.0, 0.6, 0.35, 0.2, 0.0};
    // тривалість руху курсора (мс)
    static final int[] MOUSE_MS = {800, 450, 260, 140, 0, 0};
    // скільки кроків можна виконати за один тік
    static final int[] STEPS_PER_TICK = {1, 1, 1, 1, 2, 16};
    static final int MAX_SPEED = 6;

    /** Розмір цифр на слотах (0.5 = вдвічі менші за звичайний текст). */
    static final float SLOT_NUMBER_SCALE = 0.5f;

    static Cfg cfg = new Cfg();
    static boolean cfgLoaded = false;
    static boolean running = false;
    static boolean noMore = false;
    /** Усі предмети, які продаємо (по одному еталону на кожен вид). */
    static final List<ItemStack> sellStacks = new ArrayList<ItemStack>();
    static final Deque<Step> queue = new ArrayDeque<Step>();
    static KeyBinding toggleKey;
    static KeyBinding guiKey;
    static final Random rnd = new Random();

    // рух курсора
    static boolean moving = false;
    static double sx, sy, tx, ty, bend;
    static long t0;
    static int dur;

    static int spd() {
        return Math.max(1, Math.min(MAX_SPEED, cfg.speed));
    }

    /** Переводить "базові" тіки у тіки з урахуванням швидкості. На максимумі може бути 0. */
    static int t(int ticks) {
        int v = (int) Math.round(ticks * DELAY[spd() - 1]);
        return spd() == MAX_SPEED ? Math.max(0, v) : Math.max(1, v);
    }

    static void moveTo(double x, double y) {
        MinecraftClient mc = MinecraftClient.getInstance();
        int base = MOUSE_MS[spd() - 1];
        if (base <= 0) {
            GLFW.glfwSetCursorPos(mc.getWindow().getHandle(), x, y);
            moving = false;
            return;
        }
        sx = mc.mouse.getX();
        sy = mc.mouse.getY();
        tx = x;
        ty = y;
        dur = base + rnd.nextInt(base / 3 + 1);
        bend = (rnd.nextDouble() - 0.5) * 60.0;
        t0 = System.currentTimeMillis();
        moving = true;
    }

    static void frame() {
        if (!moving) {
            return;
        }
        double tt = Math.min(1.0, (double) (System.currentTimeMillis() - t0) / (double) dur);
        double s = tt * tt * (3.0 - 2.0 * tt);
        double dx = tx - sx;
        double dy = ty - sy;
        double len = Math.max(1.0, Math.hypot(dx, dy));
        double off = Math.sin(Math.PI * tt) * bend;
        double x = sx + dx * s + -dy / len * off;
        double y = sy + dy * s + dx / len * off;
        GLFW.glfwSetCursorPos(MinecraftClient.getInstance().getWindow().getHandle(), x, y);
        if (tt >= 1.0) {
            moving = false;
        }
    }

    static int[] guiOrigin(MinecraftClient mc, GenericContainerScreenHandler h) {
        int bgH = 114 + h.getRows() * 18;
        int left = (mc.getWindow().getScaledWidth() - 176) / 2;
        int top = (mc.getWindow().getScaledHeight() - bgH) / 2;
        return new int[]{left, top};
    }

    /** Маленькі номери слотів у куті кожного слота. */
    static void drawSlotNumbers(MinecraftClient mc, MatrixStack m, GenericContainerScreen scr) {
        GenericContainerScreenHandler h = scr.getScreenHandler();
        int[] o = guiOrigin(mc, h);
        int n = h.getRows() * 9;
        for (int i = 0; i < n && i < h.slots.size(); ++i) {
            Slot s = h.slots.get(i);
            m.push();
            m.translate(o[0] + s.x + 1, o[1] + s.y + 1, 300.0);
            m.scale(SLOT_NUMBER_SCALE, SLOT_NUMBER_SCALE, 1.0f);
            mc.textRenderer.drawWithShadow(m, String.valueOf(i), 0.0f, 0.0f, 0xFFFF55);
            m.pop();
        }
    }

    // ---------------------------------------------------------------- інвентар
    /** Ключі NBT, які реально визначають "який це предмет". Решта (лор, мітки плагінів аукціону) ігнорується. */
    static final String[] KEY_TAGS = {"Potion", "CustomPotionEffects", "CustomPotionColor", "Enchantments",
            "StoredEnchantments", "Damage", "SkullOwner"};

    static boolean same(ItemStack a, ItemStack b) {
        if (a.getItem() != b.getItem()) {
            return false;
        }
        NbtCompound ca = a.getTag();
        NbtCompound cb = b.getTag();
        if (Objects.equals(ca, cb)) {
            return true;
        }
        // аукціон міг додати/змінити службові теги при поверненні предмета — порівнюємо тільки суттєві
        for (String k : KEY_TAGS) {
            if (!Objects.equals(ca == null ? null : ca.get(k), cb == null ? null : cb.get(k))) {
                return false;
            }
        }
        return true;
    }

    /** Індекс у інвентарі (0-8 хотбар, 9-35 інвентар) -> номер слота у вікні гравця. */
    static int sl(int i) {
        return i < 9 ? i + 36 : i;
    }

    static boolean needSplit() {
        return !cfg.sellCommand.contains("{count}");
    }

    static int findSrc(MinecraftClient mc, ItemStack ref) {
        if (ref.isEmpty()) {
            return -1;
        }
        int best = -1;
        for (int i = 0; i < 36; ++i) {
            ItemStack s = mc.player.inventory.getStack(i);
            if (s.isEmpty() || !same(s, ref)) {
                continue;
            }
            if (best < 0) {
                best = i;
                continue;
            }
            int cb = mc.player.inventory.getStack(best).getCount();
            int c = s.getCount();
            boolean bOk = cb >= cfg.perLot;
            boolean cOk = c >= cfg.perLot;
            if (cOk && (!bOk || c < cb)) {
                best = i;
                continue;
            }
            if (cOk || bOk || c <= cb) {
                continue;
            }
            best = i;
        }
        return best;
    }

    /** Є хоч один вид предмета, який ще можна продавати. */
    static boolean anyAvailable(MinecraftClient mc) {
        for (ItemStack ref : sellStacks) {
            if (findSrc(mc, ref) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** Випадковий вид серед тих, що ще є в інвентарі (null — нічого немає). */
    static ItemStack pickRandomType(MinecraftClient mc) {
        List<ItemStack> ok = new ArrayList<ItemStack>();
        for (ItemStack ref : sellStacks) {
            if (findSrc(mc, ref) >= 0) {
                ok.add(ref);
            }
        }
        return ok.isEmpty() ? null : ok.get(rnd.nextInt(ok.size()));
    }

    static int findEmpty(MinecraftClient mc, int exclude) {
        for (int i = 0; i < 36; ++i) {
            if (i == exclude || !mc.player.inventory.getStack(i).isEmpty()) {
                continue;
            }
            return i;
        }
        return -1;
    }

    /**
     * Клік по слоту "напряму" на сервер, БЕЗ локального передбачення клієнта.
     * Це потрібно для стаків, які на сервері більші за звичайний ліміт (наприклад зілля 21 шт.):
     * клієнт вважає, що зілля стакається лише по 1, тому його власний прорахунок кліку
     * відрізняється від серверного і інвентар розсинхронізовується.
     * Тут ми навмисно передаємо "невірний" очікуваний предмет (бар'єр) — сервер завжди виконує клік
     * по СВОЇХ правилах і присилає назад справжній стан усього інвентарю.
     */
    static void rawClick(MinecraftClient mc, int slot, int button, SlotActionType type) {
        ClientPlayNetworkHandler net = mc.getNetworkHandler();
        if (net == null || mc.player == null) {
            return;
        }
        short id = mc.player.playerScreenHandler.getNextActionId(mc.player.inventory);
        net.sendPacket(new ClickSlotC2SPacket(mc.player.playerScreenHandler.syncId, slot, button, type,
                new ItemStack(Items.BARRIER), id));
    }

    static int pingTicks(MinecraftClient mc) {
        try {
            ClientPlayNetworkHandler net = mc.getNetworkHandler();
            if (net != null && mc.player != null) {
                PlayerListEntry e = net.getPlayerListEntry(mc.player.getUuid());
                if (e != null) {
                    return (int) Math.ceil(e.getLatency() / 50.0);
                }
            }
        } catch (Exception ignored) {
            // не критично
        }
        return 0;
    }

    // ---------------------------------------------------------------- повідомлення
    static void message(MinecraftClient mc, String text, Formatting color) {
        if (mc.player != null) {
            mc.player.sendMessage(new LiteralText("[AutoAH] " + text).formatted(color), false);
        }
    }

    /** Початок роботи — зелений. */
    static void messageStart(MinecraftClient mc, String text) {
        message(mc, text, Formatting.GREEN);
    }

    /** Кінець / зупинка — червоний. */
    static void messageEnd(MinecraftClient mc, String text) {
        message(mc, text, Formatting.RED);
    }

    static void finish(MinecraftClient mc, String text) {
        running = false;
        queue.clear();
        moving = false;
        messageEnd(mc, text);
    }

    // ---------------------------------------------------------------- анти-АФК (рух камери)
    static int afkWait = 100;
    static int afkSteps = 0;
    static float afkDYaw = 0f;
    static float afkDPitch = 0f;

    static void antiAfkTick(MinecraftClient mc) {
        if (mc.player == null) {
            return;
        }
        if (afkSteps > 0) {
            mc.player.yaw += afkDYaw;
            float p = mc.player.pitch + afkDPitch;
            mc.player.pitch = Math.max(-60.0f, Math.min(60.0f, p));
            afkSteps--;
            return;
        }
        if (--afkWait > 0) {
            return;
        }
        // нова плавна зміна повороту: 8-14 тіків, невеликий кут
        afkSteps = 8 + rnd.nextInt(7);
        float yaw = (10 + rnd.nextInt(30)) * (rnd.nextBoolean() ? 1 : -1);
        float pitch = (3 + rnd.nextInt(8)) * (rnd.nextBoolean() ? 1 : -1);
        afkDYaw = yaw / afkSteps;
        afkDPitch = pitch / afkSteps;
        afkWait = 60 + rnd.nextInt(200);
    }

    static int emptyRetries = 0;

    static void buildRound(MinecraftClient mc) {
        if (!anyAvailable(mc)) {
            // предмети після скасування лотів можуть повертатися з затримкою — трохи чекаємо
            if (emptyRetries++ < 6) {
                queue.add(new Wait(20));
                queue.add(m -> {
                    buildRound(m);
                    return true;
                });
                return;
            }
            emptyRetries = 0;
            int other = 0;
            for (int i = 0; i < 36; ++i) {
                ItemStack s = mc.player.inventory.getStack(i);
                for (ItemStack ref : sellStacks) {
                    if (!s.isEmpty() && s.getItem() == ref.getItem()) {
                        other += s.getCount();
                        break;
                    }
                }
            }
            if (other > 0) {
                finish(mc, "Знайшла " + other + " шт. такого ж предмета, але з іншими даними (NBT). Зупинено.");
            } else {
                finish(mc, "Готово. Більше немає предметів для продажу.");
            }
            return;
        }
        emptyRetries = 0;
        noMore = false;
        for (int i = 0; i < cfg.lots; ++i) {
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
        queue.add(m -> {
            buildRound(m);
            return true;
        });
    }

    // ---------------------------------------------------------------- конфіг
    static File cfgFile() {
        return new File(MinecraftClient.getInstance().runDirectory, "config/autoah.json");
    }

    static void loadCfg() {
        if (cfgLoaded) {
            return;
        }
        cfgLoaded = true;
        try {
            File f = cfgFile();
            if (f.exists()) {
                Cfg c = new Gson().fromJson(new String(Files.readAllBytes(f.toPath()), "UTF-8"), Cfg.class);
                if (c != null) {
                    cfg = c;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static void saveCfg() {
        try {
            File f = cfgFile();
            f.getParentFile().mkdirs();
            Files.write(f.toPath(), new GsonBuilder().setPrettyPrinting().create().toJson(cfg).getBytes("UTF-8"));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Маска обраних слотів хотбару (біт 0 = слот 1). Якщо нічого не обрано — старий одиночний слот. */
    static int slotMask() {
        int m = cfg.slotMask & 0x1FF;
        if (m == 0) {
            m = 1 << (Math.max(1, Math.min(9, cfg.hotbarSlot)) - 1);
        }
        return m;
    }

    static void setRunning(MinecraftClient mc, boolean on) {
        queue.clear();
        moving = false;
        if (!on) {
            running = false;
            messageEnd(mc, "Зупинено.");
            return;
        }
        sellStacks.clear();
        StringBuilder names = new StringBuilder();
        int mask = slotMask();
        for (int i = 0; i < 9; i++) {
            if ((mask & (1 << i)) == 0) {
                continue;
            }
            ItemStack st = mc.player.inventory.getStack(i);
            if (st.isEmpty()) {
                continue;
            }
            boolean dup = false;
            for (ItemStack ex : sellStacks) {
                if (same(st, ex)) {
                    dup = true;
                    break;
                }
            }
            if (dup) {
                continue;
            }
            sellStacks.add(st.copy());
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(st.getName().getString());
        }
        if (sellStacks.isEmpty()) {
            running = false;
            messageEnd(mc, "Обрані слоти хотбару порожні. Покладіть предмети й оберіть їх у меню.");
            return;
        }
        running = true;
        messageStart(mc, "Початок. Продаю: " + names);
        buildRound(mc);
    }

    // ---------------------------------------------------------------- вхідна точка
    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("AutoAH: старт/стоп", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, "AutoAH"));
        guiKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("AutoAH: меню", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, "AutoAH"));

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
                if (running) {
                    setRunning(mc, false);
                    continue;
                }
                if (mc.currentScreen != null) {
                    continue;
                }
                mc.openScreen(new CfgScreen(null));
            }
            while (guiKey.wasPressed()) {
                loadCfg();
                if (mc.currentScreen != null) {
                    continue;
                }
                mc.openScreen(new CfgScreen(null));
            }
            if (!running || mc.player == null) {
                return;
            }
            if (cfg.antiAfk) {
                antiAfkTick(mc);
            }
            int n = STEPS_PER_TICK[spd() - 1];
            for (int k = 0; k < n && running; k++) {
                Step s = queue.peek();
                if (s == null) {
                    break;
                }
                if (s.tick(mc)) {
                    queue.poll();
                } else {
                    break;
                }
            }
        });
    }

    // ================================================================ дані
    static class Cfg {
        String price = "1000";
        int perLot = 1;
        int lots = 5;
        int hotbarSlot = 1;
        int slotMask = 0;
        int timerSeconds = 300;
        String openCommand = "ah";
        String sellCommand = "ah sell {price}";
        int myLotsSlot = 49;
        int speed = 2;
        boolean showSlots = true;
        boolean antiAfk = true;
    }

    // ================================================================ кроки
    interface Step {
        boolean tick(MinecraftClient mc);
    }

    /**
     * Продаж одного лоту: бере з стака потрібну кількість (perLot), кладе в руку і пише команду.
     * Для стаків, що на сервері більші за звичайний ліміт (зілля 21 шт. тощо) використовується
     * режим "raw": кожен клік виконується сервером, а ми чекаємо підтвердження від сервера.
     */
    static class Sell implements Step {
        static final int START = 0;
        static final int C_PICK = 1;
        static final int C_PUT = 2;
        static final int C_BACK = 3;
        static final int EQUIP = 4;
        static final int SEND = 5;
        static final int DONE = 6;
        static final int R_PICK = 20;
        static final int R_PICK_WAIT = 21;
        static final int R_PUT = 22;
        static final int R_PUT_WAIT = 23;
        static final int R_BACK = 24;
        static final int R_BACK_WAIT = 25;
        static final int R_SWAP_WAIT = 26;

        int phase = START;
        int w = 0;
        int src = -1;
        int dst = -1;
        int need = 1;
        int total = 0;
        int held = 0;
        int clicks = 0;
        int timeout = 0;
        boolean raw = false;
        ItemStack proto = ItemStack.EMPTY;

        @Override
        public boolean tick(MinecraftClient mc) {
            if (noMore) {
                return true;
            }
            for (int guard = 0; guard < 16; guard++) {
                int r = step(mc);
                if (r == 0) {
                    return false;
                }
                if (r == 2) {
                    return true;
                }
            }
            return false;
        }

        /** Перехід в іншу фазу; на максимальній швидкості одразу продовжуємо в цьому ж тіку. */
        int go(int next) {
            phase = next;
            return spd() == MAX_SPEED ? 1 : 0;
        }

        /** Очікування відповіді сервера: переходимо далі, коли умова виконалась, інакше таймаут. */
        int waitOrFail(MinecraftClient mc, boolean ok, int next, String msg) {
            if (ok) {
                return go(next);
            }
            if (--timeout <= 0) {
                abort(mc, msg);
                return 2;
            }
            return 0;
        }

        void abort(MinecraftClient mc, String msg) {
            // якщо щось залишилось у курсорі — повертаємо у слот-джерело
            if (src >= 0 && !mc.player.inventory.getCursorStack().isEmpty()) {
                rawClick(mc, sl(src), 0, SlotActionType.PICKUP);
            }
            finish(mc, msg);
        }

        int step(MinecraftClient mc) {
            int hid = mc.player.playerScreenHandler.syncId;
            int target = Math.max(0, Math.min(8, cfg.hotbarSlot - 1));
            switch (phase) {
                case START: {
                    ItemStack ref = pickRandomType(mc);
                    if (ref == null) {
                        noMore = true;
                        return 2;
                    }
                    src = findSrc(mc, ref);
                    if (!mc.player.inventory.getCursorStack().isEmpty()) {
                        finish(mc, "У курсорі лежить предмет. Зупинено.");
                        return 2;
                    }
                    ItemStack s = mc.player.inventory.getStack(src);
                    proto = s.copy();
                    total = s.getCount();
                    need = Math.min(cfg.perLot, total);
                    // стак більший за "клієнтський" ліміт (зілля 21 шт. тощо)
                    raw = total > s.getMaxCount();
                    if (!needSplit() || need >= total) {
                        dst = src;
                        held = total;
                        return go(EQUIP);
                    }
                    dst = findEmpty(mc, src);
                    if (dst < 0) {
                        finish(mc, "Немає вільного слота в інвентарі, щоб розділити стак. Зупинено.");
                        return 2;
                    }
                    held = need;
                    return go(raw ? R_PICK : C_PICK);
                }

                // ----- звичайний режим (клієнт передбачає кліки сам)
                case C_PICK: {
                    mc.interactionManager.clickSlot(hid, sl(src), 0, SlotActionType.PICKUP, mc.player);
                    clicks = need;
                    return go(C_PUT);
                }
                case C_PUT: {
                    mc.interactionManager.clickSlot(hid, sl(dst), 1, SlotActionType.PICKUP, mc.player);
                    if (--clicks <= 0) {
                        return go(C_BACK);
                    }
                    return go(C_PUT);
                }
                case C_BACK: {
                    mc.interactionManager.clickSlot(hid, sl(src), 0, SlotActionType.PICKUP, mc.player);
                    return go(EQUIP);
                }

                // ----- серверний режим для великих стаків
                case R_PICK: {
                    rawClick(mc, sl(src), 0, SlotActionType.PICKUP);
                    timeout = 60;
                    phase = R_PICK_WAIT;
                    return 0;
                }
                case R_PICK_WAIT: {
                    ItemStack c = mc.player.inventory.getCursorStack();
                    boolean ok = !c.isEmpty() && same(c, proto) && c.getCount() == total;
                    if (ok) {
                        clicks = 0;
                    }
                    return waitOrFail(mc, ok, R_PUT, "Сервер не дозволив взяти стак (у курсорі " + c.getCount() + " шт., потрібно " + total + "). Зупинено.");
                }
                case R_PUT: {
                    if (clicks >= need) {
                        return go(R_BACK);
                    }
                    rawClick(mc, sl(dst), 1, SlotActionType.PICKUP);
                    timeout = 60;
                    phase = R_PUT_WAIT;
                    return 0;
                }
                case R_PUT_WAIT: {
                    ItemStack d = mc.player.inventory.getStack(dst);
                    ItemStack c = mc.player.inventory.getCursorStack();
                    boolean ok = !d.isEmpty() && d.getCount() == clicks + 1 && c.getCount() == total - clicks - 1;
                    if (ok) {
                        clicks++;
                    }
                    if (!ok && timeout <= 1) {
                        abort(mc, "Сервер дозволив покласти в слот лише " + d.getCount() + " з " + need
                                + " шт. (у курсорі " + c.getCount() + ", стак було " + total + "). Зупинено.");
                        return 2;
                    }
                    return waitOrFail(mc, ok, R_PUT, "Сервер не підтвердив розділення стака. Зупинено.");
                }
                case R_BACK: {
                    rawClick(mc, sl(src), 0, SlotActionType.PICKUP);
                    timeout = 60;
                    phase = R_BACK_WAIT;
                    return 0;
                }
                case R_BACK_WAIT: {
                    boolean ok = mc.player.inventory.getCursorStack().isEmpty()
                            && mc.player.inventory.getStack(src).getCount() == total - need
                            && mc.player.inventory.getStack(dst).getCount() == need;
                    return waitOrFail(mc, ok, EQUIP, "Сервер не повернув залишок стака (джерело "
                            + mc.player.inventory.getStack(src).getCount() + ", лот "
                            + mc.player.inventory.getStack(dst).getCount() + ", потрібно "
                            + (total - need) + " і " + need + "). Зупинено.");
                }

                // ----- взяти предмет у руку
                case EQUIP: {
                    if (dst < 9) {
                        mc.player.inventory.selectedSlot = dst;
                        phase = SEND;
                        w = Math.max(1, t(6));
                        return 0;
                    }
                    if (raw) {
                        rawClick(mc, sl(dst), target, SlotActionType.SWAP);
                        timeout = 60;
                        phase = R_SWAP_WAIT;
                        return 0;
                    }
                    mc.interactionManager.clickSlot(hid, sl(dst), target, SlotActionType.SWAP, mc.player);
                    mc.player.inventory.selectedSlot = target;
                    phase = SEND;
                    w = Math.max(1, t(6));
                    return 0;
                }
                case R_SWAP_WAIT: {
                    ItemStack hnd = mc.player.inventory.getStack(target);
                    boolean ok = !hnd.isEmpty() && same(hnd, proto) && hnd.getCount() == held;
                    if (ok) {
                        mc.player.inventory.selectedSlot = target;
                        w = Math.max(1, t(6));
                        phase = SEND;
                        return 0;
                    }
                    if (--timeout <= 0) {
                        abort(mc, "Сервер не поклав предмет у руку. Зупинено.");
                        return 2;
                    }
                    return 0;
                }

                // ----- команда продажу
                case SEND: {
                    if (--w > 0) {
                        return 0;
                    }
                    String cmd = cfg.sellCommand.replace("{price}", cfg.price)
                            .replace("{count}", String.valueOf(cfg.perLot));
                    mc.player.sendChatMessage("/" + cmd);
                    phase = DONE;
                    // чекаємо, щоб сервер встиг прибрати предмет з інвентарю
                    w = Math.max(Math.max(1, t(10)), 2 + pingTicks(mc));
                    return 0;
                }
                default: {
                    return --w <= 0 ? 2 : 0;
                }
            }
        }
    }

    static class Wait implements Step {
        int left;

        Wait(int ticks) {
            this.left = ticks;
        }

        @Override
        public boolean tick(MinecraftClient mc) {
            return --this.left <= 0;
        }
    }

    static class Cmd implements Step {
        String c;
        boolean sent;
        int w = t(10);

        Cmd(String c) {
            this.c = c;
        }

        @Override
        public boolean tick(MinecraftClient mc) {
            if (!this.sent) {
                String s = this.c.replace("{price}", cfg.price).replace("{count}", String.valueOf(cfg.perLot));
                mc.player.sendChatMessage("/" + s);
                this.sent = true;
            }
            return --this.w <= 0;
        }
    }

    static class WaitScreen implements Step {
        int timeout = 120;

        @Override
        public boolean tick(MinecraftClient mc) {
            if (mc.currentScreen instanceof GenericContainerScreen) {
                return true;
            }
            if (--this.timeout <= 0) {
                finish(mc, "Меню /ah не відкрилося. Зупинено.");
            }
            return false;
        }
    }

    static class Click implements Step {
        int slot;
        int phase = 0;
        int w = t(10);

        Click(int slot) {
            this.slot = slot;
        }

        @Override
        public boolean tick(MinecraftClient mc) {
            if (!(mc.currentScreen instanceof GenericContainerScreen)) {
                return false;
            }
            GenericContainerScreenHandler h = ((GenericContainerScreen) mc.currentScreen).getScreenHandler();
            if (this.slot >= h.slots.size()) {
                return true;
            }
            if (this.phase == 0) {
                int[] o = guiOrigin(mc, h);
                double scale = mc.getWindow().getScaleFactor();
                moveTo((o[0] + h.slots.get(this.slot).x + 8) * scale, (o[1] + h.slots.get(this.slot).y + 8) * scale);
                this.phase = 1;
            } else if (this.phase == 1 && !moving) {
                mc.interactionManager.clickSlot(h.syncId, this.slot, 0, SlotActionType.PICKUP, mc.player);
                this.phase = 2;
            } else if (this.phase == 2) {
                return --this.w <= 0;
            }
            return false;
        }
    }

    static class CancelAll implements Step {
        int phase = 0;
        int w = 10;
        int slot = -1;
        int done = 0;

        int findLot(GenericContainerScreenHandler h) {
            int limit = h.getRows() * 9 - 9;
            for (int i = 0; i < limit && i < h.slots.size(); ++i) {
                ItemStack st = h.slots.get(i).getStack();
                if (st.isEmpty()) {
                    continue;
                }
                String id = Registry.ITEM.getId(st.getItem()).getPath();
                if (id.contains("pane") || id.contains("barrier")) {
                    continue;
                }
                return i;
            }
            return -1;
        }

        @Override
        public boolean tick(MinecraftClient mc) {
            if (!(mc.currentScreen instanceof GenericContainerScreen)) {
                return false;
            }
            GenericContainerScreenHandler h = ((GenericContainerScreen) mc.currentScreen).getScreenHandler();
            if (this.phase == 0) {
                if (this.done >= cfg.lots) {
                    return true;
                }
                this.slot = this.findLot(h);
                if (this.slot < 0) {
                    return true;
                }
                int[] o = guiOrigin(mc, h);
                double scale = mc.getWindow().getScaleFactor();
                moveTo((o[0] + h.slots.get(this.slot).x + 8) * scale, (o[1] + h.slots.get(this.slot).y + 8) * scale);
                this.phase = 1;
            } else if (this.phase == 1 && !moving) {
                mc.interactionManager.clickSlot(h.syncId, this.slot, 0, SlotActionType.PICKUP, mc.player);
                ++this.done;
                this.w = t(10) + rnd.nextInt(t(6) + 1);
                this.phase = 2;
            } else if (this.phase == 2 && --this.w <= 0) {
                this.phase = 0;
            }
            return false;
        }
    }

    static class Close implements Step {
        @Override
        public boolean tick(MinecraftClient mc) {
            mc.player.closeHandledScreen();
            return true;
        }
    }

    // ================================================================ меню (стиль Minecraft)
    public static class CfgScreen extends Screen {
        final Screen parent;
        TextFieldWidget fPrice;
        TextFieldWidget fPer;
        TextFieldWidget fLots;
        TextFieldWidget fTimer;
        TextFieldWidget fCmd;
        TextFieldWidget fMy;
        ButtonWidget bSpeed;
        ButtonWidget[] tog = new ButtonWidget[9];
        ButtonWidget bShow;
        ButtonWidget bAfk;
        boolean afk;
        int mask;
        int speed;
        boolean show;

        // розкладка
        int rowH = 20;
        int top;
        int panelH;
        int y0;
        static final int CTRL_H = 18;
        static final int CTRL_W = 160;
        static final String[] LABELS = {
                "Ціна", "Предметів у лоті", "Лотів за раунд", "Пауза до скасування (с)",
                "Команда продажу", "Слот «Мої лоти»", "Швидкість роботи", "Предмети (слоти 1–9)",
                "Номери слотів у /ah", "Рух камери (анти-АФК)"
        };

        public CfgScreen(Screen parent) {
            super(new LiteralText("AutoAH"));
            this.parent = parent;
        }

        static int num(String s, int def) {
            try {
                return Integer.parseInt(s.trim());
            } catch (Exception e) {
                return def;
            }
        }

        int ctrlX() {
            return this.width / 2 - 5;
        }

        int ctrlY(int row) {
            return this.y0 + row * this.rowH + (this.rowH - CTRL_H) / 2;
        }

        TextFieldWidget field(int row, String value, int max) {
            TextFieldWidget f = new TextFieldWidget(this.textRenderer, ctrlX(), ctrlY(row), CTRL_W, CTRL_H,
                    new LiteralText(""));
            f.setMaxLength(max);
            f.setText(value);
            this.addButton(f);
            return f;
        }

        String speedLabel() {
            return SPEED_NAMES[this.speed - 1];
        }

        String afkLabel() {
            return this.afk ? "Увімкнено" : "Вимкнено";
        }

        String showLabel() {
            return this.show ? "Увімкнено" : "Вимкнено";
        }

        @Override
        protected void init() {
            loadCfg();
            this.mask = slotMask();
            this.speed = Math.max(1, Math.min(MAX_SPEED, cfg.speed));
            this.show = cfg.showSlots;
            this.afk = cfg.antiAfk;

            this.rowH = Math.max(18, Math.min(22, (this.height - 112) / LABELS.length));
            this.panelH = 98 + LABELS.length * this.rowH;
            this.top = Math.max(2, (this.height - this.panelH) / 2);
            this.y0 = this.top + 26;

            this.fPrice = this.field(0, cfg.price, 20);
            this.fPer = this.field(1, String.valueOf(cfg.perLot), 4);
            this.fLots = this.field(2, String.valueOf(cfg.lots), 3);
            this.fTimer = this.field(3, String.valueOf(cfg.timerSeconds), 6);
            this.fCmd = this.field(4, cfg.sellCommand, 60);
            this.fMy = this.field(5, String.valueOf(cfg.myLotsSlot), 4);

            this.bSpeed = this.addButton(new ButtonWidget(ctrlX(), ctrlY(6), CTRL_W, CTRL_H,
                    new LiteralText(speedLabel()), b -> {
                        this.speed = this.speed % MAX_SPEED + 1;
                        b.setMessage(new LiteralText(speedLabel()));
                    }));
            for (int k = 0; k < 9; k++) {
                final int bit = 1 << k;
                this.tog[k] = this.addButton(new ButtonWidget(ctrlX() + 4 + k * 17, ctrlY(7), 16, CTRL_H,
                        new LiteralText(""), b -> this.mask ^= bit));
            }
            this.bShow = this.addButton(new ButtonWidget(ctrlX(), ctrlY(8), CTRL_W, CTRL_H,
                    new LiteralText(showLabel()), b -> {
                        this.show = !this.show;
                        b.setMessage(new LiteralText(showLabel()));
                    }));

            this.bAfk = this.addButton(new ButtonWidget(ctrlX(), ctrlY(9), CTRL_W, CTRL_H,
                    new LiteralText(afkLabel()), b -> {
                        this.afk = !this.afk;
                        b.setMessage(new LiteralText(afkLabel()));
                    }));

            int cx = this.width / 2;
            int y = this.y0 + LABELS.length * this.rowH + 6;
            this.addButton(new ButtonWidget(cx - 155, y, 100, 20, new LiteralText("Зберегти"), b -> {
                this.apply();
                saveCfg();
            }));
            this.addButton(new ButtonWidget(cx - 50, y, 100, 20, new LiteralText(running ? "Стоп" : "Старт"), b -> {
                this.apply();
                saveCfg();
                setRunning(this.client, !running);
                this.client.openScreen(null);
            }));
            this.addButton(new ButtonWidget(cx + 55, y, 100, 20, new LiteralText("Закрити"), b -> {
                this.apply();
                saveCfg();
                this.client.openScreen(this.parent);
            }));
        }

        void apply() {
            cfg.price = this.fPrice.getText().trim();
            cfg.perLot = Math.max(1, num(this.fPer.getText(), 1));
            cfg.lots = Math.max(1, num(this.fLots.getText(), 5));
            cfg.timerSeconds = Math.max(1, num(this.fTimer.getText(), 300));
            cfg.sellCommand = this.fCmd.getText().trim();
            cfg.myLotsSlot = Math.max(0, num(this.fMy.getText(), 49));
            cfg.speed = this.speed;
            if (this.mask == 0) {
                this.mask = 1;
            }
            cfg.slotMask = this.mask;
            cfg.hotbarSlot = Integer.numberOfTrailingZeros(this.mask) + 1;
            cfg.showSlots = this.show;
            cfg.antiAfk = this.afk;
        }

        @Override
        public void onClose() {
            this.apply();
            saveCfg();
            this.client.openScreen(this.parent);
        }

        @Override
        public void tick() {
            this.fPrice.tick();
            this.fPer.tick();
            this.fLots.tick();
            this.fTimer.tick();
            this.fCmd.tick();
            this.fMy.tick();
        }

        /** Панель у стилі інвентарю Minecraft: сіра з об'ємною рамкою. */
        void panel(MatrixStack m, int x1, int y1, int x2, int y2) {
            fill(m, x1, y1, x2, y2, 0xFF000000);
            fill(m, x1 + 1, y1 + 1, x2 - 1, y2 - 1, 0xFFC6C6C6);
            fill(m, x1 + 1, y1 + 1, x2 - 2, y1 + 3, 0xFFFFFFFF);
            fill(m, x1 + 1, y1 + 1, x1 + 3, y2 - 2, 0xFFFFFFFF);
            fill(m, x1 + 2, y2 - 3, x2 - 1, y2 - 1, 0xFF555555);
            fill(m, x2 - 3, y1 + 2, x2 - 1, y2 - 1, 0xFF555555);
        }

        void centered(MatrixStack m, String s, int cx, int y, int color) {
            this.textRenderer.draw(m, s, cx - this.textRenderer.getWidth(s) / 2.0f, y, color);
        }

        @Override
        public void render(MatrixStack m, int mx, int my, float d) {
            this.renderBackground(m);
            int cx = this.width / 2;
            int left = cx - 170;
            int right = cx + 170;
            this.panel(m, left, this.top, right, this.top + this.panelH);

            // заголовок
            m.push();
            m.translate(cx, this.top + 8, 0.0);
            m.scale(1.5f, 1.5f, 1.0f);
            this.textRenderer.draw(m, "AutoAH", -this.textRenderer.getWidth("AutoAH") / 2.0f, 0.0f, 0x404040);
            m.pop();

            // рядки з підписами
            for (int i = 0; i < LABELS.length; ++i) {
                int ry = this.y0 + i * this.rowH;
                if (i % 2 == 0) {
                    fill(m, left + 6, ry, right - 6, ry + this.rowH, 0x12000000);
                }
                this.textRenderer.draw(m, LABELS[i], left + 10, ry + (this.rowH - 8) / 2.0f, 0x404040);
            }

            int sy = this.y0 + LABELS.length * this.rowH + 32;
            this.centered(m, running ? "Статус: ПРАЦЮЄ" : "Статус: ЗУПИНЕНО", cx, sy, running ? 0x00AA00 : 0xAA0000);
            this.centered(m, "З {count} у команді стак не ділиться.", cx, sy + 11, 0x555555);
            this.centered(m, "Без {count} — стак ділиться автоматично.", cx, sy + 22, 0x555555);

            super.render(m, mx, my, d);

            // іконки предметів хотбару; обрані підсвічені зеленим рамкою, необрані притемнені
            for (int k = 0; k < 9; k++) {
                int bx = ctrlX() + 4 + k * 17;
                int by = ctrlY(7);
                ItemStack st = this.client.player.inventory.getStack(k);
                if (!st.isEmpty()) {
                    this.client.getItemRenderer().renderGuiItemIcon(st, bx, by + 1);
                }
                m.push();
                m.translate(0.0, 0.0, 300.0);
                if ((this.mask & (1 << k)) != 0) {
                    fill(m, bx, by, bx + 16, by + 2, 0xFF55FF55);
                    fill(m, bx, by + CTRL_H - 2, bx + 16, by + CTRL_H, 0xFF55FF55);
                    fill(m, bx, by, bx + 2, by + CTRL_H, 0xFF55FF55);
                    fill(m, bx + 14, by, bx + 16, by + CTRL_H, 0xFF55FF55);
                } else {
                    fill(m, bx, by, bx + 16, by + CTRL_H, 0x99000000);
                }
                m.pop();
            }
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
}
