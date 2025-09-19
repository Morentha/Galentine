package org.example.bot;

import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

public class Main {
    public static void main(String[] args) {
        try {
            // Создаём API для ботов
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);

            // Регистрируем нашего бота
            botsApi.registerBot(new MadBot());

            System.out.println("Бот успешно запущен!");
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Ошибка при запуске бота!");
        }
    }
    }
