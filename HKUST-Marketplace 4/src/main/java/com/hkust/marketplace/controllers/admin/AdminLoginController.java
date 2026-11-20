package com.hkust.marketplace.controllers.admin;

import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.utils.AuthSession;
import com.hkust.marketplace.utils.DummyDataService;
import com.hkust.marketplace.utils.JdbcMarketplaceDataService;
import com.hkust.marketplace.utils.SceneManager;
import com.hkust.marketplace.utils.SessionManager;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

public class AdminLoginController {

    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Label errorLabel;

    @FXML
    private void handleLogin() {
        String username = usernameField.getText().trim();
        String password = passwordField.getText();
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);

        if (username.isEmpty() || password.isEmpty()) {
            showError("Please enter credentials.");
            return;
        }
        AuthSession auth;
        try {
            auth = DummyDataService.getInstance().login(username, password);
        } catch (JdbcMarketplaceDataService.DataAccessException e) {
            String errorMessage = e.getMessage();

            if (errorMessage != null
                    && errorMessage.toLowerCase(java.util.Locale.ROOT)
                    .contains("deleted")) {
                showError("This account has been deleted and cannot be used.");
            } else {
                showError("Unable to reach the marketplace database. Please try again.");
            }
            return;
        }
        if (auth == null || auth.user() == null || auth.user().getRole() != UserRole.ADMIN) {
            showError("Invalid admin credentials.");
            return;
        }
        User user = auth.user();
        SessionManager.login(user, auth.accessToken(), auth.expiresAt());
        SceneManager.showAdminDashboard();
    }

    private void showError(String msg) {
        errorLabel.setText(msg);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    @FXML private void goWelcome() { SceneManager.showWelcome(); }
}
