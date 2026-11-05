package com.chatmiao.lottery.item;

import com.chatmiao.lottery.LotteryConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;

/** Block item that only operators may place. */
public class OpBlockItem extends BlockItem {
    public OpBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult place(BlockPlaceContext context) {
        Player player = context.getPlayer();
        if (player != null && !player.hasPermissions(LotteryConfig.opLevel())) {
            if (!context.getLevel().isClientSide) {
                player.displayClientMessage(Component.translatable("message.lottery.no_permission").withStyle(ChatFormatting.RED), true);
            }
            return InteractionResult.FAIL;
        }
        return super.place(context);
    }
}
