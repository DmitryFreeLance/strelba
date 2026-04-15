package com.strelba.bot;

public final class Env {
    private Env() {
    }

    public static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Environment variable is required: " + key);
        }
        return value.trim();
    }

    public static String optional(String key, String fallback) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }
}
