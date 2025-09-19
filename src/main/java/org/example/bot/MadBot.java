package org.example.bot;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.*;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.sql.*;
//Со стороны участниц надо: в начале появляется приветственная картинка, которая лежит в отдельной папке (чтобы я могла её менять)
//Вместе с ней появляется текст о том, что нужно внести номер, который у участниц написан на карточке
//Когда участницы вводят номер, то он за ними закрепляется, им приходит сообщение типа твой номер такой-то,
//ожидайте, ваш звонок очень важен для нас и т.д. и т.п.

public class MadBot extends TelegramLongPollingBot {

    private static final String DATA_FILE = "user_numbers.json";

    // Простое хранилище: chatId -> номер
    private Map<String, String> userNumbers = new HashMap<>();
    private Gson json = new Gson();
    private static final String DB_URL = "jdbc:sqlite:users.db";
    private static final String ADMIN_ID = "123456789L";

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
            String chatId = update.getMessage().getChatId().toString();
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
                            long targetId = Long.parseLong(parts[1]);
                            String newNumber = parts[2];
                            updateUser(targetId, newNumber);
                            sendMessage(chatId, "✅ Номер у " + targetId + " обновлён на " + newNumber);
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
                            long targetId = Long.parseLong(parts[1]);
                            deleteUser(targetId);
                            sendMessage(chatId, "🗑 Номер у " + targetId + " удалён.");
                        } catch (NumberFormatException e) {
                            sendMessage(chatId, "❌ Формат: /delete <chatId>");
                        }
                    } else {
                        sendMessage(chatId, "❌ Формат: /delete <chatId>");
                    }
                    return;
                }
            }

            if (text.equals("/start")) {
                sendWelcome(chatId);
            } else if (text.matches("\\d+")) { // если ввели номер
                if (userNumbers.containsKey(chatId)) {
                    sendMessage(chatId, "Ваш номер уже закреплён: " + userNumbers.get(chatId) +
                            "\nЕсли нужно изменить, свяжитесь с администратором.");
                } else {
                    userNumbers.put(chatId, text);
                    saveData();
                    sendMessage(chatId, "Ваш номер: " + text +
                            "\nОжидайте, ваш звонок очень важен для нас ☎️");
                }
            } else {
                sendMessage(chatId, "Введите, пожалуйста, номер с вашей карточки (только цифры).");
            }
        }
    }

    // Отправка картинки + текста
    private void sendWelcome(String chatId) {
        SendPhoto photo = new SendPhoto();
        photo.setChatId(chatId.toString());
        photo.setPhoto(new InputFile(new File("src/main/resources/images/welcome.gif")));
        photo.setCaption("Добро пожаловать! Введите номер с вашей карточки:");

        try {
            execute(photo);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    // Универсальная отправка текста
    private void sendMessage(String chatId, String text) {
        SendMessage message = new SendMessage(chatId, text);
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

    private void saveUser(Long chatId, String number) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement("INSERT INTO users(chat_id, number) VALUES (?, ?)")) {
            ps.setLong(1, chatId);
            ps.setString(2, number);
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
    private void updateUser(Long chatId, String newNumber) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement("UPDATE users SET number=? WHERE chat_id=?")) {
            ps.setString(1, newNumber);
            ps.setLong(2, chatId);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }

}