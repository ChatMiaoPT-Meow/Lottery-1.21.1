package com.chatmiao.lottery.item;

import com.chatmiao.lottery.ModRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/** A scratched, winning ticket. Unique win id; redeem it at a Lottery Station. Never stacks. */
public class WinningTicketItem extends Item {
    public WinningTicketItem(Properties properties) {
        super(properties);
    }

    public static ItemStack create(WinTicketData data) {
        ItemStack stack = new ItemStack(ModRegistry.WINNING_TICKET.get());
        stack.set(ModRegistry.WIN_TICKET.get(), data);
        return stack;
    }

    public static WinTicketData dataOf(ItemStack stack) {
        if (!stack.is(ModRegistry.WINNING_TICKET.get())) return null;
        return stack.get(ModRegistry.WIN_TICKET.get());
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public Component getName(ItemStack stack) {
        WinTicketData data = dataOf(stack);
        if (data == null) return Component.translatable("item.lottery.winning_ticket");
        return Component.translatable("item.lottery.winning_ticket.named", data.typeName());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        WinTicketData data = dataOf(stack);
        if (data == null) {
            tooltip.add(Component.translatable("tooltip.lottery.ticket.invalid").withStyle(ChatFormatting.RED));
            return;
        }
        tooltip.add(Component.translatable("tooltip.lottery.win.prize", data.prize()).withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.translatable("tooltip.lottery.win.winner", data.winner()).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.lottery.win.id", data.winId()).withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("tooltip.lottery.win.hint").withStyle(ChatFormatting.YELLOW));
    }
}
