package org.vovochka.fun.secret.ipc;

import org.vovochka.fun.secret.Secret;
import org.vovochka.fun.secret.SecretClient;
import org.vovochka.fun.secret.automation.BankBotStateMachine;

import java.io.*;
import java.net.*;

/**
 * IPC Сервер - запускается на БАНКЕ (порт 19876)
 * Банк слушает подключения от твинка
 */
public class IpcServer {

    private static final int PORT = 19876;

    private ServerSocket serverSocket;
    private volatile PrintWriter writer;
    private volatile boolean running = false;

    public void start() {
        running = true;
        Thread serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                Secret.LOGGER.info("[IPC-Server] Listening on port {}", PORT);

                if (SecretClient.telegramBot != null) {
                    SecretClient.telegramBot.sendMessage(
                            "🔌 IPC Сервер запущен!\n" +
                                    "Порт: " + PORT + "\n" +
                                    "Запустите второй клиент и нажмите F8!"
                    );
                }

                while (running) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        Secret.LOGGER.info("[IPC-Server] Worker connected: {}",
                                clientSocket.getInetAddress());
                        handleClient(clientSocket);
                    } catch (IOException e) {
                        if (running) {
                            Secret.LOGGER.error("[IPC-Server] Accept error: {}", e.getMessage());
                        }
                    }
                }
            } catch (IOException e) {
                Secret.LOGGER.error("[IPC-Server] Failed to start: {}", e.getMessage());
                if (SecretClient.telegramBot != null) {
                    SecretClient.telegramBot.sendMessage(
                            "❌ IPC Сервер не запустился: " + e.getMessage()
                    );
                }
            }
        }, "IPC-Server-Thread");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private void handleClient(Socket socket) {
        Thread handlerThread = new Thread(() -> {
            try (
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(socket.getInputStream())
                    );
                    PrintWriter pw = new PrintWriter(
                            new OutputStreamWriter(socket.getOutputStream()), true
                    )
            ) {
                this.writer = pw;

                String bankNick = SecretClient.accountManager != null
                        ? SecretClient.accountManager.getBankNick()
                        : "BankUnknown";

                send(new IpcMessage(IpcMessage.Type.HELLO_BANK, bankNick));
                Secret.LOGGER.info("[IPC-Server] Sent HELLO_BANK: {}", bankNick);

                String line;
                while ((line = reader.readLine()) != null) {
                    IpcMessage msg = IpcMessage.deserialize(line);
                    if (msg != null) {
                        Secret.LOGGER.info("[IPC-Server] Received: {}", msg);
                        onMessageFromWorker(msg);
                    }
                }

            } catch (IOException e) {
                Secret.LOGGER.warn("[IPC-Server] Worker disconnected: {}", e.getMessage());
                this.writer = null;
                if (SecretClient.telegramBot != null) {
                    SecretClient.telegramBot.sendMessage("⚠️ Твинк отключился от IPC!");
                }
            }
        }, "IPC-Handler-Thread");
        handlerThread.setDaemon(true);
        handlerThread.start();
    }

    private void onMessageFromWorker(IpcMessage msg) {
        switch (msg.type) {
            case HELLO_WORKER -> {
                Secret.LOGGER.info("[IPC-Server] Worker nick: {}", msg.data);
                if (SecretClient.telegramBot != null) {
                    SecretClient.telegramBot.sendMessage(
                            "🔗 Твинк подклютился: " + msg.data
                    );
                }
                if (SecretClient.stateMachine != null &&
                        SecretClient.stateMachine.getCurrentState() ==
                                org.vovochka.fun.secret.automation.BotState.IDLE) {
                    send(new IpcMessage(IpcMessage.Type.BANK_READY, ""));
                }
            }

            case WORKER_REPORT_BALANCES -> {
                if (SecretClient.stateMachine instanceof BankBotStateMachine bankMachine) {
                    try {
                        String[] parts = msg.data.split("\\|", 2);
                        int wCC = Integer.parseInt(parts[0].trim());
                        int wCoins = Integer.parseInt(parts[1].trim());
                        bankMachine.syncFromWorker(wCC, wCoins);
                    } catch (Exception e) {
                        Secret.LOGGER.error("[IPC-Server] Error parsing worker balances: {}", msg.data);
                    }
                }
            }

            case STAVKA_CREATED -> {
                String[] parts = msg.data.split("\\|", 2);
                String workerNick = parts.length > 0 ? parts[0] : "";
                int amount = 0;
                if (parts.length > 1) {
                    try {
                        amount = Integer.parseInt(parts[1].trim());
                    } catch (NumberFormatException e) {
                        Secret.LOGGER.error("[IPC-Server] Invalid amount: {}", parts[1]);
                    }
                }
                if (SecretClient.stateMachine != null) {
                    SecretClient.stateMachine.onWorkerCreatedStavka(workerNick, amount);
                }
            }

            case PING -> {
                send(new IpcMessage(IpcMessage.Type.PONG, ""));
            }

            case CYCLE_DONE -> {
                Secret.LOGGER.info("[IPC-Server] Worker cycle done!");
                if (SecretClient.telegramBot != null) {
                    SecretClient.telegramBot.sendMessage("✅ Твинк завершил цикл!");
                }
            }

            default ->
                    Secret.LOGGER.warn("[IPC-Server] Unknown message type: {}", msg.type);
        }
    }

    public void send(IpcMessage msg) {
        PrintWriter pw = this.writer;
        if (pw != null) {
            try {
                pw.println(msg.serialize().trim());
                Secret.LOGGER.info("[IPC-Server] Sent: {}", msg);
            } catch (Exception e) {
                Secret.LOGGER.error("[IPC-Server] Send error: {}", e.getMessage());
                this.writer = null;
            }
        } else {
            Secret.LOGGER.warn("[IPC-Server] No worker connected, cannot send: {}", msg);
        }
    }

    public boolean isWorkerConnected() {
        return writer != null;
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            Secret.LOGGER.error("[IPC-Server] Stop error: {}", e.getMessage());
        }
        Secret.LOGGER.info("[IPC-Server] Stopped.");
    }
}