package org.example.bot;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;


import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

public class MadBot extends TelegramLongPollingBot {

    // Конфигурационные константы
    private static final String DB_URL = "jdbc:sqlite:bot.db";
    private static final Set<Long> ADMIN_IDS = new HashSet<>(Arrays.asList(
            257023213L
    ));
    private static final Integer MAX_USERS = 40;
    private static final String WELCOME_IMAGE_PATH = "src/main/resources/images/welcome.png";
    private static final String QUESTIONS_PATH = "src/main/resources/questions";

    // Состояния бота
    private enum BotState {
        IDLE,           // Ожидание регистрации
        AWAITING_VOTES, // Ожидание голосов
        VOTING_COMPLETED // Голосование завершено
    }

    // Текущее состояние бота
    private BotState currentState = BotState.IDLE;
    private int currentQuestionIndex = -1; // Индекс текущего вопроса
    private List<String> questionImages = new ArrayList<>(); // Список путей к изображениям вопросов
    private Map<Long, Integer> currentQuestionResponses = new HashMap<>(); // Ответы на текущий вопрос
    private Map<Integer, Map<Long, Integer>> allResponses = new HashMap<>(); // Все ответы по вопросам
    private Integer adminMessageId = null; // ID сообщения админа для обновления

    /**
     * Конструктор бота - инициализирует БД и загружает вопросы
     */
    public MadBot() {
//        initDatabase();
        loadQuestionImages();
        for (Long adminId : ADMIN_IDS) {
            sendAdminHelp(adminId);
        }
    }

    @Override
    public String getBotUsername() {
        return "Galentine bot";
    }

    @Override
    public String getBotToken() {
        return "8332201148:AAHQ5jvjWWrqcgP_kF1DFrVYyYt6nEwB11k";
    }

