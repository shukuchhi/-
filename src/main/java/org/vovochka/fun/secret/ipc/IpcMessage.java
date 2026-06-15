package org.vovochka.fun.secret.ipc;

/**
 * Сообщения между клиентами
 */
public class IpcMessage {

    public enum Type {
        // Банк → Твинк
        HELLO_BANK,          // Банк подключился, сообщает свой ник
        BANK_READY,          // Банк на сервере, готов принимать ставки
        ACCEPT_STAVKA,       // Банк принял ставку твинка

        // Твинк → Банк
        HELLO_WORKER,        // Твинк подключился
        WORKER_READY,        // Твинк на спавне готов
        STAVKA_CREATED,      // Твинк создал ставку (amount=КК)
        CYCLE_DONE,          // Цикл завершён

        // Оба направления
        STAVKA_WIN,          // Ставка выиграна (кто выиграл)
        STAVKA_LOSS,         // Ставка проиграна
        PING,                // Проверка связи
        PONG,                // Ответ
        ERROR,               // Ошибка

        // Новые типы для динамического раунда ставок
        WORKER_REPORT_BALANCES,  // Твинк отправляет Банку свои "cc|coins"
        WORKER_CREATE_STAVKA,    // Банк просит Твинка создать ставку на сумму X
        BANK_CREATED_STAVKA      // Банк сообщает Твинку, что создал ставку, можно принимать
    }

    public final Type type;
    public final String data;  // доп. данные (ник, сумма и т.д.)

    public IpcMessage(Type type, String data) {
        this.type = type;
        this.data = data != null ? data : "";
    }

    public IpcMessage(Type type) {
        this(type, "");
    }

    /**
     * Сериализация в строку для отправки по сокету
     */
    public String serialize() {
        return type.name() + "|" + data + "\n";
    }

    /**
     * Десериализация из строки
     */
    public static IpcMessage deserialize(String line) {
        if (line == null || line.isEmpty()) return null;
        String[] parts = line.trim().split("\\|", 2);
        try {
            Type type = Type.valueOf(parts[0]);
            String data = parts.length > 1 ? parts[1] : "";
            return new IpcMessage(type, data);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "IpcMessage{" + type + ", data='" + data + "'}";
    }
}