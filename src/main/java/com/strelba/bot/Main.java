package com.strelba.bot;

import com.strelba.bot.db.Database;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

public class Main {
    public static void main(String[] args) throws Exception {
        String botToken = Env.required("BOT_TOKEN");
        String botUsername = Env.optional("BOT_USERNAME", "StrelbaDiaryBot");
        String dbPath = Env.optional("SQLITE_PATH", "data/bot.db");
        String initialAdmins = Env.optional("INITIAL_ADMINS", "");

        Database database = new Database(dbPath, initialAdmins);
        DiaryBot bot = new DiaryBot(botToken, botUsername, database);

        TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
        botsApi.registerBot(bot);

        System.out.println("Bot started: @" + botUsername);
    }
}
