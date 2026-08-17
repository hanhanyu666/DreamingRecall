package com.hhy.dreamingrecall.client;

import com.hhy.dreamingrecall.DreamingRecall;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = DreamingRecall.MOD_ID, value = Dist.CLIENT)
public final class DreamingRecallClientModEvents {
    public static final KeyMapping OPEN_ARCHIVES = new KeyMapping(
            "key.dreamingrecall.open_archives",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F8,
            "key.categories.dreamingrecall"
    );
    public static final KeyMapping TOGGLE_CAMERA_CAPTURE = new KeyMapping(
            "key.dreamingrecall.toggle_camera_capture",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F9,
            "key.categories.dreamingrecall"
    );

    private DreamingRecallClientModEvents() {
    }

    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_ARCHIVES);
        event.register(TOGGLE_CAMERA_CAPTURE);
    }
}
