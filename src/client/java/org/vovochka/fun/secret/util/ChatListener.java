package org.vovochka.fun.secret.util;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import org.vovochka.fun.secret.Secret;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Централизованный слушатель чата.
 * Регистрируется ОДИН РАЗ при старте мода.
 * Боты подписываются/отписываются динамически.
 */
public class ChatListener {

    private static final List<Consumer<String>> handlers = new CopyOnWriteArrayList<>();
    private static boolean registered = false;

    public static void register() {
        if (registered) return;
        registered = true;

        // Обычные чат сообщения от игроков
        ClientReceiveMessageEvents.CHAT.register(
                (message, signed, sender, params, time) -> {
                    String raw = message.getString();
                    Secret.LOGGER.info("[ChatListener-CHAT] {}", raw);
                    dispatch(raw);
                }
        );

        // Системные сообщения - КАПЧА ИДЁТ СЮДА
        ClientReceiveMessageEvents.GAME.register(
                (message, overlay) -> {
                    String raw = message.getString();
                    Secret.LOGGER.info("[ChatListener-GAME overlay={}] {}", overlay, raw);
                    dispatch(raw);
                }
        );

        // Allow filter - дополнительный перехват
        ClientReceiveMessageEvents.ALLOW_GAME.register(
                (message, overlay) -> {
                    String raw = message.getString();
                    String clean = ChatHelper.stripColors(raw).toLowerCase();
                    if (clean.contains("цифры") || clean.contains("капч") ||
                            clean.contains("авторизу") || clean.contains("добро пожаловать")) {
                        Secret.LOGGER.info("[ChatListener-ALLOW] Important: {}", raw);
                        dispatch(raw);
                    }
                    return true;
                }
        );

        Secret.LOGGER.info("[ChatListener] All listeners registered!");
    }

    public static void addHandler(Consumer<String> handler) {
        if (!handlers.contains(handler)) {
            handlers.add(handler);
            Secret.LOGGER.info("[ChatListener] Handler added, total: {}", handlers.size());
        }
    }

    public static void removeHandler(Consumer<String> handler) {
        handlers.remove(handler);
        Secret.LOGGER.info("[ChatListener] Handler removed, total: {}", handlers.size());
    }

    private static void dispatch(String raw) {
        if (raw == null || raw.isEmpty()) return;
        for (Consumer<String> handler : handlers) {
            try {
                handler.accept(raw);
            } catch (Exception e) {
                Secret.LOGGER.error("[ChatListener] Handler error: {}", e.getMessage(), e);
            }
        }
    }
}