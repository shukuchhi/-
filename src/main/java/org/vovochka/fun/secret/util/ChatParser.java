package org.vovochka.fun.secret.util;

import org.vovochka.fun.secret.Secret;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatParser {

    private static final Pattern KK_PATTERN =
            Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*[КкKk]{1,2}");
    private static final Pattern NUMBER_PATTERN =
            Pattern.compile("(\\d+)");

    /**
     * Удалить Minecraft цветовые коды (§x)
     */
    public static String stripFormatting(String text) {
        return text.replaceAll("§[0-9a-fk-orA-FK-OR]", "").trim();
    }

    /**
     * Парсить КК из строки. "5К" → 5, "10KK" → 10
     */
    public static int parseKK(String text) {
        String clean = stripFormatting(text);
        Matcher matcher = KK_PATTERN.matcher(clean);
        if (matcher.find()) {
            try {
                String num = matcher.group(1).replace(",", ".");
                return (int) Double.parseDouble(num);
            } catch (NumberFormatException e) {
                Secret.LOGGER.error("Failed to parse KK: {}", text);
            }
        }
        return 0;
    }

    /**
     * Найти первое число в строке
     */
    public static int parseFirstNumber(String text) {
        String clean = stripFormatting(text);
        Matcher matcher = NUMBER_PATTERN.matcher(clean);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * Проверить содержит ли строка ключевое слово (без учёта форматирования)
     */
    public static boolean contains(String text, String keyword) {
        return stripFormatting(text).toLowerCase()
                .contains(keyword.toLowerCase());
    }

    /**
     * Извлечь ник из "Ставка игрока NickName выиграла"
     */
    public static String extractNickFromStavka(String message) {
        Pattern pattern = Pattern.compile("Ставка игрока\\s+(\\S+)");
        Matcher matcher = pattern.matcher(stripFormatting(message));
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}