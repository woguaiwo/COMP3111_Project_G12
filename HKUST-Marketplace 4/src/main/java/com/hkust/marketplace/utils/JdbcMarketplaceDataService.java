package com.hkust.marketplace.utils;

import com.hkust.marketplace.models.Deal;
import com.hkust.marketplace.models.Listing;
import com.hkust.marketplace.models.ListingStatus;
import com.hkust.marketplace.models.ListingType;
import com.hkust.marketplace.models.Notification;
import com.hkust.marketplace.models.Report;
import com.hkust.marketplace.models.Review;
import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * PostgreSQL implementation used when a local Neon DATABASE_URL is present.
 *
 * <p>This is deliberately a small JDBC adapter for the course skeleton. It
 * keeps the current JavaFX screens usable while the longer-term deployment
 * moves these operations behind an authenticated HTTP API. The connection URL
 * is read from the local environment only and is never embedded in the app.</p>
 */
public class JdbcMarketplaceDataService implements MarketplaceDataService {

    private static final String LISTING_SELECT = """
            SELECT l.id,
                   l.title,
                   l.description,
                   l.category,
                   l.type::text AS type_name,
                   l.status::text AS status_name,
                   l.item_condition,
                   l.price,
                   l.lending_days,
                   l.meetup_location,
                   l.reviewed_at,
                   l.rejection_reason,
                   l.view_count,
                   l.created_at,
                   u.username AS seller_username,
                   u.full_name AS seller_full_name
              FROM listings l
              JOIN app_users u ON u.id = l.seller_id
            """;

    private static final String DEAL_SELECT = """
            SELECT d.id,
                   d.listing_id,
                   l.title AS listing_title,
                   b.username AS buyer_username,
                   s.username AS seller_username,
                   d.deal_type::text AS deal_type_name,
                   d.status::text AS status_name,
                   d.offer_price,
                   d.borrow_start,
                   d.borrow_end,
                   d.rated,
                   d.created_at,
                   d.completed_at
              FROM deals d
              JOIN listings l ON l.id = d.listing_id
              JOIN app_users b ON b.id = d.buyer_id
              JOIN app_users s ON s.id = d.seller_id
            """;

    private static final String NOTIFICATION_SELECT = """
            SELECT n.id,
                   u.username,
                   n.title,
                   n.message,
                   n.category,
                   n.is_read,
                   n.created_at
              FROM notifications n
              JOIN app_users u ON u.id = n.recipient_id
            """;

    private static final String REVIEW_SELECT = """
            SELECT r.id,
                   s.username AS seller_username,
                   b.username AS buyer_username,
                   l.title AS listing_title,
                   r.rating,
                   r.comment,
                   r.seller_reply,
                   r.is_hidden,
                   r.created_at
              FROM reviews r
              JOIN listings l ON l.id = r.listing_id
              JOIN app_users s ON s.id = r.seller_id
              JOIN app_users b ON b.id = r.buyer_id
            """;

    private static final String REPORT_SELECT = """
        SELECT r.id,
               l.id AS listing_id,
               l.title AS listing_title,
               reporter.username AS reporter_username,
               r.reason,
               r.details,
               r.status,
               r.created_at,
               r.reviewed_at,
               reviewer.username AS reviewer_username,
               r.resolution_note
          FROM reports r
          JOIN listings l ON l.id = r.listing_id
          JOIN app_users reporter ON reporter.id = r.reporter_id
          LEFT JOIN app_users reviewer ON reviewer.id = r.reviewer_id
        """;

    private final String jdbcUrl;
    private final Properties connectionProperties;

    public JdbcMarketplaceDataService(String databaseUrl) {
        ConnectionSpec connectionSpec = toJdbcConnection(databaseUrl);
        this.jdbcUrl = connectionSpec.url();
        this.connectionProperties = connectionSpec.properties();
        try (Connection ignored = open()) {
            // Opening a connection here makes a bad local configuration fail
            // immediately, allowing the facade to keep the offline demo mode.
        } catch (SQLException e) {
            throw failure("connect to Neon", e);
        }
    }

