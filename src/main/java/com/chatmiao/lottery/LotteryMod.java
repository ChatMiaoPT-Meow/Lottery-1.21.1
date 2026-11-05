package com.chatmiao.lottery;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

@Mod(LotteryMod.MODID)
public class LotteryMod {
    public static final String MODID = "lottery";
    public static final Logger LOGGER = LogUtils.getLogger();

    public LotteryMod(IEventBus modBus, ModContainer container) {
        ModRegistry.register(modBus);
        container.registerConfig(ModConfig.Type.SERVER, LotteryConfig.SPEC);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
