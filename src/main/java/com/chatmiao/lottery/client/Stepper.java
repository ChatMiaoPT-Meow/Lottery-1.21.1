package com.chatmiao.lottery.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;
import java.util.function.UnaryOperator;

/**
 * "◀ value ▶": a value picker that can go both ways, so clicking one step too far is undone with one click
 * instead of cycling all the way round. Clicking the value itself also steps forward.
 */
final class Stepper {
    private static final int ARROW_W = 12;
    final Button prev, label, next;

    /**
     * @param add   the screen's addRenderableWidget
     * @param step  called with -1 / +1
     * @param after called after every step (usually the screen's refresh)
     */
    Stepper(UnaryOperator<Button> add, int x, int y, int w, int h, IntConsumer step, Runnable after) {
        prev = add.apply(Button.builder(Component.literal("◀"), b -> { step.accept(-1); after.run(); })
                .bounds(x, y, ARROW_W, h).build());
        label = add.apply(Button.builder(Component.empty(), b -> { step.accept(1); after.run(); })
                .bounds(x + ARROW_W + 1, y, w - 2 * ARROW_W - 2, h).build());
        next = add.apply(Button.builder(Component.literal("▶"), b -> { step.accept(1); after.run(); })
                .bounds(x + w - ARROW_W, y, ARROW_W, h).build());
    }

    void setVisible(boolean v) {
        prev.visible = label.visible = next.visible = v;
    }

    void setMessage(Component text) {
        label.setMessage(text);
    }

    /** Wraps {@code value + delta} into [0, count). */
    static int wrap(int value, int delta, int count) {
        return Math.floorMod(value + delta, count);
    }
}
