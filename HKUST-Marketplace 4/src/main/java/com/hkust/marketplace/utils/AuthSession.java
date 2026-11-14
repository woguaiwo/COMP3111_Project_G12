package com.hkust.marketplace.utils;

import com.hkust.marketplace.models.User;

import java.time.Instant;

/**
 * Result of authenticating against the marketplace API.
 *
 * <p>The JavaFX client keeps the short-lived access token in
 * {@link SessionManager}; it is never stored on the User object or persisted
 * to disk.</p>
 */
public record AuthSession(User user, String accessToken, Instant expiresAt) {
}
