package org.vovochka.fun.secret;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Secret implements ModInitializer {
    public static final String MOD_ID = "secret";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Сервер
    public static final String SERVER_IP = "play.countrymc.net";
    public static final String BANK_PASSWORD = "123123zZz";

    // Бот #1 - для Клиента #1 (БАНК)
    public static final String BOT_TOKEN_1 = "8903695075:AAFkpHFvOfp1Y_pj-M_eldruel8T4twC-vs";

    // Бот #2 - для Клиента #2 (ТВИНК)
    public static final String BOT_TOKEN_2 = "8859348784:AAEMKgKPCLxqnG6a9UPvGfXqcQ0WZRTRzBM";

    // Твой Telegram ID (одинаков для обоих ботов)
    public static final long ADMIN_CHAT_ID = 1767791884L;

    @Override
    public void onInitialize() {
        LOGGER.info("Secret mod loaded!");
    }
}