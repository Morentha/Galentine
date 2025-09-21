//package org.example.bot;
//
//import org.telegram.telegrambots.bots.TelegramLongPollingBot;
//import org.telegram.telegrambots.meta.api.methods.send.SendAnimation;
//import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
//import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
//import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
//import org.telegram.telegrambots.meta.api.objects.InputFile;
//import org.telegram.telegrambots.meta.api.objects.Update;
//import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
//import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
//import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
//
//import java.io.*;
//import java.sql.*;
//import java.util.ArrayList;
//import java.util.HashMap;
//import java.util.List;
//import java.util.Map;
//import java.util.stream.Collectors;
//
//
////Со стороны участниц надо: в начале появляется приветственная картинка, которая лежит в отдельной папке (чтобы я могла её менять)
////Вместе с ней появляется текст о том, что нужно внести номер, который у участниц написан на карточке
////Когда участницы вводят номер, то он за ними закрепляется, им приходит сообщение типа твой номер такой-то,
////ожидайте, ваш звонок очень важен для нас и т.д. и т.п.
//
//public class MadBot extends TelegramLongPollingBot {
//
//    private static final String DB_URL = "jdbc:sqlite:bot.db";
//    private static final Long ADMIN_ID = 257023213L;
//    private static final Integer MAX_USERS = 40;
//    private static final String WELCOME_IMAGE_PATH = "src/main/resources/images/welcome.png";
//    private static final String QUESTIONS_PATH = "src/main/resources/questions";
//
//
//    public MadBot() {
//        initDatabase();
//        loadQuestionImages();
//    }
//
//    // Состояния бота
//    private enum BotState {
//        IDLE,
//        AWAITING_VOTES,
//        VOTING_COMPLETED
//    }
//
//    private BotState currentState = BotState.IDLE;
//    private int currentQuestionIndex = 0;
//    private Map<Long, Integer> currentQuestionResponses = new HashMap<>();
//    private Map<Integer, Map<Long, Integer>> allResponses = new HashMap<>();
//    private Integer adminMessageId = null;
//
//    @Override
//    public String getBotUsername() {
//        return "Galentine bot";
//    }
//
//    @Override
//    public String getBotToken() {
//        return "8332201148:AAHQ5jvjWWrqcgP_kF1DFrVYyYt6nEwB11k";
//    }
//
//    @Override
//    public void onUpdateReceived(Update update) {
//        if (update.hasMessage() && update.getMessage().hasText()) {
//            String text = update.getMessage().getText().trim();
//            Long chatId = update.getMessage().getChatId();
//
//            // 🔹 Команды админа
//            if (chatId.equals(ADMIN_ID)) {
//                if (text.equals("/list")) {
//                    sendMessage(chatId, listUsers());
//                    return;
//                }
//                if (text.startsWith("/update")) {
//                    String[] parts = text.split("\\s+");
//                    if (parts.length == 3) {
//                        try {
//                            Long newNumber = Long.parseLong(parts[2]);
//                            Long oldNumber = Long.parseLong(parts[1]);
//                            updateUser(oldNumber, newNumber);
//                            sendMessage(chatId, "✅ Номер у " + parts[1] + " обновлён на " + newNumber);
//                        } catch (NumberFormatException e) {
//                            sendMessage(chatId, "❌ Формат: /update <chatId> <новый номер>");
//                        }
//                    } else {
//                        sendMessage(chatId, "❌ Формат: /update <chatId> <новый номер>");
//                    }
//                    return;
//                }
//                if (text.startsWith("/delete")) {
//                    String[] parts = text.split("\\s+");
//                    if (parts.length == 2) {
//                        try {
//                            Long memberNumber = Long.parseLong(parts[1]);
//                            deleteUser(memberNumber);
//                            sendMessage(chatId, "🗑 Номер у " + parts[1] + " удалён.");
//                        } catch (NumberFormatException e) {
//                            sendMessage(chatId, "❌ Формат: /delete <chatId>");
//                        }
//                    } else {
//                        sendMessage(chatId, "❌ Формат: /delete <chatId>");
//                    }
//                    return;
//                }
//                if (text.equals("/start_voting")) {
//                    startVoting(chatId);
//                    return;
//                }
//                if (text.equals("/next_question")) {
//                    sendNextQuestion(chatId);
//                    return;
//                }
//                if (text.equals("/show_results")) {
//                    showResults(chatId);
//                }
//                if (text.startsWith("/nuke")) {
//                    String[] parts = text.split("\\s+");
//                    if (parts.length == 2 && parts[1].equals("confirm")) {
//                        nukeDatabase();
//                        sendMessage(chatId, "💥 База данных полностью очищена!");
//                        return;
//                    } else {
//                        sendMessage(chatId, "⚠️ ВНИМАНИЕ! Эта команда УДАЛИТ ВСЕ ДАННЫЕ.\n" +
//                                "Для подтверждения введите: /nuke confirm");
//                        return;
//                    }
//                }
//            } else if (text.equals("/clear_users")) {
//                clearAllUsers();
//                sendMessage(chatId, "✅ Список участниц очищен.");
//            } // Уведомление админа о новой регистрации
//            String username = getUsername(chatId);
//            String userLink = username != null ?
//                    "<a href=\"tg://user?id=" + chatId + "\">" + username + "</a>" :
//                    "Пользователь " + chatId;
//            sendMessage(ADMIN_ID, "👤 " + userLink + " зарегистрировался под номером " + number);
//
//            currentState = BotState.AWAITING_VOTES;
//            currentQuestionIndex = 0;
//            currentQuestionResponses.clear();
//            allResponses.clear();
//
//            sendMessage(chatId, "🗳 Голосование начато!");
//            sendNextQuestion(chatId);
//
//
//            // 🔹 Поведение для участниц
//            if (text.equals("/start")) {
//                sendWelcome(chatId);
//                return;
//            }
//
//            // 🔹 Попытка ввести номер
//            if (text.matches("\\d+")) {
//                Long number = Long.parseLong(text);
//                if (number < 1 || number > MAX_USERS) {
//                    sendMessage(chatId, "⚠️ Номер должен быть от 1 до " + MAX_USERS + ".");
//                    return;
//                }
//                handleRegistration(chatId, number);
//            } else {
//                sendMessage(chatId, "Введите, пожалуйста, номер");
//            }
//        }
//    }
//
//    private void startVoting(Long chatId) {
//        if (currentState != BotState.IDLE) {
//            sendMessage(chatId, "❌ Голосование уже начато!");
//        }
//    }
//
//    // Отправка картинки + текста
//    // Отправка приветственной картинки + текста
//    private void sendWelcome(Long chatId) {
//        // Список возможных форматов изображений (в порядке приоритета)
//        String[] formats = {".gif", ".mp4", ".png", ".jpg", ".jpeg"};
//        File welcomeImage = null;
//
//        // Ищем файл с любым из поддерживаемых форматов
//        for (String format : formats) {
//            File testFile = new File("src/main/resources/images/welcome" + format);
//            if (testFile.exists()) {
//                welcomeImage = testFile;
//                break;
//            }
//        }
//
//        if (welcomeImage != null && welcomeImage.exists()) {
//            try {
//                // Определяем тип контента по расширению файла
//                String fileName = welcomeImage.getName().toLowerCase();
//                InputFile inputFile = new InputFile(welcomeImage);
//
//                if (fileName.endsWith(".gif")) {
//                    // Отправка как анимированного GIF
//                    SendAnimation animation = new SendAnimation();
//                    animation.setChatId(chatId.toString());
//                    animation.setAnimation(inputFile);
//                    animation.setCaption("Добро пожаловать! Введите номер с вашей карточки:");
//                    execute(animation);
//                } else if (fileName.endsWith(".mp4")) {
//                    // Отправка как видео
//                    SendVideo video = new SendVideo();
//                    video.setChatId(chatId.toString());
//                    video.setVideo(inputFile);
//                    video.setCaption("Добро пожаловать! Введите номер с вашей карточки:");
//                    execute(video);
//                } else {
//                    // Отправка как обычного фото
//                    SendPhoto photo = new SendPhoto();
//                    photo.setChatId(chatId.toString());
//                    photo.setPhoto(inputFile);
//                    photo.setCaption("Добро пожаловать! Введите номер с вашей карточки:");
//                    execute(photo);
//                }
//            } catch (TelegramApiException e) {
//                // Если не удалось отправить изображение, отправляем текстовое приветствие
//                sendMessage(chatId, "Добро пожаловать! Введите номер с вашей карточки:");
//                System.err.println("Ошибка отправки изображения: " + e.getMessage());
//            }
//        } else {
//            sendMessage(chatId, "Добро пожаловать! Введите номер с вашей карточки:");
//        }
//    }
//
//    // Универсальная отправка текста
//    private void sendMessage(Long chatId, String text) {
//        SendMessage message = new SendMessage(chatId.toString(), text);
//        try {
//            execute(message);
//        } catch (TelegramApiException e) {
//            e.printStackTrace();
//        }
//    }
//
//    private void handleCallbackQuery(Update update) {
//        String callbackData = update.getCallbackQuery().getData();
//        Long chatId = update.getCallbackQuery().getMessage().getChatId();
//        Integer messageId = update.getCallbackQuery().getMessage().getMessageId();
//
//        if (callbackData.startsWith("vote_")) {
//            int votedNumber = Integer.parseInt(callbackData.substring(5));
//
//            // Проверяем, не голосует ли пользователь за себя
//            Integer userNumber = getUserNumberFromDB(chatId);
//            if (userNumber != null && userNumber == votedNumber) {
//                sendMessage(chatId, "❌ Нельзя голосовать за свой собственный номер!");
//                return;
//            }
//
//            currentQuestionResponses.put(chatId, votedNumber);
//
//            // Уведомление о принятии голоса
//            sendMessage(chatId, "✅ Ваш голос за номер " + votedNumber + " принят!");
//            sendMessage(chatId, "Ваш голос очень важен для нас! Оставайтесь на линии, ожидаем остальных участниц");
//
//            // Удаляем клавиатуру после голосования
//            editMessageReplyMarkup(chatId, messageId, null);
//
//            // Обновляем статус у админа
//            updateAdminStatus();
//
//            // Уведомление админа о новом голосе
//            if (!chatId.equals(ADMIN_ID)) {
//                String username = getUsername(chatId);
//                sendMessage(ADMIN_ID, "✅ " + (username != null ? username : "Участник") + " проголосовал(а) за номер " + votedNumber);
//            }
//        } else if (callbackData.equals("admin_menu")) {
//            showAdminMenu(chatId);
//        } else if (callbackData.equals("ask_question")) {
//            sendNextQuestion(chatId);
//        } else if (callbackData.equals("list_users")) {
//            sendMessage(chatId, listUsersWithVotes());
//        } else if (callbackData.equals("clear_users")) {
//            clearAllUsers();
//            sendMessage(chatId, "✅ Список участниц очищен.");
//            showAdminMenu(chatId);
//        } else if (callbackData.equals("show_results")) {
//            showResults(chatId);
//        }
//    }
//
//    private void sendNextQuestion(Long chatId) {
//        if (currentQuestionIndex >= questionImages.size()) {
//            sendMessage(chatId, "❌ Все вопросы уже отправлены!");
//            return;
//        }
//
//        String imagePath = questionImages.get(currentQuestionIndex);
//        File imageFile = new File(imagePath);
//
//        if (!imageFile.exists()) {
//            sendMessage(chatId, "❌ Изображение вопроса не найдено: " + imagePath);
//            return;
//        }
//
//        // Сохраняем предыдущие ответы
//        if (currentQuestionIndex > 0) {
//            allResponses.put(currentQuestionIndex - 1, new HashMap<>(currentQuestionResponses));
//        }
//
//        // Очищаем ответы для нового вопроса
//        currentQuestionResponses.clear();
//
//        // Отправляем вопрос всем зарегистрированным пользователям
//        List<Long> allUsers = getAllUsers();
//        for (Long userId : allUsers) {
//            try {
//                SendPhoto photo = new SendPhoto();
//                photo.setChatId(userId.toString());
//                photo.setPhoto(new InputFile(imageFile));
//                photo.setCaption("Вопрос " + (currentQuestionIndex + 1) + ". Выберите номер:");
//
//                // Добавляем клавиатуру с доступными номерами (исключая свой)
//                photo.setReplyMarkup(createVotingKeyboard(userId));
//
//                execute(photo);
//            } catch (TelegramApiException e) {
//                System.err.println("Ошибка отправки вопроса пользователю " + userId + ": " + e.getMessage());
//            }
//        }
//
//        // Отправляем вопрос админу тоже
//        try {
//            SendPhoto adminPhoto = new SendPhoto();
//            adminPhoto.setChatId(ADMIN_ID.toString());
//            adminPhoto.setPhoto(new InputFile(imageFile));
//            adminPhoto.setCaption("Вопрос " + (currentQuestionIndex + 1) + " отправлен участникам");
//            execute(adminPhoto);
//        } catch (TelegramApiException e) {
//            System.err.println("Ошибка отправки вопроса админу: " + e.getMessage());
//        }
//
//        // Обновляем статус админа
//        updateAdminStatus();
//
//        currentQuestionIndex++;
//    }
//
//    private InlineKeyboardMarkup createVotingKeyboard(Long userId) {
//        List<Long> registeredNumbers = getRegisteredNumbers();
//        Integer userNumber = getUserNumberFromDB(userId);
//
//        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
//        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
//        List<InlineKeyboardButton> currentRow = new ArrayList<>();
//
//        // Создаем кнопки для каждого номера (исключая свой)
//        for (Long number : registeredNumbers) {
//            if (userNumber != null && number.equals(userNumber)) {
//                continue; // Пропускаем собственный номер
//            }
//
//            InlineKeyboardButton button = new InlineKeyboardButton();
//            button.setText(number.toString());
//            button.setCallbackData("vote_" + number);
//
//            currentRow.add(button);
//
//            // Создаем новую строку после каждых 5 кнопок
//            if (currentRow.size() >= 5) {
//                rows.add(currentRow);
//                currentRow = new ArrayList<>();
//            }
//        }
//
//        // Добавляем оставшиеся кнопки
//        if (!currentRow.isEmpty()) {
//            rows.add(currentRow);
//        }
//
//        markup.setKeyboard(rows);
//        return markup;
//    }
//
//    private void showResults(Long chatId) {
//        if (currentQuestionResponses.isEmpty()) {
//            sendMessage(chatId, "❌ Голосов еще нет!");
//            return;
//        }
//
//        // Сохраняем текущие ответы
//        allResponses.put(currentQuestionIndex - 1, new HashMap<>(currentQuestionResponses));
//
//        // Считаем голоса
//        Map<Integer, Integer> voteCounts = new HashMap<>();
//        for (Integer vote : currentQuestionResponses.values()) {
//            voteCounts.put(vote, voteCounts.getOrDefault(vote, 0) + 1);
//        }
//
//        // Формируем текст результатов
//        StringBuilder results = new StringBuilder("🏆 Результаты голосования (Вопрос " + currentQuestionIndex + "):\n\n");
//
//        // Сортируем по убыванию голосов
//        List<Map.Entry<Integer, Integer>> sortedResults = voteCounts.entrySet()
//                .stream()
//                .sorted((e1, e2) -> e2.getValue().compareTo(e1.getValue()))
//                .collect(Collectors.toList());
//
//        for (int i = 0; i < sortedResults.size(); i++) {
//            Map.Entry<Integer, Integer> entry = sortedResults.get(i);
//            results.append(i + 1).append(". Номер ").append(entry.getKey())
//                    .append(": ").append(entry.getValue()).append(" голосов\n");
//        }
//
//        // Отправляем результаты всем
//        List<Long> allUsers = getAllUsers();
//        for (Long userId : allUsers) {
//            sendMessage(userId, results.toString());
//        }
//
//        // Отправляем админу дополнительную информацию
//        sendMessage(ADMIN_ID, results + "\n\n" + makeStatusText());
//
//        currentState = BotState.VOTING_COMPLETED;
//    }
//
//    private String makeStatusText() {
//        int total = getAllUsers().size();
//        int answered = currentQuestionResponses.size();
//
//        StringBuilder text = new StringBuilder("📊 Статус голосования (вопрос " + currentQuestionIndex + "):\n\n");
//
//        List<Long> allUsers = getAllUsers();
//        for (Long userId : allUsers) {
//            String username = getUsername(userId);
//            Integer number = getUserNumberFromDB(userId);
//
//            if (currentQuestionResponses.containsKey(userId)) {
//                Integer vote = currentQuestionResponses.get(userId);
//                text.append("✅ ").append(username != null ? username : "Участник")
//                        .append(" (№").append(number).append(") - за ").append(vote).append("\n");
//            } else {
//                text.append("❌ ").append(username != null ? username : "Участник")
//                        .append(" (№").append(number).append(")\n");
//            }
//        }
//
//        text.append("\nПроголосовали: ").append(answered).append("/").append(total);
//        return text.toString();
//    }
//
//    private void updateAdminStatus() {
//        if (adminMessageId != null) {
//            try {
//                SendMessage message = new SendMessage();
//                message.setChatId(ADMIN_ID.toString());
//                message.setText(makeStatusText());
//                message.setReplyMarkup(createAdminKeyboard());
//                message.setMessageId(adminMessageId);
//                execute(message);
//            } catch (TelegramApiException e) {
//                System.err.println("Ошибка обновления статуса админа: " + e.getMessage());
//            }
//        } else {
//            SendMessage message = new SendMessage();
//            message.setChatId(ADMIN_ID.toString());
//            message.setText(makeStatusText());
//            message.setReplyMarkup(createAdminKeyboard());
//            try {
//                SendMessage sentMessage = execute(message);
//                adminMessageId = sentMessage.getMessageId();
//            } catch (TelegramApiException e) {
//                System.err.println("Ошибка отправки статуса админа: " + e.getMessage());
//            }
//        }
//    }
//
//    private InlineKeyboardMarkup createAdminKeyboard() {
//        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();
//
//        // Кнопка следующего вопроса
//        List<InlineKeyboardButton> row1 = new ArrayList<>();
//        InlineKeyboardButton nextQuestionBtn = new InlineKeyboardButton();
//        nextQuestionBtn.setText("➕ Задать следующий вопрос");
//        nextQuestionBtn.setCallbackData("ask_question");
//        row1.add(nextQuestionBtn);
//
//        // Кнопка списка пользователей
//        List<InlineKeyboardButton> row2 = new ArrayList<>();
//        InlineKeyboardButton listUsersBtn = new InlineKeyboardButton();
//        listUsersBtn.setText("👥 Список участниц");
//        listUsersBtn.setCallbackData("list_users");
//        row2.add(listUsersBtn);
//
//        // Кнопка очистки пользователей
//        List<InlineKeyboardButton> row3 = new ArrayList<>();
//        InlineKeyboardButton clearUsersBtn = new InlineKeyboardButton();
//        clearUsersBtn.setText("🗑 Очистить список участниц");
//        clearUsersBtn.setCallbackData("clear_users");
//        row3.add(clearUsersBtn);
//
//        // Кнопка показа результатов
//        List<InlineKeyboardButton> row4 = new ArrayList<>();
//        InlineKeyboardButton showResultsBtn = new InlineKeyboardButton();
//        showResultsBtn.setText("📋 Показать результаты");
//        showResultsBtn.setCallbackData("show_results");
//        row4.add(showResultsBtn);
//
//        keyboard.add(row1);
//        keyboard.add(row2);
//        keyboard.add(row3);
//        keyboard.add(row4);
//
//        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
//        markup.setKeyboard(keyboard);
//        return markup;
//    }
//
//    private void showAdminMenu(Long chatId) {
//        SendMessage message = new SendMessage();
//        message.setChatId(chatId.toString());
//        message.setText("Меню администратора:");
//        message.setReplyMarkup(createAdminKeyboard());
//
//        try {
//            execute(message);
//        } catch (TelegramApiException e) {
//            System.err.println("Ошибка отправки меню админа: " + e.getMessage());
//        }
//    }
//
//    private void clearAllUsers() {
//        nukeDatabase();
//        currentQuestionResponses.clear();
//        allResponses.clear();
//        currentQuestionIndex = 0;
//        currentState = BotState.IDLE;
//        adminMessageId = null;
//    }
//
//    private void clearAllData() {
//        clearAllUsers();
//        // Дополнительная очистка если нужно
//    }
//
//    private void loadQuestionImages() {
//        // Загрузка изображений вопросов из папки
//        File questionsDir = new File(QUESTIONS_PATH);
//        if (questionsDir.exists() && questionsDir.isDirectory()) {
//            File[] files = questionsDir.listFiles((dir, name) ->
//                    name.toLowerCase().endsWith(".jpg") ||
//                            name.toLowerCase().endsWith(".jpeg") ||
//                            name.toLowerCase().endsWith(".png") ||
//                            name.toLowerCase().endsWith(".gif"));
//
//            if (files != null) {
//                for (File file : files) {
//                    questionImages.add(file.getAbsolutePath());
//                }
//                // Сортируем по имени для порядка
//                questionImages.sort(String::compareTo);
//            }
//        }
//
//        // Если папка не существует или пуста, добавляем тестовый путь
//        if (questionImages.isEmpty()) {
//            questionImages.add("src/main/resources/questions/sample_question.jpg");
//        }
//    }
//
//
//    // --- Работа с базой данных ---
//    private void initDatabase() {
//        try (Connection conn = DriverManager.getConnection(DB_URL);
//             Statement stmt = conn.createStatement()) {
//            stmt.execute("CREATE TABLE IF NOT EXISTS users (chat_id INTEGER PRIMARY KEY, number TEXT)");
//        } catch (SQLException e) {
//            e.printStackTrace();
//        }
//    }
//
//    private void handleRegistration(Long chatId, Long number) {
//        try (Connection conn = DriverManager.getConnection(DB_URL)) {
//            // Проверяем, есть ли юзер
//            PreparedStatement checkUser = conn.prepareStatement("SELECT number FROM users WHERE chat_id=?");
//            checkUser.setLong(1, chatId);
//            ResultSet rs = checkUser.executeQuery();
//
//            if (rs.next()) {
//                Long existingNumber = rs.getLong("number");
//                sendMessage(chatId, "Ты уже зарегистрирована под номером " + existingNumber + ". Изменить номер нельзя.");
//                return;
//            }
//
//            // Проверяем, занят ли номер
//            if (isNumberTaken(number)) {
//                sendMessage(chatId, "❌ Этот номер уже занят.");
//                return;
//            }
//
//            // Сохраняем нового пользователя
//            PreparedStatement insert = conn.prepareStatement(
//                    "INSERT INTO users(chat_id, number) VALUES (?, ?)"
//            );
//            insert.setLong(1, chatId);
//            insert.setLong(2, number);
//            insert.executeUpdate();
//
//            sendMessage(chatId, "✅ Спасибо! Ты зарегистрирована под номером " + number + ".");
//        } catch (SQLException e) {
//            e.printStackTrace();
//        }
//    }
//
//    // --- Проверка: номер занят? ---
//    private boolean isNumberTaken(Long number) {
//        try (Connection conn = DriverManager.getConnection(DB_URL);
//             PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM users WHERE number=?")) {
//            ps.setLong(1, number);
//            return ps.executeQuery().next();
//        } catch (SQLException e) {
//            e.printStackTrace();
//        }
//        return false;
//    }
//
//    private String listUsers() {
//        StringBuilder sb = new StringBuilder("📋 Список пользователей:\n");
//        try (Connection conn = DriverManager.getConnection(DB_URL);
//             Statement stmt = conn.createStatement();
//             ResultSet rs = stmt.executeQuery("SELECT * FROM users")) {
//            while (rs.next()) {
//                sb.append("ID: ").append(rs.getLong("chat_id"))
//                        .append(" → номер: ").append(rs.getString("number")).append("\n");
//            }
//        } catch (SQLException e) {
//            e.printStackTrace();
//        }
//        return sb.toString().isEmpty() ? "Список пуст" : sb.toString();
//    }
//
//    private void deleteUser(Long chatId) {
//        try (Connection conn = DriverManager.getConnection(DB_URL);
//             PreparedStatement ps = conn.prepareStatement("DELETE FROM users WHERE chat_id=?")) {
//            ps.setLong(1, chatId);
//            ps.executeUpdate();
//        } catch (SQLException e) {
//            e.printStackTrace();
//        }
//    }
//
//    private void updateUser(Long chatId, Long newNumber) {
//        try (Connection conn = DriverManager.getConnection(DB_URL);
//             PreparedStatement ps = conn.prepareStatement("UPDATE users SET number=? WHERE chat_id=?")) {
//            ps.setLong(1, newNumber);
//            ps.setLong(2, chatId);
//            ps.executeUpdate();
//        } catch (SQLException e) {
//            e.printStackTrace();
//        }
//    }
//
//    // --- Полная очистка базы данных ---
//    private void nukeDatabase() {
//        try (Connection conn = DriverManager.getConnection(DB_URL);
//             Statement stmt = conn.createStatement()) {
//            // Удаляем все записи из таблицы
//            stmt.execute("DELETE FROM users");
//
//            // Если нужно полностью сбросить базу (включая структуру)
//            // stmt.execute("DROP TABLE IF EXISTS users");
//            // initDatabase(); // пересоздаем таблицу
//        } catch (SQLException e) {
//            e.printStackTrace();
//        }
//    }
//}