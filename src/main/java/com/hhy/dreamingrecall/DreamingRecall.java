package com.hhy.dreamingrecall;

import com.hhy.dreamingrecall.config.DreamingRecallConfig;
import com.hhy.dreamingrecall.config.DreamingRecallClientConfig;
import com.hhy.dreamingrecall.network.DreamingRecallNetwork;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

@Mod(DreamingRecall.MOD_ID)
public final class DreamingRecall {
    public static final String MOD_ID = "dreamingrecall";
    public static final String VERSION = "0.1.0-alpha.1";
    public static final Logger LOGGER = LogUtils.getLogger();

    public DreamingRecall(IEventBus modBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, DreamingRecallConfig.SPEC);
        modContainer.registerConfig(ModConfig.Type.CLIENT, DreamingRecallClientConfig.SPEC);
        DreamingRecallNetwork.register(modBus);
        LOGGER.info("DreamingRecall is loading");
    }
}
