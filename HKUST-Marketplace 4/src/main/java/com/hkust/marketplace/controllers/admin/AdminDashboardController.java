package com.hkust.marketplace.controllers.admin;

import com.hkust.marketplace.models.*;
import com.hkust.marketplace.utils.DummyDataService;
import com.hkust.marketplace.utils.SceneManager;
import com.hkust.marketplace.utils.SessionManager;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.DateCell;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class AdminDashboardController {

    @FXML private StackPane contentPane;
    @FXML private Label pageTitle;
    @FXML private Label welcomeLabel;
    @FXML private Button btnReview, btnAllListings, btnUsers, btnDeals, btnRatings, btnNotifications, btnProfile;

    private Button currentActiveBtn;

    @FXML
    public void initialize() {
        User user = SessionManager.getCurrentUser();
        if (user != null) welcomeLabel.setText("Admin: " + user.getFullName());
        currentActiveBtn = btnReview;
        showReview();
    }

    private void setActive(Button btn, String title) {
        if (currentActiveBtn != null) currentActiveBtn.getStyleClass().remove("sidebar-btn-active");
        btn.getStyleClass().add("sidebar-btn-active");
        currentActiveBtn = btn;
        pageTitle.setText(title);
    }

    @FXML
    private void showReview() {
        setActive(btnReview, "Listing Review & Approval");
        contentPane.getChildren().clear();

        VBox root = new VBox(14);
        root.setPadding(new Insets(4));

        List<Listing> pending = DummyDataService.getInstance().getPendingListings();
        if (pending.isEmpty()) {
            Label empty = new Label("No listings awaiting review.");
            empty.setStyle("-fx-text-fill: #2e7d32; -fx-font-size: 15px;");
            root.getChildren().add(empty);
        } else {
            for (Listing l : pending) {
                root.getChildren().add(createPendingCard(l));
            }
        }
        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        contentPane.getChildren().add(scroll);
    }

    private VBox createPendingCard(Listing l) {
        VBox card = new VBox(10);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(16));

        Label title = new Label(l.getTitle());
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 16px;");

        Label meta = new Label(String.format("Seller: %s (@%s)  ·  %s  ·  %s  ·  %s",
                l.getSellerFullName(), l.getSellerUsername(),
                l.getCategory(), l.getTypeDisplay(), l.getMeetupLocation()));
        meta.setStyle("-fx-text-fill: #5a5a7a; -fx-font-size: 12px;");

        Label submitted = new Label("Submitted: " +
                l.getPostedAt().format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm")));
        submitted.setStyle("-fx-text-fill: #9aa0b0; -fx-font-size: 11px;");

        HBox actions = new HBox(10);
        Button approve = new Button("Approve");
        approve.getStyleClass().addAll("btn-success", "btn-sm");
        approve.setOnAction(e -> {
            Alert conf = new Alert(Alert.AlertType.CONFIRMATION, "Approve this listing?");
            conf.showAndWait().ifPresent(r -> {
                if (r == ButtonType.OK) {
                    l.setStatus(ListingStatus.LIVE);
                    l.setReviewedAt(LocalDateTime.now());
                    DummyDataService.getInstance().updateListing(l);
                    DummyDataService.getInstance().addNotification(new Notification(
                            l.getSellerUsername(), "Listing Approved",
                            "Your listing '" + l.getTitle() + "' is now live!", "System"));
                    showReview();
                }
            });
        });

        Button reject = new Button("Reject");
        reject.getStyleClass().addAll("btn-danger", "btn-sm");
        reject.setOnAction(e -> {
            TextInputDialog reasonDialog = new TextInputDialog();
            reasonDialog.setTitle("Rejection Reason");
            reasonDialog.setHeaderText("Please provide a reason for rejection");
            reasonDialog.setContentText("Reason:");
            reasonDialog.showAndWait().ifPresent(reason -> {
                if (reason.isBlank()) {
                    new Alert(Alert.AlertType.WARNING, "Reason is required.").showAndWait();
                    return;
                }
                l.setStatus(ListingStatus.REJECTED);
                l.setRejectionReason(reason);
                l.setReviewedAt(LocalDateTime.now());
                DummyDataService.getInstance().updateListing(l);
                DummyDataService.getInstance().addNotification(new Notification(
                        l.getSellerUsername(), "Listing Rejected",
                        "Your listing '" + l.getTitle() + "' was rejected. Reason: " + reason, "System"));
                showReview();
            });
        });

        actions.getChildren().addAll(approve, reject);
        card.getChildren().addAll(title, meta, submitted, actions);
        return card;
    }


    @FXML
    private void showAllListings() {
        setActive(btnAllListings, "Manage All Listings");
        contentPane.getChildren().clear();
        VBox root = new VBox(12);
        root.setPadding(new Insets(4));
        // Keep the existing Add Listing button
        Button addBtn = new Button("+ Add Listing (on behalf of seller)");
        addBtn.getStyleClass().add("btn-primary");
        addBtn.setOnAction(e -> showAddListingForm());
        root.getChildren().add(addBtn);
        // Search and status filter
        HBox filterBar = new HBox(10);
        filterBar.setAlignment(Pos.CENTER_LEFT);
        TextField searchField = new TextField();
        searchField.setPromptText("Search by title, seller or category...");
        HBox.setHgrow(searchField, Priority.ALWAYS);
        ComboBox<String> statusFilter = new ComboBox<>();
        statusFilter.getItems().add("All Statuses");
        for (ListingStatus status : ListingStatus.values()) {
            statusFilter.getItems().add(status.name());
        }
        statusFilter.setValue("All Statuses");
        statusFilter.setPromptText("Filter by status");
        filterBar.getChildren().addAll(searchField, statusFilter);
        root.getChildren().add(filterBar);
        // Keep the original listing data and card appearance
        FilteredList<Listing> filteredListings = new FilteredList<>(
                FXCollections.observableArrayList(
                        DummyDataService.getInstance().getAllListings()
                )
        );
        VBox listingRows = new VBox(12);
        root.getChildren().add(listingRows);
        Runnable applyFilter = () -> {
            String keyword = searchField.getText() == null
                    ? ""
                    : searchField.getText().trim().toLowerCase();
            String selectedStatus = statusFilter.getValue();
            filteredListings.setPredicate(l -> {
                boolean matchesKeyword =
                        keyword.isEmpty()
                                || String.valueOf(l.getTitle()).toLowerCase().contains(keyword)
                                || String.valueOf(l.getSellerFullName()).toLowerCase().contains(keyword)
                                || String.valueOf(l.getSellerUsername()).toLowerCase().contains(keyword)
                                || String.valueOf(l.getCategory()).toLowerCase().contains(keyword);

                boolean matchesStatus =
                        "All Statuses".equals(selectedStatus)
                                || l.getStatus().name().equals(selectedStatus);

                return matchesKeyword && matchesStatus;
            });
            // Rebuild the rows using the original card design
            listingRows.getChildren().clear();
            if (filteredListings.isEmpty()) {
                Label empty = new Label("No listings found.");
                empty.setStyle("-fx-text-fill: #9aa0b0; -fx-font-size: 13px;");
                listingRows.getChildren().add(empty);
                return;
            }
            for (Listing l : filteredListings) {
                HBox row = new HBox(12);
                row.getStyleClass().add("card");
                row.setAlignment(Pos.CENTER_LEFT);
                row.setPadding(new Insets(12));
                VBox info = new VBox(3);
                HBox.setHgrow(info, Priority.ALWAYS);
                Label t = new Label(l.getTitle());
                t.setStyle("-fx-font-weight: bold;");

                Label m = new Label(
                        l.getSellerFullName() + " · "
                                + l.getTypeDisplay() + " · "
                                + l.getStatus()
                );
                m.setStyle("-fx-text-fill: #5a5a7a; -fx-font-size: 12px;");
                info.getChildren().addAll(t, m);

                // Display the reason when a listing has been removed by Admin.
                if (l.getStatus() == ListingStatus.REMOVED
                        && l.getRemovalReason() != null
                        && !l.getRemovalReason().isBlank()) {
                    Label removalReason = new Label(
                            "Removal reason: " + l.getRemovalReason()
                    );
                    removalReason.setStyle(
                            "-fx-text-fill: #c0392b; -fx-font-size: 12px;"
                    );
                    removalReason.setWrapText(true);
                    info.getChildren().add(removalReason);
                }

                Button edit = new Button("Edit");
                edit.getStyleClass().addAll("btn-outline", "btn-sm");
                edit.setOnAction(e -> showEditListingForm(l));
                Button remove = new Button("Remove");
                remove.getStyleClass().addAll("btn-danger", "btn-sm");

                // Only LIVE listings can be taken down by Admin.
                remove.setDisable(l.getStatus() != ListingStatus.LIVE);

                remove.setOnAction(e -> {
                    TextInputDialog reasonDialog = new TextInputDialog();

                    reasonDialog.setTitle("Take Down Listing");
                    reasonDialog.setHeaderText(
                            "Remove Listing: " + l.getTitle()
                    );
                    reasonDialog.setContentText(
                            "Please provide a reason for taking down this listing:"
                    );

                    reasonDialog.showAndWait().ifPresent(reason -> {
                        if (reason == null || reason.isBlank()) {
                            Alert warning = new Alert(
                                    Alert.AlertType.WARNING,
                                    "Removal reason cannot be empty."
                            );
                            warning.setHeaderText("Reason Required");
                            warning.showAndWait();
                            return;
                        }

                        try {
                            DummyDataService.getInstance().adminRemoveListing(
                                    l.getId(),
                                    reason.trim()
                            );

                            new Alert(
                                    Alert.AlertType.INFORMATION,
                                    "Listing has been taken down successfully."
                            ).showAndWait();

                            showAllListings();

                        } catch (Exception ex) {
                            new Alert(
                                    Alert.AlertType.ERROR,
                                    "Failed to take down listing:\n" + ex.getMessage()
                            ).showAndWait();
                        }
                    });
                });
                row.getChildren().addAll(info, edit, remove);
                listingRows.getChildren().add(row);
            }
        };
        searchField.textProperty().addListener(
                (obs, oldValue, newValue) -> applyFilter.run()
        );
        statusFilter.valueProperty().addListener(
                (obs, oldValue, newValue) -> applyFilter.run()
        );
        applyFilter.run();
        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        contentPane.getChildren().add(scroll);
    }

    private void showAddListingForm() {
        // Do not call setActive(null, ...), because setActive requires a real button.
        pageTitle.setText("Add Listing");
        contentPane.getChildren().clear();

        VBox form = new VBox(12);
        form.setMaxWidth(520);
        form.getStyleClass().add("card");
        form.setPadding(new Insets(20));

        Label title = new Label("Post a new item on behalf of a seller");
        title.getStyleClass().add("section-title");

        // Only active Seller accounts can be selected.
        List<User> sellers = DummyDataService.getInstance()
                .getUsersByRole(UserRole.SELLER)
                .stream()
                .filter(User::isActive)
                .toList();

        ComboBox<User> sellerCombo = new ComboBox<>(
                FXCollections.observableArrayList(sellers));
        sellerCombo.setPromptText("Select Seller *");
        sellerCombo.setMaxWidth(Double.MAX_VALUE);

        Label sellerNote = new Label();
        sellerNote.setStyle("-fx-text-fill: #9aa0b0; -fx-font-size: 12px;");

        if (sellers.isEmpty()) {
            sellerNote.setText("No active Seller accounts available.");
        } else {
            sellerNote.setText("The listing will be created under the selected Seller's account.");
        }

        ComboBox<String> typeCombo = new ComboBox<>(
                FXCollections.observableArrayList("Sell", "Lend", "Give Away"));
        typeCombo.setPromptText("Listing type *");
        typeCombo.setMaxWidth(Double.MAX_VALUE);

        TextField nameField = new TextField();
        nameField.setPromptText("Item name *");

        ComboBox<String> catCombo = new ComboBox<>(
                FXCollections.observableArrayList(DummyDataService.CATEGORIES));
        catCombo.setPromptText("Category *");
        catCombo.setMaxWidth(Double.MAX_VALUE);

        ComboBox<String> condCombo = new ComboBox<>(
                FXCollections.observableArrayList(DummyDataService.CONDITIONS));
        condCombo.setPromptText("Condition *");
        condCombo.setMaxWidth(Double.MAX_VALUE);

        TextArea descArea = new TextArea();
        descArea.setPromptText("Description");
        descArea.setPrefRowCount(3);

        TextField priceField = new TextField();
        priceField.setPromptText("Price (HK$) – required for Sell");

        TextField daysField = new TextField();
        daysField.setPromptText("Lending duration (days) – required for Lend");

        ComboBox<String> meetupCombo = new ComboBox<>(
                FXCollections.observableArrayList(DummyDataService.HKUST_LOCATIONS));
        meetupCombo.setPromptText("Meet-up location *");
        meetupCombo.setMaxWidth(Double.MAX_VALUE);

        Label photoNote = new Label(
                "📷 Photos: up to 6 (UI placeholder – file chooser not wired in demo)");
        photoNote.setStyle("-fx-text-fill: #9aa0b0; -fx-font-size: 12px;");

        Button submitBtn = new Button("Submit for Review");
        submitBtn.getStyleClass().add("btn-gold");
        submitBtn.setDisable(sellers.isEmpty());

        submitBtn.setOnAction(e -> {
            User selectedSeller = sellerCombo.getValue();

            if (selectedSeller == null ||
                    typeCombo.getValue() == null ||
                    nameField.getText().isBlank() ||
                    catCombo.getValue() == null ||
                    condCombo.getValue() == null ||
                    meetupCombo.getValue() == null) {

                new Alert(Alert.AlertType.WARNING,
                        "Please fill all required fields and select a Seller.")
                        .showAndWait();
                return;
            }

            Listing listing = new Listing();

            listing.setTitle(nameField.getText().trim());
            listing.setDescription(descArea.getText());
            listing.setCategory(catCombo.getValue());
            listing.setCondition(condCombo.getValue());
            listing.setMeetupLocation(meetupCombo.getValue());

            // Assign the selected Seller, not the logged-in Admin.
            listing.setSellerUsername(selectedSeller.getUsername());
            listing.setSellerFullName(selectedSeller.getFullName());

            listing.setStatus(ListingStatus.PENDING_REVIEW);

            // Clear both fields first to avoid carrying irrelevant values.
            listing.setPrice(null);
            listing.setLendingDays(null);

            switch (typeCombo.getValue()) {
                case "Sell" -> {
                    double price;
                    try {
                        price = Double.parseDouble(priceField.getText().trim());
                    } catch (Exception ex) {
                        new Alert(Alert.AlertType.WARNING,
                                "Please enter a valid price for Sell listings.")
                                .showAndWait();
                        return;
                    }

                    if (!Double.isFinite(price) || price <= 0) {
                        new Alert(Alert.AlertType.WARNING,
                                "Price must be greater than zero.")
                                .showAndWait();
                        return;
                    }

                    listing.setType(ListingType.SELL);
                    listing.setPrice(price);
                }

                case "Lend" -> {
                    int days;
                    try {
                        days = Integer.parseInt(daysField.getText().trim());
                    } catch (Exception ex) {
                        new Alert(Alert.AlertType.WARNING,
                                "Please enter valid lending days for Lend listings.")
                                .showAndWait();
                        return;
                    }

                    if (days <= 0) {
                        new Alert(Alert.AlertType.WARNING,
                                "Lending days must be greater than zero.")
                                .showAndWait();
                        return;
                    }

                    listing.setType(ListingType.LEND);
                    listing.setLendingDays(days);
                }

                case "Give Away" -> listing.setType(ListingType.GIVEAWAY);

                default -> {
                    new Alert(Alert.AlertType.WARNING,
                            "Please select a valid listing type.")
                            .showAndWait();
                    return;
                }
            }

            try {
                DummyDataService.getInstance().addListing(listing);

                new Alert(Alert.AlertType.INFORMATION,
                        "Listing successfully created for "
                                + selectedSeller.getFullName()
                                + "!\nStatus: Pending Review.")
                        .showAndWait();

                showAllListings();

            } catch (Exception ex) {
                new Alert(Alert.AlertType.ERROR,
                        "Failed to create listing:\n" + ex.getMessage())
                        .showAndWait();
            }
        });

        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add("btn-outline");
        cancelBtn.setOnAction(e -> showAllListings());

        HBox buttonBar = new HBox(10, cancelBtn, submitBtn);
        buttonBar.setAlignment(Pos.CENTER_RIGHT);

        form.getChildren().addAll(
                title,
                new Label("Select Seller *"),
                sellerCombo,
                sellerNote,
                new Label("Listing Type *"),
                typeCombo,
                new Label("Item Name *"),
                nameField,
                new Label("Category *"),
                catCombo,
                new Label("Condition *"),
                condCombo,
                new Label("Description"),
                descArea,
                new Label("Price (Sell only)"),
                priceField,
                new Label("Lending Days (Lend only)"),
                daysField,
                new Label("Meet-up Location *"),
                meetupCombo,
                photoNote,
                buttonBar
        );

        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        contentPane.getChildren().add(scroll);
    }

    private void showEditListingForm(Listing listing) {
        contentPane.getChildren().clear();
        VBox form = new VBox(12);
        form.setMaxWidth(520);
        form.getStyleClass().add("card");
        form.setPadding(new Insets(20));
        Label heading = new Label("Edit Listing");
        heading.getStyleClass().add("section-title");

        // Display seller information without allowing it to be changed.
        Label sellerInfo = new Label(
                "Seller: " + listing.getSellerFullName()
                        + " (@" + listing.getSellerUsername() + ")"
        );
        sellerInfo.setStyle("-fx-text-fill: #5a5a7a;");
        Label statusInfo = new Label(
                "Current Status: " + listing.getStatus()
        );
        statusInfo.setStyle("-fx-text-fill: #5a5a7a;");

        // Listing type
        ComboBox<String> typeCombo = new ComboBox<>(
                FXCollections.observableArrayList("Sell", "Lend", "Give Away")
        );
        typeCombo.setPromptText("Listing type *");
        typeCombo.setMaxWidth(Double.MAX_VALUE);
        if (listing.getType() != null) {
            switch (listing.getType()) {
                case SELL -> typeCombo.setValue("Sell");
                case LEND -> typeCombo.setValue("Lend");
                case GIVEAWAY -> typeCombo.setValue("Give Away");
            }
        }

        // Listing details
        TextField nameField = new TextField(listing.getTitle());
        nameField.setPromptText("Item name *");

        ComboBox<String> catCombo = new ComboBox<>(
                FXCollections.observableArrayList(DummyDataService.CATEGORIES)
        );
        catCombo.setPromptText("Category *");
        catCombo.setMaxWidth(Double.MAX_VALUE);
        catCombo.setValue(listing.getCategory());
        ComboBox<String> condCombo = new ComboBox<>(
                FXCollections.observableArrayList(DummyDataService.CONDITIONS)
        );
        condCombo.setPromptText("Condition *");
        condCombo.setMaxWidth(Double.MAX_VALUE);
        condCombo.setValue(listing.getCondition());
        TextArea descArea = new TextArea(
                listing.getDescription() == null ? "" : listing.getDescription()
        );
        descArea.setPromptText("Description");
        descArea.setPrefRowCount(3);
        TextField priceField = new TextField(
                listing.getPrice() == null ? "" : listing.getPrice().toString()
        );
        priceField.setPromptText("Price (HK$) – required for Sell");
        TextField daysField = new TextField(
                listing.getLendingDays() == null
                        ? "" : listing.getLendingDays().toString()
        );
        daysField.setPromptText("Lending duration (days) – required for Lend");
        ComboBox<String> meetupCombo = new ComboBox<>(
                FXCollections.observableArrayList(DummyDataService.HKUST_LOCATIONS)
        );
        meetupCombo.setPromptText("Meet-up location *");
        meetupCombo.setMaxWidth(Double.MAX_VALUE);
        meetupCombo.setValue(listing.getMeetupLocation());
        Label photoNote = new Label(
                "Photos: Existing photo data will be preserved. Photo editing is not included here."
        );
        photoNote.setStyle("-fx-text-fill: #9aa0b0; -fx-font-size: 12px;");
        photoNote.setWrapText(true);

        // Buttons
        Button saveBtn = new Button("Save Changes");
        saveBtn.getStyleClass().add("btn-gold");
        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().addAll("btn-outline", "btn-sm");
        saveBtn.setOnAction(e -> {
            // Validate required fields
            if (typeCombo.getValue() == null
                    || nameField.getText().isBlank()
                    || catCombo.getValue() == null
                    || condCombo.getValue() == null
                    || meetupCombo.getValue() == null) {
                new Alert(
                        Alert.AlertType.WARNING,
                        "Please fill in all required fields."
                ).showAndWait();
                return;
            }
            ListingType selectedType;
            switch (typeCombo.getValue()) {
                case "Sell" -> selectedType = ListingType.SELL;
                case "Lend" -> selectedType = ListingType.LEND;
                default -> selectedType = ListingType.GIVEAWAY;
            }
            Double newPrice = null;
            Integer newLendingDays = null;

            // Validate fields according to listing type
            if (selectedType == ListingType.SELL) {
                try {
                    newPrice = Double.parseDouble(priceField.getText().trim());

                    if (newPrice <= 0) {
                        throw new NumberFormatException();
                    }
                } catch (Exception ex) {
                    new Alert(
                            Alert.AlertType.WARNING,
                            "Please enter a valid price greater than 0."
                    ).showAndWait();
                    return;
                }
            } else if (selectedType == ListingType.LEND) {
                try {
                    newLendingDays = Integer.parseInt(daysField.getText().trim());

                    if (newLendingDays <= 0) {
                        throw new NumberFormatException();
                    }
                } catch (Exception ex) {
                    new Alert(
                            Alert.AlertType.WARNING,
                            "Please enter valid lending days greater than 0."
                    ).showAndWait();
                    return;
                }
            }
            try {
                // Update only editable fields on the existing Listing object.
                // ID, seller, status, timestamps, views and photos remain unchanged.
                listing.setTitle(nameField.getText().trim());
                listing.setDescription(descArea.getText().trim());
                listing.setCategory(catCombo.getValue());
                listing.setType(selectedType);
                listing.setCondition(condCombo.getValue());
                listing.setMeetupLocation(meetupCombo.getValue());
                listing.setPrice(newPrice);
                listing.setLendingDays(newLendingDays);
                DummyDataService.getInstance().updateListing(listing);
                new Alert(
                        Alert.AlertType.INFORMATION,
                        "Listing updated successfully."
                ).showAndWait();
                showAllListings();
            } catch (Exception ex) {
                new Alert(
                        Alert.AlertType.ERROR,
                        "Failed to update listing:\n" + ex.getMessage()
                ).showAndWait();
            }
        });
        cancelBtn.setOnAction(e -> showAllListings());
        HBox buttonRow = new HBox(10, saveBtn, cancelBtn);
        buttonRow.setAlignment(Pos.CENTER_LEFT);
        form.getChildren().addAll(
                heading,
                sellerInfo,
                statusInfo,
                new Label("Listing Type *"),
                typeCombo,
                new Label("Item Name *"),
                nameField,
                new Label("Category *"),
                catCombo,
                new Label("Condition *"),
                condCombo,
                new Label("Description"),
                descArea,
                new Label("Price (Sell only)"),
                priceField,
                new Label("Lending Days (Lend only)"),
                daysField,
                new Label("Meet-up Location *"),
                meetupCombo,
                photoNote,
                buttonRow
        );
        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        contentPane.getChildren().add(scroll);
    }

    @FXML
    private void showUsers() {
        setActive(btnUsers, "Manage All Users");
        contentPane.getChildren().clear();
        VBox root = new VBox(12);
        root.setPadding(new Insets(4));
        try {
            for (User u : DummyDataService.getInstance().getAllUsers()) {
                if (u.getRole() == UserRole.ADMIN) continue;
                HBox row = new HBox(12);
                row.getStyleClass().add("card");
                row.setAlignment(Pos.CENTER_LEFT);
                row.setPadding(new Insets(12));
                VBox info = new VBox(3);
                HBox.setHgrow(info, Priority.ALWAYS);
                Label name = new Label(
                        u.getFullName() + " (@" + u.getUsername() + ")"
                );
                name.setStyle("-fx-font-weight: bold;");
                Label role = new Label(
                        u.getRole().name()
                                + (u.isActive() ? " · Active" : " · Deactivated")
                );
                role.setStyle("-fx-text-fill: #5a5a7a; -fx-font-size: 12px;");
                info.getChildren().addAll(name, role);
                // Edit user profile
                Button edit = new Button("Edit");
                edit.getStyleClass().addAll("btn-outline", "btn-sm");
                edit.setOnAction(e -> editUser(u));
                // Activate / deactivate user
                Button toggle = new Button(
                        u.isActive() ? "Deactivate" : "Activate"
                );
                toggle.getStyleClass().addAll(
                        u.isActive() ? "btn-danger" : "btn-success",
                        "btn-sm"
                );
                toggle.setOnAction(e -> {
                    String action = u.isActive() ? "deactivate" : "activate";
                    Alert confirm = new Alert(
                            Alert.AlertType.CONFIRMATION,
                            "Are you sure you want to " + action
                                    + " user @" + u.getUsername() + "?"
                    );
                    confirm.setHeaderText("Confirm Account Status Change");
                    confirm.showAndWait().ifPresent(result -> {
                        if (result == ButtonType.OK) {
                            try {
                                DummyDataService.getInstance().setUserActive(
                                        u.getUsername(),
                                        !u.isActive()
                                );
                                showUsers();
                            } catch (Exception ex) {
                                showError("Unable to change account status", ex);
                            }
                        }
                    });
                });
                // Permanent deletion is not implemented yet
                Button delete = new Button("Delete");
                delete.getStyleClass().addAll("btn-danger", "btn-sm");
                delete.setDisable(true);
                delete.setTooltip(
                        new Tooltip("Permanent deletion is not implemented yet.")
                );
                row.getChildren().addAll(info, edit, toggle, delete);
                root.getChildren().add(row);
            }
            if (root.getChildren().isEmpty()) {
                root.getChildren().add(new Label("No Buyer or Seller accounts found."));
            }
        } catch (Exception ex) {
            showError("Unable to load users", ex);
        }
        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        contentPane.getChildren().add(scroll);
    }

    private void editUser(User user) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Edit User");
        dialog.setHeaderText(
                "Edit profile for @" + user.getUsername()
        );

        ButtonType saveButton = new ButtonType(
                "Save Changes",
                ButtonBar.ButtonData.OK_DONE
        );

        dialog.getDialogPane().getButtonTypes().addAll(
                saveButton,
                ButtonType.CANCEL
        );

        TextField fullNameField = new TextField(user.getFullName());
        TextField hallField = new TextField(
                user.getResidenceHall() == null ? "" : user.getResidenceHall()
        );
        TextField bioField = new TextField(
                user.getBio() == null ? "" : user.getBio()
        );
        TextField meetupField = new TextField(
                user.getPreferredMeetup() == null
                        ? "" : user.getPreferredMeetup()
        );

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(12);
        grid.setPadding(new Insets(15));

        grid.add(new Label("Username:"), 0, 0);
        grid.add(new Label(user.getUsername()), 1, 0);

        grid.add(new Label("Full Name:"), 0, 1);
        grid.add(fullNameField, 1, 1);

        grid.add(new Label("Residence Hall:"), 0, 2);
        grid.add(hallField, 1, 2);

        grid.add(new Label("Bio:"), 0, 3);
        grid.add(bioField, 1, 3);

        grid.add(new Label("Preferred Meetup:"), 0, 4);
        grid.add(meetupField, 1, 4);

        dialog.getDialogPane().setContent(grid);

        dialog.showAndWait().ifPresent(result -> {
            if (result == saveButton) {
                if (fullNameField.getText().isBlank()) {
                    new Alert(
                            Alert.AlertType.WARNING,
                            "Full name cannot be empty."
                    ).showAndWait();
                    return;
                }

                try {
                    User updatedUser = new User();
                    updatedUser.setUsername(user.getUsername());
                    updatedUser.setRole(user.getRole());
                    updatedUser.setFullName(fullNameField.getText().trim());
                    updatedUser.setResidenceHall(hallField.getText().trim());
                    updatedUser.setBio(bioField.getText().trim());
                    updatedUser.setPreferredMeetup(meetupField.getText().trim());

                    DummyDataService.getInstance().updateUser(updatedUser);

                    new Alert(
                            Alert.AlertType.INFORMATION,
                            "User profile updated successfully."
                    ).showAndWait();

                    showUsers();

                } catch (Exception ex) {
                    showError("Unable to update user profile", ex);
                }
            }
        });
    }

    private void showError(String title, Exception ex) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Operation Failed");
        alert.setHeaderText(title);
        alert.setContentText(
                ex.getMessage() == null
                        ? "An unexpected error occurred."
                        : ex.getMessage()
        );
        alert.showAndWait();
    }

    @FXML
    private void showDeals() {
        setActive(btnDeals, "All Deals Record");
        contentPane.getChildren().clear();
        VBox root = new VBox(12);
        root.setPadding(new Insets(4));
        // Search and type filters
        HBox filters = new HBox(10);
        filters.setAlignment(Pos.CENTER_LEFT);
        TextField search = new TextField();
        search.setPromptText("Search by item / buyer / seller...");
        search.setPrefWidth(280);
        ComboBox<String> typeFilter = new ComboBox<>(
                FXCollections.observableArrayList(
                        "All Types", "Sell", "Lend", "Giveaway"
                )
        );
        typeFilter.getSelectionModel().selectFirst();
        filters.getChildren().addAll(search, typeFilter);
        root.getChildren().add(filters);
        // Table columns
        TableView<String[]> table = new TableView<>();
        table.setColumnResizePolicy(
                TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN
        );
        String[] headers = {
                "Item", "Buyer", "Seller", "Type",
                "Start Date", "Return / Complete Date", "Status"
        };
        for (int i = 0; i < headers.length; i++) {
            final int columnIndex = i;
            TableColumn<String[], String> column =
                    new TableColumn<>(headers[i]);
            column.setCellValueFactory(cell ->
                    new javafx.beans.property.SimpleStringProperty(
                            cell.getValue()[columnIndex]
                    )
            );
            table.getColumns().add(column);
        }
        // Retrieve actual deal records through the data service
        List<Deal> deals = DummyDataService.getInstance().getAllDeals();
        DateTimeFormatter dateFormat =
                DateTimeFormatter.ofPattern("dd MMM yyyy");
        javafx.collections.ObservableList<String[]> dealRows =
                FXCollections.observableArrayList();
        for (Deal deal : deals) {
            String item = deal.getListingTitle() == null
                    ? "—" : deal.getListingTitle();
            String buyer = deal.getBuyerUsername() == null
                    ? "—" : deal.getBuyerUsername();
            String seller = deal.getSellerUsername() == null
                    ? "—" : deal.getSellerUsername();
            String type = deal.getDealType() == null
                    ? "Unknown"
                    : switch (deal.getDealType()) {
                case SELL -> "Sell";
                case LEND -> "Lend";
                case GIVEAWAY -> "Giveaway";
            };
            // Use borrowing start date when available.
            // Otherwise, use the date the deal was created.
            String startDate = "—";
            if (deal.getBorrowStart() != null) {
                startDate = deal.getBorrowStart().format(dateFormat);
            } else if (deal.getCreatedAt() != null) {
                startDate = deal.getCreatedAt()
                        .toLocalDate()
                        .format(dateFormat);
            }
            // For lending, show the expected return date.
            // For completed transactions, show the completion date.
            String endDate = "—";
            if (deal.getBorrowEnd() != null) {
                endDate = deal.getBorrowEnd().format(dateFormat);
            } else if (deal.getCompletedAt() != null) {
                endDate = deal.getCompletedAt()
                        .toLocalDate()
                        .format(dateFormat);
            }
            String status = deal.getStatus() == null
                    ? "—" : deal.getStatus();
            dealRows.add(new String[]{
                    item, buyer, seller, type,
                    startDate, endDate, status
            });
        }
        // Enable searching and filtering without changing database records
        FilteredList<String[]> filteredDeals =
                new FilteredList<>(dealRows, row -> true);
        Runnable applyFilters = () -> {
            String keyword = search.getText() == null
                    ? "" : search.getText().trim().toLowerCase();
            String selectedType = typeFilter.getValue();
            filteredDeals.setPredicate(row -> {
                boolean matchesSearch =
                        (row[0] + " " + row[1] + " " + row[2])
                                .toLowerCase()
                                .contains(keyword);
                boolean matchesType =
                        selectedType == null
                                || selectedType.equals("All Types")
                                || row[3].equalsIgnoreCase(selectedType);
                return matchesSearch && matchesType;
            });
        };
        search.textProperty().addListener(
                (observable, oldValue, newValue) -> applyFilters.run()
        );
        typeFilter.valueProperty().addListener(
                (observable, oldValue, newValue) -> applyFilters.run()
        );
        table.setItems(filteredDeals);
        table.setPlaceholder(new Label("No deal records found."));
        root.getChildren().add(table);
        contentPane.getChildren().add(root);
    }


    @FXML
    private void showRatings() {
        setActive(btnRatings, "Seller Ratings Overview");
        contentPane.getChildren().clear();
        VBox root = new VBox(12);
        root.setPadding(new Insets(4));

        // Filter controls
        HBox filters = new HBox(10);
        filters.setAlignment(Pos.CENTER_LEFT);
        TextField userFilter = new TextField();
        userFilter.setPromptText("Filter by seller username...");
        userFilter.setPrefWidth(190);
        ComboBox<String> scoreFilter = new ComboBox<>(
                FXCollections.observableArrayList(
                        "All scores",
                        "5 stars",
                        "4 stars",
                        "3 stars",
                        "2 stars",
                        "1 star"
                )
        );
        scoreFilter.getSelectionModel().selectFirst();
        scoreFilter.setPrefWidth(120);
        LocalDate today = LocalDate.now();

        DatePicker fromDate = new DatePicker();
        fromDate.setPromptText("From date");
        fromDate.setPrefWidth(135);
        fromDate.setDayCellFactory(picker -> new DateCell() {
            @Override
            public void updateItem(LocalDate date, boolean empty) {
                super.updateItem(date, empty);
                setDisable(empty || date.isAfter(today));
            }
        });

        DatePicker toDate = new DatePicker();
        toDate.setPromptText("To date");
        toDate.setPrefWidth(135);
        toDate.setDayCellFactory(picker -> new DateCell() {
            @Override
            public void updateItem(LocalDate date, boolean empty) {
                super.updateItem(date, empty);
                setDisable(empty || date.isAfter(today));
            }
        });
        Button clearFilters = new Button("Clear Filters");
        clearFilters.getStyleClass().addAll("btn-outline", "btn-sm");
        filters.getChildren().addAll(
                userFilter,
                scoreFilter,
                fromDate,
                toDate,
                clearFilters
        );
        root.getChildren().add(filters);

        // Review list
        VBox reviewList = new VBox(10);
        root.getChildren().add(reviewList);
        List<Review> allReviews =
                DummyDataService.getInstance().getAllReviews();
        DateTimeFormatter dateFormatter =
                DateTimeFormatter.ofPattern("dd MMM yyyy");

        // Rebuild the displayed list whenever a filter changes.
        final Runnable[] applyFilters = new Runnable[1];
        applyFilters[0] = () -> {
            reviewList.getChildren().clear();
            String sellerKeyword = userFilter.getText() == null
                    ? ""
                    : userFilter.getText().trim().toLowerCase();
            String selectedScore = scoreFilter.getValue();
            LocalDate from = fromDate.getValue();
            LocalDate to = toDate.getValue();
            if (from != null && to != null && from.isAfter(to)) {
                Label invalidDate = new Label(
                        "Invalid date range: From date must not be after To date."
                );
                invalidDate.setStyle("-fx-text-fill: #d9534f;");
                reviewList.getChildren().add(invalidDate);
                return;
            }
            int displayedCount = 0;
            for (Review r : allReviews) {
                // Seller username filter (case-insensitive).
                String seller = r.getSellerUsername() == null
                        ? ""
                        : r.getSellerUsername().toLowerCase();
                if (!seller.contains(sellerKeyword)) {
                    continue;
                }

                // Rating score filter.
                if (selectedScore != null
                        && !selectedScore.equals("All scores")) {
                    int selectedRating =
                            Integer.parseInt(selectedScore.substring(0, 1));
                    if (r.getRating() != selectedRating) {
                        continue;
                    }
                }

                // Date filter (inclusive).
                LocalDateTime createdAt = r.getCreatedAt();
                if (from != null || to != null) {
                    if (createdAt == null) {
                        continue;
                    }
                    LocalDate reviewDate = createdAt.toLocalDate();
                    if (from != null && reviewDate.isBefore(from)) {
                        continue;
                    }
                    if (to != null && reviewDate.isAfter(to)) {
                        continue;
                    }
                }

                // Build review card.
                HBox row = new HBox(12);
                row.getStyleClass().add("card");
                row.setAlignment(Pos.CENTER_LEFT);
                row.setPadding(new Insets(12));
                VBox info = new VBox(4);
                HBox.setHgrow(info, Priority.ALWAYS);
                Label stars = new Label(
                        "★".repeat(r.getRating())
                                + "☆".repeat(5 - r.getRating())
                                + "  @" + r.getSellerUsername()
                                + " by @" + r.getBuyerUsername()
                );
                stars.setStyle("-fx-font-weight: bold;");
                Label comment = new Label(
                        r.getComment() == null ? "" : r.getComment()
                );
                comment.setWrapText(true);
                comment.setStyle("-fx-text-fill: #5a5a7a;");
                Label dateLabel = new Label(
                        createdAt == null
                                ? "Date unavailable"
                                : "Submitted: " + createdAt.format(dateFormatter)
                );
                dateLabel.setStyle("-fx-text-fill: #888888; -fx-font-size: 11px;");
                info.getChildren().addAll(stars, comment, dateLabel);
                Button hide = new Button(
                        r.isHidden() ? "Unhide" : "Hide"
                );
                hide.getStyleClass().addAll("btn-outline", "btn-sm");
                hide.setOnAction(e -> {
                    boolean newHiddenState = !r.isHidden();

                    Alert confirmation = new Alert(
                            Alert.AlertType.CONFIRMATION
                    );
                    confirmation.setTitle("Confirm Review Visibility");
                    confirmation.setHeaderText(
                            newHiddenState ? "Hide this review?" : "Unhide this review?"
                    );
                    confirmation.setContentText(
                            newHiddenState
                                    ? "This review will no longer be visible to the seller."
                                    : "This review will become visible to the seller again."
                    );
                    confirmation.showAndWait().ifPresent(response -> {
                        if (response == ButtonType.OK) {
                            try {
                                DummyDataService.getInstance()
                                        .setReviewHidden(r.getId(), newHiddenState);

                                // Update local state only after backend success.
                                r.setHidden(newHiddenState);
                                applyFilters[0].run();
                            } catch (RuntimeException ex) {
                                Alert error = new Alert(Alert.AlertType.ERROR);
                                error.setTitle("Update Failed");
                                error.setHeaderText("Could not update review visibility");
                                error.setContentText(
                                        ex.getMessage() == null
                                                ? "An unexpected error occurred."
                                                : ex.getMessage()
                                );
                                error.showAndWait();
                            }
                        }
                    });
                });
                row.getChildren().addAll(info, hide);
                reviewList.getChildren().add(row);
                displayedCount++;
            }
            if (displayedCount == 0) {
                Label emptyLabel = new Label("No reviews match the selected filters.");
                emptyLabel.setStyle("-fx-text-fill: #888888;");
                reviewList.getChildren().add(emptyLabel);
            }
        };
        // Attach listeners to all filter controls.
        userFilter.textProperty().addListener(
                (obs, oldValue, newValue) -> applyFilters[0].run()
        );
        scoreFilter.valueProperty().addListener(
                (obs, oldValue, newValue) -> applyFilters[0].run()
        );
        fromDate.valueProperty().addListener(
                (obs, oldValue, newValue) -> applyFilters[0].run()
        );
        toDate.valueProperty().addListener(
                (obs, oldValue, newValue) -> applyFilters[0].run()
        );
        clearFilters.setOnAction(e -> {
            userFilter.clear();
            scoreFilter.getSelectionModel().selectFirst();
            fromDate.setValue(null);
            toDate.setValue(null);
        });
        // Initial display.
        applyFilters[0].run();
        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        contentPane.getChildren().add(scroll);
    }

    @FXML
    private void showNotifications() {
        setActive(btnNotifications, "Admin Notifications");
        contentPane.getChildren().clear();
        VBox root = new VBox(12);
        root.setPadding(new Insets(4));
        List<Notification> notifs = DummyDataService.getInstance().getNotificationsFor("admin");
        if (notifs.isEmpty()) {
            root.getChildren().add(new Label("No notifications."));
        } else {
            for (Notification n : notifs) {
                VBox item = new VBox(4);
                item.getStyleClass().add("notif-item");
                Label t = new Label(n.getTitle());
                t.setStyle("-fx-font-weight: bold;");
                Label m = new Label(n.getMessage());
                m.setWrapText(true);
                m.setStyle("-fx-text-fill: #5a5a7a;");
                Label meta = new Label(n.getCategory() + " · " +
                        n.getTimestamp().format(DateTimeFormatter.ofPattern("dd MMM HH:mm")));
                meta.setStyle("-fx-text-fill: #9aa0b0; -fx-font-size: 11px;");
                item.getChildren().addAll(t, m, meta);
                root.getChildren().add(item);
            }
        }
        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        contentPane.getChildren().add(scroll);
    }


    @FXML
    private void showProfile() {
        setActive(btnProfile, "My Profile");
        contentPane.getChildren().clear();
        User user = SessionManager.getCurrentUser();
        if (user == null) {
            showError(
                    "Profile Error",
                    new IllegalArgumentException("No logged-in user.")
            );
            return;
        }
        VBox form = new VBox(12);
        form.setMaxWidth(420);
        form.getStyleClass().add("card");
        form.setPadding(new Insets(20));
        Label titleLbl = new Label("Admin Profile");
        titleLbl.getStyleClass().add("section-title");

        // Username: read-only
        TextField username = new TextField(user.getUsername());
        username.setEditable(false);
        username.setDisable(true);

        // Full Name
        TextField name = new TextField(user.getFullName());
        Button saveName = new Button("Save Full Name");
        saveName.getStyleClass().add("btn-success");
        saveName.setOnAction(e -> {
            String fullName = name.getText() == null
                    ? ""
                    : name.getText().trim();
            if (fullName.isEmpty()) {
                showError(
                        "Profile Update Failed",
                        new IllegalArgumentException(
                                "Full Name cannot be empty."
                        )
                );
                return;
            }
            if (fullName.length() > 120) {
                showError(
                        "Profile Update Failed",
                        new IllegalArgumentException(
                                "Full Name cannot exceed 120 characters."
                        )
                );
                return;
            }
            try {
                DummyDataService.getInstance()
                        .updateAdminProfile(user.getUsername(), fullName);
                user.setFullName(fullName);
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("Profile Updated");
                alert.setHeaderText(null);
                alert.setContentText("Full Name updated successfully.");
                alert.showAndWait();
            } catch (Exception ex) {
                showError("Profile Update Failed", ex);
            }
        });

        // Password fields
        PasswordField currentPass = new PasswordField();
        currentPass.setPromptText("Current password");
        PasswordField newPass = new PasswordField();
        newPass.setPromptText("New password");
        PasswordField confirmPass = new PasswordField();
        confirmPass.setPromptText("Confirm new password");
        Button changePassword = new Button("Change Password");
        changePassword.getStyleClass().add("btn-success");
        changePassword.setOnAction(e -> {
            String currentPassword = currentPass.getText();
            String newPassword = newPass.getText();
            String confirmation = confirmPass.getText();
            if (currentPassword == null || currentPassword.isBlank()) {
                showError(
                        "Password Change Failed",
                        new IllegalArgumentException(
                                "Please enter your current password."
                        )
                );
                return;
            }
            String validation =
                    DummyDataService.checkPasswordStrength(newPassword);
            if (!"OK".equals(validation)) {
                showError(
                        "Password Change Failed",
                        new IllegalArgumentException(validation)
                );
                return;
            }
            if (!newPassword.equals(confirmation)) {
                showError(
                        "Password Change Failed",
                        new IllegalArgumentException(
                                "New password and confirmation do not match."
                        )
                );
                return;
            }
            if (newPassword.equals(currentPassword)) {
                showError(
                        "Password Change Failed",
                        new IllegalArgumentException(
                                "Your new password must be different from your current password."
                        )
                );
                return;
            }
            try {
                DummyDataService.getInstance().changeAdminPassword(
                        user.getUsername(),
                        currentPassword,
                        newPassword
                );
                currentPass.clear();
                newPass.clear();
                confirmPass.clear();
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("Password Changed");
                alert.setHeaderText(null);
                alert.setContentText(
                        "Your password has been changed successfully."
                );
                alert.showAndWait();
            } catch (Exception ex) {
                showError("Password Change Failed", ex);
            }
        });
        form.getChildren().addAll(
                titleLbl,
                new Label("Username"),
                username,
                new Label("Full Name"),
                name,
                saveName,
                new Separator(),
                new Label("Current Password"),
                currentPass,
                new Label("New Password"),
                newPass,
                new Label("Confirm New Password"),
                confirmPass,
                changePassword
        );
        contentPane.getChildren().add(form);
    }


    @FXML
    private void logout() {
        DummyDataService.getInstance().logout();
        SessionManager.logout();
        SceneManager.showWelcome();
    }
}
