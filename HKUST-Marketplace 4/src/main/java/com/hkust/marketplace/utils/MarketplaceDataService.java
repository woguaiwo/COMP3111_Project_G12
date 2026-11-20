package com.hkust.marketplace.utils;

import com.hkust.marketplace.models.Deal;
import com.hkust.marketplace.models.Listing;
import com.hkust.marketplace.models.Notification;
import com.hkust.marketplace.models.Review;
import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.models.Report;

import java.util.Collection;
import java.util.List;

/**
 * Persistence boundary used by the JavaFX controllers.
 *
 * <p>The demo can implement this contract in memory, while the public client
 * uses the authenticated HTTP implementation. A local development run can
 * still select the PostgreSQL adapter through {@code DATABASE_URL}.</p>
 */
public interface MarketplaceDataService {

    boolean usernameExists(String username);

    User findUser(String username);

    /**
     * Authenticates a user and returns the application session created by the
     * backend.  The token is intentionally kept out of the User model.
     */
    AuthSession login(String username, String password);

    /**
     * Invalidates the current backend session when the implementation has one.
     */
    void logout();

    /**
     * Compatibility helper for older screens and the JDBC adapter.
     */
    default boolean authenticate(String username, String password) {
        return login(username, password) != null;
    }

    void registerUser(User user);

    Collection<User> getAllUsers();

    List<User> getUsersByRole(UserRole role);

    /**
     * Updates a user's editable profile information.
     */
    void updateUser(User user);

    /**
     * Activates or deactivates a user account.
     */
    void setUserActive(String username, boolean active);

    /**
     * Permanently deletes a user account.
     */
    void deleteUser(String username);

    void updateAdminProfile(String username, String fullName);

    void changeAdminPassword(
            String username,
            String currentPassword,
            String newPassword
    );

    Collection<Listing> getAllListings();

    List<Listing> getLiveListings();

    List<Listing> getPendingListings();

    List<Listing> getListingsBySeller(String username);

    Listing findListing(String id);

    void addListing(Listing listing);

    void updateListing(Listing listing);

    void removeListing(String id);

    void adminRemoveListing(String id, String reason);

    List<Deal> getDealsForBuyer(String username);

    List<Deal> getAllDeals();

    void addDeal(Deal deal);

    List<Notification> getNotificationsFor(String username);

    void addNotification(Notification notification);

    List<Review> getReviewsForSeller(String username);

    List<Review> getAllReviews();

    void addReview(Review review);

    void setReviewHidden(String reviewId, boolean hidden);

    void createReport(Report report);

    List<Report> getAllReports();

    void updateReport(Report report);
}
