package org.vovochka.fun.secret.util;

import org.vovochka.fun.secret.Secret;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatHelper {

    public static boolean contains(String message, String... keywords) {
        if (message == null) return false;
        String clean = stripColors(message).toLowerCase();
        for (String kw : keywords) {
            if (!clean.contains(kw.toLowerCase())) return false;
        }
        return true;
    }

    public static boolean containsAny(String message, String... keywords) {
        if (message == null) return false;
        String clean = stripColors(message).toLowerCase();
        for (String kw : keywords) {
            if (clean.contains(kw.toLowerCase())) return true;
        }
        return false;
    }

    public static String stripColors(String text) {
        if (text == null) return "";
        return text.replaceAll("§[0-9a-fk-orA-FK-OR]", "")
                .replaceAll("\u00A7[0-9a-fk-orA-FK-OR]", "")
                .trim();
    }

    public static void logChat(String prefix, String raw) {
        String clean = stripColors(raw);
        if (!clean.isEmpty()) {
            Secret.LOGGER.info("[{}] CHAT: {}", prefix, clean);
        }
    }

    /**
     * Извлечь ник из "Ставка игрока NickName выиграла"
     */
    public static String extractNick(String message) {
        String clean = stripColors(message);
        Pattern p = Pattern.compile("(?:ставка игрока|игрок)\\s+(\\S+)", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(clean);
        if (m.find()) return m.group(1);
        return null;
    }

    /**
     * Найти первое число в строке
     */
    public static int parseFirstNumber(String text) {
        String clean = stripColors(text);
        Matcher m = Pattern.compile("(\\d+)").matcher(clean);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); }
            catch (NumberFormatException ignored) {}
        }
        return 0;
    }
}