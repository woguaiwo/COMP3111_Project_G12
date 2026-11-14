package com.hkust.marketplace.utils;

import com.hkust.marketplace.models.User;

import java.time.Instant;

/**
 * Session holder for the currently logged-in user and the short-lived JWT
 * issued by the marketplace API.
 */
public class SessionManager {
    private static User currentUser;
    private static String accessToken;
    private static Instant expiresAt;

    public static void login(User user) {
        login(user, null, null);
    }

    public static void login(User user, String token, Instant tokenExpiresAt) {
        currentUser = user;
        accessToken = token;
        expiresAt = tokenExpiresAt;
    }

    public static void logout() {
        currentUser = null;
        accessToken = null;
        expiresAt = null;
    }

    public static User getCurrentUser() {
        return currentUser;
    }

    public static boolean isLoggedIn() {
        return currentUser != null && !isTokenExpired();
    }

    public static String getAccessToken() {
        return accessToken;
    }

    public static Instant getExpiresAt() {
        return expiresAt;
    }

    public static boolean isTokenExpired() {
        return expiresAt != null && !Instant.now().isBefore(expiresAt);
    }

    public static boolean isBuyer() {
        return currentUser != null && currentUser.getRole() == com.hkust.marketplace.models.UserRole.BUYER;
    }

    public static boolean isSeller() {
        return currentUser != null && currentUser.getRole() == com.hkust.marketplace.models.UserRole.SELLER;
    }

    public static boolean isAdmin() {
        return currentUser != null && currentUser.getRole() == com.hkust.marketplace.models.UserRole.ADMIN;
    }
}
