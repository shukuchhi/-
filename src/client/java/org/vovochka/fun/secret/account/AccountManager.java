package org.vovochka.fun.secret.account;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.vovochka.fun.secret.Secret;
import org.vovochka.fun.secret.SecretClient;
import org.vovochka.fun.secret.util.SessionSwitcher;

import java.util.List;

@Environment(EnvType.CLIENT)
public class AccountManager {

    private static final int WORKER_COUNT = 5;

    private final NicknameGenerator generator = new NicknameGenerator();
    private final NicknameGenerator.NickStorage nickStorage;

    private int currentWorkerIndex = 0;
    private String currentUsername = "Unknown";

    public AccountManager() {
        nickStorage = generator.loadOrCreate(WORKER_COUNT);

        Secret.LOGGER.info("=== АККАУНТЫ ===");
        Secret.LOGGER.info("БАНК: {}", nickStorage.bankNick());
        for (int i = 0; i < nickStorage.workerNicks().size(); i++) {
            Secret.LOGGER.info("Твинк #{}: {}", i + 1, nickStorage.workerNicks().get(i));
        }
        Secret.LOGGER.info("================");

        new Thread(() -> {
            try {
                Thread.sleep(5000);
                if (SecretClient.telegramBot != null) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("📋 Аккаунты загружены!\n");
                    sb.append("🏦 Банк: ").append(nickStorage.bankNick()).append("\n");
                    sb.append("👥 Твинки:\n");
                    for (String nick : nickStorage.workerNicks()) {
                        sb.append("  • ").append(nick).append("\n");
                    }
                    sb.append("\nКоманды:\n");
                    sb.append("/1bank — клиент #1 = банк\n");
                    sb.append("/2worker — клиент #2 = твинк\n");
                    sb.append("/stop — остановить");
                    SecretClient.telegramBot.sendMessage(sb.toString());
                }
            } catch (InterruptedException ignored) {}
        }, "AccountNotify").start();
    }

    public boolean switchToBank() {
        return switchAccount(nickStorage.bankNick());
    }

    public boolean switchToNextWorker() {
        List<String> workers = nickStorage.workerNicks();
        if (workers.isEmpty()) {
            generator.addNewWorker(nickStorage);
        }
        String nick = workers.get(currentWorkerIndex % workers.size());
        currentWorkerIndex++;
        return switchAccount(nick);
    }

    public String rotateCurrentWorkerNick() {
        String old = getCurrentWorkerNick();
        String newNick = generator.replaceWorkerNick(nickStorage, old);
        if (newNick != null) {
            if (SecretClient.telegramBot != null) {
                SecretClient.telegramBot.sendMessage("🔄 Ник: " + old + " → " + newNick);
            }
            switchAccount(newNick);
        }
        return newNick;
    }

    public void addNewWorkerAccount() {
        String nick = generator.addNewWorker(nickStorage);
        if (nick != null && SecretClient.telegramBot != null) {
            SecretClient.telegramBot.sendMessage("✅ Добавлен твинк: " + nick);
        }
    }

    public void addWorkerWithNick(String nick) {
        nickStorage.workerNicks().add(nick);
        generator.saveToFile(nickStorage.bankNick(), nickStorage.workerNicks());
        Secret.LOGGER.info("Added worker: {}", nick);
    }

    public boolean switchAccount(String username) {
        Secret.LOGGER.info("Switching to: {}", username);
        boolean ok = SessionSwitcher.switchTo(username);
        if (ok) {
            currentUsername = username;
        } else {
            // Даже если рефлексия не нашла поле - запоминаем ник
            // При следующем подключении к серверу используем его
            currentUsername = username;
            Secret.LOGGER.warn("Reflection failed but continuing with nick: {}", username);
        }
        return ok;
    }

    public String getBankNick() {
        return nickStorage.bankNick();
    }

    public String getCurrentWorkerNick() {
        List<String> w = nickStorage.workerNicks();
        if (w.isEmpty()) return "Unknown";
        int idx = ((currentWorkerIndex - 1) % w.size() + w.size()) % w.size();
        return w.get(idx);
    }

    public String getCurrentUsername() {
        String real = SessionSwitcher.getCurrentNick();
        if (!real.equals("Unknown") && !real.isEmpty()) {
            return real;
        }
        return currentUsername;
    }

    public List<String> getAllWorkerNicks() {
        return nickStorage.workerNicks();
    }

    public NicknameGenerator.NickStorage getNickStorage() {
        return nickStorage;
    }
}