    /**
     * Основной метод обработки входящих сообщений
     */
    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasMessage() && update.getMessage().hasText()) {
                handleMessage(update);
            } else if (update.hasCallbackQuery()) {
                handleCallbackQuery(update);
            }
        } catch (Exception e) {
            System.err.println("Ошибка обработки update: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Обработка текстовых сообщений
     */
    private void handleMessage(Update update) {
        String text = update.getMessage().getText().trim();
        Long chatId = update.getMessage().getChatId();
        User user = update.getMessage().getFrom();

        // Команды админа
        if (ADMIN_IDS.contains(chatId)) {
            handleAdminCommands(text, chatId);
            return;
        }

        // Команды для участников
        if (text.equals("/start")) {
            sendWelcome(chatId);
            return;
        }

        // Обработка ввода номера
        if (text.matches("\\d+")) {
            handleNumberInput(text, chatId, user);
        } else {
            sendMessage(chatId, "Пожалуйста, в введи номер цифрами от 1 до " + MAX_USERS);
        }
    }

    /**
     * Обработка команд администратора
     */
    private void handleAdminCommands(String text, Long chatId) {
        switch (text) {
            case "/list":
                sendMessage(chatId, listUsers());
                break;
            case "/start_voting":
                startVoting(chatId);
                break;
            case "/next_question":
                sendNextQuestion(chatId);
                break;
            case "/show_results":
                showResults(chatId);
                break;
            case "/nuke":
                nukeDatabase();
                clearAllData();
                sendMessage(chatId, "💥 Все данные полностью очищены!");
                break;
            case "/clear_users":
                clearAllUsers();
                sendMessage(chatId, "✅ Список участниц очищен.");
                break;
            case "/force_clean":
                dropUsersTable();
                handleForceCleanCommand(chatId);
                break;
            case "/drop_tables":
                dropAllTables();
                sendMessage(chatId, "✅ Все таблицы удалены");
                break;
            case "/admin_menu":
                showAdminMenu(chatId);
                break;
            case "/status":
                sendMessage(chatId, makeStatusText());
                break;
            default:
                if (text.startsWith("/update")) {
                    handleUpdateCommand(text, chatId);
                } else if (text.startsWith("/delete")) {
                    handleDeleteCommand(text, chatId);
                } else {
                    sendMessage(chatId, "❌ Неизвестная команда. Используй /admin_menu для меню");
                }
        }
    }

    /**
     * Обработка команды /update
     */
    private void handleUpdateCommand(String text, Long chatId) {
        String[] parts = text.split("\\s+");
        if (parts.length == 3) {
            try {
                Long oldNumber = Long.parseLong(parts[1]);
                Long newNumber = Long.parseLong(parts[2]);
                updateUser(oldNumber, newNumber);
                sendMessage(chatId, "✅ Номер у " + oldNumber + " обновлён на " + newNumber);
            } catch (NumberFormatException e) {
                sendMessage(chatId, "❌ Формат: /update <старый_номер> <новый_номер>");
            }
        } else {
            sendMessage(chatId, "❌ Формат: /update <старый_номер> <новый_номер>");
        }
    }

    /**
     * Обработка команды /delete
     */
    private void handleDeleteCommand(String text, Long chatId) {
        String[] parts = text.split("\\s+");
        if (parts.length == 2) {
            try {
                Long chatIdToDelete = Long.parseLong(parts[1]);
                deleteUser(chatIdToDelete);
                sendMessage(chatId, "🗑 Пользовательница " + chatIdToDelete + " удалена.");
            } catch (NumberFormatException e) {
                sendMessage(chatId, "❌ Формат: /delete <chat_id>");
            }
        } else {
            sendMessage(chatId, "❌ Формат: /delete <chat_id>");
        }
    }

    /**
     * Обработка callback-запросов от inline-кнопок
     */
    private void handleCallbackQuery(Update update) {
        String callbackData = update.getCallbackQuery().getData();
        Long chatId = update.getCallbackQuery().getMessage().getChatId();
        Integer messageId = update.getCallbackQuery().getMessage().getMessageId();

        try {
            if (callbackData.startsWith("vote_")) {
                handleVote(callbackData, chatId, messageId);
            } else {
                switch (callbackData) {
                    case "admin_menu":
                        showAdminMenu(chatId);
                        break;
                    case "ask_question":
                        sendNextQuestion(chatId);
                        break;
                    case "list_users":
                        sendMessage(chatId, listUsersWithVotes());
                        break;
                    case "clear_users":
                        clearAllUsers();
                        sendMessage(chatId, "✅ Список участниц очищен.");
                        showAdminMenu(chatId);
                        break;
                    case "show_results":
                        showResults(chatId);
                        break;

                }
            }
        } catch (Exception e) {
            System.err.println("Ошибка обработки callback: " + e.getMessage());
            sendMessage(chatId, "❌ Произошла ошибка. Попробуйте снова.");
        }
    }

    /**
     * Обработка голосования
     */
    private void handleVote(String callbackData, Long chatId, Integer messageId) {
        int votedNumber = Integer.parseInt(callbackData.substring(5));

        // Проверяем, не голосует ли пользователь за себя
        Long userNumber = getUserNumberFromDB(chatId);
        if (userNumber != null && userNumber == votedNumber) {
            sendMessage(chatId, "❌ Нельзя голосовать за свой собственный номер...");
            return;
        }

        // Проверка, голосовал ли уже этот пользователь за текущий вопрос
        if (hasUserVoted(chatId, currentQuestionIndex)) {
            sendMessage(chatId, "⚠️ Вы уже проголосовали за этот вопрос!");
            return;
        }

        // Сохраняем голос в БД
        saveVote(chatId, currentQuestionIndex, votedNumber);

        // Обновляем кэш ответов
        currentQuestionResponses.put(chatId, votedNumber);

        // Уведомление участнику
        sendMessage(chatId, "✅ Ваш голос за номер " + votedNumber + " принят!");
        sendMessage(chatId, "🗳 Ваш голос очень важен для нас! Ожидаем остальных участниц...");

        // Удаляем клавиатуру после голосования
        removeInlineKeyboard(chatId, messageId);

        // Обновляем статус у админа
        updateAdminStatus();

        String userLink = getUserLink(chatId);
        notifyAdmins("✅ " + userLink + " проголосовала за номер " + votedNumber);
    }
    private void notifyAdmins(String text) {
        for (Long adminId : ADMIN_IDS) {
            sendMessage(adminId, text);
        }
    }
    private void sendAdminHelp(Long adminId) {
        String helpText = "<b>Команды администратора:</b>\n\n" +
                "/list — список всех участниц\n" +
                "/start_voting — начать голосование\n" +
                "/next_question — отправить следующий вопрос\n" +
                "/show_results — показать результаты текущего вопроса\n" +
                "/clear_users — очистить список участниц\n" +
                "/update old new — изменить номер\n" +
                "/delete chat_id — удалить участницу\n" +
                "/status — статус голосования\n" +
                "/nuke — полностью очистить базу\n" +
                "/admin_menu — открыть меню с кнопками\n" +
                "/force_clean - снести всю базу данных\n" +
                "/drop_tables - удалить все таблицы";

        sendMessage(adminId, helpText);
    }

    private boolean hasUserVoted(Long chatId, int questionIndex) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM votes WHERE user_id=? AND question_id=?")) {
            ps.setLong(1, chatId);
            ps.setInt(2, questionIndex);
            ResultSet rs = ps.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            System.err.println("Ошибка проверки голоса: " + e.getMessage());
        }
        return false;
    }

    private void saveVote(Long chatId, int questionIndex, int votedNumber) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT OR REPLACE INTO votes(user_id, question_id, vote_for) VALUES (?, ?, ?)")) {
            ps.setLong(1, chatId);
            ps.setInt(2, questionIndex);
            ps.setInt(3, votedNumber);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Ошибка сохранения голоса: " + e.getMessage());
        }
    }

    /**
     * Обработка ввода номера участником
     */
    private void handleNumberInput(String text, Long chatId, User user) {
        try {
            Long number = Long.parseLong(text);

            // Валидация номера
            if (number < 1 || number > MAX_USERS) {
                sendMessage(chatId, "⚠️ Номер должен быть от 1 до " + MAX_USERS + ".");
                return;
            }

            // Проверка существования пользователя
            if (userExists(chatId)) {
                String existingNumber = getUserNumber(chatId);
                sendMessage(chatId, "❌ Твой номер уже закреплён: " + existingNumber +
                        "\nЕсли нужно изменить, свяжись с организаторкой.");
                return;
            }

            // Проверка занятости номера
            if (isNumberTaken(number)) {
                sendMessage(chatId, "❌ Этот номер уже занят. Выбери другой.");
                return;
            }

            // Сохраняем пользователя с username
            saveUser(chatId, number, user);

            // Отправляем приветственное сообщение участнику
            String welcomeMessage = "✨ *Твой номер: " + number + "*\n\n" +
                    "Спасибо за регистрацию! Твой номер закреплён за тобой.\n\n" +
                    "⏳ Ожидай начала голосования! Твой голос супер важен для нас. 💫\n" +
                    "Голосование начнется, когда подключатся все участницы";

            sendMessage(chatId, welcomeMessage);

            // Уведомление админа о новой регистрации
            String userLink = getUserLink(chatId);
            String adminNotification = "👤 Новая регистрация!\n" +
                    userLink + " - номер " + number +
                    "\n\nВсего зарегистрировано: " + getRegisteredCount() + "/" + MAX_USERS;
            for (Long adminId : ADMIN_IDS) {
                sendMessage(adminId, adminNotification);
            }

        } catch (NumberFormatException e) {
            sendMessage(chatId, "❌ Пожалуйста, введите корректный номер.");
        }
    }

    /**
     * Создание интерактивной ссылки на профиль пользователя
     */
    private String getUserLink(User user) {
        if (user.getUserName() != null && !user.getUserName().isEmpty()) {
            return "<a href=\"tg://user?id=" + user.getId() + "\">@" + user.getUserName() + "</a>";
        } else {
            String firstName = user.getFirstName() != null ? user.getFirstName() : "";
            String lastName = user.getLastName() != null ? user.getLastName() : "";
            String fullName = (firstName + " " + lastName).trim();

            if (!fullName.isEmpty()) {
                return "<a href=\"tg://user?id=" + user.getId() + "\">" + fullName + "</a>";
            } else {
                return "<a href=\"tg://user?id=" + user.getId() + "\">Участница " + user.getId() + "</a>";
            }
        }
    }

    /**
     * Получение интерактивной ссылки на профиль по chatId
     */
    private String getUserLink(Long chatId) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement("SELECT username FROM users WHERE chat_id=?")) {

            ps.setLong(1, chatId);
            ResultSet rs = ps.executeQuery();

            if (rs.next()) {
                String username = rs.getString("username");
                if (username != null && !username.isEmpty()) {
                    return "<a href=\"https://t.me/" + username + "\">@" + username + "</a>";
                }
            }

        } catch (SQLException e) {
            System.err.println("Ошибка получения username: " + e.getMessage());
        }

        // fallback: даже без username можно кидать кликабельную ссылку
        return "<a href=\"tg://user?id=" + chatId + "\">Участница</a>";
    }


    /**
     * Начало голосования
     */
    private void startVoting(Long chatId) {
        if (currentState != BotState.IDLE) {
            sendMessage(chatId, "❌ Голосование уже начато!");
            return;
        }

        if (getRegisteredCount() < 2) {
            sendMessage(chatId, "❌ Недостаточно участников для голосования (минимум 2)");
            return;
        }

        currentState = BotState.AWAITING_VOTES;
        currentQuestionIndex = 0;
        currentQuestionResponses.clear();
        allResponses.clear();

        // Уведомление всем участникам
        List<Long> allUsers = getAllUsers();
        String votingStartMessage = "🎉 *Голосование начинается!*\n\n" +
                "Сейчас тебе придут вопросы один за другим.\n" +
                "Выбери номер той, кто по твоему мнению подходит больше всего!\n\n" +
                "💫 Удачи в голосовании!";

        for (Long userId : allUsers) {
            sendMessage(userId, votingStartMessage);
        }

        sendMessage(chatId, "🗳 Голосование начато! Уведомления отправлены " + allUsers.size() + " участницам.");
        sendNextQuestion(chatId);
    }

    /**
     * Отправка следующего вопроса
     */
    private void sendNextQuestion(Long chatId) {
        if (currentQuestionIndex >= questionImages.size()) {
            sendMessage(chatId, "✅ Все вопросы отправлены!");
            return;
        }

        String imagePath = questionImages.get(currentQuestionIndex);
        File imageFile = new File(imagePath);

        if (!imageFile.exists()) {
            sendMessage(chatId, "❌ Изображение вопроса не найдено: " + imagePath);
            currentQuestionIndex++;
            return;
        }

        // Сохраняем предыдущие ответы
        if (currentQuestionIndex > 0) {
            allResponses.put(currentQuestionIndex - 1, new HashMap<>(currentQuestionResponses));
        }

        // Очищаем ответы для нового вопроса
        currentQuestionResponses.clear();

        // Отправляем вопрос всем участникам
        List<Long> allUsers = getAllUsers();
        int sentCount = 0;

        for (Long userId : allUsers) {
            try {
                SendPhoto photo = new SendPhoto();
                photo.setChatId(userId.toString());
                photo.setPhoto(new InputFile(imageFile));
                photo.setCaption("❓ Вопрос " + (currentQuestionIndex + 1) + "\nВыберите номер:");

                // Создаем клавиатуру без собственного номера
                photo.setReplyMarkup(createVotingKeyboard(userId));

                execute(photo);
                sentCount++;

                // Небольшая задержка чтобы не спамить
                Thread.sleep(100);
            } catch (Exception e) {
                System.err.println("Ошибка отправки вопроса пользовательнице " + userId + ": " + e.getMessage());
            }
        }

        // Отправляем вопрос админу
        try {
            SendPhoto adminPhoto = new SendPhoto();
            for (Long adminId : ADMIN_IDS) {
                adminPhoto.setChatId(adminId);
            }
            adminPhoto.setPhoto(new InputFile(imageFile));
            adminPhoto.setCaption("📨 Вопрос " + (currentQuestionIndex + 1) + " отправлен " + sentCount + " участницам");
            execute(adminPhoto);
        } catch (TelegramApiException e) {
            System.err.println("Ошибка отправки вопроса админу: " + e.getMessage());
        }

        // Обновляем статус админа
        updateAdminStatus();

        currentQuestionIndex++;
    }

    /**
     * Создание клавиатуры для голосования (исключая собственный номер)
     */
    private InlineKeyboardMarkup createVotingKeyboard(Long userId) {
        List<Long> registeredNumbers = getRegisteredNumbers();
        Long userNumber = getUserNumberFromDB(userId); // лучше возвращать Long

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> currentRow = new ArrayList<>();

        for (Long number : registeredNumbers) {
            if (userNumber != null && number.equals(userNumber)) {
                continue; // пропускаем свой номер
            }

            InlineKeyboardButton button = new InlineKeyboardButton();
            button.setText("№" + number);
            button.setCallbackData("vote_" + number);

            currentRow.add(button);

            // после 4 кнопок добавляем строку
            if (currentRow.size() == 4) {
                rows.add(currentRow);
                currentRow = new ArrayList<>();
            }
        }

        // добавляем остатки
        if (!currentRow.isEmpty()) {
            rows.add(currentRow);
        }

        markup.setKeyboard(rows);
        return markup;
    }

    /**
     * Показать результаты голосования
     */
