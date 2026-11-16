package com.hkust.marketplace.utils;

import com.hkust.marketplace.models.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Compatibility facade used by the existing controllers.
 *
 * <p>The public application uses the deployed HTTP API. A local
 * {@code DATABASE_URL} remains available for development when
 * {@code MARKETPLACE_MODE=jdbc}; {@code MARKETPLACE_MODE=demo} selects the
 * original in-memory demo.</p>
 */
public class DummyDataService implements MarketplaceDataService {

    private static final DummyDataService INSTANCE = new DummyDataService();

    private final Map<String, User> users = new LinkedHashMap<>();
    private final Map<String, Listing> listings = new LinkedHashMap<>();
    private final List<Deal> deals = new ArrayList<>();
    private final List<Notification> notifications = new ArrayList<>();
    private final List<Review> reviews = new ArrayList<>();
    private final MarketplaceDataService backend;

    private final AtomicInteger listingIdCounter = new AtomicInteger(100);
    private final AtomicInteger dealIdCounter = new AtomicInteger(500);
    private final AtomicInteger notifIdCounter = new AtomicInteger(1);
    private final AtomicInteger reviewIdCounter = new AtomicInteger(1);

    // Predefined HKUST locations
    public static final List<String> HKUST_LOCATIONS = List.of(
            "Academic Building (LG1)",
            "Library",
            "Student Centre",
            "Hall 1 Lobby",
            "Hall 2 Lobby",
            "Hall 3 Lobby",
            "Hall 4 Lobby",
            "Hall 5 Lobby",
            "Hall 6 Lobby",
            "Hall 7 Lobby",
            "Hall 8 Lobby",
            "Hall 9 Lobby",
            "UG Hall 1",
            "UG Hall 2",
            "North Gate",
            "South Gate",
            "Shaw Auditorium",
            "Coffee Shop (LG5)",
            "Sports Hall"
    );

    public static final List<String> CATEGORIES = List.of(
            "Textbooks", "Electronics", "Furniture", "Clothing",
            "Stationery", "Sports Equipment", "Kitchenware", "Others"
    );

    public static final List<String> CONDITIONS = List.of(
            "New", "Like New", "Used", "For Parts"
    );

    private DummyDataService() {
        MarketplaceDataService connectedBackend = null;
        String apiUrl = NeonConfig.apiUrl();
        if (apiUrl != null) {
            try {
                HttpMarketplaceDataService httpBackend = new HttpMarketplaceDataService(apiUrl);
                boolean apiAvailable = httpBackend.isAvailable();
                if (apiAvailable || NeonConfig.databaseUrl() == null) {
                    connectedBackend = httpBackend;
                    System.out.println("[Marketplace] Authenticated marketplace API enabled.");
                } else {
                    System.err.println("[Marketplace] Marketplace API is not reachable; "
                            + "using the configured local JDBC database.");
                }
            } catch (RuntimeException e) {
                System.err.println("[Marketplace] Marketplace API configuration was found but could not be used; "
                        + "falling back to the next available mode: " + e.getMessage());
            }
        }
        if (connectedBackend == null && NeonConfig.isConfigured()) {
            try {
                connectedBackend = new JdbcMarketplaceDataService(NeonConfig.databaseUrl());
                System.out.println("[Marketplace] Neon persistence enabled.");
            } catch (RuntimeException e) {
                System.err.println("[Marketplace] Neon configuration was found but could not be used; "
                        + "falling back to demo data: " + e.getMessage());
            }
        }
        backend = connectedBackend;
        seedData();
        if (backend instanceof JdbcMarketplaceDataService jdbc) {
            jdbc.bootstrapIfEmpty(users.values(), listings.values(), deals, notifications, reviews);
        }
    }

    public static DummyDataService getInstance() {
        return INSTANCE;
    }

    public boolean isNeonEnabled() {
        return backend != null;
    }

    public boolean isApiEnabled() {
        return backend instanceof HttpMarketplaceDataService;
    }

