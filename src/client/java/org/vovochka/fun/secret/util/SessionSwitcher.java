package org.vovochka.fun.secret.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.session.Session;
import org.vovochka.fun.secret.Secret;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

public class SessionSwitcher {

    private static Field cachedField = null;

    public static boolean switchTo(String username) {
        try {
            MinecraftClient client = MinecraftClient.getInstance();

            UUID offlineUuid = UUID.nameUUIDFromBytes(
                    ("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8)
            );

            Session newSession = new Session(
                    username,
                    offlineUuid,
                    "-",
                    Optional.empty(),
                    Optional.empty(),
                    Session.AccountType.LEGACY
            );

            Field field = getSessionField(client);
            if (field == null) {
                Secret.LOGGER.error("[Switch] Field not found!");
                return false;
            }

            field.set(client, newSession);

            String actual = client.getSession().getUsername();
            boolean success = actual.equals(username);
            Secret.LOGGER.info("[Switch] {} -> {} ({})",
                    username, actual, success ? "OK" : "FAIL");
            return success;

        } catch (Exception e) {
            Secret.LOGGER.error("[Switch] Error: {}", e.getMessage(), e);
            return false;
        }
    }

    public static String getCurrentNick() {
        try {
            return MinecraftClient.getInstance().getSession().getUsername();
        } catch (Exception e) {
            return "Unknown";
        }
    }

    private static Field getSessionField(MinecraftClient client) {
        if (cachedField != null) {
            try {
                cachedField.get(client);
                return cachedField;
            } catch (Exception e) {
                cachedField = null;
            }
        }

        // Ищем по типу Session во всей иерархии классов
        Class<?> clazz = MinecraftClient.class;
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                if (field.getType() == Session.class) {
                    field.setAccessible(true);
                    cachedField = field;
                    Secret.LOGGER.info("[Switch] Found field: '{}' in '{}'",
                            field.getName(), clazz.getSimpleName());
                    return field;
                }
            }
            clazz = clazz.getSuperclass();
        }

        // Лог всех полей для диагностики
        Secret.LOGGER.error("[Switch] NOT FOUND! All MinecraftClient fields:");
        for (Field f : MinecraftClient.class.getDeclaredFields()) {
            Secret.LOGGER.error("  {} : {}", f.getName(), f.getType().getSimpleName());
        }
        return null;
    }

    public static void clearCache() {
        cachedField = null;
    }
}