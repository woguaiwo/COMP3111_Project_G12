package com.hkust.marketplace.utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads the optional local Neon connection configuration.
 *
 * <p>The released application contains only the public Function URL. During
 * team development a database URL may be supplied through an ignored local
 * file to select the JDBC fallback.</p>
 */
public final class NeonConfig {

    private static final String DATABASE_URL = "DATABASE_URL";
    private static final String MARKETPLACE_API_URL = "MARKETPLACE_API_URL";
    private static final String MARKETPLACE_MODE = "MARKETPLACE_MODE";

    /**
     * Filled with the deployed Neon Function URL after deployment. Keeping the
     * URL in the client is safe; the database connection string and JWT secret
     * stay exclusively in the function environment.
     */
    private static final String DEFAULT_API_URL =
            "https://br-dawn-dust-b3f6mcny-marketplaceapi.compute.c-4.ap-southeast-1.aws.neon.tech";

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
        return !isDemoMode() && databaseUrl() != null;
    }

    public static String apiUrl() {
        if (isDemoMode() || isJdbcMode()) {
            return null;
        }
        return firstNonBlank(
                System.getenv(MARKETPLACE_API_URL),
                System.getProperty(MARKETPLACE_API_URL),
                readFromDotEnv(Path.of("backend", ".env.local"), MARKETPLACE_API_URL),
                readFromDotEnv(Path.of("backend", ".env"), MARKETPLACE_API_URL),
                readFromDotEnv(Path.of(".env.local"), MARKETPLACE_API_URL),
                DEFAULT_API_URL
        );
    }

    /**
     * Selects the original in-memory demo instead of contacting Neon.
     * This is useful for Scene Builder/UI work without network access.
     */
    public static boolean isDemoMode() {
        String mode = marketplaceMode();
        return "demo".equalsIgnoreCase(mode) || "offline".equalsIgnoreCase(mode);
    }

    private static boolean isJdbcMode() {
        return "jdbc".equalsIgnoreCase(marketplaceMode());
    }

    private static String marketplaceMode() {
        return firstNonBlank(
                System.getenv(MARKETPLACE_MODE),
                System.getProperty(MARKETPLACE_MODE),
                readFromDotEnv(Path.of("backend", ".env.local"), MARKETPLACE_MODE),
                readFromDotEnv(Path.of("backend", ".env"), MARKETPLACE_MODE),
                readFromDotEnv(Path.of(".env.local"), MARKETPLACE_MODE),
                readFromDotEnv(Path.of(".env"), MARKETPLACE_MODE)
        );
    }

    private static String readFromDotEnv(Path path) {
        return readFromDotEnv(path, DATABASE_URL);
    }

    private static String readFromDotEnv(Path path, String requestedKey) {
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
                if (!requestedKey.equals(key)) {
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
