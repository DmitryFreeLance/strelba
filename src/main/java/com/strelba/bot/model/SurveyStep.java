package com.strelba.bot.model;

import java.util.Arrays;
import java.util.Optional;

public enum SurveyStep {
    A1_DATE_TIME("A1", "Дата и время тренировки"),
    B1_VENUE("B1", "Площадка"),
    C1_SLEEP("C1", "Сон (1-10)"),
    D1_WELLBEING("D1", "Самочувствие (1-10)"),
    E1_FOOD("E1", "Еда до тренировки"),
    F1_WEATHER("F1", "Погода"),
    G1_WIND("G1", "Ветер (м/с)"),
    H1_LIGHTING("H1", "Освещение"),
    I1_EXERCISE("I1", "Упражнение"),
    J1_RESULT("J1", "Результат"),
    K1_TARGET_MAX("K1", "Мишень максимум"),
    L1_COMMENT("L1", "Комментарий"),
    M1_CITY("M1", "Город");

    private final String code;
    private final String title;

    SurveyStep(String code, String title) {
        this.code = code;
        this.title = title;
    }

    public String code() {
        return code;
    }

    public String title() {
        return title;
    }

    public boolean isNumericScale() {
        return this == C1_SLEEP || this == D1_WELLBEING;
    }

    public SurveyStep next() {
        int index = ordinal() + 1;
        return index >= values().length ? null : values()[index];
    }

    public static Optional<SurveyStep> byCode(String code) {
        return Arrays.stream(values()).filter(s -> s.code.equals(code)).findFirst();
    }
}
