package org.example.bot;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseInit {

    private static final String DB_URL = "jdbc:sqlite:bot.db";

    public static void init() {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {

            // Таблица участников
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    chat_id INTEGER PRIMARY KEY,
                    username TEXT,
                    number INTEGER
                );
            """);

            // Таблица вопросов
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS questions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    file_path TEXT,
                    is_active INTEGER DEFAULT 0
                );
            """);

            // Таблица голосов
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS votes (
                    question_id INTEGER,
                    user_id INTEGER,
                    vote_for INTEGER,
                    PRIMARY KEY (question_id, user_id)
                );
            """);

            System.out.println("✅ Таблицы успешно созданы/проверены.");

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}