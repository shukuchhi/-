package org.vovochka.fun.secret.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.slot.SlotActionType;
import org.vovochka.fun.secret.Secret;

public class InventoryUtil {

    public static void clickSlot(HandledScreen<?> screen, int slot) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            try {
                var handler = screen.getScreenHandler();
                if (slot >= handler.slots.size()) {
                    Secret.LOGGER.warn("Slot {} OOB (size {})", slot, handler.slots.size());
                    return;
                }
                client.interactionManager.clickSlot(
                        handler.syncId, slot, 0,
                        SlotActionType.PICKUP, client.player
                );
                Secret.LOGGER.info("Clicked slot {}", slot);
            } catch (Exception e) {
                Secret.LOGGER.error("clickSlot {} error: {}", slot, e.getMessage());
            }
        });
    }
}