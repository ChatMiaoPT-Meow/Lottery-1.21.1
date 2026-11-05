package com.chatmiao.lottery.block;

import com.chatmiao.lottery.LotteryConfig;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * 刮奖台. Interaction is handled in {@code CommonEvents} (so that sneak + right-click with a ticket in
 * hand reaches the block). {@link #HAS_TICKET} switches the top texture.
 */
public class ScratchTableBlock extends Block implements EntityBlock {
    public static final MapCodec<ScratchTableBlock> CODEC = simpleCodec(ScratchTableBlock::new);
    public static final BooleanProperty HAS_TICKET = BooleanProperty.create("has_ticket");

    public ScratchTableBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(HAS_TICKET, false));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HAS_TICKET);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ScratchTableBlockEntity(pos, state);
    }

    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        if (!player.hasPermissions(LotteryConfig.opLevel())) return 0.0F;
        return super.getDestroyProgress(state, player, level, pos);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof ScratchTableBlockEntity table) {
            table.dropContents(level, pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
