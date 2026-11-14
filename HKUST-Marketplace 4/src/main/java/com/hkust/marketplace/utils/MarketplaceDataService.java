package com.hkust.marketplace.utils;

import com.hkust.marketplace.models.Deal;
import com.hkust.marketplace.models.Listing;
import com.hkust.marketplace.models.Notification;
import com.hkust.marketplace.models.Review;
import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;

import java.util.Collection;
import java.util.List;

/**
 * Persistence boundary used by the JavaFX controllers.
 *
 * <p>The demo can implement this contract in memory, while a local development
 * run can select the PostgreSQL implementation through {@code DATABASE_URL}.
 * Keeping this contract small makes it possible to move the database calls to
 * an HTTP API later without changing the screens again.</p>
 */
public interface MarketplaceDataService {

    boolean usernameExists(String username);

    User findUser(String username);

    boolean authenticate(String username, String password);

    void registerUser(User user);

    Collection<User> getAllUsers();

    List<User> getUsersByRole(UserRole role);

    Collection<Listing> getAllListings();

    List<Listing> getLiveListings();

    List<Listing> getPendingListings();

    List<Listing> getListingsBySeller(String username);

    Listing findListing(String id);

    void addListing(Listing listing);

    void updateListing(Listing listing);

    void removeListing(String id);

    List<Deal> getDealsForBuyer(String username);

    List<Deal> getAllDeals();

    void addDeal(Deal deal);

    List<Notification> getNotificationsFor(String username);

    void addNotification(Notification notification);

    List<Review> getReviewsForSeller(String username);

    List<Review> getAllReviews();

    void addReview(Review review);
}
