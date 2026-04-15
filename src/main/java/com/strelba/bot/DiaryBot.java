package com.strelba.bot;

import com.strelba.bot.db.Database;
import com.strelba.bot.model.DiaryEntry;
import com.strelba.bot.model.SurveyStep;
import com.strelba.bot.model.UserRow;
import com.strelba.bot.model.UserSession;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DiaryBot extends TelegramLongPollingBot {
    private static final int USERS_PAGE_SIZE = 10;

    private final String token;
    private final String username;
    private final Database db;
    private final Map<Long, UserSession> sessions = new ConcurrentHashMap<>();

    public DiaryBot(String token, String username, Database db) {
        this.token = token;
        this.username = username;
        this.db = db;
    }

    @Override
    public String getBotUsername() {
        return username;
    }

    @Override
    public String getBotToken() {
        return token;
    }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasMessage() && update.getMessage().getFrom() != null) {
                db.upsertUser(update.getMessage().getFrom());
                handleMessage(update.getMessage());
                return;
            }

            if (update.hasCallbackQuery() && update.getCallbackQuery().getFrom() != null) {
                db.upsertUser(update.getCallbackQuery().getFrom());
                handleCallback(update.getCallbackQuery());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleMessage(Message message) {
        if (!message.hasText()) {
            return;
        }

        long userId = message.getFrom().getId();
        long chatId = message.getChatId();
        String text = message.getText().trim();
        UserSession session = sessions.computeIfAbsent(userId, x -> new UserSession());

        if ("/start".equalsIgnoreCase(text)) {
            sendWelcome(chatId, userId);
            return;
        }

        if ("/admin".equalsIgnoreCase(text)) {
            if (!db.isAdmin(userId)) {
                sendText(chatId, "⛔ У вас нет доступа к админ-панели.", null);
                return;
            }
            session.setAdminAction(UserSession.AdminAction.NONE);
            sendAdminPanel(chatId);
            return;
        }

        if (session.getAdminAction() != UserSession.AdminAction.NONE) {
            processAdminAction(chatId, session, text);
            return;
        }

        SurveyStep step = session.getCurrentStep();
        if (step == null) {
            sendText(chatId, "ℹ️ Чтобы начать заполнение дневника, нажмите /start", null);
            return;
        }

        if (step.isNumericScale()) {
            sendText(chatId, "🔢 Для этого вопроса выберите значение кнопкой ниже.", buildScaleKeyboard(step));
            return;
        }

        session.putAnswer(step, text);
        advanceSurvey(chatId, userId, session);
    }

    private void handleCallback(CallbackQuery callback) {
        long userId = callback.getFrom().getId();
        long chatId = callback.getMessage().getChatId();
        UserSession session = sessions.computeIfAbsent(userId, x -> new UserSession());
        String data = callback.getData();

        answerCallback(callback.getId());

        if ("START_SURVEY".equals(data) || "RESTART".equals(data)) {
            startSurvey(chatId, userId);
            return;
        }

        if (data.startsWith("SCALE:")) {
            String[] parts = data.split(":", 3);
            if (parts.length == 3) {
                SurveyStep.byCode(parts[1]).ifPresent(step -> {
                    if (session.getCurrentStep() == step) {
                        session.putAnswer(step, parts[2]);
                        advanceSurvey(chatId, userId, session);
                    }
                });
            }
            return;
        }

        if (data.startsWith("SKIP:")) {
            String code = data.substring("SKIP:".length());
            SurveyStep.byCode(code).ifPresent(step -> {
                if (session.getCurrentStep() == step) {
                    session.putAnswer(step, "-");
                    advanceSurvey(chatId, userId, session);
                }
            });
            return;
        }

        if (!db.isAdmin(userId)) {
            return;
        }

        switch (data) {
            case "ADMIN_MENU" -> sendAdminPanel(chatId);
            case "ADMIN_RESULTS" -> {
                session.setDayPage(0);
                sendResultsPage(chatId, session.getDayPage());
            }
            case "ADMIN_USERS" -> {
                session.setUsersPage(0);
                sendUsersPage(chatId, session.getUsersPage());
            }
            case "ADMIN_ADD" -> {
                session.setAdminAction(UserSession.AdminAction.WAITING_ADD_ADMIN_ID);
                sendText(chatId,
                        "➕ Введите Telegram ID пользователя, которого нужно сделать админом.",
                        oneButton("❌ Отмена", "ADMIN_CANCEL_ACTION"));
            }
            case "ADMIN_REMOVE" -> {
                session.setAdminAction(UserSession.AdminAction.WAITING_REMOVE_ADMIN_ID);
                sendText(chatId,
                        "➖ Введите Telegram ID администратора, которого нужно удалить.",
                        oneButton("❌ Отмена", "ADMIN_CANCEL_ACTION"));
            }
            case "ADMIN_CANCEL_ACTION" -> {
                session.setAdminAction(UserSession.AdminAction.NONE);
                sendAdminPanel(chatId);
            }
            case "ADMIN_EXIT" -> sendText(chatId, "👋 Вы вышли из админ-панели.", null);
            default -> {
                if (data.startsWith("ADMIN_RESULTS_PAGE:")) {
                    int page = parsePage(data, "ADMIN_RESULTS_PAGE:");
                    session.setDayPage(page);
                    sendResultsPage(chatId, page);
                    return;
                }
                if (data.startsWith("ADMIN_USERS_PAGE:")) {
                    int page = parsePage(data, "ADMIN_USERS_PAGE:");
                    session.setUsersPage(page);
                    sendUsersPage(chatId, page);
                }
            }
        }
    }

    private int parsePage(String data, String prefix) {
        try {
            return Math.max(0, Integer.parseInt(data.substring(prefix.length())));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void processAdminAction(long chatId, UserSession session, String text) {
        long targetId;
        try {
            targetId = Long.parseLong(text);
        } catch (NumberFormatException e) {
            sendText(chatId, "⚠️ ID должен быть числом. Попробуйте еще раз или нажмите Отмена.",
                    oneButton("❌ Отмена", "ADMIN_CANCEL_ACTION"));
            return;
        }

        try {
            if (session.getAdminAction() == UserSession.AdminAction.WAITING_ADD_ADMIN_ID) {
                db.promoteAdmin(targetId);
                sendText(chatId, "✅ Пользователь " + targetId + " теперь админ.", null);
            } else if (session.getAdminAction() == UserSession.AdminAction.WAITING_REMOVE_ADMIN_ID) {
                db.demoteAdmin(targetId);
                sendText(chatId, "✅ Админ " + targetId + " удален.", null);
            }
        } catch (SQLException e) {
            sendText(chatId, "❌ Не получилось обновить права администратора.", null);
        }

        session.setAdminAction(UserSession.AdminAction.NONE);
        sendAdminPanel(chatId);
    }

    private void sendWelcome(long chatId, long userId) {
        boolean admin = db.isAdmin(userId);
        String text = "👋 Привет! Это дневник тренировок по стрельбе.\n"
                + "Я последовательно задам 13 вопросов, сохраню запись и отправлю администраторам уведомление.\n\n"
                + "Если нужно пропустить пункт, используйте кнопку «Пропустить».";

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(button("🚀 Начать опрос", "START_SURVEY")));
        if (admin) {
            rows.add(List.of(button("🛠️ Открыть админ-панель", "ADMIN_MENU")));
        }
        sendText(chatId, text, new InlineKeyboardMarkup(rows));
    }

    private void startSurvey(long chatId, long userId) {
        UserSession session = sessions.computeIfAbsent(userId, x -> new UserSession());
        session.resetSurvey();
        askCurrentStep(chatId, session);
    }

    private void askCurrentStep(long chatId, UserSession session) {
        SurveyStep step = session.getCurrentStep();
        if (step == null) {
            return;
        }

        String text = questionText(step);
        InlineKeyboardMarkup markup = step.isNumericScale() ? buildScaleKeyboard(step) : skipOnlyKeyboard(step);
        sendText(chatId, text, markup);
    }

    private InlineKeyboardMarkup buildScaleKeyboard(SurveyStep step) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            row.add(button(String.valueOf(i), "SCALE:" + step.code() + ":" + i));
            if (row.size() == 5) {
                rows.add(row);
                row = new ArrayList<>();
            }
        }
        if (!row.isEmpty()) {
            rows.add(row);
        }
        rows.add(List.of(button("⏭️ Пропустить", "SKIP:" + step.code())));
        return new InlineKeyboardMarkup(rows);
    }

    private InlineKeyboardMarkup skipOnlyKeyboard(SurveyStep step) {
        return oneButton("⏭️ Пропустить", "SKIP:" + step.code());
    }

    private void advanceSurvey(long chatId, long userId, UserSession session) {
        SurveyStep current = session.getCurrentStep();
        SurveyStep next = current.next();
        session.setCurrentStep(next);
        if (next == null) {
            finishSurvey(chatId, userId, session);
        } else {
            askCurrentStep(chatId, session);
        }
    }

    private void finishSurvey(long chatId, long userId, UserSession session) {
        db.saveEntry(userId, session.getAnswers());
        String summary = entryText(session.getAnswers());

        sendText(chatId,
                "✅ Спасибо! Запись сохранена.\n\n" + summary,
                oneButton("🔄 Заполнить еще", "RESTART"));

        notifyAdmins(userId, summary);
        session.setCurrentStep(null);
        session.getAnswers().clear();
    }

    private void notifyAdmins(long authorUserId, String summary) {
        String msg = "🆕 Новая запись в дневнике!\n"
                + "👤 Автор ID: " + authorUserId + "\n\n"
                + summary;
        InlineKeyboardMarkup kb = oneButton("🛠️ Открыть админ-панель", "ADMIN_MENU");
        for (Long adminId : db.getAdminIds()) {
            sendText(adminId, msg, kb);
        }
    }

    private String entryText(Map<SurveyStep, String> answers) {
        return "📅 Дата и время: " + safe(answers.get(SurveyStep.A1_DATE_TIME)) + "\n"
                + "🏟️ Площадка: " + safe(answers.get(SurveyStep.B1_VENUE)) + "\n"
                + "😴 Сон: " + safe(answers.get(SurveyStep.C1_SLEEP)) + "\n"
                + "💪 Самочувствие: " + safe(answers.get(SurveyStep.D1_WELLBEING)) + "\n"
                + "🍽️ Еда до тренировки: " + safe(answers.get(SurveyStep.E1_FOOD)) + "\n"
                + "🌦️ Погода: " + safe(answers.get(SurveyStep.F1_WEATHER)) + "\n"
                + "🌬️ Ветер (м/с): " + safe(answers.get(SurveyStep.G1_WIND)) + "\n"
                + "💡 Освещение: " + safe(answers.get(SurveyStep.H1_LIGHTING)) + "\n"
                + "🎯 Упражнение: " + safe(answers.get(SurveyStep.I1_EXERCISE)) + "\n"
                + "📈 Результат: " + safe(answers.get(SurveyStep.J1_RESULT)) + "\n"
                + "🏹 Мишень максимум: " + safe(answers.get(SurveyStep.K1_TARGET_MAX)) + "\n"
                + "📝 Комментарий: " + safe(answers.get(SurveyStep.L1_COMMENT)) + "\n"
                + "🏙️ Город: " + safe(answers.get(SurveyStep.M1_CITY));
    }

    private void sendAdminPanel(long chatId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(button("📊 Все результаты", "ADMIN_RESULTS")));
        rows.add(List.of(button("👥 Все пользователи", "ADMIN_USERS")));
        rows.add(List.of(button("➕ Добавить админа", "ADMIN_ADD")));
        rows.add(List.of(button("➖ Удалить админа", "ADMIN_REMOVE")));
        rows.add(List.of(button("🚪 Выйти из панели", "ADMIN_EXIT")));

        sendText(chatId,
                "🛠️ Админ-панель:\nВыберите действие кнопкой ниже.",
                new InlineKeyboardMarkup(rows));
    }

    private void sendResultsPage(long chatId, int page) {
        int totalDays = db.countDaysWithEntries();
        if (totalDays == 0) {
            sendText(chatId, "📭 Результатов пока нет.", oneButton("⬅️ Назад в панель", "ADMIN_MENU"));
            return;
        }

        int safePage = Math.max(0, Math.min(page, totalDays - 1));
        String day = db.getDayByPage(safePage);
        if (day == null) {
            sendText(chatId, "❌ Не удалось получить результаты за выбранный день.", oneButton("⬅️ Назад в панель", "ADMIN_MENU"));
            return;
        }

        List<DiaryEntry> entries = db.getEntriesByDay(day);
        StringBuilder sb = new StringBuilder();
        sb.append("📊 Результаты за ").append(day)
                .append(" (день ").append(safePage + 1).append(" из ").append(totalDays).append(")\n\n");

        for (DiaryEntry e : entries) {
            sb.append("Запись #").append(e.id())
                    .append(" | user_id=").append(e.userId())
                    .append(" | created_at=").append(safe(e.createdAt())).append("\n")
                    .append("A1: ").append(safe(e.dateTime())).append("\n")
                    .append("B1: ").append(safe(e.venue())).append("\n")
                    .append("C1: ").append(safe(e.sleep())).append("\n")
                    .append("D1: ").append(safe(e.wellbeing())).append("\n")
                    .append("E1: ").append(safe(e.food())).append("\n")
                    .append("F1: ").append(safe(e.weather())).append("\n")
                    .append("G1: ").append(safe(e.wind())).append("\n")
                    .append("H1: ").append(safe(e.lighting())).append("\n")
                    .append("I1: ").append(safe(e.exercise())).append("\n")
                    .append("J1: ").append(safe(e.result())).append("\n")
                    .append("K1: ").append(safe(e.targetMax())).append("\n")
                    .append("L1: ").append(safe(e.comment())).append("\n")
                    .append("M1: ").append(safe(e.city())).append("\n\n");
        }

        String text = trimToTelegramLimit(sb.toString());
        sendText(chatId, text, resultsKeyboard(safePage, totalDays));
    }

    private InlineKeyboardMarkup resultsKeyboard(int page, int total) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> nav = new ArrayList<>();

        if (page > 0) {
            nav.add(button("⬅️ День", "ADMIN_RESULTS_PAGE:" + (page - 1)));
        }
        if (page < total - 1) {
            nav.add(button("День ➡️", "ADMIN_RESULTS_PAGE:" + (page + 1)));
        }
        if (!nav.isEmpty()) {
            rows.add(nav);
        }
        rows.add(List.of(button("⬅️ Назад в панель", "ADMIN_MENU")));
        return new InlineKeyboardMarkup(rows);
    }

    private void sendUsersPage(long chatId, int page) {
        int totalUsers = db.countUsers();
        if (totalUsers == 0) {
            sendText(chatId, "📭 Пользователей пока нет.", oneButton("⬅️ Назад в панель", "ADMIN_MENU"));
            return;
        }

        int totalPages = (int) Math.ceil(totalUsers / (double) USERS_PAGE_SIZE);
        int safePage = Math.max(0, Math.min(page, totalPages - 1));

        List<UserRow> users = db.getUsersPage(safePage, USERS_PAGE_SIZE);
        StringBuilder sb = new StringBuilder();
        sb.append("👥 Пользователи (страница ").append(safePage + 1).append(" из ").append(totalPages).append(")\n\n");
        for (UserRow u : users) {
            sb.append("ID: ").append(u.id())
                    .append(" | @").append(safe(u.username()))
                    .append(" | ").append(safe(u.firstName())).append(" ").append(safe(u.lastName()))
                    .append(" | admin=").append(u.admin() ? "yes" : "no")
                    .append("\n");
        }

        sendText(chatId, sb.toString(), usersKeyboard(safePage, totalPages));
    }

    private InlineKeyboardMarkup usersKeyboard(int page, int totalPages) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> nav = new ArrayList<>();

        if (page > 0) {
            nav.add(button("⬅️ Страница", "ADMIN_USERS_PAGE:" + (page - 1)));
        }
        if (page < totalPages - 1) {
            nav.add(button("Страница ➡️", "ADMIN_USERS_PAGE:" + (page + 1)));
        }
        if (!nav.isEmpty()) {
            rows.add(nav);
        }
        rows.add(List.of(button("⬅️ Назад в панель", "ADMIN_MENU")));
        return new InlineKeyboardMarkup(rows);
    }

    private String questionText(SurveyStep step) {
        return switch (step) {
            case A1_DATE_TIME -> "📅 Вопрос A1: Дата и время тренировки";
            case B1_VENUE -> "🏟️ Вопрос B1: Площадка";
            case C1_SLEEP -> "😴 Вопрос C1: Сон (1-10)";
            case D1_WELLBEING -> "💪 Вопрос D1: Самочувствие (1-10)";
            case E1_FOOD -> "🍽️ Вопрос E1: Еда до тренировки";
            case F1_WEATHER -> "🌦️ Вопрос F1: Погода";
            case G1_WIND -> "🌬️ Вопрос G1: Ветер (м/с)";
            case H1_LIGHTING -> "💡 Вопрос H1: Освещение";
            case I1_EXERCISE -> "🎯 Вопрос I1: Упражнение";
            case J1_RESULT -> "📈 Вопрос J1: Результат";
            case K1_TARGET_MAX -> "🏹 Вопрос K1: Мишень максимум";
            case L1_COMMENT -> "📝 Вопрос L1: Комментарий";
            case M1_CITY -> "🏙️ Вопрос M1: Город";
        };
    }

    private InlineKeyboardMarkup oneButton(String text, String callbackData) {
        return new InlineKeyboardMarkup(List.of(List.of(button(text, callbackData))));
    }

    private InlineKeyboardButton button(String text, String callbackData) {
        InlineKeyboardButton btn = new InlineKeyboardButton();
        btn.setText(text);
        btn.setCallbackData(callbackData);
        return btn;
    }

    private void answerCallback(String callbackId) {
        try {
            AnswerCallbackQuery query = new AnswerCallbackQuery();
            query.setCallbackQueryId(callbackId);
            execute(query);
        } catch (Exception ignored) {
        }
    }

    private void sendText(long chatId, String text, InlineKeyboardMarkup markup) {
        try {
            SendMessage msg = new SendMessage();
            msg.setChatId(String.valueOf(chatId));
            msg.setText(trimToTelegramLimit(text));
            if (markup != null) {
                msg.setReplyMarkup(markup);
            }
            execute(msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @SuppressWarnings("unused")
    private void editText(long chatId, int messageId, String text, InlineKeyboardMarkup markup) {
        try {
            EditMessageText edit = new EditMessageText();
            edit.setChatId(String.valueOf(chatId));
            edit.setMessageId(messageId);
            edit.setText(trimToTelegramLimit(text));
            edit.setReplyMarkup(markup);
            execute(edit);
        } catch (Exception e) {
            sendText(chatId, text, markup);
        }
    }

    private String trimToTelegramLimit(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= 3900) {
            return text;
        }
        return text.substring(0, 3900) + "\n\n...сообщение сокращено";
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
