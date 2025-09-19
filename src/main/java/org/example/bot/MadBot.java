package org.example.bot;

import com.google.gson.Gson;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.sql.*;
//Со стороны участниц надо: в начале появляется приветственная картинка, которая лежит в отдельной папке (чтобы я могла её менять)
//Вместе с ней появляется текст о том, что нужно внести номер, который у участниц написан на карточке
//Когда участницы вводят номер, то он за ними закрепляется, им приходит сообщение типа твой номер такой-то,
//ожидайте, ваш звонок очень важен для нас и т.д. и т.п.

public class MadBot extends TelegramLongPollingBot {

    private static final String DB_URL = "jdbc:sqlite:bot.db";
    private static final Long ADMIN_ID = 257023213L;
    private static final Integer MAX_USERS = 40;
    private Map<Long, String> userNumbers = new HashMap<>();
    private Gson gson = new Gson();
    private static final String DATA_FILE = "user_numbers.json";

    public MadBot() {
        initDatabase();
    }

    @Override
    public String getBotUsername() {
        return "Galentine bot";
    }

    @Override
    public String getBotToken() {
        return "8332201148:AAHQ5jvjWWrqcgP_kF1DFrVYyYt6nEwB11k";
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            String text = update.getMessage().getText().trim();
            Long chatId = update.getMessage().getChatId();
            String username = update.getMessage().getFrom().getUserName();
            // 🔹 Команды админа
            if (chatId.equals(ADMIN_ID)) {
                if (text.equals("/list")) {
                    sendMessage(chatId, listUsers());
                    return;
                }
                if (text.startsWith("/update")) {
                    String[] parts = text.split("\\s+");
                    if (parts.length == 3) {
                        try {
                            Long newNumber = Long.parseLong(parts[2]);
                            Long oldNumber = Long.parseLong(parts[1]);
                            updateUser(oldNumber, newNumber);
                            sendMessage(chatId, "✅ Номер у " + parts[1] + " обновлён на " + newNumber);
                        } catch (NumberFormatException e) {
                            sendMessage(chatId, "❌ Формат: /update <chatId> <новый номер>");
                        }
                    } else {
                        sendMessage(chatId, "❌ Формат: /update <chatId> <новый номер>");
                    }
                    return;
                }
                if (text.startsWith("/delete")) {
                    String[] parts = text.split("\\s+");
                    if (parts.length == 2) {
                        try {
                            Long memberNumber = Long.parseLong(parts[1]);
                            deleteUser(memberNumber);
                            sendMessage(chatId, "🗑 Номер у " + parts[1] + " удалён.");
                        } catch (NumberFormatException e) {
                            sendMessage(chatId, "❌ Формат: /delete <chatId>");
                        }
                    } else {
                        sendMessage(chatId, "❌ Формат: /delete <chatId>");
                    }
                    return;
                }
            }
            // 🔹 Поведение для участниц
            if (text.equals("/start")) {
                sendWelcome(chatId);
                return;
            }
            // 🔹 Попытка ввести номер
            if (text.matches("\\d+")) {
                Long number = Long.parseLong(text);
                if (number < 1 || number > MAX_USERS) {
                    sendMessage(chatId, "⚠️ Номер должен быть от 1 до " + MAX_USERS + ".");
                    return;
                }
                if (userExists(chatId)) {
                    String existingNumber = getUserNumber(chatId);
                    sendMessage(chatId, "Ваш номер уже закреплён: " + existingNumber +
                            "\nЕсли нужно изменить, свяжитесь с организаторкой.");
                    return;
                }
                if (isNumberTaken(number)) {
                    sendMessage(chatId, "❌ Этот номер уже занят.");
                    return;
                }
                if (!text.matches("\\d+")) {
                    sendMessage(chatId, "Введите, пожалуйста, номер");
                }
                saveUser(chatId, username, number);
                sendMessage(chatId, "✅ Спасибо! Вы зарегистрированы под номером " + number + ".");
                return;
            }
        }
    }

    private void saveData() {
        try (FileWriter writer = new FileWriter(DATA_FILE)) {
            gson.toJson(userNumbers, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Отправка картинки + текста
    private void sendWelcome(Long chatId) {
        SendPhoto photo = new SendPhoto();
        photo.setChatId(chatId);
        photo.setPhoto(new InputFile(new File("src/main/resources/images/welcome.gif")));
        photo.setCaption("Добро пожаловать! Введите номер с вашей карточки:");

        try {
            execute(photo);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    // Универсальная отправка текста
    private void sendMessage(Long chatId, String text) {
        SendMessage message = new SendMessage(chatId.toString(), text);
        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    // --- Работа с базой данных ---
    private void initDatabase() {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS users (chat_id INTEGER PRIMARY KEY, number TEXT)");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
    // --- Проверка: участник уже зарегистрирован? ---
    private boolean userExists(Long chatId) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM users WHERE chat_id=?")) {
            ps.setLong(1, chatId);
            ResultSet rs = ps.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }
    // --- Получение номера участницы ---
    private String getUserNumber(Long chatId) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement("SELECT number FROM users WHERE chat_id=?")) {
            ps.setLong(1, chatId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString("number");
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }
    private void handleRegistration(Long userId, Long number, String username, Long chatId) {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            // Проверяем, зарегистрирован ли юзер
            PreparedStatement checkUser = conn.prepareStatement("SELECT number FROM users WHERE user_id = ?");
            checkUser.setLong(1, userId);
            ResultSet rs = checkUser.executeQuery();

            if (rs.next()) {
                // Уже есть запись
                Long existingNumber = rs.getLong("number");
                sendMessage(chatId, "Ты уже зарегистрирована под номером " + existingNumber + ". Изменить номер нельзя.");
                return;
            }

            // Проверяем, занят ли номер
            if (isNumberTaken(number)) {
                sendMessage(chatId, "Этот номер уже занят.");
                return;
            }

            // Сохраняем нового пользователя
            PreparedStatement insert = conn.prepareStatement(
                    "INSERT INTO users(user_id, number, username) VALUES (?, ?, ?)"
            );
            insert.setLong(1, userId);
            insert.setLong(2, number);
            insert.setString(3, username);
            insert.executeUpdate();

            sendMessage(chatId, "Спасибо! Ты зарегистрирована под номером " + number + ".");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
    // --- Проверка: номер занят? ---
    private boolean isNumberTaken(Long number) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM users WHERE number=?")) {
            ps.setLong(1, number);
            return ps.executeQuery().next();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }
    // --- Сохранение участницы ---
    private void saveUser(Long chatId, String username, Long number) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement("INSERT INTO users(chat_id, username, number) VALUES (?, ?, ?)")) {
            ps.setLong(1, chatId);
            ps.setString(2, username != null ? "@" + username : "Без ника");
            ps.setLong(3, number);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private String listUsers() {
        StringBuilder sb = new StringBuilder("📋 Список пользователей:\n");
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM users")) {
            while (rs.next()) {
                sb.append("ID: ").append(rs.getLong("chat_id"))
                        .append(" → номер: ").append(rs.getString("number")).append("\n");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return sb.toString().isEmpty() ? "Список пуст" : sb.toString();
    }

    private void deleteUser(Long chatId) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement("DELETE FROM users WHERE chat_id=?")) {
            ps.setLong(1, chatId);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void updateUser(Long chatId, Long newNumber) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement("UPDATE users SET number=? WHERE chat_id=?")) {
            ps.setLong(1, newNumber);
            ps.setLong(2, chatId);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }

    }
}