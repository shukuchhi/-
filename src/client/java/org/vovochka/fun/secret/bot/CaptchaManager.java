package org.vovochka.fun.secret.bot;

import org.vovochka.fun.secret.Secret;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Менеджер капчи
 * Собирает датасет скринов + ответов для будущего обучения модели
 */
public class CaptchaManager {

    private static final Path DATASET_DIR = Paths.get("captcha_dataset");
    private final List<CaptchaEntry> dataset = new ArrayList<>();

    public CaptchaManager() {
        // Создаём папку для датасета
        try {
            Files.createDirectories(DATASET_DIR);
            Secret.LOGGER.info("Captcha dataset directory: {}", DATASET_DIR.toAbsolutePath());
        } catch (IOException e) {
            Secret.LOGGER.error("Failed to create dataset directory: {}", e.getMessage());
        }
    }

    /**
     * Сохранить запись в датасет (скрин + ответ)
     */
    public void saveToDataset(File screenshot, String answer) {
        try {
            String timestamp = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));

            // Копируем скрин в датасет
            Path destPath = DATASET_DIR.resolve("captcha_" + timestamp + "_" + answer + ".png");
            Files.copy(screenshot.toPath(), destPath);

            dataset.add(new CaptchaEntry(destPath.toFile(), answer));
            Secret.LOGGER.info("Saved captcha to dataset: {} -> {}", destPath.getFileName(), answer);

        } catch (IOException e) {
            Secret.LOGGER.error("Failed to save captcha to dataset: {}", e.getMessage());
        }
    }

    public List<CaptchaEntry> getDataset() {
        return dataset;
    }

    public record CaptchaEntry(File screenshot, String answer) {}
}