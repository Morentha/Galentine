package org.example.bot;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
//Со стороны участниц надо: в начале появляется приветственная картинка, которая лежит в отдельной папке (чтобы я могла её менять)
//Вместе с ней появляется текст о том, что нужно внести номер, который у участниц написан на карточке
//Когда участницы вводят номер, то он за ними закрепляется, им приходит сообщение типа твой номер такой-то,
//ожидайте, ваш звонок очень важен для нас и т.д. и т.п.

public class MadBot extends TelegramLongPollingBot {

    // Простое хранилище: chatId -> номер
    private final Map<String, String> userNumbers = new HashMap<>();

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
            String text = update.getMessage().getText();
            String chatId = update.getMessage().getChatId().toString();

            if (text.equals("/start")) {
                sendWelcome(chatId);
            } else if (text.matches("\\d+")) { // если ввели номер
                userNumbers.put(chatId, text);
                sendMessage(chatId, "Ваш номер: " + text);
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
}