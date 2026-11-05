package com.chatmiao.lottery.block;

import com.chatmiao.lottery.LotteryConfig;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** 彩票站 (兑奖台). Stateless: everything it checks lives in the server ledger. */
public class RedeemStationBlock extends Block {
    public static final MapCodec<RedeemStationBlock> CODEC = simpleCodec(RedeemStationBlock::new);

    public RedeemStationBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        if (!player.hasPermissions(LotteryConfig.opLevel())) return 0.0F;
        return super.getDestroyProgress(state, player, level, pos);
    }
}
