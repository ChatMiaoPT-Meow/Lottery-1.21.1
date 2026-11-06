package com.chatmiao.lottery.item;

import com.chatmiao.lottery.LotteryConfig;
import com.chatmiao.lottery.logic.TerminalService;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/** OP-only hand-held tool: configure lottery types and print tickets. */
public class LotteryTerminalItem extends Item {
    public LotteryTerminalItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer sp) {
            if (!sp.hasPermissions(LotteryConfig.opLevel())) {
                sp.displayClientMessage(Component.translatable("message.lottery.no_permission").withStyle(ChatFormatting.RED), true);
                return InteractionResultHolder.fail(stack);
            }
            TerminalService.openMenu(sp);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.lottery.terminal").withStyle(ChatFormatting.GRAY));
    }
}