    private void seedData() {
        // Admin
        User admin = new User("admin", "System Admin", "admin123", UserRole.ADMIN);
        admin.setFullName("HKUST Marketplace Admin");
        users.put("admin", admin);

        // Sample buyers
        User buyer1 = new User("alice", "Alice Chan", "password1", UserRole.BUYER);
        buyer1.setResidenceHall("Hall 3");
        users.put("alice", buyer1);

        User buyer2 = new User("bob", "Bob Wong", "password1", UserRole.BUYER);
        buyer2.setResidenceHall("Off-campus");
        users.put("bob", buyer2);

        // Sample sellers
        User seller1 = new User("charlie", "Charlie Lee", "password1", UserRole.SELLER);
        seller1.setBio("Final year CSE student selling textbooks and gadgets.");
        seller1.setPreferredMeetup("Library");
        seller1.setAverageRating(4.7);
        seller1.setRatingCount(12);
        users.put("charlie", seller1);

        User seller2 = new User("diana", "Diana Ng", "password1", UserRole.SELLER);
        seller2.setBio("Selling furniture and kitchen items before graduation.");
        seller2.setPreferredMeetup("Hall 5 Lobby");
        seller2.setAverageRating(4.3);
        seller2.setRatingCount(8);
        users.put("diana", seller2);

        // Sample listings
        Listing l1 = createListing("Calculus Textbook (Stewart)", "Barely used, 8th edition. Perfect for MATH 1013.",
                "Textbooks", ListingType.SELL, "Like New", 180.0, null, "Library", "charlie", "Charlie Lee");
        l1.setStatus(ListingStatus.LIVE);
        listings.put(l1.getId(), l1);

        Listing l2 = createListing("MacBook Pro Charger 61W", "Original Apple charger, works perfectly.",
                "Electronics", ListingType.SELL, "Used", 250.0, null, "Academic Building (LG1)", "charlie", "Charlie Lee");
        l2.setStatus(ListingStatus.LIVE);
        listings.put(l2.getId(), l2);

        Listing l3 = createListing("IKEA Desk Lamp", "Good condition, LED bulb included.",
                "Furniture", ListingType.GIVEAWAY, "Used", null, null, "Hall 5 Lobby", "diana", "Diana Ng");
        l3.setStatus(ListingStatus.LIVE);
        listings.put(l3.getId(), l3);

        Listing l4 = createListing("Graphing Calculator TI-84", "Can lend for one semester.",
                "Electronics", ListingType.LEND, "Like New", null, 90, "Student Centre", "diana", "Diana Ng");
        l4.setStatus(ListingStatus.LIVE);
        listings.put(l4.getId(), l4);

        Listing l5 = createListing("Mini Fridge", "Works well, cleaning required.",
                "Furniture", ListingType.SELL, "Used", 400.0, null, "Hall 2 Lobby", "diana", "Diana Ng");
        l5.setStatus(ListingStatus.PENDING_REVIEW);
        listings.put(l5.getId(), l5);

        // Sample deals
        Deal d1 = new Deal();
        d1.setId("D" + dealIdCounter.getAndIncrement());
        d1.setListingId(l1.getId());
        d1.setListingTitle(l1.getTitle());
        d1.setBuyerUsername("alice");
        d1.setSellerUsername("charlie");
        d1.setDealType(ListingType.SELL);
        d1.setStatus("Accepted");
        d1.setOfferPrice(160.0);
        deals.add(d1);

        // Sample notifications
        notifications.add(new Notification("alice", "Offer Accepted",
                "Charlie accepted your offer of HK$160 for Calculus Textbook.", "Offer"));
        notifications.add(new Notification("charlie", "New Offer Received",
                "Alice offered HK$160 for your Calculus Textbook.", "Offer"));
        notifications.add(new Notification("diana", "Listing Pending",
                "Your Mini Fridge listing is awaiting admin review.", "System"));
        notifications.add(new Notification("admin", "New Listing Submitted",
                "Diana submitted 'Mini Fridge' for review.", "System"));

        // Sample review
        Review r1 = new Review();
        r1.setId("R" + reviewIdCounter.getAndIncrement());
        r1.setSellerUsername("charlie");
        r1.setBuyerUsername("bob");
        r1.setListingTitle("Old Chemistry Book");
        r1.setRating(5);
        r1.setComment("Very smooth transaction, book in excellent condition!");
        reviews.add(r1);
    }

    private Listing createListing(String title, String desc, String cat, ListingType type,
                                  String condition, Double price, Integer days,
                                  String meetup, String sellerUser, String sellerName) {
        Listing l = new Listing();
        l.setId("L" + listingIdCounter.getAndIncrement());
        l.setTitle(title);
        l.setDescription(desc);
        l.setCategory(cat);
        l.setType(type);
        l.setCondition(condition);
        l.setPrice(price);
        l.setLendingDays(days);
        l.setMeetupLocation(meetup);
        l.setSellerUsername(sellerUser);
        l.setSellerFullName(sellerName);
        l.setPostedAt(LocalDateTime.now().minusDays(new Random().nextInt(10)));
        return l;
    }

    // ========== User methods ==========
    @Override
    public boolean usernameExists(String username) {
        if (backend != null) return backend.usernameExists(username);
        return users.containsKey(username.toLowerCase());
    }

    @Override
    public User findUser(String username) {
        if (backend != null) return backend.findUser(username);
        return users.get(username.toLowerCase());
    }

    @Override
    public AuthSession login(String username, String password) {
        if (backend != null) return backend.login(username, password);
        User user = findUser(username);
        return user != null && user.getPasswordHash() != null
                && user.getPasswordHash().equals(password)
                ? new AuthSession(user, null, null)
                : null;
    }

    @Override
    public void logout() {
        if (backend != null) {
            backend.logout();
        }
    }

