package org.vovochka.fun.secret.automation;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
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

import java.io.File;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Environment(EnvType.CLIENT)
public class WorkerBotStateMachine extends BotStateMachine {

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Worker-Scheduler"); t.setDaemon(true); return t;
    });
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private volatile BotState currentState = BotState.IDLE;

    private volatile boolean captchaSent = false;
    private volatile long lastScreenshotTime = 0;
    private volatile long lastConnectTime = 0;

    private volatile int myCC = -1;
    private volatile int myCoins = -1;
    private volatile int targetRoundAmount = 0;
    private volatile String bankNick = "";

    private volatile boolean rewardReceived = false;
    private volatile int interactionAttempts = 0;
    private volatile String stavkaMenuStep = "idle";
    private ScheduledFuture<?> watchdogTask;

    private static final BlockPos CASE_1 = new BlockPos(-15, 46, 153);
    private static final BlockPos CASE_2 = new BlockPos(-15, 46, 158);

    public WorkerBotStateMachine() { registerChatListeners(); }

    private void sendTelegramDebug(String msg) {
        if (SecretClient.telegramBot != null) {
            SecretClient.telegramBot.sendMessage("👷 [ТВИНК] " + msg);
        }
    }

    @Override
    public void start() {
        if (isRunning.compareAndSet(false, true)) {
            sendTelegramDebug("🚀 Твинк-автомат запущен.");
            watchdogTask = scheduler.scheduleAtFixedRate(this::runConnectionWatchdog, 5, 5, TimeUnit.SECONDS);
            transitionTo(BotState.CONNECTING_TO_SERVER);
        }
    }

    @Override
    public void stop() {
        isRunning.set(false); currentState = BotState.IDLE;
        if (watchdogTask != null) { watchdogTask.cancel(false); watchdogTask = null; }
        sendTelegramDebug("🛑 Твинк остановлен.");
    }

    private void runConnectionWatchdog() {
        if (!isRunning.get()) return;
        if (currentState == BotState.IDLE || currentState == BotState.CONNECTING_TO_SERVER || currentState == BotState.BANNED) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.currentScreen != null) {
            String screenName = client.currentScreen.getClass().getSimpleName().toLowerCase();
            if (screenName.contains("disconnect") || screenName.contains("kick")) {
                sendTelegramDebug("⚠️ [ВАТЧДОГ] Обнаружен кик/дисконнект твинка. Меняю IP...");
                transitionTo(BotState.BANNED);
            }
        }
    }

    @Override
    public void transitionTo(BotState newState) {
        if (!isRunning.get() && newState != BotState.IDLE) return;
        currentState = newState;
        sendTelegramDebug("🔄 Статус твинка -> " + newState.name());

        final BotState capturedState = newState;
        scheduler.schedule(() -> {
            try {
                if (currentState != capturedState || !isRunning.get()) return;
                handleWorkerState(capturedState);
            } catch (Exception ignored) {}
        }, getDelay(newState), TimeUnit.MILLISECONDS);
    }

    @Override public BotState getCurrentState() { return currentState; }
    @Override public boolean isRunning() { return isRunning.get(); }

    @Override public void onBankConnected(String nick) { this.bankNick = nick; }

    @Override
    public void onBankReady() {
        long delay = SecretClient.ipcClient != null ? 0 : 2000;
        scheduler.schedule(this::syncAndReportBalances, delay, TimeUnit.MILLISECONDS);
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
        } catch (Exception ignored) {}
    }

    private void syncAndReportBalances() {
        updateBalancesFromScoreboard();
        sendTelegramDebug("📊 Мой баланс доната: " + myCC + " CC.");
        if (SecretClient.ipcClient != null) {
            SecretClient.ipcClient.send(new IpcMessage(IpcMessage.Type.WORKER_REPORT_BALANCES, myCC + "|" + myCoins));
        }
    }

    public void runWorkerCreateStavkaFlow(int amount) {
        this.targetRoundAmount = amount;
        sendTelegramDebug("🎰 Запрос IPC: У меня меньше CC. Создаю ставку на " + targetRoundAmount + " CC.");
        stavkaMenuStep = "click_create";
        sendChat(MinecraftClient.getInstance(), "/stavka");
    }

    public void runWorkerAcceptStavkaFlow(int amount) {
        this.targetRoundAmount = amount;
    }

    public void onBankCreatedStavkaNotification() {
        sendTelegramDebug("🎰 Ставка банка создана. Иду принимать...");
        stavkaMenuStep = "click_view";
        sendChat(MinecraftClient.getInstance(), "/stavka");
    }

    private void handleWorkerState(BotState state) {
        MinecraftClient client = MinecraftClient.getInstance();
        switch (state) {
            case CONNECTING_TO_SERVER -> doConnect(client);
            case JOINING_QUEUE        -> doJoinQueue(client);
            case WAITING_SPAWN        -> transitionTo(BotState.WRITING_FREE);
            case WRITING_FREE         -> doWriteFree(client);
            case WALKING_TO_CASE_1    -> { interactionAttempts = 0; rewardReceived = false; doWalkToCase(client, CASE_1, BotState.OPENING_CASE_1); }
            case WALKING_TO_CASE_2    -> { interactionAttempts = 0; rewardReceived = false; doWalkToCase(client, CASE_2, BotState.OPENING_CASE_2); }
            case BANNED               -> doWorkerBanned();
            default -> {}
        }
    }

    private void doConnect(MinecraftClient client) {
        long now = System.currentTimeMillis();
        if (now - lastConnectTime < 5000) { scheduler.schedule(() -> { if (currentState == BotState.CONNECTING_TO_SERVER && isRunning.get()) doConnect(client); }, 5000, TimeUnit.MILLISECONDS); return; }
        lastConnectTime = now;
        try {
            ServerAddress addr = ServerAddress.parse(Secret.SERVER_IP);
            ServerInfo info = new ServerInfo("CountryMC", Secret.SERVER_IP, ServerInfo.ServerType.OTHER);
            info.setResourcePackPolicy(ServerInfo.ResourcePackPolicy.ENABLED);
            client.execute(() -> ConnectScreen.connect(client.currentScreen, client, addr, info, false, null));
        } catch (Exception e) { transitionTo(BotState.BANNED); }
    }

    private void doJoinQueue(MinecraftClient client) {
        sendChat(client, "/joinq sirius");
        scheduler.schedule(() -> { if (currentState == BotState.JOINING_QUEUE && isRunning.get()) transitionTo(BotState.WAITING_SPAWN); }, 7, TimeUnit.SECONDS);
    }

    private void doWriteFree(MinecraftClient client) { sendChat(client, "/free"); currentState = BotState.HANDLING_FREE_MENU_1; }

    private void doWalkToCase(MinecraftClient client, BlockPos target, BotState nextState) {
        if (client.player == null) return;
        sendTelegramDebug("🚶 [ПУТЬ] Движение к кейсу " + target.toShortString());

        final int[] ticks = {0}; final int[] stuckTicks = {0}; final Vec3d[] lastPos = {client.player.getPos()};
        final BotState expectedState = currentState;
        AtomicReference<ScheduledFuture<?>> taskRef = new AtomicReference<>();

        taskRef.set(scheduler.scheduleAtFixedRate(() -> client.execute(() -> {
            if (!isRunning.get() || currentState != expectedState) {
                ScheduledFuture<?> f = taskRef.get(); if (f != null) f.cancel(false); return;
            }
            if (client.player == null || client.interactionManager == null) return;
            ticks[0]++; Vec3d pos = client.player.getPos();
            double dx = target.getX() + 0.5 - pos.x, dz = target.getZ() + 0.5 - pos.z;
            if (ticks[0] % 10 == 0) {
                if (pos.distanceTo(lastPos[0]) < 0.05) { stuckTicks[0]++; if (stuckTicks[0] >= 2) { client.player.jump(); stuckTicks[0] = 0; } }
                else { stuckTicks[0] = 0; } lastPos[0] = pos;
            }
            if (Math.sqrt(dx * dx + dz * dz) < 2.0 || ticks[0] > 140) {
                taskRef.get().cancel(false); client.options.forwardKey.setPressed(false);
                Vec3d center = Vec3d.ofCenter(target);
                client.player.setYaw((float)(Math.toDegrees(Math.atan2(center.z - client.player.getZ(), center.x - client.player.getX())) - 90));
                client.player.setPitch((float)(-Math.toDegrees(Math.atan2(center.y - client.player.getEyeY(), Math.sqrt((center.x - client.player.getX())*(center.x - client.player.getX()) + (center.z - client.player.getZ())*(center.z - client.player.getZ()))))));
                client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, new BlockHitResult(center, Direction.UP, target, false));

                currentState = nextState;
                sendTelegramDebug("🎬 Кейс активирован твинком. Ожидаю 10 секунд анимации рулетки...");
                scheduler.schedule(() -> { if (isRunning.get() && currentState == nextState) verifyCaseReward(target, nextState); }, 10000, TimeUnit.MILLISECONDS);
            } else { client.player.setYaw((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90)); client.options.forwardKey.setPressed(true); }
        }), 0, 50, TimeUnit.MILLISECONDS));
    }

    private void verifyCaseReward(BlockPos target, BotState nextState) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> { if (client.player != null) client.player.closeHandledScreen(); });

        if (rewardReceived) {
            sendTelegramDebug("✅ 10 секунд анимации истекли, чат подтвердил начисление твинку. Иду дальше.");
            if (nextState == BotState.OPENING_CASE_1) transitionTo(BotState.WALKING_TO_CASE_2);
            else syncAndReportBalances();
            return;
        }
        interactionAttempts++;
        if (interactionAttempts < 3) {
            sendTelegramDebug("⚠️ Твинк не получил награду за 10 секунд. Ретрай, попытка #" + (interactionAttempts + 1));
            currentState = (nextState == BotState.OPENING_CASE_1) ? BotState.WALKING_TO_CASE_1 : BotState.WALKING_TO_CASE_2;
            doWalkToCase(client, target, nextState);
        } else {
            sendTelegramDebug("❌ Твинк исчерпал попытки открытия кейса. Двигаюсь дальше.");
            if (nextState == BotState.OPENING_CASE_1) transitionTo(BotState.WALKING_TO_CASE_2);
            else syncAndReportBalances();
        }
    }

    @Override
    public void onScreenOpened(HandledScreen<?> screen) {
        scheduler.schedule(() -> {
            MinecraftClient client = MinecraftClient.getInstance();
            switch (currentState) {
                case HANDLING_FREE_MENU_1 -> { clickSlot(screen, 23); currentState = BotState.HANDLING_FREE_MENU_2; }
                case HANDLING_FREE_MENU_2 -> { clickSlot(screen, 15); client.execute(() -> { if (client.player != null) client.player.closeHandledScreen(); }); transitionTo(BotState.WALKING_TO_CASE_1); }
                case OPENING_CASE_1 -> {
                    sendTelegramDebug("静态 Кликаю слот 10 для запуска рулетки Кейса 1 твинка.");
                    clickSlot(screen, 10);
                }
                case OPENING_CASE_2 -> {
                    sendTelegramDebug("静态 Кликаю слот 10 для запуска рулетки Кейса 2 твинка.");
                    clickSlot(screen, 10);
                }
                default -> handleStavkaGuiFlow(screen, client);
            }
        }, 950, TimeUnit.MILLISECONDS);
    }

    private void handleStavkaGuiFlow(HandledScreen<?> screen, MinecraftClient client) {
        if (stavkaMenuStep.equals("click_create")) {
            sendTelegramDebug("鼠标 Клик слот 14 (Создать ставку)");
            clickSlot(screen, 14);
            stavkaMenuStep = "click_currency";
        } else if (stavkaMenuStep.equals("click_currency")) {
            sendTelegramDebug("鼠标 Клик слот 51 (Выбор донат валюты)");
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
                sendTelegramDebug("鼠标 Клик слот 26 (Финальное подтверждение создания)");
                clickSlot(screen, 26);
                stavkaMenuStep = "idle";
                client.execute(() -> { if (client.player != null) client.player.closeHandledScreen(); });
                if (SecretClient.ipcClient != null) {
                    SecretClient.ipcClient.send(new IpcMessage(IpcMessage.Type.STAVKA_CREATED, "done"));
                }
            }, delay + 300, TimeUnit.MILLISECONDS);

        } else if (stavkaMenuStep.equals("click_view")) {
            sendTelegramDebug("鼠标 Клик слот 11 (Посмотреть ставки)");
            clickSlot(screen, 11);
            stavkaMenuStep = "find_bet";
        } else if (stavkaMenuStep.equals("find_bet")) {
            String bNick = bankNick.isEmpty() ? SecretClient.accountManager.getBankNick() : bankNick;
            int targetSlot = findStavkaSlot(screen, bNick, targetRoundAmount);
            if (targetSlot != -1) {
                sendTelegramDebug("✅ Ставка банка найдена в слоте " + targetSlot + ". Принимаю...");
                clickSlot(screen, targetSlot);
                stavkaMenuStep = "confirm_accept";
            } else {
                sendTelegramDebug("❌ Не нашёл ставку банка в слотах 10-16. Выхожу.");
                client.execute(() -> { if (client.player != null) client.player.closeHandledScreen(); });
                stavkaMenuStep = "idle";
            }
        } else if (stavkaMenuStep.equals("confirm_accept")) {
            sendTelegramDebug("鼠标 Клик слот 11 (Подтвердить принятие)");
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

    @Override public void onCaptchaAnswer(String a) { captchaSent = false; sendChat(MinecraftClient.getInstance(), a); }

    private void doWorkerBanned() {
        captchaSent = false;
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> { if (client.player != null) client.disconnect(); });

        sendTelegramDebug("🚫 Переключаю IP через VPN...");
        new Thread(() -> {
            if (SecretClient.vpnController.changeIp()) {
                try { Thread.sleep(10000); } catch (InterruptedException ignored) {}
                if (isRunning.get() && currentState == BotState.BANNED) {
                    SecretClient.accountManager.switchToNextWorker();
                    transitionTo(BotState.CONNECTING_TO_SERVER);
                }
            } else { scheduler.schedule(() -> { if (isRunning.get() && currentState == BotState.BANNED) doWorkerBanned(); }, 30, TimeUnit.SECONDS); }
        }, "Worker-VPN-Thread").start();
    }

    private void registerChatListeners() {
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) -> onWorkerChat(message.getString()));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onWorkerChat(message.getString()));
    }

    private void onWorkerChat(String raw) {
        if (!isRunning.get()) return;
        String clean = raw.toLowerCase();

        if (clean.contains("начислен") || clean.contains("забрал") || clean.contains("получен")) {
            rewardReceived = true;
        }

        if (clean.contains("твоя ставка выиграла") || (clean.contains("ставка игрока") && clean.contains("выиграла"))) {
            sendTelegramDebug("🎲 Раунд ставок завершен. Синхронизирую балансы твинка через 4 секунды...");
            scheduler.schedule(() -> { if (isRunning.get()) syncAndReportBalances(); }, 4, TimeUnit.SECONDS);
        }

        if (ChatHelper.contains(raw, "введи", "цифры") || ChatHelper.contains(raw, "введите", "цифры")) {
            long now = System.currentTimeMillis();
            if (now - lastScreenshotTime > 20000) { lastScreenshotTime = now; captchaSent = true; captureAndSendScreenshot(MinecraftClient.getInstance(), "[ТВИНК]"); }
        }
        if (ChatHelper.containsAny(raw, "авторизуйтесь", "/l ")) {
            scheduler.schedule(() -> { if (isRunning.get()) { SecretClient.accountManager.rotateCurrentWorkerNick(); MinecraftClient.getInstance().execute(() -> { if (MinecraftClient.getInstance().player != null) MinecraftClient.getInstance().disconnect(); }); transitionTo(BotState.CONNECTING_TO_SERVER); } }, 500, TimeUnit.MILLISECONDS);
        } else if (ChatHelper.containsAny(raw, "зарегистрируйтесь", "/reg ")) {
            scheduler.schedule(() -> { if (isRunning.get()) sendChat(MinecraftClient.getInstance(), "/reg " + Secret.BANK_PASSWORD + " " + Secret.BANK_PASSWORD); }, 1, TimeUnit.SECONDS);
        } else if (ChatHelper.containsAny(raw, "добро пожаловать", "welcome")) { captchaSent = false; transitionTo(BotState.JOINING_QUEUE); }
    }

    private void clickSlot(HandledScreen<?> screen, int slot) {
        MinecraftClient.getInstance().execute(() -> { try { MinecraftClient.getInstance().interactionManager.clickSlot(screen.getScreenHandler().syncId, slot, 0, SlotActionType.PICKUP, MinecraftClient.getInstance().player); } catch (Exception ignored) {} });
    }

    private void sendChat(MinecraftClient client, String message) {
        client.execute(() -> { if (client.player != null) { if (message.startsWith("/")) client.player.networkHandler.sendCommand(message.substring(1)); else client.player.networkHandler.sendChatMessage(message); } });
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

    @Override public void onWorkerCreatedStavka(String n, int a) {}
    private long getDelay(BotState state) { return switch (state) { case CONNECTING_TO_SERVER -> 1000; case WAITING_SPAWN -> 5000; default -> 800; }; }
}