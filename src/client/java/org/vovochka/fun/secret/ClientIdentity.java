package org.vovochka.fun.secret;

import org.vovochka.fun.secret.Secret;

import java.io.*;
import java.nio.file.*;

/**
 * Каждый запущенный клиент получает уникальный ID (1, 2, 3...)
 * ID сохраняется в файл config/secret_client_id.txt
 *
 * Первый запущенный клиент = ID 1 (банк)
 * Второй = ID 2 (твинк)
 */
public class ClientIdentity {

    private static final Path ID_FILE = Paths.get("config", "secret_client_id.txt");
    private static int clientId = -1;

    /**
     * Получить или создать ID этого клиента
     */
    public static int getOrCreate() {
        if (clientId != -1) return clientId;

        try {
            Files.createDirectories(ID_FILE.getParent());

            if (Files.exists(ID_FILE)) {
                String content = Files.readString(ID_FILE).trim();
                clientId = Integer.parseInt(content);
                Secret.LOGGER.info("[Identity] Client ID loaded: {}", clientId);
            } else {
                // Новый клиент - присваиваем ID
                // Смотрим какие ID уже заняты (через lock файлы)
                clientId = findFreeId();
                Files.writeString(ID_FILE, String.valueOf(clientId));
                Secret.LOGGER.info("[Identity] Client ID created: {}", clientId);
            }
        } catch (Exception e) {
            clientId = 1;
            Secret.LOGGER.error("[Identity] Error: {}", e.getMessage());
        }

        return clientId;
    }

    /**
     * Найти свободный ID
     */
    private static int findFreeId() {
        for (int id = 1; id <= 10; id++) {
            Path lockFile = Paths.get("config", "secret_lock_" + id + ".tmp");
            if (!Files.exists(lockFile)) {
                // Создаём lock файл
                try {
                    Files.writeString(lockFile, String.valueOf(ProcessHandle.current().pid()));
                    // Удаляем при закрытии JVM
                    lockFile.toFile().deleteOnExit();
                    return id;
                } catch (IOException ignored) {}
            } else {
                // Проверяем жив ли процесс с этим ID
                try {
                    String pidStr = Files.readString(lockFile).trim();
                    long pid = Long.parseLong(pidStr);
                    if (!ProcessHandle.of(pid).isPresent()) {
                        // Процесс мёртв - забираем ID
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
     *
     * Примеры:
     * "/bank"  → все клиенты слышат
     * "/1bank" → только клиент #1
     * "/2worker" → только клиент #2
     */
    public static boolean isCommandForMe(String command) {
        if (command == null) return false;
        command = command.toLowerCase().trim();

        // Универсальные команды (для всех клиентов)
        if (command.equals("/stop") ||
                command.equals("/status") ||
                command.equals("/nicks") ||
                command.equals("/ip") ||
                command.equals("/help") ||
                command.equals("/start") ||
                command.matches("\\d+")) {  // капча
            return true;
        }

        // Команды с префиксом ID: /1bank, /2worker, /1stop
        if (command.length() > 1 && Character.isDigit(command.charAt(1))) {
            int targetId = Character.getNumericValue(command.charAt(1));
            return targetId == clientId;
        }

        // Команды без префикса - для всех (каждый решает сам нужно ли)
        return true;
    }

    /**
     * Извлечь команду без ID префикса
     * "/1bank" -> "/bank"
     * "/2worker" -> "/worker"
     */
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