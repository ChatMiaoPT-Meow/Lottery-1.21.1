package com.chatmiao.lottery.item;

import com.chatmiao.lottery.ModRegistry;
import com.chatmiao.lottery.data.VoucherNames;
import com.chatmiao.lottery.logic.HubService;
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

/**
 * A gacha voucher (卡券). Carries only its voucher type, so all vouchers of one type stack. Spent in the gacha
 * screen; right-clicking one opens that screen.
 */
public class VoucherItem extends Item {
    public VoucherItem(Properties properties) {
        super(properties);
    }

    public static ItemStack create(int voucherId, int count) {
        ItemStack stack = new ItemStack(ModRegistry.VOUCHER.get(), count);
        stack.set(ModRegistry.VOUCHER_TYPE.get(), voucherId);
        return stack;
    }

    /** @return the voucher type, or 0 if the stack is not a valid voucher. */
    public static int typeOf(ItemStack stack) {
        if (!stack.is(ModRegistry.VOUCHER.get())) return 0;
        Integer id = stack.get(ModRegistry.VOUCHER_TYPE.get());
        return id == null ? 0 : id;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer sp) HubService.open(sp);
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }

    @Override
    public Component getName(ItemStack stack) {
        int id = typeOf(stack);
        if (id <= 0) return Component.translatable("item.lottery.voucher.invalid");
        return Component.translatable("item.lottery.voucher.named", VoucherNames.name(id));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        if (typeOf(stack) <= 0) {
            tooltip.add(Component.translatable("tooltip.lottery.voucher.invalid").withStyle(ChatFormatting.RED));
            return;
        }
        tooltip.add(Component.translatable("tooltip.lottery.voucher.use").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.lottery.voucher.hint").withStyle(ChatFormatting.DARK_GRAY));
    }
}
