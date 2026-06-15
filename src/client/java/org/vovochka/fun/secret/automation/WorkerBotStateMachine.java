package org.vovochka.fun.secret.automation;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.screen.slot.SlotActionType;
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

    private int workerKK = 5;
    private String bankNickFromIpc = "";

    private static final BlockPos CASE_1 = new BlockPos(-15, 46, 153);
    private static final BlockPos CASE_2 = new BlockPos(-15, 46, 158);

    public WorkerBotStateMachine() { registerChatListeners(); }

    @Override
    public void start() {
        if (isRunning.compareAndSet(false, true)) {
            Secret.LOGGER.info("[WORKER] Starting...");
            transitionTo(BotState.CONNECTING_TO_SERVER);
        }
    }

    @Override public void stop() { isRunning.set(false); currentState = BotState.IDLE; }

    @Override
    public void transitionTo(BotState newState) {
        if (!isRunning.get() && newState != BotState.IDLE) return;
        Secret.LOGGER.info("[WORKER] {} -> {}", currentState, newState);
        currentState = newState;
        scheduler.schedule(() -> {
            try { handleWorkerState(newState); }
            catch (Exception e) { Secret.LOGGER.error("[WORKER] Error: {}", e.getMessage()); }
        }, getDelay(newState), TimeUnit.MILLISECONDS);
    }

    @Override public BotState getCurrentState() { return currentState; }
    @Override public boolean isRunning() { return isRunning.get(); }

    @Override public void onBankConnected(String bankNick) { this.bankNickFromIpc = bankNick; }
    @Override public void onBankReady() { Secret.LOGGER.info("[WORKER] Bank ready!"); }
    @Override public void onWorkerCreatedStavka(String n, int a) {}

    private void handleWorkerState(BotState state) {
        MinecraftClient client = MinecraftClient.getInstance();
        switch (state) {
            case CONNECTING_TO_SERVER -> doConnect(client);
            case JOINING_QUEUE        -> doJoinQueue(client);
            case WAITING_SPAWN        -> transitionTo(BotState.WRITING_FREE);
            case WRITING_FREE         -> doWriteFree(client);
            case WALKING_TO_CASE_1    -> doWalkToCase(client, CASE_1, BotState.OPENING_CASE_1);
            case WALKING_TO_CASE_2    -> doWalkToCase(client, CASE_2, BotState.OPENING_CASE_2);
            case PAYING_BANK          -> doPayBank(client);
            case CREATING_STAVKA      -> doCreateStavka(client);
            case CYCLE_COMPLETE       -> doCycleComplete(client);
            case BANNED               -> doWorkerBanned();
            default -> {}
        }
    }

    private void doConnect(MinecraftClient client) {
        long now = System.currentTimeMillis();
        if (now - lastConnectTime < 5000) {
            scheduler.schedule(() -> doConnect(client), 5000, TimeUnit.MILLISECONDS);
            return;
        }
        lastConnectTime = now;
        try {
            ServerAddress addr = ServerAddress.parse(Secret.SERVER_IP);
            ServerInfo info = new ServerInfo("CountryMC", Secret.SERVER_IP, ServerInfo.ServerType.OTHER);
            client.execute(() -> ConnectScreen.connect(client.currentScreen, client, addr, info, false, null));
        } catch (Exception e) { transitionTo(BotState.BANNED); }
    }

    private void doJoinQueue(MinecraftClient client) {
        sendChat(client, "/joinq sirius");
        scheduler.schedule(() -> transitionTo(BotState.WAITING_SPAWN), 7, TimeUnit.SECONDS);
    }

    private void doWriteFree(MinecraftClient client) {
        sendChat(client, "/free");
        currentState = BotState.HANDLING_FREE_MENU_1;
    }

    private void doWalkToCase(MinecraftClient client, BlockPos target, BotState nextState) {
        if (client.player == null) return;
        final int[] ticks = {0};
        AtomicReference<ScheduledFuture<?>> taskRef = new AtomicReference<>();
        taskRef.set(scheduler.scheduleAtFixedRate(() -> {
            if (client.player == null) return;
            ticks[0]++;
            Vec3d pos = client.player.getPos();
            double dx = target.getX() + 0.5 - pos.x, dz = target.getZ() + 0.5 - pos.z;
            if (Math.sqrt(dx * dx + dz * dz) < 3.5 || ticks[0] > 80) {
                taskRef.get().cancel(false);
                client.execute(() -> {
                    if (client.player == null) return;
                    client.options.forwardKey.setPressed(false);
                    client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, new BlockHitResult(Vec3d.ofCenter(target), Direction.UP, target, false));
                    currentState = nextState;
                });
            } else {
                client.execute(() -> {
                    if (client.player == null) return;
                    client.player.setYaw((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90));
                    client.options.forwardKey.setPressed(true);
                });
            }
        }, 0, 200, TimeUnit.MILLISECONDS));
    }

    private void doPayBank(MinecraftClient client) {
        String bank = bankNickFromIpc.isEmpty() ? SecretClient.accountManager.getBankNick() : bankNickFromIpc;
        sendChat(client, "/pay " + bank + " 50");
        scheduler.schedule(() -> transitionTo(BotState.CREATING_STAVKA), 2, TimeUnit.SECONDS);
    }

    private void doCreateStavka(MinecraftClient client) {
        sendChat(client, "/stavka");
        scheduler.schedule(() -> {
            if (client.currentScreen instanceof HandledScreen<?> s) {
                clickSlot(s, 14);
                scheduler.schedule(() -> {
                    if (client.currentScreen instanceof HandledScreen<?> s2) {
                        clickSlot(s2, 51);
                        scheduler.schedule(() -> {
                            if (client.currentScreen instanceof HandledScreen<?> s3) {
                                adjustStavkaAmount(s3, workerKK);
                                scheduler.schedule(() -> {
                                    if (client.currentScreen instanceof HandledScreen<?> s4) {
                                        clickSlot(s4, 27);
                                        if (SecretClient.ipcClient != null) SecretClient.ipcClient.send(new IpcMessage(IpcMessage.Type.STAVKA_CREATED, SecretClient.accountManager.getCurrentUsername() + "|" + workerKK));
                                        currentState = BotState.WAITING_STAVKA_RESULT;
                                    }
                                }, 2000, TimeUnit.MILLISECONDS);
                            }
                        }, 1000, TimeUnit.MILLISECONDS);
                    }
                }, 1000, TimeUnit.MILLISECONDS);
            }
        }, 2000, TimeUnit.MILLISECONDS);
    }

    private void doCycleComplete(MinecraftClient client) {
        if (SecretClient.ipcClient != null) SecretClient.ipcClient.send(new IpcMessage(IpcMessage.Type.CYCLE_DONE, ""));
        client.execute(() -> { if (client.player != null) client.disconnect(); });
        scheduler.schedule(() -> { SecretClient.accountManager.switchToNextWorker(); transitionTo(BotState.CONNECTING_TO_SERVER); }, 3, TimeUnit.SECONDS);
    }

    private void doWorkerBanned() {
        captchaSent = false;
        new Thread(() -> {
            if (SecretClient.vpnController.changeIp()) {
                try { Thread.sleep(10000); } catch (InterruptedException ignored) {}
                SecretClient.accountManager.switchToNextWorker();
                transitionTo(BotState.CONNECTING_TO_SERVER);
            } else { scheduler.schedule(this::doWorkerBanned, 30, TimeUnit.SECONDS); }
        }).start();
    }

    @Override
    public void onScreenOpened(HandledScreen<?> screen) {
        scheduler.schedule(() -> {
            switch (currentState) {
                case HANDLING_FREE_MENU_1 -> { clickSlot(screen, 24); currentState = BotState.HANDLING_FREE_MENU_2; }
                case HANDLING_FREE_MENU_2 -> clickSlot(screen, 16);
                case OPENING_CASE_1 -> { clickSlot(screen, 11); scheduler.schedule(() -> transitionTo(BotState.WALKING_TO_CASE_2), 3, TimeUnit.SECONDS); }
                case OPENING_CASE_2 -> { clickSlot(screen, 11); scheduler.schedule(() -> transitionTo(BotState.PAYING_BANK), 3, TimeUnit.SECONDS); }
            }
        }, 600, TimeUnit.MILLISECONDS);
    }

    @Override public void onCaptchaAnswer(String answer) { captchaSent = false; sendChat(MinecraftClient.getInstance(), answer); }

    private void registerChatListeners() {
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) -> onWorkerChat(message.getString()));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onWorkerChat(message.getString()));
    }

    private void onWorkerChat(String raw) {
        if (!isRunning.get()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (ChatHelper.contains(raw, "введи", "цифры") || ChatHelper.contains(raw, "введите", "цифры")) {
            long now = System.currentTimeMillis();
            if (now - lastScreenshotTime > 20000) {
                lastScreenshotTime = now;
                captchaSent = true;
                captureAndSendScreenshot(client, "[ТВИНК]");
            }
            return;
        }
        if (ChatHelper.containsAny(raw, "авторизуйтесь", "/l ")) {
            scheduler.schedule(() -> { SecretClient.accountManager.rotateCurrentWorkerNick(); client.execute(client::disconnect); transitionTo(BotState.CONNECTING_TO_SERVER); }, 500, TimeUnit.MILLISECONDS);
        } else if (ChatHelper.containsAny(raw, "зарегистрируйтесь", "/reg ")) {
            scheduler.schedule(() -> sendChat(client, "/reg " + Secret.BANK_PASSWORD + " " + Secret.BANK_PASSWORD), 1, TimeUnit.SECONDS);
        } else if (ChatHelper.containsAny(raw, "добро пожаловать", "welcome")) {
            captchaSent = false;
            transitionTo(BotState.JOINING_QUEUE);
        }
    }

    private long getDelay(BotState state) {
        return switch (state) {
            case CONNECTING_TO_SERVER -> 1000;
            case WAITING_SPAWN        -> 5000;
            default                   -> 800;
        };
    }

    private void adjustStavkaAmount(HandledScreen<?> screen, int target) {
        int current = 5, btn = target > current ? 15 : 13;
        for (int i = 0; i < Math.abs(target - current); i++) scheduler.schedule(() -> clickSlot(screen, btn), i * 150L, TimeUnit.MILLISECONDS);
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

    private void captureAndSendScreenshot(MinecraftClient client, String prefix) {
        SecretClient.telegramBot.sendMessage(prefix + " 📸 Делаю скриншот капчи...");
        client.execute(() -> {
            try {
                net.minecraft.client.util.ScreenshotRecorder.saveScreenshot(client.runDirectory, client.getFramebuffer(), (text) -> {
                    scheduler.schedule(() -> {
                        File dir = new File(client.runDirectory, "screenshots");
                        for (int i = 0; i < 3; i++) {
                            File latest = getLatestFile(dir);
                            if (latest != null && (System.currentTimeMillis() - latest.lastModified() < 10000)) {
                                SecretClient.telegramBot.sendPhoto(latest, prefix + "🔐 КАПЧА!");
                                return;
                            }
                            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                        }
                    }, 1500, TimeUnit.MILLISECONDS);
                });
            } catch (Exception ignored) {}
        });
    }

    private File getLatestFile(File dir) {
        File[] files = dir.listFiles(f -> f.getName().endsWith(".png"));
        if (files == null || files.length == 0) return null;
        File last = files[0];
        for (File f : files) if (f.lastModified() > last.lastModified()) last = f;
        return last;
    }
}