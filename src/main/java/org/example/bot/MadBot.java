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
    private static final Long ADMIN_ID = 257023213L;
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
        initDatabase();
        loadQuestionImages();
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
        if (chatId.equals(ADMIN_ID)) {
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
            sendMessage(chatId, "Пожалуйста, введите номер цифрами от 1 до " + MAX_USERS);
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
            case "/admin_menu":
                showAdminMenu(chatId);
                break;
            case "/clear_users":
                clearAllUsers();
                sendMessage(chatId, "✅ Список участниц очищен.");
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
                    sendMessage(chatId, "❌ Неизвестная команда. Используйте /admin_menu для меню");
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
                sendMessage(chatId, "🗑 Пользователь " + chatIdToDelete + " удалён.");
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
            sendMessage(chatId, "❌ Нельзя голосовать за свой собственный номер!");
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
        sendMessage(chatId, "🗳 Ваш голос очень важен для нас! Оставайтесь на линии, ожидаем остальных участниц...");

        // Удаляем клавиатуру после голосования
        removeInlineKeyboard(chatId, messageId);

        // Обновляем статус у админа
        updateAdminStatus();

        // Уведомление админа о новом голосе
        if (!chatId.equals(ADMIN_ID)) {
            String userLink = getUserLink(chatId);
            sendMessage(ADMIN_ID, "✅ " + userLink + " проголосовал(а) за номер " + votedNumber);
        }
    }

    private boolean hasUserVoted(Long chatId, int questionIndex) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM votes WHERE chat_id=? AND question_index=?")) {
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
                     "INSERT OR REPLACE INTO votes(chat_id, question_index, voted_number) VALUES (?, ?, ?)")) {
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
                sendMessage(chatId, "❌ Ваш номер уже закреплён: " + existingNumber +
                        "\nЕсли нужно изменить, свяжитесь с организаторкой.");
                return;
            }

            // Проверка занятости номера
            if (isNumberTaken(number)) {
                sendMessage(chatId, "❌ Этот номер уже занят. Выберите другой.");
                return;
            }

            // Сохраняем пользователя
            saveUser(chatId, number, user);

            // Отправляем приветственное сообщение участнику
            String welcomeMessage = "✨ *Твой номер: " + number + "*\n\n" +
                    "Спасибо за регистрацию! Твой номер закреплён за тобой.\n\n" +
                    "⏳ Ожидай начала голосования! Твой звонок очень важен для нас 💫\n" +
                    "Мы свяжемся с тобой, когда все участницы будут готовы.";

            sendMessage(chatId, welcomeMessage);

            // Уведомление админа о новой регистрации
            String userLink = getUserLink(user);
            String adminNotification = "👤 Новая регистрация!\n" +
                    userLink + " - номер " + number +
                    "\n\nВсего зарегистрировано: " + getRegisteredCount() + "/" + MAX_USERS;
            sendMessage(ADMIN_ID, adminNotification);

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
                return "<a href=\"tg://user?id=" + user.getId() + "\">Участник " + user.getId() + "</a>";
            }
        }
    }

    /**
     * Получение интерактивной ссылки на профиль по chatId
     */
    private String getUserLink(Long chatId) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement("SELECT username, first_name, last_name FROM users WHERE chat_id=?")) {
            ps.setLong(1, chatId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String username = rs.getString("username");
                String firstName = rs.getString("first_name");
                String lastName = rs.getString("last_name");

                if (username != null && !username.isEmpty()) {
                    return "<a href=\"tg://user?id=" + chatId + "\">@" + username + "</a>";
                } else {
                    String fullName = (firstName + " " + (lastName != null ? lastName : "")).trim();
                    if (!fullName.isEmpty()) {
                        return "<a href=\"tg://user?id=" + chatId + "\">" + fullName + "</a>";
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("Ошибка получения данных пользователя: " + e.getMessage());
        }
        return "<a href=\"tg://user?id=" + chatId + "\">Участник " + chatId + "</a>";
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
                "Сейчас вам придут вопросы один за другим.\n" +
                "Выбирайте номер, который вам больше всего нравится!\n\n" +
                "💫 Удачи в голосовании!";

        for (Long userId : allUsers) {
            sendMessage(userId, votingStartMessage);
        }

        sendMessage(chatId, "🗳 Голосование начато! Уведомления отправлены " + allUsers.size() + " участникам.");
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
                System.err.println("Ошибка отправки вопроса пользователю " + userId + ": " + e.getMessage());
            }
        }

        // Отправляем вопрос админу
        try {
            SendPhoto adminPhoto = new SendPhoto();
            adminPhoto.setChatId(ADMIN_ID.toString());
            adminPhoto.setPhoto(new InputFile(imageFile));
            adminPhoto.setCaption("📨 Вопрос " + (currentQuestionIndex + 1) + " отправлен " + sentCount + " участникам");
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
    private void showResults(Long chatId) {
        Map<Integer, Integer> voteCounts = new HashMap<>();

        // Загружаем голоса из БД
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT voted_number, COUNT(*) as cnt FROM votes WHERE question_index=? GROUP BY voted_number")) {
            ps.setInt(1, currentQuestionIndex);
            ResultSet rs = ps.executeQuery();

            while (rs.next()) {
                int votedNumber = rs.getInt("voted_number");
                int count = rs.getInt("cnt");
                voteCounts.put(votedNumber, count);
            }
        } catch (SQLException e) {
            System.err.println("Ошибка загрузки результатов: " + e.getMessage());
        }

        if (voteCounts.isEmpty()) {
            sendMessage(chatId, "📊 Пока нет голосов для текущего вопроса.");
            return;
        }

        // Формируем текст с результатами
        StringBuilder resultText = new StringBuilder("📊 Результаты голосования:\n\n");

        voteCounts.entrySet().stream()
                .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
                .forEach(entry -> {
                    resultText.append("№").append(entry.getKey())
                            .append(" — ").append(entry.getValue()).append(" голос(ов)\n");
                });

        sendMessage(chatId, resultText.toString());
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
                message.setChatId(ADMIN_ID.toString());
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
                edit.setChatId(ADMIN_ID.toString());
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
    private void initDatabase() {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS users (" +
                    "chat_id INTEGER PRIMARY KEY, " +
                    "number INTEGER UNIQUE, " +
                    "username TEXT, " +
                    "first_name TEXT, " +
                    "last_name TEXT, " +
                    "registered_at DATETIME DEFAULT CURRENT_TIMESTAMP)");
            System.out.println("✅ База данных инициализирована");
        } catch (SQLException e) {
            System.err.println("❌ Ошибка инициализации БД: " + e.getMessage());
        }
    }

    /**
     * Сохранение пользователя в БД
     */
    private void saveUser(Long chatId, Long number, User user) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users(chat_id, number, username, first_name, last_name) VALUES (?, ?, ?, ?, ?)")) {

            ps.setLong(1, chatId);
            ps.setLong(2, number);
            ps.setString(3, user.getUserName());
            ps.setString(4, user.getFirstName());
            ps.setString(5, user.getLastName());
            ps.executeUpdate();

        } catch (SQLException e) {
            if (e.getMessage().contains("UNIQUE")) {
                System.err.println("Попытка повторной регистрации: " + chatId + " → " + number);
            } else {
                System.err.println("Ошибка сохранения пользователя: " + e.getMessage());
            }
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
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM users ORDER BY number")) {

            int count = 0;
            while (rs.next()) {
                count++;
                String userLink = getUserLink(rs.getLong("chat_id"));
                sb.append("№").append(rs.getInt("number"))
                        .append(" - ").append(userLink).append("\n");
            }
            sb.append("\nВсего: ").append(count).append("/").append(MAX_USERS);

        } catch (SQLException e) {
            System.err.println("Ошибка получения списка пользователей: " + e.getMessage());
        }
        return sb.toString().isEmpty() ? "📭 Нет зарегистрированных участников" : sb.toString();
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
}
