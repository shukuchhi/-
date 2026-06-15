package org.vovochka.fun.secret.automation;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.vovochka.fun.secret.Secret;
import org.vovochka.fun.secret.SecretClient;
import org.vovochka.fun.secret.ipc.IpcMessage;
import org.vovochka.fun.secret.util.ChatHelper;
import org.vovochka.fun.secret.util.ChatListener;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Environment(EnvType.CLIENT)
public class BankBotStateMachine extends BotStateMachine {

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2,
            r -> { Thread t = new Thread(r, "Bank-Scheduler"); t.setDaemon(true); return t; });

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private volatile BotState currentState = BotState.IDLE;

    // АФК защита
    private ScheduledFuture<?> afkTask;

    // Данные ставки от твинка
    private volatile int pendingStavkaAmount = 0;
    private volatile String pendingWorkerNick = "";

    // Данные банка
    private volatile boolean didFirstFarm = false;

    // Анти-спам капча
    private volatile boolean captchaSent = false;

    // КД между подключениями
    private volatile long lastConnectTime = 0;

    // Координаты кейсов
    private static final BlockPos CASE_1 = new BlockPos(-15, 46, 153);
    private static final BlockPos CASE_2 = new BlockPos(-15, 46, 158);

    // Обработчик чата
    private Consumer<String> chatHandler;

    public BankBotStateMachine() {
        registerChatListeners();
    }

    // ========================================================
    //  ПУБЛИЧНЫЕ МЕТОДЫ
    // ========================================================

    @Override
    public void start() {
        if (isRunning.compareAndSet(false, true)) {
            Secret.LOGGER.info("[BANK] Starting...");
            transitionTo(BotState.CONNECTING_TO_SERVER);
        }
    }

    @Override
    public void stop() {
        isRunning.set(false);
        currentState = BotState.IDLE;
        stopAfkProtection();
        captchaSent = false;

        if (chatHandler != null) {
            ChatListener.removeHandler(chatHandler);
            chatHandler = null;
        }

        Secret.LOGGER.info("[BANK] Stopped.");
    }

    @Override
    public void transitionTo(BotState newState) {
        if (!isRunning.get() && newState != BotState.IDLE) return;
        Secret.LOGGER.info("[BANK] {} -> {}", currentState, newState);
        currentState = newState;

        scheduler.schedule(() -> {
            try {
                handleState(newState);
            } catch (Exception e) {
                Secret.LOGGER.error("[BANK] Error in {}: {}", newState, e.getMessage(), e);
                if (SecretClient.telegramBot != null) {
                    SecretClient.telegramBot.sendMessage(
                            "[БАНК] ❌ Ошибка: " + e.getMessage());
                }
            }
        }, getDelay(newState), TimeUnit.MILLISECONDS);
    }

    @Override
    public BotState getCurrentState() { return currentState; }

    @Override
    public boolean isRunning() { return isRunning.get(); }

    // ========================================================
    //  ОБРАБОТКА СОСТОЯНИЙ
    // ========================================================

    private void handleState(BotState state) {
        MinecraftClient client = MinecraftClient.getInstance();
        switch (state) {
            case CONNECTING_TO_SERVER -> doConnect(client);
            case JOINING_QUEUE        -> doJoinQueue(client);
            case WAITING_SPAWN        -> doOnSpawn(client);
            case WRITING_FREE         -> doWriteFree(client);
            case WALKING_TO_CASE_1    -> doWalkToCase(client, CASE_1, BotState.OPENING_CASE_1);
            case WALKING_TO_CASE_2    -> doWalkToCase(client, CASE_2, BotState.OPENING_CASE_2);
            case ACCEPTING_STAVKA     -> doAcceptStavka(client);
            case BANNED               -> doBanned();
            default -> Secret.LOGGER.info("[BANK] Waiting in: {}", state);
        }
    }

    private long getDelay(BotState state) {
        return switch (state) {
            case CONNECTING_TO_SERVER -> 1000;
            case WAITING_SPAWN        -> 3000;
            case WRITING_FREE         -> 2000;
            default                   -> 800;
        };
    }

    // ========================================================
    //  ДЕЙСТВИЯ
    // ========================================================

    private void doConnect(MinecraftClient client) {
        // КД 5 секунд между подключениями
        long now = System.currentTimeMillis();
        long timeSinceLast = now - lastConnectTime;
        if (timeSinceLast < 5000) {
            long wait = 5000 - timeSinceLast;
            Secret.LOGGER.info("[BANK] Connect cooldown {}ms...", wait);
            scheduler.schedule(() -> doConnect(client), wait, TimeUnit.MILLISECONDS);
            return;
        }
        lastConnectTime = now;
        captchaSent = false;

        Secret.LOGGER.info("[BANK] Connecting to {}...", Secret.SERVER_IP);
        if (SecretClient.telegramBot != null) {
            SecretClient.telegramBot.sendMessage("🔗 БАНК: Подключаюсь...");
        }

        try {
            ServerAddress addr = ServerAddress.parse(Secret.SERVER_IP);
            ServerInfo info = new ServerInfo(
                    "CountryMC", Secret.SERVER_IP, ServerInfo.ServerType.OTHER
            );
            client.execute(() -> {
                try {
                    ConnectScreen.connect(
                            client.currentScreen, client, addr, info, false, null);
                } catch (Exception e) {
                    Secret.LOGGER.error("[BANK] ConnectScreen error: {}", e.getMessage());
                    transitionTo(BotState.BANNED);
                }
            });

            scheduler.schedule(() -> {
                if (client.world == null || client.player == null) {
                    Secret.LOGGER.warn("[BANK] Not connected after 30s");
                    transitionTo(BotState.BANNED);
                }
            }, 30, TimeUnit.SECONDS);

        } catch (Exception e) {
            Secret.LOGGER.error("[BANK] Connect error: {}", e.getMessage());
            transitionTo(BotState.BANNED);
        }
    }

    private void doJoinQueue(MinecraftClient client) {
        sendChat(client, "/joinq sirius");
        scheduler.schedule(() -> transitionTo(BotState.WAITING_SPAWN), 7, TimeUnit.SECONDS);
    }

    private void doOnSpawn(MinecraftClient client) {
        startAfkProtection(client);

        if (!didFirstFarm) {
            Secret.LOGGER.info("[BANK] First time - doing /free farm");
            if (SecretClient.telegramBot != null) {
                SecretClient.telegramBot.sendMessage("🏦 Банк на сервере! Фармлю /free...");
            }
            scheduler.schedule(() -> transitionTo(BotState.WRITING_FREE), 2, TimeUnit.SECONDS);
        } else {
            Secret.LOGGER.info("[BANK] On spawn, waiting for worker...");
            if (SecretClient.telegramBot != null) {
                SecretClient.telegramBot.sendMessage(
                        "🏦 Банк на сервере! Жду ставок от твинка...");
            }
            if (SecretClient.ipcServer != null) {
                SecretClient.ipcServer.send(new IpcMessage(IpcMessage.Type.BANK_READY, ""));
            }
            currentState = BotState.IDLE;
        }
    }

    private void doWriteFree(MinecraftClient client) {
        if (client.player == null) {
            scheduler.schedule(() -> transitionTo(BotState.WRITING_FREE), 2, TimeUnit.SECONDS);
            return;
        }
        sendChat(client, "/free");
        currentState = BotState.HANDLING_FREE_MENU_1;
    }

    private void doWalkToCase(MinecraftClient client, BlockPos target, BotState nextState) {
        if (client.player == null) return;

        client.execute(() -> {
            if (client.player == null) return;
            Vec3d tv = Vec3d.ofCenter(target);
            Vec3d ep = client.player.getEyePos();
            Vec3d dir = tv.subtract(ep).normalize();
            client.player.setYaw((float) Math.toDegrees(Math.atan2(-dir.x, dir.z)));
            client.player.setPitch((float) Math.toDegrees(
                    -Math.asin(Math.max(-1, Math.min(1, dir.y)))));
        });

        final int[] ticks = {0};
        AtomicReference<ScheduledFuture<?>> taskRef = new AtomicReference<>();

        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            if (client.player == null) return;
            ticks[0]++;

            Vec3d pos = client.player.getPos();
            double dx = target.getX() + 0.5 - pos.x;
            double dz = target.getZ() + 0.5 - pos.z;
            double dist = Math.sqrt(dx * dx + dz * dz);

            if (dist < 3.5 || ticks[0] > 80) {
                ScheduledFuture<?> f = taskRef.get();
                if (f != null) f.cancel(false);

                client.execute(() -> {
                    if (client.player == null || client.interactionManager == null) return;
                    client.options.forwardKey.setPressed(false);
                    BlockHitResult hit = new BlockHitResult(
                            Vec3d.ofCenter(target), Direction.UP, target, false);
                    client.interactionManager.interactBlock(
                            client.player, Hand.MAIN_HAND, hit);
                    currentState = nextState;
                    Secret.LOGGER.info("[BANK] Interacted with block at {}", target);
                });
            } else {
                client.execute(() -> {
                    if (client.player == null) return;
                    float yaw = (float)(Math.toDegrees(Math.atan2(dz, dx)) - 90);
                    client.player.setYaw(yaw);
                    client.options.forwardKey.setPressed(true);
                });
            }
        }, 0, 200, TimeUnit.MILLISECONDS);

        taskRef.set(task);
    }

    private void doAcceptStavka(MinecraftClient client) {
        Secret.LOGGER.info("[BANK] Accepting stavka {} KK from {}",
                pendingStavkaAmount, pendingWorkerNick);
        sendChat(client, "/stavka");

        scheduler.schedule(() -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (!(mc.currentScreen instanceof HandledScreen<?> s)) {
                scheduler.schedule(() -> doAcceptStavka(mc), 3, TimeUnit.SECONDS);
                return;
            }
            clickSlot(s, 11); // Посмотреть ставки

            scheduler.schedule(() -> {
                if (!(mc.currentScreen instanceof HandledScreen<?> s2)) return;
                int slot = findStavkaSlot(s2, pendingWorkerNick, pendingStavkaAmount);

                if (slot < 0) {
                    mc.execute(() -> { if (mc.currentScreen != null) mc.currentScreen = null; });
                    scheduler.schedule(() -> doAcceptStavka(mc), 5, TimeUnit.SECONDS);
                    return;
                }

                clickSlot(s2, slot);
                scheduler.schedule(() -> {
                    if (!(mc.currentScreen instanceof HandledScreen<?> s3)) return;
                    clickSlot(s3, 11); // Подтвердить
                    if (SecretClient.ipcServer != null) {
                        SecretClient.ipcServer.send(
                                new IpcMessage(IpcMessage.Type.ACCEPT_STAVKA, ""));
                    }
                    currentState = BotState.IDLE;
                }, 1, TimeUnit.SECONDS);

            }, 2, TimeUnit.SECONDS);
        }, 2, TimeUnit.SECONDS);
    }

    private void doBanned() {
        stopAfkProtection();
        captchaSent = false;

        if (SecretClient.telegramBot != null) {
            SecretClient.telegramBot.sendMessage(
                    "🚫 БАНК: Подозрительная активность! Меняю IP...");
        }

        new Thread(() -> {
            boolean ok = SecretClient.vpnController.changeIp();
            if (ok) {
                if (SecretClient.telegramBot != null) {
                    SecretClient.telegramBot.sendMessage(
                            "✅ IP сменён! Жду 10 секунд перед переподключением...");
                }
                // 10 секунд после смены VPN
                try { Thread.sleep(10000); } catch (InterruptedException ignored) {}

                SecretClient.accountManager.switchToBank();
                transitionTo(BotState.CONNECTING_TO_SERVER);
            } else {
                if (SecretClient.telegramBot != null) {
                    SecretClient.telegramBot.sendMessage("❌ Не удалось сменить IP! Жду 30с...");
                }
                scheduler.schedule(() -> doBanned(), 30, TimeUnit.SECONDS);
            }
        }, "Bank-VPN-Thread").start();
    }

    // ========================================================
    //  АФК ЗАЩИТА
    // ========================================================

    private void startAfkProtection(MinecraftClient client) {
        stopAfkProtection();
        afkTask = scheduler.scheduleAtFixedRate(() -> {
            client.execute(() -> {
                if (client.player == null) return;
                // Прыжок каждые 50 секунд
                client.player.jump();
                Secret.LOGGER.debug("[BANK] AFK jump");
            });
        }, 50, 50, TimeUnit.SECONDS);
        Secret.LOGGER.info("[BANK] AFK protection started");
    }

    private void stopAfkProtection() {
        if (afkTask != null && !afkTask.isCancelled()) {
            afkTask.cancel(false);
            afkTask = null;
        }
    }

    // ========================================================
    //  GUI
    // ========================================================

    @Override
    public void onScreenOpened(HandledScreen<?> screen) {
        scheduler.schedule(() -> {
            switch (currentState) {
                case HANDLING_FREE_MENU_1 -> {
                    Secret.LOGGER.info("[BANK] Free menu 1 - clicking slot 24");
                    clickSlot(screen, 24);
                    currentState = BotState.HANDLING_FREE_MENU_2;
                }
                case HANDLING_FREE_MENU_2 -> {
                    Secret.LOGGER.info("[BANK] Free menu 2 - clicking slot 16");
                    clickSlot(screen, 16);
                }
                case OPENING_CASE_1 -> {
                    Secret.LOGGER.info("[BANK] Case 1 menu - clicking slot 11");
                    clickSlot(screen, 11);
                    scheduler.schedule(() ->
                            transitionTo(BotState.WALKING_TO_CASE_2), 3, TimeUnit.SECONDS);
                }
                case OPENING_CASE_2 -> {
                    Secret.LOGGER.info("[BANK] Case 2 menu - clicking slot 11");
                    clickSlot(screen, 11);
                    scheduler.schedule(() -> {
                        didFirstFarm = true;
                        if (SecretClient.telegramBot != null) {
                            SecretClient.telegramBot.sendMessage(
                                    "🏦 Банк пофармил! Жду ставок от твинка...");
                        }
                        if (SecretClient.ipcServer != null) {
                            SecretClient.ipcServer.send(
                                    new IpcMessage(IpcMessage.Type.BANK_READY, ""));
                        }
                        currentState = BotState.IDLE;
                    }, 3, TimeUnit.SECONDS);
                }
                default -> {}
            }
        }, 600, TimeUnit.MILLISECONDS);
    }

    // ========================================================
    //  IPC КОЛБЭКИ
    // ========================================================

    @Override
    public void onWorkerCreatedStavka(String workerNick, int amount) {
        pendingWorkerNick = workerNick;
        pendingStavkaAmount = amount;
        transitionTo(BotState.ACCEPTING_STAVKA);
    }

    @Override
    public void onBankConnected(String bankNick) {}

    @Override
    public void onBankReady() {}

    // ========================================================
    //  КАПЧА
    // ========================================================

    @Override
    public void onCaptchaAnswer(String answer) {
        Secret.LOGGER.info("[BANK] Captcha answer received: {}", answer);
        captchaSent = false;
        sendChat(MinecraftClient.getInstance(), answer);
    }

    // ========================================================
    //  ЧАТ
    // ========================================================

    private void registerChatListeners() {
        chatHandler = this::onBankChat;
        ChatListener.addHandler(chatHandler);
        Secret.LOGGER.info("[BANK] Chat handler registered");
    }

    private void onBankChat(String raw) {
        if (!isRunning.get()) return;

        // Убираем форматирование и нормализуем
        String clean = ChatHelper.stripColors(raw)
                .replace("\\n", "")
                .replace("\n", "")
                .toLowerCase()
                .trim();

        if (clean.isEmpty()) return;

        Secret.LOGGER.debug("[BANK-CHAT] Processing: '{}'", clean);

        MinecraftClient client = MinecraftClient.getInstance();

        // ===== КАПЧА =====
        boolean isCaptcha = clean.contains("введи цифры") ||
                clean.contains("введите цифры") ||
                (clean.contains("цифры") && clean.contains("картинки")) ||
                (clean.contains("цифры") && clean.contains("чат"));

        if (isCaptcha) {
            Secret.LOGGER.info("[BANK] ✅ CAPTCHA DETECTED! captchaSent={}", captchaSent);
            if (!captchaSent) {
                captchaSent = true;
                captureAndSendScreenshot(client, "[БАНК]");
            }
            return;
        }

        // ===== АВТОРИЗАЦИЯ =====
        boolean needAuth = clean.contains("авторизуйт") ||
                clean.contains("авторизуйс") ||
                clean.contains("введите пароль") ||
                (clean.contains("/l ") && clean.length() < 50);

        if (needAuth) {
            captchaSent = false;
            Secret.LOGGER.info("[BANK] Auth required");
            scheduler.schedule(() ->
                    sendChat(client, "/l " + Secret.BANK_PASSWORD), 1, TimeUnit.SECONDS);
            return;
        }

        // ===== РЕГИСТРАЦИЯ =====
        boolean needReg = clean.contains("зарегистрируйт") ||
                clean.contains("зарегистрируйс") ||
                clean.contains("не зарегистрирован") ||
                (clean.contains("/reg ") && clean.length() < 50);

        if (needReg) {
            captchaSent = false;
            Secret.LOGGER.info("[BANK] Registration required");
            scheduler.schedule(() ->
                            sendChat(client, "/reg " + Secret.BANK_PASSWORD + " " + Secret.BANK_PASSWORD),
                    1, TimeUnit.SECONDS);
            return;
        }

        // ===== ДОБРО ПОЖАЛОВАТЬ =====
        if (clean.contains("добро пожаловать") || clean.contains("welcome")) {
            captchaSent = false;
            Secret.LOGGER.info("[BANK] Welcome! Joining queue...");
            transitionTo(BotState.JOINING_QUEUE);
            return;
        }

        // ===== МОНЕТЫ (после /free) =====
        if (clean.contains("50") &&
                (clean.contains("монет") || clean.contains("coin")) &&
                !didFirstFarm) {
            Secret.LOGGER.info("[BANK] Got 50 coins! Walking to cases...");
            transitionTo(BotState.WALKING_TO_CASE_1);
            return;
        }

        // ===== СТАВКА ВЫИГРАНА =====
        if (clean.contains("твоя ставка") && clean.contains("выиграла")) {
            Secret.LOGGER.info("[BANK] Stavka WON!");
            if (SecretClient.telegramBot != null) {
                SecretClient.telegramBot.sendMessage("🎉 БАНК выиграл ставку!");
            }
            if (SecretClient.ipcServer != null) {
                SecretClient.ipcServer.send(new IpcMessage(IpcMessage.Type.STAVKA_WIN, "bank"));
            }
            return;
        }

        // ===== СТАВКА ПРОИГРАНА =====
        if (clean.contains("ставка игрока") && clean.contains("выиграла")) {
            Secret.LOGGER.info("[BANK] Stavka LOST!");
            String winner = ChatHelper.extractNick(raw);
            if (SecretClient.telegramBot != null) {
                SecretClient.telegramBot.sendMessage("💸 БАНК проиграл! Выиграл: " + winner);
            }
            if (SecretClient.ipcServer != null) {
                SecretClient.ipcServer.send(new IpcMessage(
                        IpcMessage.Type.STAVKA_LOSS, winner != null ? winner : ""));
            }
            return;
        }

        // ===== КИК / БАН =====
        if (clean.contains("подозрительн") ||
                clean.contains("заблокирован") ||
                clean.contains("проверка не пройдена") ||
                clean.contains("banned")) {
            captchaSent = false;
            Secret.LOGGER.info("[BANK] Kicked/banned!");
            transitionTo(BotState.BANNED);
        }
    }

    // ========================================================
    //  СКРИНШОТ
    // ========================================================

    private void captureAndSendScreenshot(MinecraftClient client, String prefix) {
        scheduler.schedule(() -> {
            client.execute(() -> {
                try {
                    Secret.LOGGER.info("{} Taking screenshot...", prefix);
                    net.minecraft.client.util.ScreenshotRecorder.saveScreenshot(
                            client.runDirectory,
                            client.getFramebuffer(),
                            (text) -> {
                                Secret.LOGGER.info("{} Screenshot saved: {}", prefix,
                                        text.getString());
                                scheduler.schedule(() ->
                                                sendLatestScreenshot(client, prefix),
                                        1000, TimeUnit.MILLISECONDS);
                            }
                    );
                } catch (Exception e) {
                    Secret.LOGGER.error("{} Screenshot error: {}", prefix, e.getMessage());
                    if (SecretClient.telegramBot != null) {
                        SecretClient.telegramBot.sendMessage(
                                prefix + " ❌ Не удалось сделать скриншот!\n" +
                                        "Введите цифры капчи вручную:");
                    }
                }
            });
        }, 300, TimeUnit.MILLISECONDS);
    }

    private void sendLatestScreenshot(MinecraftClient client, String prefix) {
        try {
            java.io.File dir = new java.io.File(client.runDirectory, "screenshots");
            if (!dir.exists()) {
                Secret.LOGGER.warn("{} Screenshots dir not found: {}",
                        prefix, dir.getAbsolutePath());
                if (SecretClient.telegramBot != null) {
                    SecretClient.telegramBot.sendMessage(
                            prefix + " ❌ Папка скриншотов не найдена!");
                }
                return;
            }

            java.io.File[] files = dir.listFiles(
                    f -> f.isFile() && f.getName().endsWith(".png"));

            if (files == null || files.length == 0) {
                Secret.LOGGER.warn("{} No screenshots found", prefix);
                if (SecretClient.telegramBot != null) {
                    SecretClient.telegramBot.sendMessage(
                            prefix + " ❌ Скриншот не найден! Введите цифры:");
                }
                return;
            }

            // Берём самый новый
            java.io.File latest = files[0];
            for (java.io.File f : files) {
                if (f.lastModified() > latest.lastModified()) latest = f;
            }

            long age = System.currentTimeMillis() - latest.lastModified();
            Secret.LOGGER.info("{} Sending: {} ({}ms old)", prefix, latest.getName(), age);

            if (SecretClient.telegramBot != null) {
                SecretClient.telegramBot.sendPhoto(
                        latest,
                        prefix + " 🔐 КАПЧА!\n" +
                                "Аккаунт: " + SecretClient.accountManager.getCurrentUsername() + "\n" +
                                "Введите цифры в ответ!"
                );
            }
        } catch (Exception e) {
            Secret.LOGGER.error("{} sendLatestScreenshot error: {}", prefix, e.getMessage());
        }
    }

    // ========================================================
    //  УТИЛИТЫ
    // ========================================================

    private void clickSlot(HandledScreen<?> screen, int slot) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            try {
                ScreenHandler handler = screen.getScreenHandler();
                if (slot >= handler.slots.size()) {
                    Secret.LOGGER.warn("[BANK] Slot {} OOB ({})", slot, handler.slots.size());
                    return;
                }
                client.interactionManager.clickSlot(
                        handler.syncId, slot, 0, SlotActionType.PICKUP, client.player);
                Secret.LOGGER.info("[BANK] Clicked slot {}", slot);
            } catch (Exception e) {
                Secret.LOGGER.error("[BANK] clickSlot error: {}", e.getMessage());
            }
        });
    }

    private int findStavkaSlot(HandledScreen<?> screen, String workerNick, int amount) {
        ScreenHandler handler = screen.getScreenHandler();
        for (int slot = 11; slot <= 17; slot++) {
            if (slot >= handler.slots.size()) break;
            var stack = handler.slots.get(slot).getStack();
            if (stack.isEmpty()) continue;
            try {
                List<Text> tooltip = stack.getTooltip(
                        net.minecraft.item.Item.TooltipContext.DEFAULT, null,
                        net.minecraft.item.tooltip.TooltipType.BASIC);
                for (Text line : tooltip) {
                    String t = ChatHelper.stripColors(line.getString());
                    if (t.contains(workerNick) || t.contains(String.valueOf(amount))) {
                        Secret.LOGGER.info("[BANK] Found stavka at slot {}", slot);
                        return slot;
                    }
                }
            } catch (Exception ignored) {}
        }
        return -1;
    }

    private void sendChat(MinecraftClient client, String message) {
        client.execute(() -> {
            if (client.player == null) {
                Secret.LOGGER.warn("[BANK] sendChat: player null! msg={}", message);
                return;
            }
            try {
                if (message.startsWith("/")) {
                    client.player.networkHandler.sendCommand(message.substring(1));
                } else {
                    client.player.networkHandler.sendChatMessage(message);
                }
                Secret.LOGGER.info("[BANK] Chat: {}", message);
            } catch (Exception e) {
                Secret.LOGGER.error("[BANK] sendChat error: {}", e.getMessage());
            }
        });
    }
}