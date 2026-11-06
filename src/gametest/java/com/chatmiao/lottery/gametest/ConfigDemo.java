package com.chatmiao.lottery.gametest;

import com.chatmiao.lottery.LotteryMod;
import com.chatmiao.lottery.client.ClientEvents;
import com.chatmiao.lottery.client.GachaScreen;
import com.chatmiao.lottery.client.PlayScreen;
import com.chatmiao.lottery.client.PrizePoolScreen;
import com.chatmiao.lottery.client.TerminalMenuScreen;
import com.chatmiao.lottery.client.TerminalScreen;
import com.chatmiao.lottery.client.VoucherScreen;
import com.chatmiao.lottery.data.HubEntry;
import com.chatmiao.lottery.data.PrizePool;
import com.chatmiao.lottery.logic.TerminalService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Dev-only (-Dlottery.configdemo=true): an admin sets up a gacha pool from scratch through the real GUIs and a
 * player pulls a ten-pull from it, against the real integrated server. A drawn cursor moves and clicks, text is
 * typed character by character and a caption names each step; every 2nd tick is saved as screenshots/cfg_*.
 */
@EventBusSubscriber(modid = LotteryMod.MODID, value = Dist.CLIENT)
public final class ConfigDemo {
    private static final boolean ON = Boolean.getBoolean("lottery.configdemo");

    /** A scripted action; returns how many ticks to wait before the next one. */
    private interface Step {
        int run(Minecraft mc);
    }

    private static final Deque<Step> STEPS = new ArrayDeque<>();
    private static int ticks, nextAt, frame;
    private static boolean recording, scripted;
    private static float curX = 300, curY = 200, fromX, fromY, toX, toY;
    private static int moveStart, moveDur = 1, clickAt = -100;
    private static String caption = "";
    /** A waitFor step currently blocking (condition + give-up tick). */
    private static BooleanSupplier waiting;
    private static int waitLimit;

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        if (!ON) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        ticks++;
        if (!scripted && ticks == 40) {
            script();
            scripted = true;
            nextAt = ticks;
        }
        // cursor animation (ease in-out), and keep the real mouse on it so hover states follow
        float p = Mth.clamp((ticks - moveStart) / (float) moveDur, 0, 1);
        float e = p * p * (3 - 2 * p);
        curX = fromX + (toX - fromX) * e;
        curY = fromY + (toY - fromY) * e;
        setMouse(mc, curX, curY);

