package com.chatmiao.lottery.block;

import com.chatmiao.lottery.ModRegistry;
import com.chatmiao.lottery.item.ScratchTicketItem;
import com.chatmiao.lottery.logic.ScratchRound;
import com.chatmiao.lottery.logic.ScratchService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Containers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

/**
 * State of one scratch table: who is using it, which ticket type, how many unscratched tickets are
 * loaded ({@link #stock}) and the ticket currently being scratched ({@link #round}, result already locked).
 */
public class ScratchTableBlockEntity extends BlockEntity {
    public UUID owner;
    public String ownerName = "";
    public int typeId;
    public int stock;
    public ScratchRound round;
    public int nextRoundId = 1;

    public ScratchTableBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistry.SCRATCH_TABLE_BE.get(), pos, state);
    }

    public boolean isEmpty() {
        return stock <= 0 && round == null;
    }

    /** Clears ownership when nothing is left, syncs the top texture and marks dirty. */
    public void changed() {
        if (isEmpty()) {
            owner = null;
            ownerName = "";
            typeId = 0;
            stock = 0;
        }
        setChanged();
        if (level != null && !level.isClientSide) {
            BlockState state = getBlockState();
            boolean has = !isEmpty();
            if (state.hasProperty(ScratchTableBlock.HAS_TICKET) && state.getValue(ScratchTableBlock.HAS_TICKET) != has) {
                level.setBlock(worldPosition, state.setValue(ScratchTableBlock.HAS_TICKET, has), 3);
            }
        }
    }

    /** Called when the block is broken: unscratched tickets drop, a locked winning ticket drops as won. */
    public void dropContents(Level level, BlockPos pos) {
        if (level.isClientSide) return;
        int left = stock;
        while (left > 0 && typeId > 0) {
            int n = Math.min(left, 64);
            Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5, ScratchTicketItem.create(typeId, n));
            left -= n;
        }
        if (round != null && round.won()) {
            Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5,
                    ScratchService.winningTicket(round, ownerName));
        }
        stock = 0;
        round = null;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (owner != null) tag.putUUID("owner", owner);
        tag.putString("ownerName", ownerName);
        tag.putInt("typeId", typeId);
        tag.putInt("stock", stock);
        tag.putInt("nextRoundId", nextRoundId);
        if (round != null) tag.put("round", round.save(registries));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        owner = tag.hasUUID("owner") ? tag.getUUID("owner") : null;
        ownerName = tag.getString("ownerName");
        typeId = tag.getInt("typeId");
        stock = tag.getInt("stock");
        nextRoundId = Math.max(1, tag.getInt("nextRoundId"));
        round = tag.contains("round") ? ScratchRound.load(tag.getCompound("round"), registries) : null;
    }
}
