package com.strelba.bot.model;

public record DiaryEntry(
        long id,
        long userId,
        String dateTime,
        String venue,
        String sleep,
        String wellbeing,
        String food,
        String weather,
        String wind,
        String lighting,
        String exercise,
        String result,
        String targetMax,
        String comment,
        String city,
        String createdAt
) {
}
