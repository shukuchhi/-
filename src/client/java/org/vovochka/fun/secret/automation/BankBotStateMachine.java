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

import java.io.File;
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

    private ScheduledFuture<?> afkTask;
    private ScheduledFuture<?> watchdogTask;

    private volatile int myCC = -1;
    private volatile int myCoins = -1;
    private volatile int opponentCC = -1;
    private volatile int targetRoundAmount = 0;

    private volatile String pendingWorkerNick = "";
    private volatile boolean didFirstFarm = false;
    private volatile boolean captchaSent = false;
    private volatile long lastConnectTime = 0;

    private volatile boolean rewardReceived = false;
    private volatile int interactionAttempts = 0;
    private volatile String stavkaMenuStep = "idle";

    private static final BlockPos CASE_1 = new BlockPos(-15, 46, 153);
    private static final BlockPos CASE_2 = new BlockPos(-15, 46, 158);

    private Consumer<String> chatHandler;

    public BankBotStateMachine() {
        registerChatListeners();
    }

    private void sendTelegramDebug(String msg) {
        if (SecretClient.telegramBot != null) {
            SecretClient.telegramBot.sendMessage("🏦 [БАНК] " + msg);
        }
    }

    @Override
    public void start() {
        if (isRunning.compareAndSet(false, true)) {
            Secret.LOGGER.info("[BANK] Starting...");
            sendTelegramDebug("🚀 Кнопка старт нажата. Активация автомата...");
            watchdogTask = scheduler.scheduleAtFixedRate(this::runConnectionWatchdog, 5, 5, TimeUnit.SECONDS);
            transitionTo(BotState.CONNECTING_TO_SERVER);
        }
    }

    @Override
    public void stop() {
        isRunning.set(false);
        currentState = BotState.IDLE;
        stopAfkProtection();
        if (watchdogTask != null) { watchdogTask.cancel(false); watchdogTask = null; }
        captchaSent = false;
        if (chatHandler != null) { ChatListener.removeHandler(chatHandler); chatHandler = null; }
        Secret.LOGGER.info("[BANK] Stopped.");
        sendTelegramDebug("🛑 Бот успешно остановлен пользователем.");
    }

    private void runConnectionWatchdog() {
        if (!isRunning.get()) return;
        if (currentState == BotState.IDLE || currentState == BotState.CONNECTING_TO_SERVER || currentState == BotState.BANNED) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.currentScreen != null) {
            String screenName = client.currentScreen.getClass().getSimpleName().toLowerCase();
            if (screenName.contains("disconnect") || screenName.contains("kick")) {
                Secret.LOGGER.warn("[WATCHDOG] Disconnect detected: {}", screenName);
                sendTelegramDebug("⚠️ [ВАТЧДОГ] Зафиксировано отключение от сервера. Перенаправляю на смену IP...");
                transitionTo(BotState.BANNED);
            }
        }
    }

    @Override
    public void transitionTo(BotState newState) {
        if (!isRunning.get() && newState != BotState.IDLE) return;
        Secret.LOGGER.info("[BANK] {} -> {}", currentState, newState);
        currentState = newState;
        sendTelegramDebug("🔄 Смена состояния -> " + newState.name());

        final BotState capturedState = newState;
        scheduler.schedule(() -> {
            try {
                if (currentState != capturedState || !isRunning.get()) return;
                handleState(capturedState);
            } catch (Exception e) {
                sendTelegramDebug("❌ Ошибка в " + capturedState.name() + ": " + e.getMessage());
            }
        }, getDelay(newState), TimeUnit.MILLISECONDS);
    }

    @Override public BotState getCurrentState() { return currentState; }
    @Override public boolean isRunning() { return isRunning.get(); }

    private void handleState(BotState state) {
        MinecraftClient client = MinecraftClient.getInstance();
        switch (state) {
            case CONNECTING_TO_SERVER -> doConnect(client);
            case JOINING_QUEUE        -> doJoinQueue(client);
            case WAITING_SPAWN        -> doOnSpawn(client);
            case WRITING_FREE         -> doWriteFree(client);
            case WALKING_TO_CASE_1    -> { interactionAttempts = 0; rewardReceived = false; doWalkToCase(client, CASE_1, BotState.OPENING_CASE_1); }
            case WALKING_TO_CASE_2    -> { interactionAttempts = 0; rewardReceived = false; doWalkToCase(client, CASE_2, BotState.OPENING_CASE_2); }
            case BANNED               -> doBanned();
            default -> Secret.LOGGER.info("[BANK] Waiting in: {}", state);
        }
    }

    private void doConnect(MinecraftClient client) {
        long now = System.currentTimeMillis();
        if (now - lastConnectTime < 5000) {
            scheduler.schedule(() -> {
                if (currentState == BotState.CONNECTING_TO_SERVER && isRunning.get()) doConnect(client);
            }, 5000 - (now - lastConnectTime), TimeUnit.MILLISECONDS);
            return;
        }
        lastConnectTime = now;
        captchaSent = false;
        sendTelegramDebug("🔗 Подключаюсь к серверу: " + Secret.SERVER_IP);
        try {
            ServerAddress addr = ServerAddress.parse(Secret.SERVER_IP);
            ServerInfo info = new ServerInfo("CountryMC", Secret.SERVER_IP, ServerInfo.ServerType.OTHER);
            info.setResourcePackPolicy(ServerInfo.ResourcePackPolicy.ENABLED);
            client.execute(() -> ConnectScreen.connect(client.currentScreen, client, addr, info, false, null));
        } catch (Exception e) { transitionTo(BotState.BANNED); }
    }

    private void doJoinQueue(MinecraftClient client) {
        sendChat(client, "/joinq sirius");
        scheduler.schedule(() -> {
            if (currentState == BotState.JOINING_QUEUE && isRunning.get()) {
                transitionTo(BotState.WAITING_SPAWN);
            }
        }, 7, TimeUnit.SECONDS);
    }

    private void doOnSpawn(MinecraftClient client) {
        startAfkProtection(client);
        if (!didFirstFarm) {
            transitionTo(BotState.WRITING_FREE);
        } else {
            startStavkaEngine();
        }
    }

    private void doWriteFree(MinecraftClient client) {
        sendChat(client, "/free");
        currentState = BotState.HANDLING_FREE_MENU_1;
    }

    private void doWalkToCase(MinecraftClient client, BlockPos target, BotState nextState) {
        if (client.player == null) return;
        sendTelegramDebug("🚶 [ПУТЬ] Начинаю активное движение к блоку: " + target.toShortString());

        final int[] ticks = {0};
        final int[] stuckTicks = {0};
        final Vec3d[] lastPos = {client.player.getPos()};
        final BotState expectedState = currentState;
        AtomicReference<ScheduledFuture<?>> taskRef = new AtomicReference<>();

        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            client.execute(() -> {
                if (!isRunning.get() || currentState != expectedState) {
                    ScheduledFuture<?> f = taskRef.get();
                    if (f != null) f.cancel(false);
                    return;
                }
                if (client.player == null || client.interactionManager == null) return;
                ticks[0]++;
                Vec3d pos = client.player.getPos();
                double dx = target.getX() + 0.5 - pos.x, dz = target.getZ() + 0.5 - pos.z;
                double dist = Math.sqrt(dx * dx + dz * dz);

                if (ticks[0] % 10 == 0) {
                    if (pos.distanceTo(lastPos[0]) < 0.05) {
                        stuckTicks[0]++;
                        if (stuckTicks[0] >= 2) { client.player.jump(); stuckTicks[0] = 0; }
                    } else { stuckTicks[0] = 0; }
                    lastPos[0] = pos;
                }

                if (dist < 2.0 || ticks[0] > 140) {
                    ScheduledFuture<?> f = taskRef.get();
                    if (f != null) f.cancel(false);

                    client.options.forwardKey.setPressed(false);
                    Vec3d center = Vec3d.ofCenter(target);
                    client.player.setYaw((float)(Math.toDegrees(Math.atan2(center.z - client.player.getZ(), center.x - client.player.getX())) - 90));
                    client.player.setPitch((float)(-Math.toDegrees(Math.atan2(center.y - client.player.getEyeY(), Math.sqrt((center.x - client.player.getX())*(center.x - client.player.getX()) + (center.z - client.player.getZ())*(center.z - client.player.getZ()))))));
                    client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, new BlockHitResult(center, Direction.UP, target, false));

                    currentState = nextState;
                    sendTelegramDebug("🔄 Смена состояния -> " + nextState.name());
                    sendTelegramDebug("🎬 Кейс активирован ПКМ. Ожидаю 10 секунд завершения серверной анимации рулетки...");

                    scheduler.schedule(() -> {
                        if (isRunning.get() && currentState == nextState) {
                            verifyCaseReward(target, nextState);
                        }
                    }, 10000, TimeUnit.MILLISECONDS);
                } else {
                    client.player.setYaw((float)(Math.toDegrees(Math.atan2(dz, dx)) - 90));
                    client.options.forwardKey.setPressed(true);
                }
            });
        }, 0, 50, TimeUnit.MILLISECONDS);
        taskRef.set(task);
    }

    private void verifyCaseReward(BlockPos target, BotState nextState) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> { if (client.player != null) client.player.closeHandledScreen(); });

        if (rewardReceived) {
            sendTelegramDebug("✅ 10 секунд анимации истекли, чат подтвердил получение награды банка.");
            if (nextState == BotState.OPENING_CASE_1) {
                transitionTo(BotState.WALKING_TO_CASE_2);
            } else {
                didFirstFarm = true;
                startStavkaEngine();
            }
            return;
        }

        interactionAttempts++;
        if (interactionAttempts < 3) {
            sendTelegramDebug("⚠️ Награда не найдена за 10 секунд. Повторная попытка открытия кейса #" + (interactionAttempts + 1));
            currentState = (nextState == BotState.OPENING_CASE_1) ? BotState.WALKING_TO_CASE_1 : BotState.WALKING_TO_CASE_2;
            doWalkToCase(client, target, nextState);
        } else {
            sendTelegramDebug("❌ Все попытки открытия исчерпаны. Пропускаю блок кейса.");
            if (nextState == BotState.OPENING_CASE_1) transitionTo(BotState.WALKING_TO_CASE_2);
            else { didFirstFarm = true; startStavkaEngine(); }
        }
    }

    private void updateBalancesFromScoreboard() {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world == null) return;
            var scoreboard = client.world.getScoreboard();
            var objective = scoreboard.getObjectiveForSlot(net.minecraft.scoreboard.ScoreboardDisplaySlot.SIDEBAR);
            if (objective == null) return;

            for (var entry : scoreboard.getScoreboardEntries(objective)) {
                String clean = ChatHelper.stripColors(entry.name().getString()).toLowerCase().trim();
                if (clean.contains("баланс")) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(clean);
                    java.util.List<Integer> numbers = new java.util.ArrayList<>();
                    while (m.find()) {
                        numbers.add(Integer.parseInt(m.group()));
                    }
                    if (numbers.size() >= 2) {
                        this.myCoins = numbers.get(0);
                        this.myCC = numbers.get(1);
                        return;
                    }
                }
            }
        } catch (Exception e) {
            Secret.LOGGER.error("Scoreboard parsing fail: {}", e.getMessage());
        }
    }

    private void startStavkaEngine() {
        didFirstFarm = true;
        updateBalancesFromScoreboard();

        sendTelegramDebug("📊 Мой баланс доната: " + myCC + " CC. Баланс монет: " + myCoins);
        if (SecretClient.ipcServer != null) {
            SecretClient.ipcServer.send(new IpcMessage(IpcMessage.Type.BANK_READY, myCC + "|" + myCoins));
        }
        currentState = BotState.IDLE;
    }

    public void syncFromWorker(int workerCC, int workerCoins) {
        this.opponentCC = workerCC;
        this.pendingWorkerNick = SecretClient.accountManager.getCurrentWorkerNick();

        updateBalancesFromScoreboard();
        targetRoundAmount = Math.min(myCC, opponentCC);

        sendTelegramDebug("🎲 Синхронизация раунда: Банк (" + myCC + " CC) vs Твинк (" + opponentCC + " CC). Целевая ставка: " + targetRoundAmount + " CC.");

        if (targetRoundAmount <= 0) {
            sendTelegramDebug("🏁 Передача донат-валюты окончена. Баланс обнулен.");
            if (myCC == 0 && myCoins > 0) {
                sendTelegramDebug("💸 Передаю монеты твинку: /pay " + pendingWorkerNick + " " + myCoins);
                sendChat(MinecraftClient.getInstance(), "/pay " + pendingWorkerNick + " " + myCoins);
            }
            return;
        }

        if (myCC <= opponentCC) {
            sendTelegramDebug("🎰 У меня меньше CC. Создаю ставку " + targetRoundAmount + " CC...");
            stavkaMenuStep = "click_create";
            sendChat(MinecraftClient.getInstance(), "/stavka");
        } else {
            sendTelegramDebug("⏳ У Твинка меньше CC. Ожидаю, пока он выставит ставку...");
            if (SecretClient.ipcServer != null) {
                SecretClient.ipcServer.send(new IpcMessage(IpcMessage.Type.WORKER_CREATE_STAVKA, String.valueOf(targetRoundAmount)));
            }
        }
    }

    public void onWorkerCreatedStavkaFromIpc() {
        sendTelegramDebug("📩 Твинк выставил ставку. Пишу /stavka для принятия...");
        stavkaMenuStep = "click_view";
        sendChat(MinecraftClient.getInstance(), "/stavka");
    }

    @Override
    public void onScreenOpened(HandledScreen<?> screen) {
        scheduler.schedule(() -> {
            MinecraftClient client = MinecraftClient.getInstance();
            switch (currentState) {
                case HANDLING_FREE_MENU_1 -> { clickSlot(screen, 23); currentState = BotState.HANDLING_FREE_MENU_2; }
                case HANDLING_FREE_MENU_2 -> {
                    clickSlot(screen, 15);
                    client.execute(() -> { if (client.player != null) client.player.closeHandledScreen(); });
                    transitionTo(BotState.WALKING_TO_CASE_1);
                }
                case OPENING_CASE_1 -> {
                    sendTelegramDebug("🖱️ Кликаю слот 10 для запуска рулетки Кейса 1. Оставляю инвентарь открытым.");
                    clickSlot(screen, 10);
                }
                case OPENING_CASE_2 -> {
                    sendTelegramDebug("🖱️ Кликаю слот 10 для запуска рулетки Кейса 2. Оставляю инвентарь открытым.");
                    clickSlot(screen, 10);
                }
                default -> handleStavkaGuiFlow(screen, client);
            }
        }, 950, TimeUnit.MILLISECONDS);
    }

    private void handleStavkaGuiFlow(HandledScreen<?> screen, MinecraftClient client) {
        if (stavkaMenuStep.equals("click_create")) {
            sendTelegramDebug("🖱️ Клик слот 14 (Создать ставку)");
            clickSlot(screen, 14);
            stavkaMenuStep = "click_currency";
        } else if (stavkaMenuStep.equals("click_currency")) {
            sendTelegramDebug("静态 Клик слот 51 (Выбор донат валюты)");
            clickSlot(screen, 51);
            stavkaMenuStep = "adjust_amount";
        } else if (stavkaMenuStep.equals("adjust_amount")) {
            int current = 5;
            int diff = targetRoundAmount - current;
            long delay = 0;
            if (diff > 0) {
                for (int i = 0; i < diff; i++) {
                    scheduler.schedule(() -> { clickSlot(screen, 14); }, delay, TimeUnit.MILLISECONDS);
                    delay += 200;
                }
            } else if (diff < 0) {
                for (int i = 0; i < Math.abs(diff); i++) {
                    scheduler.schedule(() -> { clickSlot(screen, 13); }, delay, TimeUnit.MILLISECONDS);
                    delay += 200;
                }
            }
            scheduler.schedule(() -> {
                sendTelegramDebug("静态 Клик слот 26 (Подтвердить ставку)");
                clickSlot(screen, 26);
                stavkaMenuStep = "idle";
                client.execute(() -> { if (client.player != null) client.player.closeHandledScreen(); });
                if (SecretClient.ipcServer != null) {
                    SecretClient.ipcServer.send(new IpcMessage(IpcMessage.Type.BANK_CREATED_STAVKA, ""));
                }
            }, delay + 300, TimeUnit.MILLISECONDS);

        } else if (stavkaMenuStep.equals("click_view")) {
            sendTelegramDebug("🖱️ Клик слот 11 (Посмотреть ставки)");
            clickSlot(screen, 11);
            stavkaMenuStep = "find_bet";
        } else if (stavkaMenuStep.equals("find_bet")) {
            int targetSlot = findStavkaSlot(screen, pendingWorkerNick, targetRoundAmount);
            if (targetSlot != -1) {
                sendTelegramDebug("✅ Ставка твинка найдена в слоте " + targetSlot + ". Нажимаю...");
                clickSlot(screen, targetSlot);
                stavkaMenuStep = "confirm_accept";
            } else {
                sendTelegramDebug("❌ Не удалось найти ставку твинка в слотах 10-16. Закрываю.");
                client.execute(() -> { if (client.player != null) client.player.closeHandledScreen(); });
                stavkaMenuStep = "idle";
            }
        } else if (stavkaMenuStep.equals("confirm_accept")) {
            sendTelegramDebug("🖱️ Клик слот 11 (Подтвердить принятие)");
            clickSlot(screen, 11);
            stavkaMenuStep = "idle";
            client.execute(() -> { if (client.player != null) client.player.closeHandledScreen(); });
        }
    }

    private int findStavkaSlot(HandledScreen<?> screen, String nick, int amount) {
        ScreenHandler handler = screen.getScreenHandler();
        for (int slot = 10; slot <= 16; slot++) {
            if (slot >= handler.slots.size()) break;
            var stack = handler.slots.get(slot).getStack();
            if (stack.isEmpty()) continue;
            try {
                List<Text> tooltip = stack.getTooltip(net.minecraft.item.Item.TooltipContext.DEFAULT, null, net.minecraft.item.tooltip.TooltipType.BASIC);
                for (Text line : tooltip) {
                    String t = ChatHelper.stripColors(line.getString()).toLowerCase();
                    if (t.contains(nick.toLowerCase()) || t.contains(String.valueOf(amount))) return slot;
                }
            } catch (Exception ignored) {}
        }
        return -1;
    }

    @Override
    public void onCaptchaAnswer(String answer) {
        captchaSent = false;
        sendChat(MinecraftClient.getInstance(), answer);
    }

    private void doBanned() {
        stopAfkProtection();
        captchaSent = false;

        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> { if (client.player != null) client.disconnect(); });

        sendTelegramDebug("🚫 Запуск смены IP через WARP VPN...");
        new Thread(() -> {
            if (SecretClient.vpnController.changeIp()) {
                try { Thread.sleep(10000); } catch (InterruptedException ignored) {}
                if (isRunning.get() && currentState == BotState.BANNED) {
                    SecretClient.accountManager.switchToBank();
                    transitionTo(BotState.CONNECTING_TO_SERVER);
                }
            } else {
                scheduler.schedule(() -> {
                    if (isRunning.get() && currentState == BotState.BANNED) doBanned();
                }, 30, TimeUnit.SECONDS);
            }
        }, "Bank-VPN-Thread").start();
    }

    private void startAfkProtection(MinecraftClient client) {
        stopAfkProtection();
        afkTask = scheduler.scheduleAtFixedRate(() -> client.execute(() -> { if (client.player != null) client.player.jump(); }), 50, 50, TimeUnit.SECONDS);
    }

    private void stopAfkProtection() { if (afkTask != null) { afkTask.cancel(false); afkTask = null; } }

    private void onBankChat(String raw) {
        if (!isRunning.get()) return;
        String clean = ChatHelper.stripColors(raw).toLowerCase().trim();

        if (clean.contains("начислен") || clean.contains("забрал") || clean.contains("получен")) {
            rewardReceived = true;
        }

        if (clean.contains("твоя ставка выиграла") || (clean.contains("ставка игрока") && clean.contains("выиграла"))) {
            sendTelegramDebug("🎲 Раунд ставок завершен. Синхронизирую балансы через 4 секунды...");
            scheduler.schedule(() -> {
                if (isRunning.get() && currentState == BotState.IDLE) startStavkaEngine();
            }, 4, TimeUnit.SECONDS);
        }

        if (clean.contains("введи цифры") || clean.contains("введите цифры")) {
            if (!captchaSent) { captchaSent = true; captureAndSendScreenshot(MinecraftClient.getInstance(), "[БАНК]"); }
        }
        if (clean.contains("авторизуйт") || clean.contains("/l ")) {
            scheduler.schedule(() -> { if (isRunning.get()) sendChat(MinecraftClient.getInstance(), "/l " + Secret.BANK_PASSWORD); }, 1, TimeUnit.SECONDS);
        }
        if (clean.contains("зарегистрируйт") || clean.contains("/reg ")) {
            scheduler.schedule(() -> { if (isRunning.get()) sendChat(MinecraftClient.getInstance(), "/reg " + Secret.BANK_PASSWORD + " " + Secret.BANK_PASSWORD); }, 1, TimeUnit.SECONDS);
        }
        if (clean.contains("добро пожаловать") || clean.contains("welcome")) transitionTo(BotState.JOINING_QUEUE);
    }

    private void captureAndSendScreenshot(MinecraftClient client, String prefix) {
        scheduler.schedule(() -> client.execute(() -> {
            try {
                net.minecraft.client.texture.NativeImage image = net.minecraft.client.util.ScreenshotRecorder.takeScreenshot(client.getFramebuffer());
                File dir = new File(client.runDirectory, "screenshots"); if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, "captcha_tg.png"); image.writeTo(file); image.close();
                if (SecretClient.telegramBot != null) SecretClient.telegramBot.sendPhoto(file, prefix + " 🔐 КАПЧА!");
            } catch (Exception ignored) {}
        }), 1000, TimeUnit.MILLISECONDS);
    }

    private void clickSlot(HandledScreen<?> screen, int slot) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            try { client.interactionManager.clickSlot(screen.getScreenHandler().syncId, slot, 0, SlotActionType.PICKUP, client.player); } catch (Exception ignored) {}
        });
    }

    private void sendChat(MinecraftClient client, String message) {
        client.execute(() -> {
            if (client.player == null) return;
            if (message.startsWith("/")) client.player.networkHandler.sendCommand(message.substring(1));
            else client.player.networkHandler.sendChatMessage(message);
        });
    }

    private void registerChatListeners() { chatHandler = this::onBankChat; ChatListener.addHandler(chatHandler); }
    private long getDelay(BotState state) { return switch (state) { case CONNECTING_TO_SERVER -> 1000; case WAITING_SPAWN -> 3000; case WRITING_FREE -> 2000; default -> 800; }; }

    @Override public void onWorkerCreatedStavka(String nick, int amount) {}
    @Override public void onBankConnected(String bankNick) {}
    @Override public void onBankReady() {}
}