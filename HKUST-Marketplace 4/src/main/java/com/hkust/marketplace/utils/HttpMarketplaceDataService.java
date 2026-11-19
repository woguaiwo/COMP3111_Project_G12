package com.hkust.marketplace.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hkust.marketplace.models.Deal;
import com.hkust.marketplace.models.Listing;
import com.hkust.marketplace.models.ListingStatus;
import com.hkust.marketplace.models.ListingType;
import com.hkust.marketplace.models.Notification;
import com.hkust.marketplace.models.Review;
import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Client-side adapter for Neon Data API + Managed Neon Auth.
 *
 * <p>The JavaFX process only knows public HTTPS endpoints. Passwords are sent
 * to Neon Auth, while marketplace operations go through one protected
 * PostgreSQL RPC. No database connection string, owner password, or password
 * hash is shipped to users.</p>
 */
public class HttpMarketplaceDataService implements MarketplaceDataService {

    private final String baseUrl;
    private final String authUrl;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile String authCookie;
    private volatile String authJwt;
    private volatile Instant authJwtExpiresAt;

    public HttpMarketplaceDataService(String baseUrl) {
        this(baseUrl, NeonConfig.authUrl());
    }

    public HttpMarketplaceDataService(String baseUrl, String authUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("Marketplace API URL is required");
        }
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        if (authUrl == null || authUrl.isBlank()) {
            throw new IllegalArgumentException("Neon Auth URL is required");
        }
        this.authUrl = authUrl.replaceAll("/+$", "");
    }

    /**
     * Checks the public Data API endpoint. A 401 is expected without a JWT and
     * still proves that the endpoint is reachable.
     */
    public boolean isAvailable() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/"))
                    .timeout(Duration.ofSeconds(3))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<Void> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() < 500;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public boolean usernameExists(String username) {
        // Neon Data API requires a JWT even for its anonymous database role.
        // Registration still performs the authoritative uniqueness check in
        // ensure_profile after Neon Auth creates the session.
        if (authJwt == null || authJwt.isBlank()) return false;
        JsonNode result = callAction("check_username", Map.of("username", username), true);
        return !result.path("available").asBoolean(true);
    }

    @Override
    public User findUser(String username) {
        return toUser(callAction("user", Map.of("username", username), true));
    }

    @Override
    public AuthSession login(String username, String password) {
        authCookie = null;
        authJwt = null;
        authJwtExpiresAt = null;
        JsonNode result;
        try {
            result = authRequest("/sign-in/email", Map.of(
                    "email", authEmail(username), "password", password), false);
        } catch (JdbcMarketplaceDataService.DataAccessException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("401:")) return null;
            throw e;
        }
        if (result == null) return null;
        String token = refreshAuthJwt();
        JsonNode profile = callAction("ensure_profile", Map.of("role", "BUYER", "fullName", username), true);
        return new AuthSession(toUser(profile), token, authJwtExpiresAt);
    }

    @Override
    public void logout() {
        if (authCookie != null) {
            try {
                authRequest("/sign-out", Map.of(), true);
            } finally {
                authCookie = null;
                authJwt = null;
                authJwtExpiresAt = null;
            }
        }
    }

    @Override
    public void registerUser(User user) {
        authCookie = null;
        authJwt = null;
        authJwtExpiresAt = null;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", authEmail(user.getUsername()));
        body.put("name", user.getFullName());
        body.put("password", user.getPasswordHash());
        authRequest("/sign-up/email", body, false);
        refreshAuthJwt();
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("role", user.getRole().name());
        profile.put("fullName", user.getFullName());
        profile.put("residenceHall", user.getResidenceHall());
        profile.put("bio", user.getBio());
        profile.put("preferredMeetup", user.getPreferredMeetup());
        copyUser(toUser(callAction("ensure_profile", profile, true)), user);
        user.setPasswordHash(null);
        // Registration does not log the user in at the application layer.
        logout();
    }

    @Override
    public Collection<User> getAllUsers() {
        JsonNode result = request("GET", "/api/users", null, true);
        List<User> users = new ArrayList<>();
        result.forEach(node -> users.add(toUser(node)));
        return users;
    }

    @Override
    public List<User> getUsersByRole(UserRole role) {
        JsonNode result = request("GET", "/api/users?role=" + role.name(), null, true);
        List<User> users = new ArrayList<>();
        result.forEach(node -> users.add(toUser(node)));
        return users;
    }

    @Override
    public void updateUser(User user) {
        if (user == null || user.getUsername() == null
                || user.getUsername().isBlank()) {
            throw new IllegalArgumentException("Invalid user.");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", user.getUsername());
        payload.put("fullName", user.getFullName());
        payload.put("residenceHall", user.getResidenceHall());
        payload.put("bio", user.getBio());
        payload.put("preferredMeetup", user.getPreferredMeetup());
        User updated = toUser(
                callAction("admin_update_user", payload, true)
        );
        copyUser(updated, user);
    }

    @Override
    public void updateAdminProfile(String username, String fullName) {
        if (fullName == null || fullName.trim().isEmpty()) {
            throw new IllegalArgumentException("Full Name cannot be empty.");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", username);
        payload.put("full_name", fullName.trim());
        callAction("admin_update_profile", payload, true);
    }

    @Override
    public void changeAdminPassword(
            String username,
            String currentPassword,
            String newPassword
    ) {
        if (currentPassword == null || currentPassword.isBlank()) {
            throw new IllegalArgumentException("Current password cannot be empty.");
        }
        String validation = DummyDataService.checkPasswordStrength(newPassword);
        if (!"OK".equals(validation)) {
            throw new IllegalArgumentException(validation);
        }
        if (authCookie == null || authCookie.isBlank()) {
            throw new IllegalStateException(
                    "Your Neon Auth session has expired. Please log in again."
            );
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("currentPassword", currentPassword);
        payload.put("newPassword", newPassword);
        payload.put("revokeOtherSessions", true);
        authRequest("/change-password", payload, true);
    }

    @Override
    public void setUserActive(String username, boolean active) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", username);
        payload.put("active", active);
        callAction("admin_set_user_active", payload, true);
    }

    @Override
    public void deleteUser(String username) {
        throw new UnsupportedOperationException(
                "Permanent deletion requires Neon Auth account deletion support."
        );
    }

    @Override
    public Collection<Listing> getAllListings() {
        return toListings(request("GET", "/api/listings?status=ALL", null, true));
    }

    @Override
    public List<Listing> getLiveListings() {
        return toListings(request("GET", "/api/listings?status=LIVE", null, true));
    }

    @Override
    public List<Listing> getPendingListings() {
        return toListings(request("GET", "/api/listings?status=PENDING_REVIEW", null, true));
    }

    @Override
    public List<Listing> getListingsBySeller(String username) {
        return toListings(request("GET", "/api/listings?seller=" + encode(username), null, true));
    }

    @Override
    public Listing findListing(String id) {
        return toListing(request("GET", "/api/listings/" + encode(id), null, true));
    }

    @Override
    public void addListing(Listing listing) {
        JsonNode saved = request("POST", "/api/listings", listingBody(listing), true);
        copyListing(toListing(saved), listing);
    }

    @Override
    public void updateListing(Listing listing) {
        JsonNode saved = request("PUT", "/api/listings/" + encode(listing.getId()), listingBody(listing), true);
        copyListing(toListing(saved), listing);
    }

    @Override
    public void removeListing(String id) {
        request("DELETE", "/api/listings/" + encode(id), null, true);
    }

    @Override
    public void adminRemoveListing(String id, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", reason);
        request("POST", "/api/admin/listings/" + encode(id), body, true);
    }

    @Override
    public List<Deal> getDealsForBuyer(String username) {
        JsonNode result = request("GET", "/api/deals?buyer=" + encode(username), null, true);
        return toDeals(result);
    }

    @Override
    public List<Deal> getAllDeals() {
        return toDeals(request("GET", "/api/deals", null, true));
    }

    @Override
    public void addDeal(Deal deal) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("listingId", deal.getListingId());
        body.put("offerPrice", deal.getOfferPrice());
        body.put("borrowStart", deal.getBorrowStart() == null
                ? null : deal.getBorrowStart().toString());
        body.put("borrowEnd", deal.getBorrowEnd() == null
                ? null : deal.getBorrowEnd().toString());
        JsonNode saved = request("POST", "/api/deals", body, true);
        copyDeal(toDeal(saved), deal);
    }

    @Override
    public List<Notification> getNotificationsFor(String username) {
        JsonNode result = request("GET", "/api/notifications?username=" + encode(username), null, true);
        return toNotifications(result);
    }

    @Override
    public void addNotification(Notification notification) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", notification.getUsername());
        body.put("title", notification.getTitle());
        body.put("message", notification.getMessage());
        body.put("category", notification.getCategory());
        body.put("read", notification.isRead());
        JsonNode saved = request("POST", "/api/notifications", body, true);
        copyNotification(toNotification(saved), notification);
    }

    @Override
    public List<Review> getReviewsForSeller(String username) {
        JsonNode result = request("GET", "/api/reviews?seller=" + encode(username), null, true);
        return toReviews(result);
    }

    @Override
    public List<Review> getAllReviews() {
        return toReviews(request("GET", "/api/reviews", null, true));
    }

    @Override
    public void addReview(Review review) {
        String listingId = findListingId(review);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("listingId", listingId);
        body.put("rating", review.getRating());
        body.put("comment", review.getComment());
        JsonNode saved = request("POST", "/api/reviews", body, true);
        copyReview(toReview(saved), review);
    }

    @Override
    public void setReviewHidden(String reviewId, boolean hidden) {
        if (reviewId == null || !reviewId.matches("(?i)^R\\d+$")) {
            throw new IllegalArgumentException("Invalid review ID.");
        }

        callAction(
                "admin_set_review_hidden",
                Map.of("reviewId", reviewId, "hidden", hidden),
                true
        );
    }

    private JsonNode request(String method, String path, Object body, boolean authenticated) {
        URI relative = URI.create(path);
        String route = relative.getPath();
        Map<String, Object> payload = new LinkedHashMap<>();
        if (body != null) {
            payload.putAll(objectMapper.convertValue(body, Map.class));
        }
        Map<String, String> query = query(relative.getRawQuery());
        String action;
        if (route.equals("/auth/check-username")) {
            action = "check_username";
            payload.put("username", query.get("username"));
        } else if (route.equals("/api/users")) {
            action = "users";
            if (query.containsKey("role")) payload.put("role", query.get("role"));
        } else if (route.startsWith("/api/admin/listings/")) {
            String id = decode(route.substring("/api/admin/listings/".length()));
            payload.put("id", id);
            action = "admin_remove_listing";
        } else if (route.startsWith("/api/users/")) {
            action = "user";
            payload.put("username", decode(route.substring("/api/users/".length())));
        } else if (route.equals("/api/listings")) {
            if ("POST".equalsIgnoreCase(method)) {
                action = "create_listing";
            } else {
                action = "listings";
                if (query.containsKey("status")) payload.put("status", query.get("status"));
                if (query.containsKey("seller")) payload.put("seller", query.get("seller"));
            }
        } else if (route.startsWith("/api/listings/")) {
            String id = decode(route.substring("/api/listings/".length()));
            payload.put("id", id);
            action = "GET".equalsIgnoreCase(method) ? "listing"
                    : "PUT".equalsIgnoreCase(method) ? "update_listing" : "delete_listing";
        } else if (route.equals("/api/deals")) {
            action = "POST".equalsIgnoreCase(method) ? "create_deal" : "deals";
            if (query.containsKey("buyer")) payload.put("buyer", query.get("buyer"));
        } else if (route.equals("/api/notifications")) {
            action = "POST".equalsIgnoreCase(method) ? "create_notification" : "notifications";
            if (query.containsKey("username")) payload.put("username", query.get("username"));
        } else if (route.equals("/api/reviews")) {
            action = "POST".equalsIgnoreCase(method) ? "create_review" : "reviews";
            if (query.containsKey("seller")) payload.put("seller", query.get("seller"));
        } else {
            throw failure("Unknown marketplace route: " + path);
        }
        return callAction(action, payload, authenticated);
    }

    private JsonNode callAction(String action, Map<String, ?> payload, boolean authenticated) {
        try {
            ObjectNode arguments = objectMapper.createObjectNode();
            arguments.put("p_action", action);
            arguments.set("p_payload", objectMapper.valueToTree(payload));
            String token = authenticated ? currentAuthToken() : null;
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/rpc/marketplace_api"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json");
            if (token != null && !token.isBlank()) builder.header("Authorization", "Bearer " + token);
            HttpResponse<String> response = httpClient.send(
                    builder.POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(arguments))).build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode result;
            try {
                result = response.body() == null || response.body().isBlank()
                        ? objectMapper.createObjectNode()
                        : objectMapper.readTree(response.body());
            } catch (IOException invalidJson) {
                result = objectMapper.createObjectNode();
                ((ObjectNode) result).put("error", response.body() == null
                        ? "Marketplace API returned an empty response"
                        : response.body().trim());
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String message = result.path("error").asText(
                        result.path("message").asText("Marketplace API request failed"));
                throw failure(response.statusCode() + ": " + message);
            }
            if (result.has("ok") && !result.path("ok").asBoolean()) {
                throw failure(result.path("error").asText("Marketplace operation failed"));
            }
            return result.has("data") ? result.path("data") : result;
        } catch (IOException e) {
            throw failure("connect to marketplace API", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure("complete marketplace API request", e);
        }
    }

    private JsonNode authRequest(String path, Map<String, ?> body, boolean withSession) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(authUrl + path))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("Origin", "http://localhost");
            if (withSession && authCookie != null) builder.header("Cookie", authCookie);
            HttpResponse<String> response = httpClient.send(
                    builder.POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
            captureCookie(response);
            JsonNode result = response.body() == null || response.body().isBlank()
                    ? objectMapper.createObjectNode() : objectMapper.readTree(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String message = result.path("message").asText(
                        result.path("error").asText("Neon Auth request failed"));
                throw failure(response.statusCode() + ": " + message);
            }
            return result;
        } catch (IOException e) {
            throw failure("connect to Neon Auth", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure("complete Neon Auth request", e);
        }
    }

    private String refreshAuthJwt() {
        if (authCookie == null || authCookie.isBlank()) throw failure("No active Neon Auth session");
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(authUrl + "/get-session"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .header("Origin", "http://localhost")
                    .header("Cookie", authCookie)
                    .GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw failure(response.statusCode() + ": Neon Auth session is no longer valid");
            }
            String jwt = response.headers().firstValue("set-auth-jwt").orElse(null);
            if (jwt == null || jwt.isBlank()) throw failure("Neon Auth did not return an access JWT");
            authJwt = jwt;
            authJwtExpiresAt = jwtExpiry(jwt);
            return jwt;
        } catch (IOException e) {
            throw failure("refresh Neon Auth session", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure("refresh Neon Auth session", e);
        }
    }

    private String currentAuthToken() {
        if (authJwt != null && authJwtExpiresAt != null
                && Instant.now().isBefore(authJwtExpiresAt.minusSeconds(20))) {
            return authJwt;
        }
        if (authCookie != null && !authCookie.isBlank()) return refreshAuthJwt();
        String token = SessionManager.getAccessToken();
        if (token == null || token.isBlank()) throw failure("No active API session");
        return token;
    }

    private void captureCookie(HttpResponse<?> response) {
        List<String> cookies = response.headers().allValues("set-cookie");
        if (cookies.isEmpty()) return;
        StringBuilder value = new StringBuilder();
        for (String cookie : cookies) {
            int separator = cookie.indexOf(';');
            String pair = separator < 0 ? cookie : cookie.substring(0, separator);
            if (value.length() > 0) value.append("; ");
            value.append(pair);
        }
        authCookie = value.toString();
    }

    private static Instant jwtExpiry(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]),
                    java.nio.charset.StandardCharsets.UTF_8);
            long exp = new ObjectMapper().readTree(payload).path("exp").asLong(0);
            return exp > 0 ? Instant.ofEpochSecond(exp) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String authEmail(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT) + "@marketplace.invalid";
    }

    private static Map<String, String> query(String rawQuery) {
        Map<String, String> result = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) return result;
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = decode(parts[0]);
            String value = parts.length == 1 ? "" : decode(parts[1]);
            result.put(key, value);
        }
        return result;
    }

    private static String decode(String value) {
        return java.net.URLDecoder.decode(value == null ? "" : value,
                java.nio.charset.StandardCharsets.UTF_8);
    }

    private Map<String, Object> listingBody(Listing listing) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", listing.getTitle());
        body.put("description", listing.getDescription());
        body.put("category", listing.getCategory());
        body.put("type", listing.getType() == null ? null : listing.getType().name());
        body.put("status", listing.getStatus() == null ? null : listing.getStatus().name());
        body.put("condition", listing.getCondition());
        body.put("price", listing.getPrice());
        body.put("lendingDays", listing.getLendingDays());
        body.put("meetupLocation", listing.getMeetupLocation());
        body.put("sellerUsername", listing.getSellerUsername());
        body.put("reviewedAt", listing.getReviewedAt() == null
                ? null : listing.getReviewedAt().toString());
        body.put("rejectionReason", listing.getRejectionReason());
        return body;
    }

    private String findListingId(Review review) {
        for (Listing listing : getListingsBySeller(review.getSellerUsername())) {
            if (review.getListingTitle() != null && review.getListingTitle().equals(listing.getTitle())) {
                return listing.getId();
            }
        }
        throw failure("find review listing");
    }

    private List<Listing> toListings(JsonNode node) {
        List<Listing> result = new ArrayList<>();
        node.forEach(item -> result.add(toListing(item)));
        return result;
    }

    private List<Deal> toDeals(JsonNode node) {
        List<Deal> result = new ArrayList<>();
        node.forEach(item -> result.add(toDeal(item)));
        return result;
    }

    private List<Notification> toNotifications(JsonNode node) {
        List<Notification> result = new ArrayList<>();
        node.forEach(item -> result.add(toNotification(item)));
        return result;
    }

    private List<Review> toReviews(JsonNode node) {
        List<Review> result = new ArrayList<>();
        node.forEach(item -> result.add(toReview(item)));
        return result;
    }

    private User toUser(JsonNode node) {
        User user = new User();
        user.setUsername(text(node, "username"));
        user.setFullName(text(node, "fullName"));
        user.setRole(enumValue(UserRole.class, text(node, "role")));
        user.setResidenceHall(nullableText(node, "residenceHall"));
        user.setBio(nullableText(node, "bio"));
        user.setPreferredMeetup(nullableText(node, "preferredMeetup"));
        user.setActive(node.path("active").asBoolean(true));
        user.setAverageRating(node.path("averageRating").asDouble(0));
        user.setRatingCount(node.path("ratingCount").asInt(0));
        user.setCreatedAt(dateTime(node, "createdAt"));
        user.setPasswordHash(null);
        return user;
    }

    private Listing toListing(JsonNode node) {
        Listing listing = new Listing();
        listing.setId(text(node, "id"));
        listing.setTitle(text(node, "title"));
        listing.setDescription(nullableText(node, "description"));
        listing.setCategory(text(node, "category"));
        listing.setType(enumValue(ListingType.class, text(node, "type")));
        listing.setStatus(enumValue(ListingStatus.class, text(node, "status")));
        listing.setCondition(text(node, "condition"));
        listing.setPrice(node.path("price").isNull() ? null : node.path("price").asDouble());
        listing.setLendingDays(node.path("lendingDays").isNull() ? null : node.path("lendingDays").asInt());
        listing.setMeetupLocation(text(node, "meetupLocation"));
        listing.setSellerUsername(text(node, "sellerUsername"));
        listing.setSellerFullName(text(node, "sellerFullName"));
        listing.setPostedAt(dateTime(node, "postedAt"));
        listing.setReviewedAt(dateTime(node, "reviewedAt"));
        listing.setRejectionReason(nullableText(node, "rejectionReason"));
        listing.setRemovalReason(nullableText(node, "removalReason"));
        listing.setViewCount(node.path("viewCount").asInt(0));
        List<String> photos = new ArrayList<>();
        node.path("photoPaths").forEach(photo -> photos.add(photo.asText()));
        listing.setPhotoPaths(photos);
        return listing;
    }

    private Deal toDeal(JsonNode node) {
        Deal deal = new Deal();
        deal.setId(text(node, "id"));
        deal.setListingId(text(node, "listingId"));
        deal.setListingTitle(text(node, "listingTitle"));
        deal.setBuyerUsername(text(node, "buyerUsername"));
        deal.setSellerUsername(text(node, "sellerUsername"));
        deal.setDealType(enumValue(ListingType.class, text(node, "dealType")));
        deal.setStatus(text(node, "status"));
        deal.setOfferPrice(node.path("offerPrice").isNull() ? null : node.path("offerPrice").asDouble());
        deal.setBorrowStart(date(node, "borrowStart"));
        deal.setBorrowEnd(date(node, "borrowEnd"));
        deal.setRated(node.path("rated").asBoolean(false));
        deal.setCreatedAt(dateTime(node, "createdAt"));
        deal.setCompletedAt(dateTime(node, "completedAt"));
        return deal;
    }

    private Notification toNotification(JsonNode node) {
        Notification notification = new Notification();
        notification.setId(text(node, "id"));
        notification.setUsername(text(node, "username"));
        notification.setTitle(text(node, "title"));
        notification.setMessage(text(node, "message"));
        notification.setCategory(text(node, "category"));
        notification.setTimestamp(dateTime(node, "timestamp"));
        notification.setRead(node.path("read").asBoolean(false));
        return notification;
    }

    private Review toReview(JsonNode node) {
        Review review = new Review();
        review.setId(text(node, "id"));
        review.setSellerUsername(text(node, "sellerUsername"));
        review.setBuyerUsername(text(node, "buyerUsername"));
        review.setListingTitle(text(node, "listingTitle"));
        review.setRating(node.path("rating").asInt());
        review.setComment(text(node, "comment"));
        review.setSellerReply(nullableText(node, "sellerReply"));
        review.setHidden(node.path("hidden").asBoolean(false));
        review.setCreatedAt(dateTime(node, "createdAt"));
        return review;
    }

    private void copyUser(User from, User to) {
        to.setUsername(from.getUsername());
        to.setFullName(from.getFullName());
        to.setRole(from.getRole());
        to.setResidenceHall(from.getResidenceHall());
        to.setBio(from.getBio());
        to.setPreferredMeetup(from.getPreferredMeetup());
        to.setActive(from.isActive());
        to.setAverageRating(from.getAverageRating());
        to.setRatingCount(from.getRatingCount());
        to.setCreatedAt(from.getCreatedAt());
    }

    private void copyListing(Listing from, Listing to) {
        to.setId(from.getId());
        to.setTitle(from.getTitle());
        to.setDescription(from.getDescription());
        to.setCategory(from.getCategory());
        to.setType(from.getType());
        to.setStatus(from.getStatus());
        to.setCondition(from.getCondition());
        to.setPrice(from.getPrice());
        to.setLendingDays(from.getLendingDays());
        to.setMeetupLocation(from.getMeetupLocation());
        to.setSellerUsername(from.getSellerUsername());
        to.setSellerFullName(from.getSellerFullName());
        to.setPostedAt(from.getPostedAt());
        to.setReviewedAt(from.getReviewedAt());
        to.setRejectionReason(from.getRejectionReason());
        to.setRemovalReason(from.getRemovalReason());
        to.setViewCount(from.getViewCount());
        to.setPhotoPaths(from.getPhotoPaths());
    }

    private void copyDeal(Deal from, Deal to) {
        to.setId(from.getId());
        to.setListingId(from.getListingId());
        to.setListingTitle(from.getListingTitle());
        to.setBuyerUsername(from.getBuyerUsername());
        to.setSellerUsername(from.getSellerUsername());
        to.setDealType(from.getDealType());
        to.setStatus(from.getStatus());
        to.setOfferPrice(from.getOfferPrice());
        to.setBorrowStart(from.getBorrowStart());
        to.setBorrowEnd(from.getBorrowEnd());
        to.setRated(from.isRated());
        to.setCreatedAt(from.getCreatedAt());
        to.setCompletedAt(from.getCompletedAt());
    }

    private void copyNotification(Notification from, Notification to) {
        to.setId(from.getId());
        to.setUsername(from.getUsername());
        to.setTitle(from.getTitle());
        to.setMessage(from.getMessage());
        to.setCategory(from.getCategory());
        to.setTimestamp(from.getTimestamp());
        to.setRead(from.isRead());
    }

    private void copyReview(Review from, Review to) {
        to.setId(from.getId());
        to.setSellerUsername(from.getSellerUsername());
        to.setBuyerUsername(from.getBuyerUsername());
        to.setListingTitle(from.getListingTitle());
        to.setRating(from.getRating());
        to.setComment(from.getComment());
        to.setSellerReply(from.getSellerReply());
        to.setHidden(from.isHidden());
        to.setCreatedAt(from.getCreatedAt());
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static String nullableText(JsonNode node, String field) {
        String value = text(node, field);
        return value == null || value.isBlank() ? null : value;
    }

    private static LocalDateTime dateTime(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value).atZone(ZoneId.systemDefault()).toLocalDateTime();
        } catch (Exception ignored) {
            try {
                return OffsetDateTime.parse(value).toLocalDateTime();
            } catch (Exception ignoredAgain) {
                return LocalDateTime.parse(value);
            }
        }
    }

    private static LocalDate date(JsonNode node, String field) {
        String value = text(node, field);
        return value == null || value.isBlank() ? null : LocalDate.parse(value.substring(0, 10));
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        if (value == null || value.isBlank()) return null;
        return Enum.valueOf(type, value.toUpperCase(Locale.ROOT).replace(' ', '_'));
    }

    private static JdbcMarketplaceDataService.DataAccessException failure(String message) {
        return new JdbcMarketplaceDataService.DataAccessException(message);
    }

    private static JdbcMarketplaceDataService.DataAccessException failure(String message, Throwable cause) {
        return new JdbcMarketplaceDataService.DataAccessException(message, cause);
    }
}