        if (waiting != null) {
            if (waiting.getAsBoolean() || ticks >= waitLimit) waiting = null;
        } else if (scripted && ticks >= nextAt && !STEPS.isEmpty()) {
            nextAt = ticks + STEPS.poll().run(mc);
        }
        if (recording && ticks % 2 == 0) {
            String name = String.format("cfg_%04d", frame++);
            Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), msg -> {});
        }
    }

    /** Cursor, click ripple and caption, drawn over every screen. */
    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Post event) {
        if (!ON || !scripted) return;
        GuiGraphics g = event.getGuiGraphics();
        Screen s = event.getScreen();
        g.pose().pushPose();
        g.pose().translate(0, 0, 1000);
        if (!caption.isEmpty()) {
            var font = Minecraft.getInstance().font;
            // in the strip above the windows, so it never covers the UI being shown
            int w = font.width(caption) + 16, x = (s.width - w) / 2;
            g.fill(x, 0, x + w, 11, 0xE0101018);
            g.fill(x, 0, x + 2, 11, 0xFFFFC94A);
            g.drawString(font, caption, x + 9, 2, 0xFFFFFFFF, false);
        }
        int age = ticks - clickAt;
        if (age >= 0 && age < 8) {
            int r = 3 + age * 2, a = (int) ((1 - age / 8F) * 220);
            g.renderOutline((int) curX - r, (int) curY - r, r * 2, r * 2, (a << 24) | 0xFFC94A);
        }
        drawCursor(g, (int) curX, (int) curY);
        g.pose().popPose();
    }

    private static void drawCursor(GuiGraphics g, int x, int y) {
        // a small arrow: black outline, white fill
        int[] widths = {1, 2, 3, 4, 5, 6, 7, 8, 5, 5, 3, 2};
        for (int row = 0; row < widths.length; row++) g.fill(x - 1, y + row - 1, x + widths[row] + 1, y + row + 1, 0xFF000000);
        for (int row = 0; row < widths.length - 1; row++) g.fill(x, y + row, x + widths[row] - 1, y + row + 1, 0xFFFFFFFF);
    }

    private static void setMouse(Minecraft mc, float x, float y) {
        double scale = mc.getWindow().getGuiScale();
        try {
            for (String n : new String[]{"xpos", "ypos"}) {
                var f = net.minecraft.client.MouseHandler.class.getDeclaredField(n);
                f.setAccessible(true);
                f.setDouble(mc.mouseHandler, (n.equals("xpos") ? x : y) * scale);
            }
        } catch (ReflectiveOperationException ignored) {
        }
    }

    // ------------------------------------------------------------------ step builders

    private static void add(Step s) {
        STEPS.add(s);
    }

    private static void say(String text) {
        add(mc -> {
            caption = text;
            LotteryMod.LOGGER.info("config demo caption frame={} {}", frame, text);
            return 4;
        });
    }

    private static void wait(int t) {
        add(mc -> t);
    }

    private static void waitFor(BooleanSupplier cond, int timeout) {
        add(mc -> {
            waiting = cond;
            waitLimit = ticks + timeout;
            return 1;
        });
    }

    private static void moveTo(Supplier<float[]> target) {
        add(mc -> {
            float[] t = target.get();
            if (t == null) return 1;
            fromX = curX;
            fromY = curY;
            toX = t[0];
            toY = t[1];
            moveStart = ticks;
            moveDur = Mth.clamp((int) (Math.hypot(toX - fromX, toY - fromY) / 18) + 4, 5, 14);
            return moveDur + 2;
        });
    }

    private static void click() {
        add(mc -> {
            Screen s = mc.screen;
            if (s != null) {
                s.mouseClicked(curX, curY, 0);
                s.mouseReleased(curX, curY, 0);
            }
            clickAt = ticks;
            return 6;
        });
    }

    private static void clickOn(Supplier<float[]> target) {
        moveTo(target);
        click();
    }

    /** Click a text box, clear it and type the text one character per tick. */
    private static void type(String field, String text) {
        clickOn(() -> center(widget(field)));
        add(mc -> {
            EditBox box = (EditBox) widget(field);
            if (box != null) {
                mc.screen.setFocused(box);
                box.setValue("");
            }
            return 2;
        });
        for (char c : text.toCharArray()) {
            add(mc -> {
                EditBox box = (EditBox) widget(field);
                if (box != null) box.charTyped(c, 0);
                return 2;
            });
        }
        wait(4);
    }

    // ------------------------------------------------------------------ targets

    private static Object field(Object o, String name) {
        try {
            var f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(o);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static int intField(String name) {
        Object v = field(Minecraft.getInstance().screen, name);
        return v instanceof Integer i ? i : 0;
    }

    private static AbstractWidget widget(String name) {
        Screen s = Minecraft.getInstance().screen;
        return s == null ? null : (AbstractWidget) field(s, name);
    }

    private static float[] center(AbstractWidget w) {
        return w == null ? null : new float[]{w.getX() + w.getWidth() / 2F, w.getY() + w.getHeight() / 2F};
    }

    private static Supplier<float[]> button(String key, Object... args) {
        return () -> {
            Screen s = Minecraft.getInstance().screen;
            if (s == null) return null;
            String label = Component.translatable(key, args).getString();
            for (var c : s.children()) {
                if (c instanceof Button b && b.visible && b.getMessage().getString().equals(label)) return center(b);
            }
            LotteryMod.LOGGER.warn("config demo: no button {}", label);
            return null;
        };
    }

    private static Supplier<float[]> widgetAt(String name) {
        return () -> center(widget(name));
    }

    /** One arrow ("prev"/"next") of a ◀ value ▶ stepper field. */
    private static Supplier<float[]> stepper(String name, String arrow) {
        return () -> {
            Screen s = Minecraft.getInstance().screen;
            Object st = s == null ? null : field(s, name);
            return st == null ? null : center((AbstractWidget) field(st, arrow));
        };
    }

    /** A row of the "all pools" list in the 卡池设置 pools tab. */
    @SuppressWarnings("unchecked")
    private static Supplier<float[]> availRow(String name) {
        return () -> {
            List<PrizePool> pools = (List<PrizePool>) field(Minecraft.getInstance().screen, "prizePools");
            if (pools == null) return null;
            for (int i = 0; i < pools.size(); i++) {
                if (pools.get(i).name.equals(name)) return new float[]{intField("colBX") + 40, intField("availY") + i * 12 + 6};
            }
            return null;
        };
    }

    /** The terminal search result showing {@code item}. */
    @SuppressWarnings("unchecked")
    private static Supplier<float[]> result(Item item) {
        return () -> {
            List<ItemStack> results = (List<ItemStack>) field(Minecraft.getInstance().screen, "results");
            if (results == null) return null;
            int cols = intField("resultCols");
            for (int i = 0; i < results.size(); i++) {
                if (results.get(i).is(item)) {
                    return new float[]{intField("colBX") + (i % cols) * 18 + 9, intField("resultY") + (i / cols) * 18 + 9};
                }
            }
            return null;
        };
    }

    private static Supplier<float[]> swatch(int index) {
        return () -> new float[]{intField("rx") + 40 + index * 14 + 6, intField("top") + 64 + 6};
    }

    @SuppressWarnings("unchecked")
    private static Supplier<float[]> hubRow(String name) {
        return () -> {
            List<HubEntry> entries = (List<HubEntry>) field(Minecraft.getInstance().screen, "entries");
            if (entries == null) return null;
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).name.equals(name)) return new float[]{intField("left") + 60, intField("bodyTop") + i * 30 + 14};
            }
            return null;
        };
    }

    private static void clicks(Supplier<float[]> target, int n) {
        moveTo(target);
        for (int i = 0; i < n; i++) click();
    }

    // ------------------------------------------------------------------ the script

    private static void script() {
        Minecraft mc = Minecraft.getInstance();
        mc.options.hideGui = true;
        add(m -> {
            var server = m.getSingleplayerServer();
            server.execute(() -> {
                ServerPlayer sp = server.getPlayerList().getPlayer(m.player.getUUID());
                server.getPlayerList().op(sp.getGameProfile()); // the admin may use the terminal
                sp.getInventory().clearContent();
                TerminalService.openMenu(sp);
            });
            return 10;
        });
        add(m -> {
            recording = true;
            return 6;
        });

        say("① 右键彩票终端，进入「卡券设置」");
        waitFor(() -> mc.screen instanceof TerminalMenuScreen, 40);
        wait(10);
        clickOn(button("gui.lottery.menu.vouchers"));
        waitFor(() -> mc.screen instanceof VoucherScreen, 40);
        wait(6);

        say("② 新建卡券「星辉券」，选金色，保存");
        clickOn(button("gui.lottery.vouchers.new"));
        type("nameBox", "星辉券");
        clickOn(swatch(3));
        clickOn(button("gui.lottery.terminal.save"));
        wait(10);

        say("③ 生成 30 张卡券（也可以放到模块商店卖）");
        type("countBox", "30");
        clickOn(button("gui.lottery.terminal.generate"));
        wait(12);

        say("④ 返回菜单，进入「奖池设置」");
        clickOn(button("gui.lottery.vouchers.back"));
        waitFor(() -> mc.screen instanceof TerminalMenuScreen, 40);
        wait(8);
        clickOn(button("gui.lottery.menu.pools"));
        waitFor(() -> mc.screen instanceof PrizePoolScreen, 40);
        wait(6);

        say("⑤ 新建奖池「普通」：铁锭 ×8~16，保存");
        clickOn(button("gui.lottery.pools.new"));
        type("nameBox", "普通");
        type("searchBox", "铁锭");
        clickOn(result(Items.IRON_INGOT));
        type("minBox", "8");
        type("maxBox", "16");
        clickOn(button("gui.lottery.terminal.save"));
        wait(12);

        say("⑥ 奖池「史诗」：钻石。稀有度点过头了？点 ◀ 退回一格");
        clickOn(button("gui.lottery.pools.new"));
        type("nameBox", "史诗");
        clicks(stepper("tierStep", "next"), 4);
        wait(6);
        clickOn(stepper("tierStep", "prev"));
        type("searchBox", "钻石");
        clickOn(result(Items.DIAMOND));
        clickOn(button("gui.lottery.terminal.save"));
        wait(12);

        say("⑦ 奖池「神话」：下界之星。从「普通」点 ◀ 直接到「神话」");
        clickOn(button("gui.lottery.pools.new"));
        type("nameBox", "神话");
        clickOn(stepper("tierStep", "prev"));
        type("searchBox", "下界之星");
        clickOn(result(Items.NETHER_STAR));
        clickOn(button("gui.lottery.terminal.save"));
        wait(14);

        say("⑧ 返回菜单，进入「卡池设置」，新建「星辉十连」");
        clickOn(button("gui.lottery.menu.back"));
        waitFor(() -> mc.screen instanceof TerminalMenuScreen, 40);
        wait(8);
        clickOn(button("gui.lottery.menu.types"));
        waitFor(() -> mc.screen instanceof TerminalScreen, 40);
        wait(6);
        clickOn(button("gui.lottery.terminal.new"));
        type("nameBox", "星辉十连");
        type("emptyBox", "40");

        say("⑨ 奖池页签：点击加入三个奖池，分别设权重 60 / 8 / 2");
        clickOn(button("gui.lottery.terminal.tab_pools"));
        clickOn(availRow("普通"));
        type("slotWeightBox", "60");
        clickOn(availRow("史诗"));
        type("slotWeightBox", "8");
        clickOn(availRow("神话"));
        type("slotWeightBox", "2");
        wait(10);

        say("⑩ 玩法：卡牌抽卡，保底 50 抽必出神话，十连至少一个史诗");
        clickOn(button("gui.lottery.terminal.tab_style"));
        clicks(stepper("styleStep", "next"), 2);
        type("drawsBox", "10");
        type("pityCountBox", "50");
        clickOn(stepper("pityTierStep", "next"));
        clicks(stepper("guarTierStep", "next"), 4);
        wait(8);

        say("⑪ 上架：用星辉券，单抽 1 张、10 连 9 张");
        clickOn(button("gui.lottery.terminal.tab_hub"));
        clickOn(widgetAt("hubBtn"));
        clickOn(stepper("voucherStep", "next"));
        type("costSingleBox", "1");
        type("multiCountBox", "10");
        type("costMultiBox", "9");
        wait(10);

        say("⑫ 保存：卡池马上开放，不需要生成彩票");
        clickOn(button("gui.lottery.terminal.save"));
        wait(24);

        say("⑬ 玩家按 K 打开卡池，点「10 连」");
        add(m -> {
            m.setScreen(null);
            return 6;
        });
        add(m -> {
            net.minecraft.client.KeyMapping.click(ClientEvents.OPEN_HUB.getKey());
            return 4;
        });
        waitFor(() -> mc.screen instanceof GachaScreen, 40);
        wait(14);
        clickOn(hubRow("星辉十连"));
        wait(10);
        clickOn(button("gui.lottery.hub.pull_multi", 10, 9));
        add(m -> {
            caption = "";
            return 1;
        });
        // summon → cards are dealt → flip them all
        waitFor(() -> mc.screen instanceof PlayScreen, 200);
        wait(36);
        clickOn(button("gui.lottery.cards.flip_all"));
        wait(300);
        add(m -> {
            recording = false;
            return 10;
        });
        add(m -> {
            m.stop();
            return 100;
        });
    }

    private ConfigDemo() {}
}