    /**
     * Copies the existing demo records to Neon the first time the database is
     * used. Existing rows are left untouched so restarting the app cannot
     * duplicate the seed data.
     */
    public void bootstrapIfEmpty(Collection<User> users,
                                 Collection<Listing> listings,
                                 Collection<Deal> deals,
                                 Collection<Notification> notifications,
                                 Collection<Review> reviews) {
        if (!isEmpty()) {
            return;
        }

        for (User user : users) {
            registerUser(user);
        }

        Map<String, String> listingIds = new HashMap<>();
        for (Listing listing : listings) {
            String oldId = listing.getId();
            addListing(listing);
            listingIds.put(oldId, listing.getId());
        }

        for (Deal deal : deals) {
            if (listingIds.containsKey(deal.getListingId())) {
                deal.setListingId(listingIds.get(deal.getListingId()));
            }
            addDeal(deal);
        }

        for (Notification notification : notifications) {
            addNotification(notification);
        }

        for (Review review : reviews) {
            try {
                addReview(review);
            } catch (DataAccessException ignored) {
                // The original demo review references a listing that is not in
                // the seed listing set. Keep the valid marketplace rows and
                // let the UI continue without that orphaned demo review.
            }
        }
    }

    public boolean isEmpty() {
        String sql = "SELECT COUNT(*) FROM app_users";
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            return result.next() && result.getLong(1) == 0;
        } catch (SQLException e) {
            throw failure("check Neon seed state", e);
        }
    }

    @Override
    public boolean usernameExists(String username) {
        String sql = "SELECT 1 FROM app_users WHERE lower(username) = lower(?) LIMIT 1";
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException e) {
            throw failure("check username", e);
        }
    }

    @Override
    public User findUser(String username) {
        String sql = """
                SELECT id, username, full_name, role::text AS role_name,
                       residence_hall, bio, preferred_meetup, is_active,
                       average_rating, rating_count, created_at
                  FROM app_users
                 WHERE lower(username) = lower(?)
                """;
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? readUser(result) : null;
            }
        } catch (SQLException e) {
            throw failure("find user", e);
        }
    }

    @Override
    public AuthSession login(String username, String password) {
        String sql = """
                SELECT u.id, u.username, u.full_name, u.role::text AS role_name,
                       u.residence_hall, u.bio, u.preferred_meetup, u.is_active,
                       u.average_rating, u.rating_count, u.created_at,
                       c.password_hash
                  FROM app_credentials c
                  JOIN app_users u ON u.id = c.user_id
                 WHERE lower(u.username) = lower(?)
                   AND u.is_active = true
                """;
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !PasswordHasher.matches(password, result.getString("password_hash"))) {
                    return null;
                }
                return new AuthSession(readUser(result), null, null);
            }
        } catch (SQLException e) {
            throw failure("login user", e);
        }
    }

    @Override
    public void logout() {
        // JDBC development sessions are held by the JavaFX process only.
    }

    @Override
    public void registerUser(User user) {
        if (user == null || user.getUsername() == null || user.getUsername().isBlank()) {
            throw new IllegalArgumentException("A username is required");
        }
        String insertUser = """
                INSERT INTO app_users
                    (username, full_name, role, residence_hall, bio, preferred_meetup,
                     is_active, average_rating, rating_count)
                VALUES (?, ?, ?::app_user_role, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (username) DO UPDATE SET
                    full_name = EXCLUDED.full_name,
                    role = EXCLUDED.role,
                    residence_hall = EXCLUDED.residence_hall,
                    bio = EXCLUDED.bio,
                    preferred_meetup = EXCLUDED.preferred_meetup,
                    updated_at = now()
                RETURNING id
                """;
        String insertCredential = """
                INSERT INTO app_credentials (user_id, password_hash)
                VALUES (?, ?)
                ON CONFLICT (user_id) DO UPDATE SET password_hash = EXCLUDED.password_hash
                """;

        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                long userId;
                try (PreparedStatement statement = connection.prepareStatement(insertUser)) {
                    statement.setString(1, user.getUsername().toLowerCase(Locale.ROOT));
                    statement.setString(2, user.getFullName());
                    statement.setString(3, user.getRole().name());
                    statement.setString(4, user.getResidenceHall());
                    statement.setString(5, user.getBio());
                    statement.setString(6, user.getPreferredMeetup());
                    statement.setBoolean(7, user.isActive());
                    statement.setDouble(8, user.getAverageRating());
                    statement.setInt(9, user.getRatingCount());
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) {
                            throw new DataAccessException("Neon did not return the user id");
                        }
                        userId = result.getLong(1);
                    }
                }

                if (user.getPasswordHash() != null && !user.getPasswordHash().isBlank()) {
                    try (PreparedStatement statement = connection.prepareStatement(insertCredential)) {
                        statement.setLong(1, userId);
                        statement.setString(2, PasswordHasher.hash(user.getPasswordHash()));
                        statement.executeUpdate();
                    }
                    user.setPasswordHash(null);
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw failure("register user", e);
        }
    }

    @Override
    public Collection<User> getAllUsers() {
        String sql = """
                SELECT id, username, full_name, role::text AS role_name,
                       residence_hall, bio, preferred_meetup, is_active,
                       average_rating, rating_count, created_at
                  FROM app_users
                 ORDER BY lower(username)
                """;
        List<User> users = new ArrayList<>();
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                users.add(readUser(result));
            }
            return users;
        } catch (SQLException e) {
            throw failure("load users", e);
        }
    }

    @Override
    public List<User> getUsersByRole(UserRole role) {
        String sql = """
                SELECT id, username, full_name, role::text AS role_name,
                       residence_hall, bio, preferred_meetup, is_active,
                       average_rating, rating_count, created_at
                  FROM app_users
                 WHERE role = ?::app_user_role
                 ORDER BY lower(username)
                """;
        List<User> users = new ArrayList<>();
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, role.name());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    users.add(readUser(result));
                }
            }
            return users;
        } catch (SQLException e) {
            throw failure("load users by role", e);
        }
    }

    @Override
    public void updateUser(User user) {
        String sql = """
            UPDATE app_users
               SET full_name = ?,
                   residence_hall = ?,
                   bio = ?,
                   preferred_meetup = ?
             WHERE lower(username) = lower(?)
            """;
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, user.getFullName());
            statement.setString(2, user.getResidenceHall());
            statement.setString(3, user.getBio());
            statement.setString(4, user.getPreferredMeetup());
            statement.setString(5, user.getUsername());
            if (statement.executeUpdate() == 0) {
                throw new DataAccessException("User not found.");
            }
        } catch (SQLException e) {
            throw failure("update user", e);
        }
    }

    @Override
    public void updateAdminProfile(String username, String fullName) {
        if (fullName == null || fullName.trim().isEmpty()) {
            throw new IllegalArgumentException("Full Name cannot be empty.");
        }
        String sql = """
        UPDATE app_users
           SET full_name = ?
         WHERE lower(username) = lower(?)
           AND role = 'ADMIN'::app_user_role
        """;
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, fullName.trim());
            statement.setString(2, username);
            if (statement.executeUpdate() == 0) {
                throw new DataAccessException("Admin account not found.");
            }
        } catch (SQLException e) {
            throw failure("update admin profile", e);
        }
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
        String selectSql = """
        SELECT c.password_hash
          FROM app_credentials c
          JOIN app_users u ON u.id = c.user_id
         WHERE lower(u.username) = lower(?)
           AND u.role = 'ADMIN'::app_user_role
           AND u.is_active = true
        """;
        String updateSql = """
        UPDATE app_credentials
           SET password_hash = ?
         WHERE user_id = (
             SELECT id
               FROM app_users
              WHERE lower(username) = lower(?)
                AND role = 'ADMIN'::app_user_role
         )
        """;
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                String storedHash;
                try (PreparedStatement statement =
                             connection.prepareStatement(selectSql)) {
                    statement.setString(1, username);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) {
                            throw new IllegalArgumentException(
                                    "Admin account not found."
                            );
                        }
                        storedHash = result.getString("password_hash");
                    }
                }
                if (!PasswordHasher.matches(currentPassword, storedHash)) {
                    throw new IllegalArgumentException(
                            "Current password is incorrect."
                    );
                }
                try (PreparedStatement statement =
                             connection.prepareStatement(updateSql)) {
                    statement.setString(1, PasswordHasher.hash(newPassword));
                    statement.setString(2, username);
                    if (statement.executeUpdate() == 0) {
                        throw new IllegalArgumentException(
                                "Admin account not found."
                        );
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw failure("change admin password", e);
        }
    }

    @Override
    public void setUserActive(String username, boolean active) {
        String sql = """
            UPDATE app_users
               SET is_active = ?
             WHERE lower(username) = lower(?)
               AND role <> 'ADMIN'::app_user_role
            """;
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBoolean(1, active);
            statement.setString(2, username);
            if (statement.executeUpdate() == 0) {
                throw new DataAccessException(
                        "User not found or cannot deactivate an admin account."
                );
            }
        } catch (SQLException e) {
            throw failure("change user active status", e);
        }
    }

    @Override
    public void deleteUser(String username) {
        throw new UnsupportedOperationException(
                "Permanent deletion is not implemented yet."
        );
    }

    @Override
    public Collection<Listing> getAllListings() {
        return queryListings(LISTING_SELECT + " ORDER BY l.created_at DESC", null);
    }

    @Override
    public List<Listing> getLiveListings() {
        return queryListings(LISTING_SELECT + " WHERE l.status = 'LIVE' ORDER BY l.created_at DESC", null);
    }

    @Override
    public List<Listing> getPendingListings() {
        return queryListings(LISTING_SELECT + " WHERE l.status = 'PENDING_REVIEW' ORDER BY l.created_at DESC", null);
    }

    @Override
    public List<Listing> getListingsBySeller(String username) {
        return queryListings(LISTING_SELECT +
                " WHERE lower(u.username) = lower(?) ORDER BY l.created_at DESC", username);
    }

    @Override
    public Listing findListing(String id) {
        Long numericId = numericId(id, "L");
        if (numericId == null) {
            return null;
        }
        List<Listing> listings = queryListings(LISTING_SELECT + " WHERE l.id = ?", numericId);
        return listings.isEmpty() ? null : listings.get(0);
    }

    @Override
    public void addListing(Listing listing) {
        Long sellerId = findUserId(listing.getSellerUsername());
        if (sellerId == null) {
            throw new DataAccessException("Seller does not exist: " + listing.getSellerUsername());
        }
        String sql = """
                INSERT INTO listings
                    (seller_id, title, description, category, type, status, item_condition,
                     price, lending_days, meetup_location)
                VALUES (?, ?, ?, ?, ?::listing_type, ?::listing_status, ?, ?, ?, ?)
                RETURNING id, created_at
                """;
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sellerId);
            statement.setString(2, listing.getTitle());
            statement.setString(3, listing.getDescription());
            statement.setString(4, listing.getCategory());
            statement.setString(5, listing.getType().name());
            statement.setString(6, listing.getStatus().name());
            statement.setString(7, listing.getCondition());
            setNullableDouble(statement, 8, listing.getPrice());
            setNullableInteger(statement, 9, listing.getLendingDays());
            statement.setString(10, listing.getMeetupLocation());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    listing.setId(prefixed("L", result.getLong("id")));
                    listing.setPostedAt(toLocalDateTime(result.getTimestamp("created_at")));
                }
            }
        } catch (SQLException e) {
            throw failure("create listing", e);
        }
    }

    @Override
    public void updateListing(Listing listing) {
        Long listingId = numericId(listing.getId(), "L");
        if (listingId == null) {
            throw new DataAccessException("Listing id is required for update");
        }
        String sql = """
                UPDATE listings
                   SET title = ?, description = ?, category = ?, type = ?::listing_type,
                       status = ?::listing_status, item_condition = ?, price = ?,
                       lending_days = ?, meetup_location = ?, reviewed_at = ?,
                       rejection_reason = ?, updated_at = now()
                 WHERE id = ?
                """;
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, listing.getTitle());
            statement.setString(2, listing.getDescription());
            statement.setString(3, listing.getCategory());
            statement.setString(4, listing.getType().name());
            statement.setString(5, listing.getStatus().name());
            statement.setString(6, listing.getCondition());
            setNullableDouble(statement, 7, listing.getPrice());
            setNullableInteger(statement, 8, listing.getLendingDays());
            statement.setString(9, listing.getMeetupLocation());
            setNullableDateTime(statement, 10, listing.getReviewedAt());
            statement.setString(11, listing.getRejectionReason());
            statement.setLong(12, listingId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw failure("update listing", e);
        }
    }

    @Override
    public void removeListing(String id) {
        Long listingId = numericId(id, "L");
        if (listingId == null) {
            return;
        }
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM listings WHERE id = ?")) {
            statement.setLong(1, listingId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw failure("remove listing", e);
        }
    }

    @Override
    public void adminRemoveListing(String id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Removal reason is required");
        }
        Long listingId = numericId(id, "L");
        if (listingId == null) {
            throw new DataAccessException("Invalid listing id");
        }
        String sql = """
            UPDATE listings
               SET status = 'REMOVED'::listing_status,
                   removal_reason = ?,
                   updated_at = now()
             WHERE id = ?
            """;
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, reason.trim());
            statement.setLong(2, listingId);
            if (statement.executeUpdate() == 0) {
                throw new DataAccessException("Listing not found");
            }
        } catch (SQLException e) {
            throw failure("admin remove listing", e);
        }
    }

    @Override
    public List<Deal> getDealsForBuyer(String username) {
        return queryDeals(DEAL_SELECT +
                " WHERE lower(b.username) = lower(?) ORDER BY d.created_at DESC", username);
    }

    @Override
    public List<Deal> getAllDeals() {
        return queryDeals(DEAL_SELECT + " ORDER BY d.created_at DESC", null);
    }

    @Override
    public void addDeal(Deal deal) {
        Long listingId = numericId(deal.getListingId(), "L");
        Long buyerId = findUserId(deal.getBuyerUsername());
        Long sellerId = findUserId(deal.getSellerUsername());
        if (listingId == null || buyerId == null || sellerId == null) {
            throw new DataAccessException("Deal references an unknown listing or user");
        }
        String sql = """
                INSERT INTO deals
                    (listing_id, buyer_id, seller_id, deal_type, status, offer_price,
                     borrow_start, borrow_end, rated, completed_at)
                VALUES (?, ?, ?, ?::listing_type, ?::deal_status, ?, ?, ?, ?, ?)
                RETURNING id, created_at
                """;
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, listingId);
            statement.setLong(2, buyerId);
            statement.setLong(3, sellerId);
            statement.setString(4, deal.getDealType().name());
            statement.setString(5, enumValue(deal.getStatus()));
            setNullableDouble(statement, 6, deal.getOfferPrice());
            setNullableDate(statement, 7, deal.getBorrowStart());
            setNullableDate(statement, 8, deal.getBorrowEnd());
            statement.setBoolean(9, deal.isRated());
            setNullableDateTime(statement, 10, deal.getCompletedAt());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    deal.setId(prefixed("D", result.getLong("id")));
                    deal.setCreatedAt(toLocalDateTime(result.getTimestamp("created_at")));
                }
            }
        } catch (SQLException e) {
            throw failure("create deal", e);
        }
    }

    @Override
    public List<Notification> getNotificationsFor(String username) {
        return queryNotifications(NOTIFICATION_SELECT +
                " WHERE lower(u.username) = lower(?) ORDER BY n.created_at DESC", username);
    }

    @Override
    public void addNotification(Notification notification) {
        Long recipientId = findUserId(notification.getUsername());
        if (recipientId == null) {
            throw new DataAccessException("Notification recipient does not exist: " + notification.getUsername());
        }
        String sql = """
                INSERT INTO notifications (recipient_id, title, message, category, is_read)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id, created_at
                """;
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, recipientId);
            statement.setString(2, notification.getTitle());
            statement.setString(3, notification.getMessage());
            statement.setString(4, notification.getCategory());
            statement.setBoolean(5, notification.isRead());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    notification.setId(prefixed("N", result.getLong("id")));
                    notification.setTimestamp(toLocalDateTime(result.getTimestamp("created_at")));
                }
            }
        } catch (SQLException e) {
            throw failure("create notification", e);
        }
    }

    @Override
    public List<Review> getReviewsForSeller(String username) {
        return queryReviews(REVIEW_SELECT +
                " WHERE lower(s.username) = lower(?) AND r.is_hidden = false ORDER BY r.created_at DESC", username);
    }

    @Override
    public List<Review> getAllReviews() {
        return queryReviews(REVIEW_SELECT + " ORDER BY r.created_at DESC", null);
    }

    @Override
    public void addReview(Review review) {
        Long sellerId = findUserId(review.getSellerUsername());
        Long buyerId = findUserId(review.getBuyerUsername());
        Long listingId = findListingId(review.getListingTitle(), review.getSellerUsername());
        if (sellerId == null || buyerId == null || listingId == null) {
            throw new DataAccessException("Review references an unknown listing or user");
        }
        String sql = """
                INSERT INTO reviews
                    (listing_id, seller_id, buyer_id, rating, comment, seller_reply, is_hidden)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING id, created_at
                """;
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, listingId);
            statement.setLong(2, sellerId);
            statement.setLong(3, buyerId);
            statement.setInt(4, review.getRating());
            statement.setString(5, review.getComment());
            statement.setString(6, review.getSellerReply());
            statement.setBoolean(7, review.isHidden());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    review.setId(prefixed("R", result.getLong("id")));
                    review.setCreatedAt(toLocalDateTime(result.getTimestamp("created_at")));
                }
            }
        } catch (SQLException e) {
            throw failure("create review", e);
        }
    }

    @Override
    public void setReviewHidden(String reviewId, boolean hidden) {
        if (reviewId == null || !reviewId.matches("(?i)^R\\d+$")) {
            throw new IllegalArgumentException("Invalid review ID.");
        }
        long id = Long.parseLong(reviewId.substring(1));
        String sql = "UPDATE reviews SET is_hidden = ? WHERE id = ?";
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBoolean(1, hidden);
            statement.setLong(2, id);
            if (statement.executeUpdate() == 0) {
                throw new DataAccessException("Review not found.");
            }
        } catch (SQLException e) {
            throw failure("update review visibility", e);
        }
    }

    @Override
    public void createReport(Report report) {
        Long reporterId = findUserId(report.getReporterUsername());
        Long listingId = findListingIdById(report.getListingId());
        if (reporterId == null || listingId == null) {
            throw new DataAccessException("Report references an unknown user or listing.");
        }
        String sql = """
            INSERT INTO reports
                (listing_id, reporter_id, reason, details, status)
            VALUES (?, ?, ?, ?, 'OPEN')
            RETURNING id, created_at
            """;
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, listingId);
            statement.setLong(2, reporterId);
            statement.setString(3, report.getReason());
            statement.setString(4, report.getDetails());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    report.setId(prefixed("P", result.getLong("id")));
                    report.setStatus("OPEN");
                    report.setCreatedAt(
                            toLocalDateTime(result.getTimestamp("created_at"))
                    );
                }
            }
        } catch (SQLException e) {
            throw failure("create report", e);
        }
    }

    @Override
    public List<Report> getAllReports() {
        return queryReports(
                REPORT_SELECT + " ORDER BY r.created_at DESC",
                null
        );
    }

    @Override
    public void updateReport(Report report) {
        if (report == null || report.getId() == null) {
            throw new IllegalArgumentException("Invalid report.");
        }
        long id = parsePrefixedId(report.getId(), "P");
        String sql = """
            UPDATE reports
               SET status = ?,
                   resolution_note = ?,
                   reviewed_at = CURRENT_TIMESTAMP
             WHERE id = ?
            RETURNING reviewed_at
            """;
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, report.getStatus());
            statement.setString(2, report.getResolutionNote());
            statement.setLong(3, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new DataAccessException("Report not found.");
                }
                report.setReviewedAt(
                        toLocalDateTime(result.getTimestamp("reviewed_at"))
                );
            }
        } catch (SQLException e) {
            throw failure("update report", e);
        }
    }

    private List<Listing> queryListings(String sql, Object parameter) {
        List<Listing> listings = new ArrayList<>();
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (parameter != null) {
                setParameter(statement, 1, parameter);
            }
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    listings.add(readListing(result));
                }
            }
            return listings;
        } catch (SQLException e) {
            throw failure("load listings", e);
        }
    }

    private List<Deal> queryDeals(String sql, String username) {
        List<Deal> deals = new ArrayList<>();
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (username != null) {
                statement.setString(1, username);
            }
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    deals.add(readDeal(result));
                }
            }
            return deals;
        } catch (SQLException e) {
            throw failure("load deals", e);
        }
    }

    private List<Notification> queryNotifications(String sql, String username) {
        List<Notification> notifications = new ArrayList<>();
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (username != null) {
                statement.setString(1, username);
            }
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    notifications.add(readNotification(result));
                }
            }
            return notifications;
        } catch (SQLException e) {
            throw failure("load notifications", e);
        }
    }

    private List<Review> queryReviews(String sql, String username) {
        List<Review> reviews = new ArrayList<>();
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (username != null) {
                statement.setString(1, username);
            }
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    reviews.add(readReview(result));
                }
            }
            return reviews;
        } catch (SQLException e) {
            throw failure("load reviews", e);
        }
    }

    private List<Report> queryReports(String sql, String reporterUsername) {
        List<Report> reports = new ArrayList<>();
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (reporterUsername != null) {
                statement.setString(1, reporterUsername);
            }
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    reports.add(readReport(result));
                }
            }
            return reports;
        } catch (SQLException e) {
            throw failure("load reports", e);
        }
    }

    private User readUser(ResultSet result) throws SQLException {
        User user = new User();
        user.setUsername(result.getString("username"));
        user.setFullName(result.getString("full_name"));
        user.setRole(UserRole.valueOf(result.getString("role_name")));
        user.setResidenceHall(result.getString("residence_hall"));
        user.setBio(result.getString("bio"));
        user.setPreferredMeetup(result.getString("preferred_meetup"));
        user.setActive(result.getBoolean("is_active"));
        BigDecimal rating = result.getBigDecimal("average_rating");
        user.setAverageRating(rating == null ? 0 : rating.doubleValue());
        user.setRatingCount(result.getInt("rating_count"));
        user.setCreatedAt(toLocalDateTime(result.getTimestamp("created_at")));
        return user;
    }

    private Listing readListing(ResultSet result) throws SQLException {
        Listing listing = new Listing();
        listing.setId(prefixed("L", result.getLong("id")));
        listing.setTitle(result.getString("title"));
        listing.setDescription(result.getString("description"));
        listing.setCategory(result.getString("category"));
        listing.setType(ListingType.valueOf(result.getString("type_name")));
        listing.setStatus(ListingStatus.valueOf(result.getString("status_name")));
        listing.setCondition(result.getString("item_condition"));
        BigDecimal price = result.getBigDecimal("price");
        listing.setPrice(price == null ? null : price.doubleValue());
        int lendingDays = result.getInt("lending_days");
        listing.setLendingDays(result.wasNull() ? null : lendingDays);
        listing.setMeetupLocation(result.getString("meetup_location"));
        listing.setReviewedAt(toLocalDateTime(result.getTimestamp("reviewed_at")));
        listing.setRejectionReason(result.getString("rejection_reason"));
        listing.setViewCount(result.getInt("view_count"));
        listing.setPostedAt(toLocalDateTime(result.getTimestamp("created_at")));
        listing.setSellerUsername(result.getString("seller_username"));
        listing.setSellerFullName(result.getString("seller_full_name"));
        return listing;
    }

    private Deal readDeal(ResultSet result) throws SQLException {
        Deal deal = new Deal();
        deal.setId(prefixed("D", result.getLong("id")));
        deal.setListingId(prefixed("L", result.getLong("listing_id")));
        deal.setListingTitle(result.getString("listing_title"));
        deal.setBuyerUsername(result.getString("buyer_username"));
        deal.setSellerUsername(result.getString("seller_username"));
        deal.setDealType(ListingType.valueOf(result.getString("deal_type_name")));
        deal.setStatus(displayStatus(result.getString("status_name")));
        BigDecimal offerPrice = result.getBigDecimal("offer_price");
        deal.setOfferPrice(offerPrice == null ? null : offerPrice.doubleValue());
        deal.setBorrowStart(result.getObject("borrow_start", LocalDate.class));
        deal.setBorrowEnd(result.getObject("borrow_end", LocalDate.class));
        deal.setRated(result.getBoolean("rated"));
        deal.setCreatedAt(toLocalDateTime(result.getTimestamp("created_at")));
        deal.setCompletedAt(toLocalDateTime(result.getTimestamp("completed_at")));
        return deal;
    }

    private Notification readNotification(ResultSet result) throws SQLException {
        Notification notification = new Notification();
        notification.setId(prefixed("N", result.getLong("id")));
        notification.setUsername(result.getString("username"));
        notification.setTitle(result.getString("title"));
        notification.setMessage(result.getString("message"));
        notification.setCategory(result.getString("category"));
        notification.setRead(result.getBoolean("is_read"));
        notification.setTimestamp(toLocalDateTime(result.getTimestamp("created_at")));
        return notification;
    }

    private Review readReview(ResultSet result) throws SQLException {
        Review review = new Review();
        review.setId(prefixed("R", result.getLong("id")));
        review.setSellerUsername(result.getString("seller_username"));
        review.setBuyerUsername(result.getString("buyer_username"));
        review.setListingTitle(result.getString("listing_title"));
        review.setRating(result.getInt("rating"));
        review.setComment(result.getString("comment"));
        review.setSellerReply(result.getString("seller_reply"));
        review.setHidden(result.getBoolean("is_hidden"));
        review.setCreatedAt(toLocalDateTime(result.getTimestamp("created_at")));
        return review;
    }

    private Report readReport(ResultSet result) throws SQLException {
        Report report = new Report();
        report.setId(prefixed("P", result.getLong("id")));
        report.setListingId(
                prefixed("L", result.getLong("listing_id"))
        );
        report.setListingTitle(result.getString("listing_title"));
        report.setReporterUsername(result.getString("reporter_username"));
        report.setReason(result.getString("reason"));
        report.setDetails(result.getString("details"));
        report.setStatus(result.getString("status"));
        report.setCreatedAt(
                toLocalDateTime(result.getTimestamp("created_at"))
        );
        report.setReviewedAt(
                toLocalDateTime(result.getTimestamp("reviewed_at"))
        );
        report.setReviewerUsername(
                result.getString("reviewer_username")
        );
        report.setResolutionNote(
                result.getString("resolution_note")
        );
        return report;
    }

    private Long findUserId(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        String sql = "SELECT id FROM app_users WHERE lower(username) = lower(?)";
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : null;
            }
        } catch (SQLException e) {
            throw failure("find user id", e);
        }
    }

    private Long findListingId(String title, String sellerUsername) {
        String sql = """
                SELECT l.id
                  FROM listings l
                  JOIN app_users u ON u.id = l.seller_id
                 WHERE l.title = ? AND lower(u.username) = lower(?)
                 ORDER BY l.created_at DESC
                 LIMIT 1
                """;
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, title);
            statement.setString(2, sellerUsername);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : null;
            }
        } catch (SQLException e) {
            throw failure("find listing for review", e);
        }
    }

    private Long findListingIdById(String listingId) {
        if (listingId == null || !listingId.matches("(?i)^L\\d+$")) {
            return null;
        }
        long id = Long.parseLong(listingId.substring(1));
        String sql = "SELECT id FROM listings WHERE id = ?";
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : null;
            }
        } catch (SQLException e) {
            throw failure("find listing by id", e);
        }
    }

    private long parsePrefixedId(String value, String prefix) {
        if (value == null
                || prefix == null
                || !value.matches("(?i)^" + prefix + "\\d+$")) {
            throw new IllegalArgumentException("Invalid ID: " + value);
        }
        return Long.parseLong(value.substring(prefix.length()));
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, connectionProperties);
    }

    private static ConnectionSpec toJdbcConnection(String databaseUrl) {
        if (databaseUrl == null || databaseUrl.isBlank()) {
            throw new IllegalArgumentException("DATABASE_URL is empty");
        }
        if (databaseUrl.startsWith("jdbc:")) {
            return new ConnectionSpec(databaseUrl, new Properties());
        }

        try {
            URI uri = URI.create(databaseUrl);
            if (!"postgresql".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw new IllegalArgumentException("DATABASE_URL must be a PostgreSQL connection URL");
            }

            StringBuilder jdbcUrl = new StringBuilder("jdbc:postgresql://")
                    .append(uri.getHost());
            if (uri.getPort() > 0) {
                jdbcUrl.append(':').append(uri.getPort());
            }
            if (uri.getRawPath() != null) {
                jdbcUrl.append(uri.getRawPath());
            }
            if (uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) {
                jdbcUrl.append('?').append(uri.getRawQuery());
            }

            Properties properties = new Properties();
            String userInfo = uri.getRawUserInfo();
            if (userInfo != null) {
                int separator = userInfo.indexOf(':');
                if (separator >= 0) {
                    properties.setProperty("user", decode(userInfo.substring(0, separator)));
                    properties.setProperty("password", decode(userInfo.substring(separator + 1)));
                } else {
                    properties.setProperty("user", decode(userInfo));
                }
            }
            return new ConnectionSpec(jdbcUrl.toString(), properties);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("DATABASE_URL is not a valid PostgreSQL URL", e);
        }
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private record ConnectionSpec(String url, Properties properties) {
    }

    private static void setNullableDouble(PreparedStatement statement, int index, Double value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.NUMERIC);
        } else {
            statement.setDouble(index, value);
        }
    }

    private static void setNullableInteger(PreparedStatement statement, int index, Integer value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private static void setNullableDate(PreparedStatement statement, int index, LocalDate value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.DATE);
        } else {
            statement.setObject(index, value);
        }
    }

    private static void setNullableDateTime(PreparedStatement statement, int index, LocalDateTime value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
        } else {
            statement.setTimestamp(index, Timestamp.valueOf(value));
        }
    }

    private static void setParameter(PreparedStatement statement, int index, Object value)
            throws SQLException {
        if (value instanceof Long number) {
            statement.setLong(index, number);
        } else {
            statement.setObject(index, value);
        }
    }

    private static LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private static Long numericId(String id, String prefix) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String numeric = id.startsWith(prefix) ? id.substring(prefix.length()) : id;
        try {
            return Long.parseLong(numeric);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String prefixed(String prefix, long id) {
        return prefix + id;
    }

    private static String enumValue(String value) {
        if (value == null || value.isBlank()) {
            return "PENDING";
        }
        return value.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private static String displayStatus(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private static DataAccessException failure(String operation, SQLException cause) {
        return new DataAccessException("Unable to " + operation + ". Check DATABASE_URL and the Neon schema.", cause);
    }

    public static class DataAccessException extends RuntimeException {
        public DataAccessException(String message) {
            super(message);
        }

        public DataAccessException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
