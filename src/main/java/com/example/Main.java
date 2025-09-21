package com.example;

import com.example.bot.DatabaseInit;
import com.example.bot.MadBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

public class Main {
    public static void main(String[] args) {
        try {
            // Создаём API для ботов
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);

            // Регистрируем нашего бота
            botsApi.registerBot(new MadBot());
            DatabaseInit.init();
            System.out.println("✅ База данных инициализирована");
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Ошибка при запуске бота!");
        }
        System.out.println("Бот успешно запущен!");
    }
    }
