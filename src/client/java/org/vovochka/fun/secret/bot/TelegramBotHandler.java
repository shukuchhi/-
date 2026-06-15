package org.vovochka.fun.secret.bot;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import org.vovochka.fun.secret.ClientIdentity;
import org.vovochka.fun.secret.Secret;
import org.vovochka.fun.secret.SecretClient;
import org.vovochka.fun.secret.automation.BankBotStateMachine;
import org.vovochka.fun.secret.automation.WorkerBotStateMachine;
import org.vovochka.fun.secret.ipc.IpcClient;
import org.vovochka.fun.secret.ipc.IpcServer;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;

public class TelegramBotHandler extends TelegramLongPollingBot {

    private final String token;
    private final long adminId;
    private final String myLabel;

    public TelegramBotHandler(String token, long adminId) {
        this.token = token;
        this.adminId = adminId;
        this.myLabel = ClientIdentity.getLabel();
    }

    public void start() throws TelegramApiException {
        TelegramBotsApi api = new TelegramBotsApi(DefaultBotSession.class);
        api.registerBot(this);
        Secret.LOGGER.info("[TG-{}] Bot started!", ClientIdentity.getId());
    }

    @Override
    public String getBotUsername() {
        return "SecretFarmBot" + ClientIdentity.getId();
    }

    @Override
    public String getBotToken() { return token; }

    @Override
    public void onUpdateReceived(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) return;
        if (update.getMessage().getChatId() != adminId) return;

        String text = update.getMessage().getText().trim();
        Secret.LOGGER.info("[TG-{}] Received: '{}'", ClientIdentity.getId(), text);

