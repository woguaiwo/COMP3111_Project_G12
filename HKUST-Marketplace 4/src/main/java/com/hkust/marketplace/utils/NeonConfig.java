package com.hkust.marketplace.utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads the optional local Neon connection configuration.
 *
 * <p>The released application must not contain a database password. During
 * team development the value can be supplied as {@code DATABASE_URL} or in an
 * ignored {@code database/.env} file. A missing value intentionally keeps the
 * existing offline demo mode working.</p>
 */
public final class NeonConfig {

    private static final String DATABASE_URL = "DATABASE_URL";

    private NeonConfig() {
    }

    public static String databaseUrl() {
        String value = firstNonBlank(
                System.getenv(DATABASE_URL),
                System.getProperty(DATABASE_URL),
                readFromDotEnv(Path.of("database", ".env")),
                readFromDotEnv(Path.of(".env.local")),
                readFromDotEnv(Path.of(".env"))
        );
        return value;
    }

    public static boolean isConfigured() {
        return databaseUrl() != null;
    }

    private static String readFromDotEnv(Path path) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int separator = trimmed.indexOf('=');
                if (separator <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, separator).trim();
                if (!DATABASE_URL.equals(key)) {
                    continue;
                }
                return unquote(trimmed.substring(separator + 1).trim());
            }
        } catch (IOException ignored) {
            // A missing or unreadable local env file means demo mode remains
            // available. Connection failures are reported by the data layer.
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