    @Override
    public void registerUser(User user) {
        if (backend != null) {
            backend.registerUser(user);
            return;
        }
        users.put(user.getUsername().toLowerCase(), user);
    }

    @Override
    public Collection<User> getAllUsers() {
        if (backend != null) return backend.getAllUsers();
        return users.values();
    }

    @Override
    public List<User> getUsersByRole(UserRole role) {
        if (backend != null) return backend.getUsersByRole(role);
        return users.values().stream()
                .filter(u -> u.getRole() == role)
                .collect(Collectors.toList());
    }

    // ========== Listing methods ==========
    @Override
    public Collection<Listing> getAllListings() {
        if (backend != null) return backend.getAllListings();
        return listings.values();
    }

    @Override
    public List<Listing> getLiveListings() {
        if (backend != null) return backend.getLiveListings();
        return listings.values().stream()
                .filter(l -> l.getStatus() == ListingStatus.LIVE)
                .collect(Collectors.toList());
    }

    @Override
    public List<Listing> getPendingListings() {
        if (backend != null) return backend.getPendingListings();
        return listings.values().stream()
                .filter(l -> l.getStatus() == ListingStatus.PENDING_REVIEW)
                .collect(Collectors.toList());
    }

    @Override
    public List<Listing> getListingsBySeller(String username) {
        if (backend != null) return backend.getListingsBySeller(username);
        return listings.values().stream()
                .filter(l -> l.getSellerUsername().equalsIgnoreCase(username))
                .collect(Collectors.toList());
    }

    @Override
    public Listing findListing(String id) {
        if (backend != null) return backend.findListing(id);
        return listings.get(id);
    }

    @Override
    public void addListing(Listing listing) {
        if (backend != null) {
            backend.addListing(listing);
            return;
        }
        if (listing.getId() == null) {
            listing.setId("L" + listingIdCounter.getAndIncrement());
        }
        listings.put(listing.getId(), listing);
    }

    @Override
    public void updateListing(Listing listing) {
        if (backend != null) {
            backend.updateListing(listing);
            return;
        }
        listings.put(listing.getId(), listing);
    }

    @Override
    public void removeListing(String id) {
        if (backend != null) {
            backend.removeListing(id);
            return;
        }
        listings.remove(id);
    }

    // ========== Deal methods ==========
    @Override
    public List<Deal> getDealsForBuyer(String username) {
        if (backend != null) return backend.getDealsForBuyer(username);
        return deals.stream()
                .filter(d -> d.getBuyerUsername().equalsIgnoreCase(username))
                .collect(Collectors.toList());
    }

    @Override
    public List<Deal> getAllDeals() {
        if (backend != null) return backend.getAllDeals();
        return new ArrayList<>(deals);
    }

    @Override
    public void addDeal(Deal deal) {
        if (backend != null) {
            backend.addDeal(deal);
            return;
        }
        if (deal.getId() == null) {
            deal.setId("D" + dealIdCounter.getAndIncrement());
        }
        deals.add(deal);
    }

    // ========== Notification methods ==========
    @Override
    public List<Notification> getNotificationsFor(String username) {
        if (backend != null) return backend.getNotificationsFor(username);
        return notifications.stream()
                .filter(n -> n.getUsername().equalsIgnoreCase(username))
                .sorted(Comparator.comparing(Notification::getTimestamp).reversed())
                .collect(Collectors.toList());
    }

    @Override
    public void addNotification(Notification n) {
        if (backend != null) {
            backend.addNotification(n);
            return;
        }
        if (n.getId() == null) {
            n.setId("N" + notifIdCounter.getAndIncrement());
        }
        notifications.add(n);
    }

    // ========== Review methods ==========
    @Override
    public List<Review> getReviewsForSeller(String username) {
        if (backend != null) return backend.getReviewsForSeller(username);
        return reviews.stream()
                .filter(r -> r.getSellerUsername().equalsIgnoreCase(username) && !r.isHidden())
                .collect(Collectors.toList());
    }

    @Override
    public List<Review> getAllReviews() {
        if (backend != null) return backend.getAllReviews();
        return new ArrayList<>(reviews);
    }

    @Override
    public void addReview(Review r) {
        if (backend != null) {
            backend.addReview(r);
            return;
        }
        if (r.getId() == null) {
            r.setId("R" + reviewIdCounter.getAndIncrement());
        }
        reviews.add(r);
    }

    // Password strength helper (demo)
    public static String checkPasswordStrength(String password) {
        if (password == null || password.isEmpty()) return "Password cannot be empty.";
        if (password.length() < 8) return "Password must be at least 8 characters.";
        boolean hasUpper = password.chars().anyMatch(Character::isUpperCase);
        boolean hasLower = password.chars().anyMatch(Character::isLowerCase);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (!hasUpper || !hasLower || !hasDigit) {
            return "Password should contain uppercase, lowercase and a digit.";
        }
        return "OK";
    }
}
