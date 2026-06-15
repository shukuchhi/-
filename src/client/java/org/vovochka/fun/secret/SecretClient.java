package org.vovochka.fun.secret;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import org.vovochka.fun.secret.account.AccountManager;
import org.vovochka.fun.secret.automation.BotStateMachine;
import org.vovochka.fun.secret.bot.TelegramBotHandler;
import org.vovochka.fun.secret.ipc.IpcClient;
import org.vovochka.fun.secret.ipc.IpcServer;
import org.vovochka.fun.secret.network.VpnController;
import org.vovochka.fun.secret.util.ChatListener;

public class SecretClient implements ClientModInitializer {

    public static TelegramBotHandler telegramBot;
    public static VpnController vpnController;
    public static AccountManager accountManager;
    public static BotStateMachine stateMachine;
    public static IpcServer ipcServer;
    public static IpcClient ipcClient;
    public static ClientRole clientRole = ClientRole.UNKNOWN;

    public enum ClientRole { UNKNOWN, BANK, WORKER }

    private static KeyBinding startBankKey;
    private static KeyBinding startWorkerKey;
    private static KeyBinding stopKey;

    @Override
    public void onInitializeClient() {
        // 1. ID клиента
        ClientIdentity.getOrCreate();

        // 2. ПЕРВЫМ ДЕЛОМ регистрируем слушатель чата
        ChatListener.register();

        Secret.LOGGER.info("=== {} запущен ===", ClientIdentity.getLabel());

        // 3. Выбираем токен по ID клиента
        String botToken = ClientIdentity.getId() == 1
                ? Secret.BOT_TOKEN_1
                : Secret.BOT_TOKEN_2;

        // 4. Инициализация компонентов
        vpnController = new VpnController();
        accountManager = new AccountManager();
        telegramBot = new TelegramBotHandler(botToken, Secret.ADMIN_CHAT_ID);

        // 5. Запуск Telegram бота
        try {
            telegramBot.start();
            Secret.LOGGER.info("Telegram bot #{} started!", ClientIdentity.getId());
        } catch (Exception e) {
            Secret.LOGGER.error("Telegram bot failed: {}", e.getMessage());
        }

        // 6. Горячие клавиши
        startBankKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.secret.start_bank",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F7,
                "category.secret"
        ));
        startWorkerKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.secret.start_worker",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F8,
                "category.secret"
        ));
        stopKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.secret.stop",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F9,
                "category.secret"
        ));

        // 7. Тик-обработчик
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);

        // 8. GUI обработчик
        ScreenEvents.AFTER_INIT.register(this::onScreenInit);

        // 9. Приветствие
        new Thread(() -> {
            try {
                Thread.sleep(3000);
                if (telegramBot != null) {
                    telegramBot.sendMessage(
                            "🎮 " + ClientIdentity.getLabel() + " запущен!\n\n" +
                                    "Команды:\n" +
                                    "/bank — стать банком\n" +
                                    "/worker — стать твинком\n" +
                                    "/stop — стоп\n" +
                                    "/status — статус\n" +
                                    "/nicks — все ники\n\n" +
                                    "Капча: просто напишите цифры!"
                    );
                }
            } catch (InterruptedException ignored) {}
        }, "Welcome-Thread").start();

        Secret.LOGGER.info("{} initialized! F7=Bank F8=Worker F9=Stop",
                ClientIdentity.getLabel());
    }

    private void onTick(MinecraftClient client) {
        while (startBankKey.wasPressed()) {
            if (telegramBot != null) telegramBot.startAsBank();
        }
        while (startWorkerKey.wasPressed()) {
            if (telegramBot != null) telegramBot.startAsWorker();
        }
        while (stopKey.wasPressed()) {
            if (telegramBot != null) telegramBot.stopAll();
        }
    }

    private void onScreenInit(MinecraftClient client, Screen screen,
                              int scaledWidth, int scaledHeight) {
        if (screen instanceof HandledScreen<?> hs && stateMachine != null) {
            stateMachine.onScreenOpened(hs);
        }
    }
}