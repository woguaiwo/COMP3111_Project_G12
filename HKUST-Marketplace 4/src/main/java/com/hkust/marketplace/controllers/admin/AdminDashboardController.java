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

        Button addBtn = new Button("+ Add Listing (on behalf of seller)");
        addBtn.getStyleClass().add("btn-primary");
        addBtn.setOnAction(e -> new Alert(Alert.AlertType.INFORMATION,
                "Admin can create a listing on behalf of a seller here.\nSame form fields as Seller Create Listing.\n(UI placeholder)").showAndWait());
        root.getChildren().add(addBtn);

        for (Listing l : DummyDataService.getInstance().getAllListings()) {
            HBox row = new HBox(12);
            row.getStyleClass().add("card");
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(12));

            VBox info = new VBox(3);
            HBox.setHgrow(info, Priority.ALWAYS);
            Label t = new Label(l.getTitle());
            t.setStyle("-fx-font-weight: bold;");
            Label m = new Label(l.getSellerFullName() + " · " + l.getTypeDisplay() + " · " + l.getStatus());
            m.setStyle("-fx-text-fill: #5a5a7a; -fx-font-size: 12px;");
            info.getChildren().addAll(t, m);

            Button edit = new Button("Edit");
            edit.getStyleClass().addAll("btn-outline", "btn-sm");
            edit.setOnAction(e -> new Alert(Alert.AlertType.INFORMATION, "Edit listing dialog (demo).").showAndWait());

            Button remove = new Button("Remove");
            remove.getStyleClass().addAll("btn-danger", "btn-sm");
            remove.setOnAction(e -> {
                Alert conf = new Alert(Alert.AlertType.CONFIRMATION, "Permanently remove this listing?");
                conf.showAndWait().ifPresent(r -> {
                    if (r == ButtonType.OK) {
                        DummyDataService.getInstance().removeListing(l.getId());
                        showAllListings();
                    }
                });
            });

            row.getChildren().addAll(info, edit, remove);
            root.getChildren().add(row);
        }
        ScrollPane scroll = new ScrollPane(root);
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

        HBox filters = new HBox(10);
        TextField userFilter = new TextField();
        userFilter.setPromptText("Filter by seller...");
        ComboBox<String> scoreFilter = new ComboBox<>(FXCollections.observableArrayList(
                "All scores", "5 stars", "4 stars", "3 stars", "2 stars", "1 star"));
        scoreFilter.getSelectionModel().selectFirst();
        filters.getChildren().addAll(userFilter, scoreFilter);
        root.getChildren().add(filters);

        for (Review r : DummyDataService.getInstance().getAllReviews()) {
            HBox row = new HBox(12);
            row.getStyleClass().add("card");
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(12));

            VBox info = new VBox(4);
            HBox.setHgrow(info, Priority.ALWAYS);
            Label stars = new Label("★".repeat(r.getRating()) + "☆".repeat(5 - r.getRating()) +
                    "  @" + r.getSellerUsername() + " by @" + r.getBuyerUsername());
            stars.setStyle("-fx-font-weight: bold;");
            Label comment = new Label(r.getComment());
            comment.setWrapText(true);
            comment.setStyle("-fx-text-fill: #5a5a7a;");
            info.getChildren().addAll(stars, comment);

            Button hide = new Button(r.isHidden() ? "Unhide" : "Hide");
            hide.getStyleClass().addAll("btn-outline", "btn-sm");
            hide.setOnAction(e -> {
                r.setHidden(!r.isHidden());
                showRatings();
            });
            row.getChildren().addAll(info, hide);
            root.getChildren().add(row);
        }
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
        VBox form = new VBox(12);
        form.setMaxWidth(400);
        form.getStyleClass().add("card");
        form.setPadding(new Insets(20));
        Label titleLbl = new Label("Admin Profile");
        titleLbl.getStyleClass().add("section-title");
        form.getChildren().add(titleLbl);

        TextField name = new TextField(user != null ? user.getFullName() : "");
        PasswordField pass = new PasswordField();
        pass.setPromptText("New password (optional)");
        Button save = new Button("Save");
        save.getStyleClass().add("btn-success");
        save.setOnAction(e -> new Alert(Alert.AlertType.INFORMATION, "Profile updated (demo).").showAndWait());
        form.getChildren().addAll(new Label("Full Name"), name, new Label("New Password"), pass, save);
        contentPane.getChildren().add(form);
    }

    @FXML
    private void logout() {
        DummyDataService.getInstance().logout();
        SessionManager.logout();
        SceneManager.showWelcome();
    }
}
