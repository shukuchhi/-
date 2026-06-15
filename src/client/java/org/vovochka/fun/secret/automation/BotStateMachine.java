package org.vovochka.fun.secret.automation;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;

/**
 * Базовый класс для BankBotStateMachine и WorkerBotStateMachine
 */
public abstract class BotStateMachine {

    public abstract void start();
    public abstract void stop();
    public abstract void transitionTo(BotState newState);
    public abstract BotState getCurrentState();
    public abstract boolean isRunning();

    public abstract void onScreenOpened(HandledScreen<?> screen);
    public abstract void onCaptchaAnswer(String answer);

    /**
     * Вызывается на каждом игровом тике клиента для плавной ходьбы и контроля движения.
     */
    public void tickMovement(MinecraftClient client) {}

    // IPC колбэки
    public abstract void onBankConnected(String bankNick);
    public abstract void onBankReady();
    public abstract void onWorkerCreatedStavka(String nick, int amount);
}