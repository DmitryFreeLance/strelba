package com.strelba.bot.model;

public record UserRow(long id, String username, String firstName, String lastName, boolean admin, String createdAt) {
}