//    private void showResults(Long chatId) {
//        Map<Integer, Integer> voteCounts = new HashMap<>();
//
//        // Загружаем голоса из БД
//        try (Connection conn = DriverManager.getConnection(DB_URL);
//             PreparedStatement ps = conn.prepareStatement(
//                     "SELECT vote_for, COUNT(*) as cnt FROM votes WHERE question_id=? GROUP BY vote_for")) {
//            ps.setInt(1, currentQuestionIndex);
//            ResultSet rs = ps.executeQuery();
//
//            while (rs.next()) {
//                int votedNumber = rs.getInt("voted_for");
//                int count = rs.getInt("cnt");
//                voteCounts.put(votedNumber, count);
//            }
//        } catch (SQLException e) {
//            System.err.println("Ошибка загрузки результатов: " + e.getMessage());
//        }
//
//        if (voteCounts.isEmpty()) {
//            sendMessage(chatId, "📊 Пока нет голосов для текущего вопроса.");
//            return;
//        }
//
//        // Формируем текст с результатами
//        StringBuilder resultText = new StringBuilder("📊 Результаты голосования:\n\n");
//
//        voteCounts.entrySet().stream()
//                .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
//                .forEach(entry -> {
//                    resultText.append("№").append(entry.getKey())
//                            .append(" — ").append(entry.getValue()).append(" голос(ов)\n");
//                });
//
//        sendMessage(chatId, resultText.toString());
//    }

    /**
     * Показать результаты голосования
     */
    private void showResults(Long chatId) {
        Map<Integer, Integer> voteCounts = new HashMap<>();
        if (currentQuestionResponses.isEmpty() && currentQuestionIndex == 0) {
            sendMessage(chatId, "❌ Голосов еще нет!");
            return;
        }

        // Сохраняем текущие ответы
        if (currentQuestionIndex > 0) {
            allResponses.put(currentQuestionIndex - 1, new HashMap<>(currentQuestionResponses));
        }
       //  Загружаем голоса из БД
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT vote_for, COUNT(*) as cnt FROM votes WHERE question_id=? GROUP BY vote_for")) {
            ps.setInt(1, currentQuestionIndex);
            ResultSet rs = ps.executeQuery();

            while (rs.next()) {
                int votedNumber = rs.getInt("voted_for");
                int count = rs.getInt("cnt");
                voteCounts.put(votedNumber, count);
            }
        } catch (SQLException e) {
            System.err.println("Ошибка загрузки результатов: " + e.getMessage());
        }

        // Считаем голоса для текущего вопроса
        Map<Integer, Integer> voteCount = new HashMap<>();
        for (Integer vote : currentQuestionResponses.values()) {
            voteCount.put(vote, voteCount.getOrDefault(vote, 0) + 1);
        }

        if (voteCounts.isEmpty()) {
            sendMessage(chatId, "❌ Нет голосов для текущего вопроса!");
            return;
        }

        // Формируем текст результатов
        StringBuilder results = new StringBuilder("🏆 Результаты голосования (Вопрос " + currentQuestionIndex + "):\n\n");

        // Сортируем по убыванию голосов
        List<Map.Entry<Integer, Integer>> sortedResults = voteCounts.entrySet()
                .stream()
                .sorted((e1, e2) -> e2.getValue().compareTo(e1.getValue()))
                .collect(Collectors.toList());

        for (int i = 0; i < sortedResults.size(); i++) {
            Map.Entry<Integer, Integer> entry = sortedResults.get(i);
            double percentage = (double) entry.getValue() / currentQuestionResponses.size() * 100;
            results.append(i + 1).append(". №").append(entry.getKey())
                    .append(": ").append(entry.getValue()).append(" голосов (")
                    .append(String.format("%.1f", percentage)).append("%)\n");
        }

        results.append("\nВсего проголосовало: ").append(currentQuestionResponses.size())
                .append("/").append(getRegisteredCount());

        // Отправляем результаты всем участникам
        List<Long> allUsers = getAllUsers();
        for (Long userId : allUsers) {
            sendMessage(userId, results.toString());
        }

        // Отправляем админу детальную статистику
        String adminResults = results + "\n\n" + makeStatusText();
        for(Long adminID:ADMIN_IDS) {
            sendMessage(adminID, adminResults);
        }

        // УСТАНОВКА СОСТОЯНИЯ VOTING_COMPLETED ПОСЛЕ ПОКАЗА РЕЗУЛЬТАТОВ
        currentState = BotState.VOTING_COMPLETED;

        // Дополнительное сообщение о завершении голосования
        for (Long adminID:ADMIN_IDS) {
            sendMessage(adminID, "✅ Голосование завершено! Статус: " + currentState);
        }
    }



    /**
     * Создание текста статуса голосования
     */
    private String makeStatusText() {
        int total = getRegisteredCount();
        int answered = currentQuestionResponses.size();

        StringBuilder text = new StringBuilder("📊 Статус голосования (вопрос " + currentQuestionIndex + "):\n\n");

        List<Long> allUsers = getAllUsers();
        for (Long userId : allUsers) {
            String userLink = getUserLink(userId);
            Long number = getUserNumberFromDB(userId);

            if (currentQuestionResponses.containsKey(userId)) {
                Integer vote = currentQuestionResponses.get(userId);
                text.append("✅ ").append(userLink)
                        .append(" (№").append(number).append(") → за №").append(vote).append("\n");
            } else {
                text.append("❌ ").append(userLink)
                        .append(" (№").append(number).append(") - не проголосовала\n");
            }
        }

        text.append("\n🗳 Проголосовали: ").append(answered).append("/").append(total);
        return text.toString();
    }

    /**
     * Обновление статуса у админа
     */
    private void updateAdminStatus() {
        String statusText = makeStatusText();

        try {
            if (adminMessageId == null) {
                // Отправляем новое сообщение админу и сохраняем его id
                SendMessage message = new SendMessage();
                for (Long adminId : ADMIN_IDS) {
                    message.setChatId(adminId);
                }
                message.setText(statusText);
                message.setReplyMarkup(createAdminKeyboard());
                message.setParseMode("HTML");
                message.setDisableWebPagePreview(true);

                Message sent = execute(message); // execute возвращает Message
                if (sent != null) {
                    adminMessageId = sent.getMessageId();
                }
            } else {
                // Редактируем уже отправленное сообщение (EditMessageText)
                EditMessageText edit = new EditMessageText();
                for (Long adminId : ADMIN_IDS) {
                    edit.setChatId(adminId);
                }
                edit.setMessageId(adminMessageId); // id ранее сохранённого сообщения
                edit.setText(statusText);
                edit.setReplyMarkup(createAdminKeyboard());
                edit.setParseMode("HTML");
                edit.setDisableWebPagePreview(true);

                execute(edit); // редактируем сообщение
            }
        } catch (TelegramApiException e) {
            System.err.println("Ошибка обновления статуса админа: " + e.getMessage());
            e.printStackTrace();
            // при ошибке можно обнулить adminMessageId, чтобы на следующем вызове отправить новое сообщение
            // adminMessageId = null;
        }
    }

    /**
     * Создание клавиатуры администратора
     */
    private InlineKeyboardMarkup createAdminKeyboard() {
        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();

        // Кнопка следующего вопроса
        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton nextQuestionBtn = new InlineKeyboardButton();
        nextQuestionBtn.setText("➡️ Следующий вопрос");
        nextQuestionBtn.setCallbackData("ask_question");
        row1.add(nextQuestionBtn);

        // Кнопка показа результатов
        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton showResultsBtn = new InlineKeyboardButton();
        showResultsBtn.setText("📊 Показать результаты");
        showResultsBtn.setCallbackData("show_results");
        row2.add(showResultsBtn);

        // Кнопка списка пользователей
        List<InlineKeyboardButton> row3 = new ArrayList<>();
        InlineKeyboardButton listUsersBtn = new InlineKeyboardButton();
        listUsersBtn.setText("👥 Список участников");
        listUsersBtn.setCallbackData("list_users");
        row3.add(listUsersBtn);

        // Кнопка очистки
        List<InlineKeyboardButton> row4 = new ArrayList<>();
        InlineKeyboardButton clearUsersBtn = new InlineKeyboardButton();
        clearUsersBtn.setText("🗑 Очистить всех");
        clearUsersBtn.setCallbackData("clear_users");
        row4.add(clearUsersBtn);

        keyboard.add(row1);
        keyboard.add(row2);
        keyboard.add(row3);
        keyboard.add(row4);

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(keyboard);
        return markup;
    }

    /**
     * Показать меню администратора
     */
    private void showAdminMenu(Long chatId) {
        String menuText = "<b>Меню администратора</b>\n\n" +
                "Зарегистрировано: " + getRegisteredCount() + "/" + MAX_USERS + "\n" +
                "Текущий статус: " + currentState + "\n" +
                "Активный вопрос: " + (currentQuestionIndex >= 0 ? (currentQuestionIndex + 1) : "нет");


        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText(menuText);
        message.setReplyMarkup(createAdminKeyboard());

        try {
            execute(message);
        } catch (TelegramApiException e) {
            System.err.println("Ошибка отправки меню админа: " + e.getMessage());
        }
    }

    /**
     * Удаление inline-клавиатуры
     */
    private void removeInlineKeyboard(Long chatId, Integer messageId) {
        try {
            EditMessageReplyMarkup edit = new EditMessageReplyMarkup();
            edit.setChatId(chatId.toString());
            edit.setMessageId(messageId);
            edit.setReplyMarkup(null); // убираем клавиатуру
            execute(edit);
        } catch (TelegramApiException e) {
            System.err.println("Ошибка удаления inline-клавиатуры: " + e.getMessage());
            // fallback: отправить короткое подтверждение
            sendMessage(chatId, "✅ Голос принят!");
        }
    }

    /**
     * Очистка всех пользователей
     */
    private void clearAllUsers() {
        nukeDatabase();
        currentQuestionResponses.clear();
        allResponses.clear();
        currentQuestionIndex = 0;
        currentState = BotState.IDLE;
        adminMessageId = null;
    }

    /**
     * Полная очистка всех данных
     */
    private void clearAllData() {
        clearAllUsers();
        questionImages.clear();
        loadQuestionImages();
    }

    /**
     * Загрузка изображений вопросов
     */
    private void loadQuestionImages() {
        File questionsDir = new File(QUESTIONS_PATH);
        if (questionsDir.exists() && questionsDir.isDirectory()) {
            File[] files = questionsDir.listFiles((dir, name) ->
                    name.toLowerCase().endsWith(".jpg") ||
                            name.toLowerCase().endsWith(".jpeg") ||
                            name.toLowerCase().endsWith(".png") ||
                            name.toLowerCase().endsWith(".gif"));

            if (files != null) {
                for (File file : files) {
                    questionImages.add(file.getAbsolutePath());
                }
                // Сортируем по имени для порядка
                questionImages.sort(String::compareTo);
            }
        }

        // Если папка не существует или пуста
        if (questionImages.isEmpty()) {
            System.out.println("⚠️ В папке questions нет изображений");
        } else {
            System.out.println("✅ Загружено " + questionImages.size() + " вопросов");
        }
    }

    /**
     * Отправка приветственного сообщения с картинкой
     */
    private void sendWelcome(Long chatId) {
        File welcomeImage = new File(WELCOME_IMAGE_PATH);

        if (welcomeImage.exists()) {
            try {
                SendPhoto photo = new SendPhoto();
                photo.setChatId(chatId.toString());
                photo.setPhoto(new InputFile(welcomeImage));
                photo.setCaption("🎀 Добро пожаловать на Galentine!\n\nВведите номер с вашей карточки:");
                execute(photo);
            } catch (TelegramApiException e) {
                sendMessage(chatId, "🎀 Добро пожаловать на Galentine!\n\nВведите номер с вашей карточки:");
            }
        } else {
            sendMessage(chatId, "🎀 Добро пожаловать на Galentine!\n\nВведите номер с вашей карточки:");
        }
    }

    /**
     * Универсальная отправка текстового сообщения
     */
    private void sendMessage(Long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText(text);
        message.setParseMode("HTML");
        message.setDisableWebPagePreview(true);

        try {
            execute(message);
        } catch (TelegramApiException e) {
            System.err.println("Ошибка отправки сообщения " + chatId + ": " + e.getMessage());
        }
    }

    // --- БАЗА ДАННЫХ ---

    /**
     * Инициализация базы данных
     */
