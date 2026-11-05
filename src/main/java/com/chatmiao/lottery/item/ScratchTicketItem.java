package com.chatmiao.lottery.item;

import com.chatmiao.lottery.ModRegistry;
import com.chatmiao.lottery.data.TicketNames;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/** An unscratched ticket. Carries only its type number, so all tickets of one type stack together. */
public class ScratchTicketItem extends Item {
    public ScratchTicketItem(Properties properties) {
        super(properties);
    }

    public static ItemStack create(int typeId, int count) {
        ItemStack stack = new ItemStack(ModRegistry.SCRATCH_TICKET.get(), count);
        stack.set(ModRegistry.TICKET_TYPE.get(), typeId);
        return stack;
    }

    /** @return the type number, or 0 if the stack is not a valid ticket. */
    public static int typeOf(ItemStack stack) {
        if (!stack.is(ModRegistry.SCRATCH_TICKET.get())) return 0;
        Integer id = stack.get(ModRegistry.TICKET_TYPE.get());
        return id == null ? 0 : id;
    }

    @Override
    public Component getName(ItemStack stack) {
        int id = typeOf(stack);
        if (id <= 0) return Component.translatable("item.lottery.scratch_ticket.invalid");
        String name = TicketNames.get(id);
        return Component.translatable("item.lottery.scratch_ticket.named", id, name == null ? "?" : name);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        int id = typeOf(stack);
        if (id <= 0) {
            tooltip.add(Component.translatable("tooltip.lottery.ticket.invalid").withStyle(ChatFormatting.RED));
            return;
        }
        tooltip.add(Component.translatable("tooltip.lottery.ticket.type", id).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.lottery.ticket.hint").withStyle(ChatFormatting.DARK_GRAY));
    }
}
