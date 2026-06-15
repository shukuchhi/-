package org.vovochka.fun.secret.account;

import org.vovochka.fun.secret.Secret;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class NicknameGenerator {

    private static final Path NICKS_FILE = Paths.get("config", "secret_nicks.txt");

    // Огромный расширенный список префиксов (более 100+ комбинаций)
    private static final String[] PREFIXES = {
            "Alex", "Max", "Pro", "Dark", "Cool", "Neo", "Sky", "Red", "Blue", "Fast",
            "Top", "Big", "Hot", "Ice", "Fire", "Wind", "Star", "Wolf", "Fox", "Bear",
            "Gamer", "Knight", "Viper", "Shadow", "Ghost", "Titan", "Alpha", "Omega",
            "Zeus", "Frost", "Blaze", "Storm", "Rider", "Rogue", "Pixel", "Nexus",
            "Quantum", "Vertex", "Slayer", "Mystic", "Cyber", "Hyper", "Sonic", "Laser",
            "Gold", "Iron", "Steel", "Stone", "Rock", "Diamond", "Emerald", "Ruby"
    };

    // Расширенный список суффиксов
    private static final String[] SUFFIXES = {
            "123", "321", "007", "999", "777", "2025", "xD", "Pro", "GG", "PvP",
            "1337", "404", "HD", "YT", "X", "Z", "K", "V", "M", "N", "B", "Play", "Win",
            "Master", "Boss", "King", "Lord", "Elite", "Prime", "Force", "Squad", "Zone"
    };

    private static final String CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";
    private final Random random = new Random();

    /**
     * Пул генерации расширен. Координация стилей гарантирует 500,000+ вариаций.
     * Ники никогда не закончатся, бот может держать сотни аккаунтов.
     */
    public String generateNick() {
        int style = random.nextInt(5);
        return switch (style) {
            case 0 -> PREFIXES[random.nextInt(PREFIXES.length)] + (random.nextInt(9000) + 1000);
            case 1 -> PREFIXES[random.nextInt(PREFIXES.length)] + SUFFIXES[random.nextInt(SUFFIXES.length)];
            case 2 -> generateRandom(random.nextInt(4) + 6); // Полный случайный буквенно-цифровой ник от 6 до 10 знаков
            case 3 -> PREFIXES[random.nextInt(PREFIXES.length)] +
                    PREFIXES[random.nextInt(PREFIXES.length)].toLowerCase() +
                    (random.nextInt(90) + 10);
            default -> PREFIXES[random.nextInt(PREFIXES.length)] + SUFFIXES[random.nextInt(SUFFIXES.length)] + (random.nextInt(90) + 10);
        };
    }

    private String generateRandom(int length) {
        StringBuilder sb = new StringBuilder();
        sb.append((char)('A' + random.nextInt(26)));
        for (int i = 1; i < length; i++) {
            sb.append(CHARS.charAt(random.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    public String generateBankNick() {
        String[] names = {"Vault", "Treasury", "Reserve", "Depot", "Bank", "Base", "Store", "Safe", "Fort", "Secure"};
        return names[random.nextInt(names.length)] + (random.nextInt(900) + 100);
    }

    public List<String> generateUnique(int count, Set<String> existing) {
        List<String> result = new ArrayList<>();
        int attempts = 0;
        while (result.size() < count && attempts < count * 100) {
            String nick = generateNick();
            attempts++;
            if (!existing.contains(nick) && !result.contains(nick) &&
                    nick.length() >= 3 && nick.length() <= 16 && !nick.isEmpty()) {
                result.add(nick);
            }
        }
        return result;
    }

    public NickStorage loadOrCreate(int workerCount) {
        try {
            Files.createDirectories(NICKS_FILE.getParent());

            if (Files.exists(NICKS_FILE)) {
                NickStorage loaded = loadFromFile(workerCount);
                if (loaded != null) return loaded;
            }

            return createNew(workerCount);

        } catch (IOException e) {
            Secret.LOGGER.error("Failed to load nicks: {}", e.getMessage());
            return createNew(workerCount);
        }
    }

    private NickStorage loadFromFile(int workerCount) throws IOException {
        List<String> lines = Files.readAllLines(NICKS_FILE);

        String bankNick = null;
        List<String> workerNicks = new ArrayList<>();

        for (String line : lines) {
            line = line.trim();

            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            if (bankNick == null) {
                bankNick = line;
                Secret.LOGGER.info("Loaded bank nick: '{}'", bankNick);
            } else {
                workerNicks.add(line);
                Secret.LOGGER.info("Loaded worker nick: '{}'", line);
            }
        }

        if (bankNick == null || bankNick.isEmpty()) {
            Secret.LOGGER.warn("Nicks file has no valid nicks, creating new...");
            return null;
        }

        if (workerNicks.size() < workerCount) {
            Set<String> existing = new HashSet<>(workerNicks);
            existing.add(bankNick);
            int need = workerCount - workerNicks.size();
            List<String> newNicks = generateUnique(need, existing);
            workerNicks.addAll(newNicks);
            saveToFile(bankNick, workerNicks);
            Secret.LOGGER.info("Generated {} extra workers", newNicks.size());
        }

        Secret.LOGGER.info("Loaded: bank='{}', workers={}", bankNick, workerNicks);
        return new NickStorage(bankNick, workerNicks);
    }

    private NickStorage createNew(int workerCount) {
        String bankNick = generateBankNick();
        Set<String> existing = new HashSet<>();
        existing.add(bankNick);
        List<String> workers = generateUnique(workerCount, existing);

        saveToFile(bankNick, workers);

        Secret.LOGGER.info("Created new nicks: bank='{}', workers={}", bankNick, workers);
        return new NickStorage(bankNick, workers);
    }

    public void saveToFile(String bankNick, List<String> workerNicks) {
        try {
            Files.createDirectories(NICKS_FILE.getParent());

            StringBuilder sb = new StringBuilder();
            sb.append("# Secret Farm - Ники\n");
            sb.append("# Формат: первая строка = банк, остальные = твинки\n");
            sb.append("# Не трогайте этот файл вручную!\n");
            sb.append("\n");
            sb.append(bankNick).append("\n");
            sb.append("\n");
            for (String nick : workerNicks) {
                sb.append(nick).append("\n");
            }

            Files.writeString(NICKS_FILE, sb.toString());
            Secret.LOGGER.info("Nicks saved to: {}", NICKS_FILE.toAbsolutePath());

        } catch (IOException e) {
            Secret.LOGGER.error("Failed to save nicks: {}", e.getMessage());
        }
    }

    public String addNewWorker(NickStorage storage) {
        Set<String> existing = new HashSet<>(storage.workerNicks());
        existing.add(storage.bankNick());

        List<String> newNicks = generateUnique(1, existing);
        if (newNicks.isEmpty()) return null;

        String nick = newNicks.get(0);
        storage.workerNicks().add(nick);
        saveToFile(storage.bankNick(), storage.workerNicks());
        return nick;
    }

    public String replaceWorkerNick(NickStorage storage, String oldNick) {
        Set<String> existing = new HashSet<>(storage.workerNicks());
        existing.add(storage.bankNick());

        List<String> newNicks = generateUnique(1, existing);
        if (newNicks.isEmpty()) return null;

        String newNick = newNicks.get(0);
        int idx = storage.workerNicks().indexOf(oldNick);
        if (idx >= 0) {
            storage.workerNicks().set(idx, newNick);
        } else {
            storage.workerNicks().add(newNick);
        }

        saveToFile(storage.bankNick(), storage.workerNicks());
        return newNick;
    }

    public record NickStorage(String bankNick, List<String> workerNicks) {
        public NickStorage(String bankNick, List<String> workerNicks) {
            this.bankNick = bankNick;
            this.workerNicks = new ArrayList<>(workerNicks);
        }
    }
}