//    private void initDatabase() {
//        try (Connection conn = DriverManager.getConnection(DB_URL);
//             Statement stmt = conn.createStatement()) {
//            System.out.println("✅ База данных инициализирована");
//        } catch (SQLException e) {
//            System.err.println("❌ Ошибка инициализации БД: " + e.getMessage());
//        }
//    }

    /**
     * Сохранение пользователя в БД
     */
    private void saveUser(Long chatId, Long number, User user) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users(chat_id, number, username) VALUES (?, ?, ?)")) {

            String username = user.getUserName();
            if (username == null || username.isEmpty()) {
                username = "Участница"; // если нет ника
            }

            ps.setLong(1, chatId);
            ps.setLong(2, number);
            ps.setString(3, user.getUserName());
            ps.executeUpdate();

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * Проверка существования пользователя
     */
    private boolean userExists(Long chatId) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM users WHERE chat_id=?")) {
            ps.setLong(1, chatId);
            ResultSet rs = ps.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            System.err.println("Ошибка проверки пользователя: " + e.getMessage());
        }
        return false;
    }

    /**
     * Получение номера пользователя
     */
    private String getUserNumber(Long chatId) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement("SELECT number FROM users WHERE chat_id=?")) {
            ps.setLong(1, chatId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString("number");
        } catch (SQLException e) {
            System.err.println("Ошибка получения номера: " + e.getMessage());
        }
        return null;
    }

    /**
     * Проверка занятости номера
     */
    private boolean isNumberTaken(Long number) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM users WHERE number=?")) {
            ps.setLong(1, number);
            return ps.executeQuery().next();
        } catch (SQLException e) {
            System.err.println("Ошибка проверки номера: " + e.getMessage());
        }
        return false;
    }

    /**
     * Получение всех пользователей
     */
    private List<Long> getAllUsers() {
        List<Long> users = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT chat_id FROM users ORDER BY number")) {
            while (rs.next()) {
                users.add(rs.getLong("chat_id"));
            }
        } catch (SQLException e) {
            System.err.println("Ошибка получения пользователей: " + e.getMessage());
        }
        return users;
    }

    /**
     * Получение количества зарегистрированных
     */
    private int getRegisteredCount() {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM users")) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            System.err.println("Ошибка подсчета пользователей: " + e.getMessage());
        }
        return 0;
    }

    /**
     * Получение зарегистрированных номеров
     */
    private List<Long> getRegisteredNumbers() {
        List<Long> numbers = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT number FROM users ORDER BY number")) {
            while (rs.next()) {
                numbers.add(rs.getLong("number"));
            }
        } catch (SQLException e) {
            System.err.println("Ошибка получения номеров: " + e.getMessage());
        }
        return numbers;
    }

    /**
     * Получение номера пользователя из БД
     */
    private Long getUserNumberFromDB(Long chatId) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement("SELECT number FROM users WHERE chat_id=?")) {
            ps.setLong(1, chatId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getLong("number");
        } catch (SQLException e) {
            System.err.println("Ошибка получения номера из БД: " + e.getMessage());
        }
        return null;
    }

    /**
     * Список пользователей с форматированием
     */
    private String listUsers() {
        StringBuilder sb = new StringBuilder("📋 Зарегистрированные участники:\n\n");
        int count = 0;

        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT number, username FROM users ORDER BY number ASC")) {

            while (rs.next()) {
                Long number = rs.getLong("number");
                String username = rs.getString("username");

                if (username == null || username.isEmpty()) {
                    username = "Участница"; // fallback
                } else if (!username.startsWith("@")) {
                    username = "@" + username;
                }

                sb.append("№").append(number).append(" - ").append(username).append("\n");
                count++;
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }

        if (count == 0) {
            return "❌ Пока никто не зарегистрирован.";
        }

        sb.append("\nВсего: ").append(count).append("/").append(MAX_USERS);
        return sb.toString();
    }

    /**
     * Список пользователей с статусом голосования
     */
    private String listUsersWithVotes() {
        StringBuilder text = new StringBuilder("👥 Статус участников:\n\n");
        List<Long> allUsers = getAllUsers();

        for (Long userId : allUsers) {
            String userLink = getUserLink(userId);
            Long number = getUserNumberFromDB(userId);
            Integer vote = currentQuestionResponses.get(userId);

            String status = vote != null ? "✅ Проголосовала (за №" + vote + ")" : "❌ Не проголосовала";
            text.append(userLink).append(" (№").append(number).append(") - ").append(status).append("\n");
        }

        text.append("\n🗳 Проголосовали: ").append(currentQuestionResponses.size())
                .append("/").append(allUsers.size());

        return text.toString();
    }

    /**
     * Удаление пользователя
     */
    private void deleteUser(Long chatId) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement("DELETE FROM users WHERE chat_id=?")) {
            ps.setLong(1, chatId);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Ошибка удаления пользователя: " + e.getMessage());
        }
    }

    /**
     * Обновление номера пользователя
     */
    private void updateUser(Long oldNumber, Long newNumber) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement("UPDATE users SET number=? WHERE number=?")) {
            ps.setLong(1, newNumber);
            ps.setLong(2, oldNumber);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Ошибка обновления номера: " + e.getMessage());
        }
    }

    /**
     * Полная очистка базы данных
     */
    private void nukeDatabase() {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM users");
            System.out.println("✅ База данных очищена");
        } catch (SQLException e) {
            System.err.println("❌ Ошибка очистки БД: " + e.getMessage());
        }
    }
    /**
     * Полное удаление всех таблиц из базы данных
     */
    private void dropAllTables() {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {

            // Получаем список всех таблиц в базе данных
            ResultSet rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table'");

            List<String> tables = new ArrayList<>();
            while (rs.next()) {
                tables.add(rs.getString("name"));
            }

            // Удаляем каждую таблицу
            for (String table : tables) {
                if (!table.equals("sqlite_sequence")) { // Пропускаем системную таблицу
                    stmt.execute("DROP TABLE IF EXISTS " + table);
                    System.out.println("✅ Таблица удалена: " + table);
                }
            }

            System.out.println("🗑️ Удалено таблиц: " + tables.size());

        } catch (SQLException e) {
            System.err.println("❌ Ошибка удаления таблиц: " + e.getMessage());
        }
    }

    /**
     * Удаление конкретной таблицы users
     */
    private void dropUsersTable() {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {

            stmt.execute("DROP TABLE IF EXISTS users");
            System.out.println("✅ Таблица 'users' удалена");

        } catch (SQLException e) {
            System.err.println("❌ Ошибка удаления таблицы users: " + e.getMessage());
        }
    }
    /**
     * Обработка команды /force_clean - полная очистка БД
     */
    private void handleForceCleanCommand(Long chatId) {
        if (!ADMIN_IDS.contains(chatId)) {
            sendMessage(chatId, "❌ Эта команда только для администратора");
            return;
        }

        dropAllTables();

        // Сбрасываем все состояния
        currentState = BotState.IDLE;
        currentQuestionIndex = 0;
        currentQuestionResponses.clear();
        allResponses.clear();
        adminMessageId = null;

        sendMessage(chatId, "💥 База данных полностью очищена и пересоздана!\n" +
                "Все таблицы удалены, состояние сброшено.");
    }
}
