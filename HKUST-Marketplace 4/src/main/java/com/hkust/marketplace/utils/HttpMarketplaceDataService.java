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
 * Client-side adapter for the deployed Neon Function.
 *
 * <p>The JavaFX process only knows the public function URL and the access
 * token returned by login. It never receives {@code DATABASE_URL}, the JWT
 * signing key, or a password hash.</p>
 */
public class HttpMarketplaceDataService implements MarketplaceDataService {

    private final String baseUrl;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HttpMarketplaceDataService(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("Marketplace API URL is required");
        }
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    /**
     * Checks the public Function without requiring an application session.
     * This lets a local developer with a DATABASE_URL use JDBC when a network
     * blocks the Function endpoint, while public clients continue using HTTP.
     */
    public boolean isAvailable() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/health"))
                    .timeout(Duration.ofSeconds(3))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<Void> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public boolean usernameExists(String username) {
        JsonNode result = request("GET", "/auth/check-username?username=" + encode(username), null, false);
        return !result.path("available").asBoolean(true);
    }

    @Override
    public User findUser(String username) {
        return toUser(request("GET", "/api/users/" + encode(username), null, true));
    }

    @Override
    public AuthSession login(String username, String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password);
        JsonNode result;
        try {
            result = request("POST", "/auth/login", body, false);
        } catch (JdbcMarketplaceDataService.DataAccessException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("401:")) {
                return null;
            }
            throw e;
        }
        String token = result.path("token").asText(null);
        if (token == null || token.isBlank()) {
            throw failure("API did not return an access token");
        }
        long expiresAt = result.path("expiresAt").asLong(0);
        return new AuthSession(toUser(result.path("user")), token,
                expiresAt > 0 ? Instant.ofEpochSecond(expiresAt) : null);
    }

    @Override
    public void logout() {
        if (SessionManager.getAccessToken() == null) {
            return;
        }
        request("POST", "/auth/logout", Map.of(), true);
    }

    @Override
    public void registerUser(User user) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", user.getUsername());
        body.put("fullName", user.getFullName());
        body.put("password", user.getPasswordHash());
        body.put("role", user.getRole().name());
        body.put("residenceHall", user.getResidenceHall());
        body.put("bio", user.getBio());
        body.put("preferredMeetup", user.getPreferredMeetup());
        JsonNode result = request("POST", "/auth/register", body, false);
        copyUser(toUser(result.path("user")), user);
        user.setPasswordHash(null);
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

    private JsonNode request(String method, String path, Object body, boolean authenticated) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .header("Accept", "application/json");
            if (body != null) {
                builder.header("Content-Type", "application/json");
            }
            if (authenticated) {
                String token = SessionManager.getAccessToken();
                if (token == null || token.isBlank()) {
                    throw failure("No active API session");
                }
                builder.header("Authorization", "Bearer " + token);
            }
            String requestBody = body == null ? "" : objectMapper.writeValueAsString(body);
            HttpRequest.BodyPublisher publisher = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(requestBody);
            HttpResponse<String> response = httpClient.send(
                    builder.timeout(Duration.ofSeconds(15))
                            .method(method, publisher).build(),
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
                String message = result.path("error").asText("Marketplace API request failed");
                throw failure(response.statusCode() + ": " + message);
            }
            return result;
        } catch (IOException e) {
            throw failure("connect to marketplace API", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure("complete marketplace API request", e);
        }
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
