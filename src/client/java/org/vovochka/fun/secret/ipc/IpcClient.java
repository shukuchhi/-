package org.vovochka.fun.secret.ipc;

import org.vovochka.fun.secret.Secret;
import org.vovochka.fun.secret.SecretClient;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * IPC Клиент - запускается на ТВИНКЕ
 * Твинк подключается к серверу банка
 */
public class IpcClient {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 19876;
    private static final int RECONNECT_DELAY_MS = 5000;

    private Socket socket;
    private volatile PrintWriter writer;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean shouldRun = new AtomicBoolean(true);

    public void connect() {
        Thread connectThread = new Thread(this::connectLoop, "IPC-Client-Thread");
        connectThread.setDaemon(true);
        connectThread.start();
    }

    private void connectLoop() {
        while (shouldRun.get()) {
            try {
                Secret.LOGGER.info("[IPC-Client] Connecting to bank at {}:{}...", HOST, PORT);

                socket = new Socket(HOST, PORT);
                connected.set(true);

                Secret.LOGGER.info("[IPC-Client] Connected to bank!");

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream())
                );
                writer = new PrintWriter(
                        new OutputStreamWriter(socket.getOutputStream()), true
                );

                // Представляемся банку
                String myNick = SecretClient.accountManager != null
                        ? SecretClient.accountManager.getCurrentUsername()
                        : "WorkerUnknown";

                send(new IpcMessage(IpcMessage.Type.HELLO_WORKER, myNick));

                if (SecretClient.telegramBot != null) {
                    SecretClient.telegramBot.sendMessage(
                            "🔗 Твинк подключён к банку!\n" +
                                    "Ник твинка: " + myNick
                    );
                }

                // Читаем сообщения от банка
                String line;
                while ((line = reader.readLine()) != null && shouldRun.get()) {
                    IpcMessage msg = IpcMessage.deserialize(line);
                    if (msg != null) {
                        Secret.LOGGER.info("[IPC-Client] Received: {}", msg);
                        onMessageFromBank(msg);
                    }
                }

            } catch (IOException e) {
                connected.set(false);
                writer = null;

                if (shouldRun.get()) {
                    Secret.LOGGER.warn("[IPC-Client] Disconnected: {}. Retry in {}ms...",
                            e.getMessage(), RECONNECT_DELAY_MS);

                    try {
                        Thread.sleep(RECONNECT_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        Secret.LOGGER.info("[IPC-Client] Connect loop ended.");
    }

    /**
     * Обработка сообщений от банка
     */
    private void onMessageFromBank(IpcMessage msg) {
        switch (msg.type) {
            case HELLO_BANK -> {
                Secret.LOGGER.info("[IPC-Client] Bank nick: {}", msg.data);
                if (SecretClient.stateMachine != null) {
                    SecretClient.stateMachine.onBankConnected(msg.data);
                }
            }

            case BANK_READY -> {
                Secret.LOGGER.info("[IPC-Client] Bank is ready!");
                if (SecretClient.stateMachine != null) {
                    SecretClient.stateMachine.onBankReady();
                }
            }

            case ACCEPT_STAVKA -> {
                Secret.LOGGER.info("[IPC-Client] Bank accepted stavka!");
                if (SecretClient.telegramBot != null) {
                    SecretClient.telegramBot.sendMessage("✅ Банк принял ставку!");
                }
            }

            case STAVKA_WIN -> {
                Secret.LOGGER.info("[IPC-Client] Stavka WIN! Winner: {}", msg.data);
                if (SecretClient.stateMachine != null) {
                    // Оповещаем твинка о результате
                    SecretClient.stateMachine.transitionTo(
                            org.vovochka.fun.secret.automation.BotState.CYCLE_COMPLETE
                    );
                }
            }

            case STAVKA_LOSS -> {
                Secret.LOGGER.info("[IPC-Client] Stavka LOSS. Winner: {}", msg.data);
                if (SecretClient.stateMachine != null) {
                    SecretClient.stateMachine.transitionTo(
                            org.vovochka.fun.secret.automation.BotState.CYCLE_COMPLETE
                    );
                }
            }

            case PONG -> Secret.LOGGER.debug("[IPC-Client] Pong received");

            default ->
                    Secret.LOGGER.warn("[IPC-Client] Unknown message: {}", msg.type);
        }
    }

    /**
     * Отправить сообщение банку
     */
    public void send(IpcMessage msg) {
        PrintWriter pw = this.writer;
        if (pw != null && connected.get()) {
            try {
                pw.println(msg.serialize().trim());
                Secret.LOGGER.info("[IPC-Client] Sent: {}", msg);
            } catch (Exception e) {
                Secret.LOGGER.error("[IPC-Client] Send error: {}", e.getMessage());
                connected.set(false);
                writer = null;
            }
        } else {
            Secret.LOGGER.warn("[IPC-Client] Not connected! Cannot send: {}", msg);
        }
    }

    public boolean isConnected() {
        return connected.get();
    }

    public void disconnect() {
        shouldRun.set(false);
        connected.set(false);
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            Secret.LOGGER.error("[IPC-Client] Disconnect error: {}", e.getMessage());
        }
        Secret.LOGGER.info("[IPC-Client] Disconnected.");
    }
}