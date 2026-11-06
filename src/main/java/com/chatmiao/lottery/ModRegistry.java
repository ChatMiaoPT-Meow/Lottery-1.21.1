package com.chatmiao.lottery;

import com.chatmiao.lottery.block.RedeemStationBlock;
import com.chatmiao.lottery.block.ScratchTableBlock;
import com.chatmiao.lottery.block.ScratchTableBlockEntity;
import com.chatmiao.lottery.item.LotteryTerminalItem;
import com.chatmiao.lottery.item.OpBlockItem;
import com.chatmiao.lottery.item.ScratchTicketItem;
import com.chatmiao.lottery.item.WinTicketData;
import com.chatmiao.lottery.item.WinningTicketItem;
import com.mojang.serialization.Codec;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModRegistry {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(LotteryMod.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(LotteryMod.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, LotteryMod.MODID);
    public static final DeferredRegister<DataComponentType<?>> COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, LotteryMod.MODID);
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, LotteryMod.MODID);

    // ---- data components ----
    /** Lottery type number on an unscratched ticket. Same number => identical stacks => stackable. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> TICKET_TYPE =
            COMPONENTS.register("ticket_type", () -> DataComponentType.<Integer>builder()
                    .persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT).build());
    /** Voucher type on a gacha voucher. Same type => identical stacks => stackable. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> VOUCHER_TYPE =
            COMPONENTS.register("voucher_type", () -> DataComponentType.<Integer>builder()
                    .persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT).build());
    /** Data on a winning ticket: its unique win id plus display info. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<WinTicketData>> WIN_TICKET =
            COMPONENTS.register("win_ticket", () -> DataComponentType.<WinTicketData>builder()
                    .persistent(WinTicketData.CODEC).networkSynchronized(WinTicketData.STREAM_CODEC).build());

    // ---- blocks ----
    private static BlockBehaviour.Properties stationProps(MapColor color) {
        // Effectively indestructible for non-OPs (see CommonEvents), immune to explosions and pistons.
        return BlockBehaviour.Properties.of().mapColor(color).strength(2.5F, 3600000.0F)
                .sound(SoundType.WOOD).pushReaction(PushReaction.BLOCK);
    }

    public static final DeferredBlock<ScratchTableBlock> SCRATCH_TABLE =
            BLOCKS.register("scratch_table", () -> new ScratchTableBlock(stationProps(MapColor.COLOR_GREEN).noOcclusion()));
    public static final DeferredBlock<RedeemStationBlock> REDEEM_STATION =
            BLOCKS.register("redeem_station", () -> new RedeemStationBlock(stationProps(MapColor.COLOR_RED).noOcclusion()
                    .lightLevel(s -> s.getValue(RedeemStationBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER ? 7 : 0)));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ScratchTableBlockEntity>> SCRATCH_TABLE_BE =
            BLOCK_ENTITIES.register("scratch_table", () ->
                    BlockEntityType.Builder.of(ScratchTableBlockEntity::new, SCRATCH_TABLE.get()).build(null));

    // ---- items ----
    public static final DeferredItem<LotteryTerminalItem> LOTTERY_TERMINAL = ITEMS.register("lottery_terminal",
            () -> new LotteryTerminalItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)));
    public static final DeferredItem<ScratchTicketItem> SCRATCH_TICKET = ITEMS.register("scratch_ticket",
            () -> new ScratchTicketItem(new Item.Properties().stacksTo(64)));
    public static final DeferredItem<WinningTicketItem> WINNING_TICKET = ITEMS.register("winning_ticket",
            () -> new WinningTicketItem(new Item.Properties().stacksTo(1).rarity(Rarity.RARE)));
    public static final DeferredItem<com.chatmiao.lottery.item.VoucherItem> VOUCHER = ITEMS.register("voucher",
            () -> new com.chatmiao.lottery.item.VoucherItem(new Item.Properties().stacksTo(64).rarity(Rarity.UNCOMMON)));
    public static final DeferredItem<OpBlockItem> SCRATCH_TABLE_ITEM = ITEMS.register("scratch_table",
            () -> new OpBlockItem(SCRATCH_TABLE.get(), new Item.Properties()));
    public static final DeferredItem<OpBlockItem> REDEEM_STATION_ITEM = ITEMS.register("redeem_station",
            () -> new OpBlockItem(REDEEM_STATION.get(), new Item.Properties().stacksTo(16)));

    // ---- creative tab (only shows items to operators with "operator items" enabled) ----
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("main", () ->
            CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.lottery"))
                    .icon(() -> new ItemStack(SCRATCH_TICKET.get()))
                    .displayItems((params, out) -> {
                        if (params.hasPermissions()) {
                            out.accept(LOTTERY_TERMINAL.get());
                            out.accept(SCRATCH_TABLE_ITEM.get());
                            out.accept(REDEEM_STATION_ITEM.get());
                        }
                    })
                    .build());

    static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
        COMPONENTS.register(bus);
        TABS.register(bus);
    }

    private ModRegistry() {}
}