        handleCommand(text);
    }

    private void handleCommand(String text) {
        String cmd = text.toLowerCase().trim();

        switch (cmd) {
            case "/start", "/help" -> sendHelp();
            case "/bank"           -> startAsBank();
            case "/worker"         -> startAsWorker();
            case "/stop"           -> stopAll();
            case "/status"         -> sendStatus();
            case "/nicks"          -> sendNicks();
            case "/ip" -> {
                String ip = SecretClient.vpnController != null
                        ? SecretClient.vpnController.getCurrentIp() : "N/A";
                sendMessage(myLabel + " IP: " + ip);
            }
            case "/changeip" -> {
                sendMessage(myLabel + ": 🔄 Меняю IP...");
                new Thread(() -> {
                    boolean ok = SecretClient.vpnController != null
                            && SecretClient.vpnController.changeIp();
                    sendMessage(myLabel + ": " + (ok ? "✅ IP сменён!" : "❌ Ошибка!"));
                }, "TG-VPN").start();
            }
            case "/addworker" -> {
                if (SecretClient.accountManager != null) {
                    SecretClient.accountManager.addNewWorkerAccount();
                }
            }
            default -> {
                if (text.matches("\\d+")) {
                    if (SecretClient.stateMachine != null) {
                        SecretClient.stateMachine.onCaptchaAnswer(text);
                        sendMessage(myLabel + ": ✅ Капча отправлена: " + text);
                    } else {
                        sendMessage(myLabel + ": ❌ Бот не запущен!\nНапишите /bank или /worker");
                    }
                } else if (cmd.startsWith("/addnick ")) {
                    String nick = text.substring("/addnick ".length()).trim();
                    if (!nick.isEmpty() && SecretClient.accountManager != null) {
                        SecretClient.accountManager.addWorkerWithNick(nick);
                        sendMessage(myLabel + ": ✅ Ник добавлен: " + nick);
                    }
                } else {
                    sendMessage(myLabel + ": ❓ Неизвестная команда\nНапишите /help");
                }
            }
        }
    }

    public void startAsBank() {
        if (SecretClient.clientRole != SecretClient.ClientRole.UNKNOWN) {
            sendMessage(myLabel + ": Уже " + SecretClient.clientRole + "\nСначала /stop");
            return;
        }
        sendMessage(myLabel + ": 🏦 Запускаю как БАНК...");
        try {
            SecretClient.clientRole = SecretClient.ClientRole.BANK;
            boolean switched = SecretClient.accountManager.switchToBank();

            SecretClient.ipcServer = new IpcServer();
            SecretClient.ipcServer.start();

            SecretClient.stateMachine = new BankBotStateMachine();
            SecretClient.stateMachine.start();

            sendMessage(
                    "🏦 " + myLabel + " = БАНК\n" +
                            "Ник: " + SecretClient.accountManager.getBankNick() + "\n" +
                            "Смена ника: " + (switched ? "✅" : "⚠️") + "\n\n" +
                            "Теперь на 2м клиенте напишите /worker"
            );
        } catch (Exception e) {
            Secret.LOGGER.error("[TG] startAsBank error: {}", e.getMessage(), e);
            sendMessage(myLabel + ": ❌ Ошибка: " + e.getMessage());
            SecretClient.clientRole = SecretClient.ClientRole.UNKNOWN;
        }
    }

    public void startAsWorker() {
        if (SecretClient.clientRole != SecretClient.ClientRole.UNKNOWN) {
            sendMessage(myLabel + ": Уже " + SecretClient.clientRole + "\nСначала /stop");
            return;
        }
        sendMessage(myLabel + ": 👷 Запускаю как ТВИНК...");
        try {
            SecretClient.clientRole = SecretClient.ClientRole.WORKER;
            SecretClient.accountManager.switchToNextWorker();

            SecretClient.ipcClient = new IpcClient();
            SecretClient.ipcClient.connect();

            SecretClient.stateMachine = new WorkerBotStateMachine();
            SecretClient.stateMachine.start();

            sendMessage(
                    "👷 " + myLabel + " = ТВИНК\n" +
                            "Ник: " + SecretClient.accountManager.getCurrentUsername() + "\n" +
                            "Подключаюсь к банку..."
            );
        } catch (Exception e) {
            Secret.LOGGER.error("[TG] startAsWorker error: {}", e.getMessage(), e);
            sendMessage(myLabel + ": ❌ Ошибка: " + e.getMessage());
            SecretClient.clientRole = SecretClient.ClientRole.UNKNOWN;
        }
    }

    public void stopAll() {
        if (SecretClient.stateMachine != null) {
            SecretClient.stateMachine.stop();
            SecretClient.stateMachine = null;
        }
        if (SecretClient.ipcServer != null) {
            SecretClient.ipcServer.stop();
            SecretClient.ipcServer = null;
        }
        if (SecretClient.ipcClient != null) {
            SecretClient.ipcClient.disconnect();
            SecretClient.ipcClient = null;
        }
        SecretClient.clientRole = SecretClient.ClientRole.UNKNOWN;
        sendMessage(myLabel + ": 🛑 Остановлен!");
    }

    private void sendHelp() {
        sendMessage(
                "🤖 " + myLabel + " - Secret Farm\n" +
                        "━━━━━━━━━━━━━━━━\n\n" +
                        "/bank — стать банком\n" +
                        "/worker — стать твинком\n" +
                        "/stop — остановить\n" +
                        "/status — статус\n" +
                        "/nicks — все ники\n" +
                        "/ip — текущий IP\n" +
                        "/changeip — сменить IP\n\n" +
                        "Капча: просто напишите цифры!\n" +
                        "Например: 1234\n\n" +
                        "Текущая роль: " + SecretClient.clientRole
        );
    }

    private void sendStatus() {
        String state = SecretClient.stateMachine != null
                ? SecretClient.stateMachine.getCurrentState().toString() : "нет";
        boolean running = SecretClient.stateMachine != null
                && SecretClient.stateMachine.isRunning();
        String nick = SecretClient.accountManager != null
                ? SecretClient.accountManager.getCurrentUsername() : "нет";

        String ipcStatus;
        if (SecretClient.clientRole == SecretClient.ClientRole.BANK) {
            ipcStatus = SecretClient.ipcServer != null && SecretClient.ipcServer.isWorkerConnected()
                    ? "🟢 Твинк подключён" : "🔴 Твинк не подключён";
        } else if (SecretClient.clientRole == SecretClient.ClientRole.WORKER) {
            ipcStatus = SecretClient.ipcClient != null && SecretClient.ipcClient.isConnected()
                    ? "🟢 Банк подключён" : "🔴 Банк не подключён";
        } else {
            ipcStatus = "—";
        }

        sendMessage(
                "📊 " + myLabel + " статус:\n" +
                        "Роль: " + SecretClient.clientRole + "\n" +
                        "Запущен: " + (running ? "✅" : "❌") + "\n" +
                        "Состояние: " + state + "\n" +
                        "Ник: " + nick + "\n" +
                        "IPC: " + ipcStatus
        );
    }

    private void sendNicks() {
        if (SecretClient.accountManager == null) {
            sendMessage(myLabel + ": AccountManager не готов");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("📋 ").append(myLabel).append(" ники:\n");
        sb.append("🏦 Банк: ")
                .append(SecretClient.accountManager.getBankNick()).append("\n\n");
        sb.append("👥 Твинки:\n");
        var workers = SecretClient.accountManager.getAllWorkerNicks();
        for (int i = 0; i < workers.size(); i++) {
            sb.append(i + 1).append(". ").append(workers.get(i)).append("\n");
        }
        sendMessage(sb.toString());
    }

    public void sendMessage(String text) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                SendMessage msg = new SendMessage();
                msg.setChatId(String.valueOf(adminId));
                msg.setText(text);
                execute(msg);
                return;
            } catch (TelegramApiException e) {
                Secret.LOGGER.error("[TG-{}] sendMessage attempt {}/3: {}",
                        ClientIdentity.getId(), attempt, e.getMessage());
                if (attempt < 3) {
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                }
            }
        }
    }

    /**
     * Идеальный метод отправки фото через встроенный Java Native HttpClient.
     * Полностью исключает NoClassDefFoundError для MultipartEntityBuilder в среде Fabric.
     */
    public void sendPhoto(File file, String caption) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                String boundary = "Boundary-" + System.currentTimeMillis();
                HttpClient client = HttpClient.newHttpClient();

                byte[] fileBytes = Files.readAllBytes(file.toPath());
                StringBuilder sb = new StringBuilder();

                // Chat ID
                sb.append("--").append(boundary).append("\r\n");
                sb.append("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n");
                sb.append(adminId).append("\r\n");

                // Caption
                if (caption != null) {
                    sb.append("--").append(boundary).append("\r\n");
                    sb.append("Content-Disposition: form-data; name=\"caption\"\r\n\r\n");
                    sb.append(caption).append("\r\n");
                }

                // File
                sb.append("--").append(boundary).append("\r\n");
                sb.append("Content-Disposition: form-data; name=\"photo\"; filename=\"").append(file.getName()).append("\"\r\n");
                sb.append("Content-Type: image/png\r\n\r\n");

                byte[] headerBytes = sb.toString().getBytes("UTF-8");
                byte[] footerBytes = ("\r\n--" + boundary + "--\r\n").getBytes("UTF-8");

                byte[] multipartBody = new byte[headerBytes.length + fileBytes.length + footerBytes.length];
                System.arraycopy(headerBytes, 0, multipartBody, 0, headerBytes.length);
                System.arraycopy(fileBytes, 0, multipartBody, headerBytes.length, fileBytes.length);
                System.arraycopy(footerBytes, 0, multipartBody, headerBytes.length + fileBytes.length, footerBytes.length);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("https://api.telegram.org/bot" + token + "/sendPhoto"))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody))
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    Secret.LOGGER.info("[TG] Native sendPhoto transfer complete on attempt {}", attempt);
                    return;
                } else {
                    Secret.LOGGER.warn("[TG] Native sendPhoto rejected with code: {}", response.statusCode());
                }
            } catch (Exception e) {
                Secret.LOGGER.error("[TG] Native sendPhoto crash on attempt {}/3: {}", attempt, e.getMessage());
            }
            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
        }
    }
}