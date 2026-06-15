package org.vovochka.fun.secret;

import org.vovochka.fun.secret.Secret;

import java.io.*;
import java.nio.file.*;

/**
 * Каждый запущенный клиент получает уникальный ID (1, 2, 3...)
 * Динамически определяется через lock-файлы при каждом старте.
 * * Первый запущенный клиент = ID 1 (банк)
 * Второй = ID 2 (твинк)
 */
public class ClientIdentity {

    private static int clientId = -1;

    /**
     * Получить или создать ID этого клиента
     */
    public static int getOrCreate() {
        if (clientId != -1) return clientId;

        try {
            // Динамически ищем свободный слот, чтобы избежать конфликтов токенов Telegram
            clientId = findFreeId();
            Secret.LOGGER.info("[Identity] Client ID dynamically assigned: {}", clientId);
        } catch (Exception e) {
            clientId = 1;
            Secret.LOGGER.error("[Identity] Error finding free ID, fallback to 1: {}", e.getMessage());
        }

        return clientId;
    }

    /**
     * Найти свободный ID по lock-файлам процессов
     */
    private static int findFreeId() {
        try { Files.createDirectories(Paths.get("config")); } catch (IOException ignored) {}

        for (int id = 1; id <= 10; id++) {
            Path lockFile = Paths.get("config", "secret_lock_" + id + ".tmp");
            if (!Files.exists(lockFile)) {
                try {
                    Files.writeString(lockFile, String.valueOf(ProcessHandle.current().pid()));
                    lockFile.toFile().deleteOnExit();
                    return id;
                } catch (IOException ignored) {}
            } else {
                // Проверяем, жив ли процесс, удерживающий этот ID
                try {
                    String pidStr = Files.readString(lockFile).trim();
                    long pid = Long.parseLong(pidStr);
                    if (!ProcessHandle.of(pid).isPresent()) {
                        // Процесс мертв — забираем ID себе
                        Files.writeString(lockFile, String.valueOf(ProcessHandle.current().pid()));
                        lockFile.toFile().deleteOnExit();
                        return id;
                    }
                } catch (Exception ignored) {}
            }
        }
        return 1; // fallback
    }

    /**
     * Проверить является ли команда для этого клиента.
     */
    public static boolean isCommandForMe(String command) {
        if (command == null) return false;
        command = command.toLowerCase().trim();

        if (command.equals("/stop") ||
                command.equals("/status") ||
                command.equals("/nicks") ||
                command.equals("/ip") ||
                command.equals("/help") ||
                command.equals("/start") ||
                command.matches("\\d+")) {  // капча
            return true;
        }

        if (command.length() > 1 && Character.isDigit(command.charAt(1))) {
            int targetId = Character.getNumericValue(command.charAt(1));
            return targetId == clientId;
        }

        return true;
    }

    public static String stripIdPrefix(String command) {
        if (command == null) return "";
        if (command.length() > 2 && command.charAt(0) == '/' &&
                Character.isDigit(command.charAt(1))) {
            return "/" + command.substring(2);
        }
        return command;
    }

    public static int getId() { return clientId; }

    public static String getLabel() {
        return "Клиент #" + clientId;
    }
}