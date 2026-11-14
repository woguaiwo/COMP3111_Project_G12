# HKUST Marketplace — JavaFX UI skeleton

This folder contains the JavaFX application for the COMP3111 HKUST Marketplace project. It is a JavaFX + Maven application with FXML views, a shared stylesheet, HKUST branding, and a switchable API/JDBC/in-memory data layer. The released client uses the public API; use `MARKETPLACE_MODE=demo` for an offline UI preview.

## Quick UI preview (Windows)

Open PowerShell in this folder (`HKUST-Marketplace 4`) and run:

```powershell
.\mvnw.cmd clean javafx:run
```

The first run downloads Maven and JavaFX dependencies. A JavaFX window opens at the Welcome screen, where you can enter the Buyer, Seller, or Admin portal. The same command is also available from [`run-ui.cmd`](run-ui.cmd).

Requirements:

- JDK 17 or newer, including `javac` (`java -version` and `javac -version` should both work).
- A normal desktop session. JavaFX cannot display a window in a headless terminal.
- Internet access on the first run so Maven can download dependencies.

## Demo accounts

| Portal | Username | Password |
| --- | --- | --- |
| Admin | `admin` | `admin123` |
| Buyer | `alice` | `password1` |
| Buyer | `bob` | `password1` |
| Seller | `charlie` | `password1` |
| Seller | `diana` | `password1` |

The released client uses the public `marketplaceapi` Neon Function. It sends only HTTPS requests and a short-lived JWT; it never contains a Neon connection string or password hash. The seeded accounts above are available in the shared `dev` branch, and new buyers/sellers register with a username, full name, and password without creating a Neon account. A local `DATABASE_URL` is still supported as a development-only JDBC fallback when `MARKETPLACE_MODE=jdbc` is selected.

## Preview individual FXML files in Scene Builder

Scene Builder is useful for inspecting or editing one layout at a time; it does not execute the Java controllers or navigation. It is installed separately from this Maven project. On Windows it can be installed with:

```powershell
winget install --id Gluon.SceneBuilder --exact
```

Then open the default Welcome view with [`open-scenebuilder.cmd`](open-scenebuilder.cmd), or open any of these files from Scene Builder's **File → Open** menu:

```text
src/main/resources/fxml/WelcomeView.fxml
src/main/resources/fxml/buyer/BuyerLoginView.fxml
src/main/resources/fxml/buyer/BuyerRegisterView.fxml
src/main/resources/fxml/buyer/BuyerDashboardView.fxml
src/main/resources/fxml/seller/SellerLoginView.fxml
src/main/resources/fxml/seller/SellerRegisterView.fxml
src/main/resources/fxml/seller/SellerDashboardView.fxml
src/main/resources/fxml/admin/AdminLoginView.fxml
src/main/resources/fxml/admin/AdminDashboardView.fxml
```

For the complete styling and navigation, use `mvnw.cmd javafx:run`; the application loads [`styles.css`](src/main/resources/css/styles.css) and the matching controller at runtime. Scene Builder only previews the FXML structure and may show controller or stylesheet warnings that do not prevent the application from running.

## Neon database and external-user mode

The compatibility facade still exposes `DummyDataService` to the existing controllers, but it now prefers [`HttpMarketplaceDataService`](src/main/java/com/hkust/marketplace/utils/HttpMarketplaceDataService.java), backed by the deployed Neon Function. The function owns `DATABASE_URL`, PBKDF2 verification, HMAC JWT signing, and revocable `app_sessions` rows. `SessionManager` keeps the token only in memory and logout revokes the server-side session. Set `MARKETPLACE_MODE=demo` for the original in-memory UI data or `MARKETPLACE_MODE=jdbc` for a local JDBC development run. Follow [`backend/README.md`](backend/README.md) and [`database/README.md`](database/README.md) for deployment and schema details. Never put a real Neon connection string or `JWT_SECRET` in Git or in a released JavaFX client.

## Project layout

```text
src/main/java/com/hkust/marketplace/
├── MarketplaceApp.java       # JavaFX entry point
├── controllers/              # FXML event handlers
├── models/                   # In-memory domain objects
└── utils/                    # SceneManager, session, demo data
src/main/resources/
├── fxml/                     # Buyer, Seller, Admin and Welcome views
├── css/styles.css             # Shared visual theme
└── images/hkust_logo.png     # HKUST logo asset
database/
├── schema.sql                 # Neon/PostgreSQL starter schema
└── README.md                  # Neon setup and integration notes
```

## Other ways to run

If Maven is already installed:

```powershell
mvn clean javafx:run
```

In IntelliJ IDEA, open this folder as a Maven project and run `MarketplaceApp.java`. In VS Code, install the Extension Pack for Java and run the same class.

## Troubleshooting

- If `mvn` is not recognized, use `.\mvnw.cmd clean javafx:run`; the repository includes the Maven Wrapper configuration.
- If `java` or `javac` is not recognized, install a full JDK 17+ and restart PowerShell.
- To see the full Maven error, run `.\mvnw.cmd clean javafx:run -e`.

## Current scope

The screens demonstrate the intended UI and interaction flow. External users now use the authenticated API for users, listings, deals, notifications, and reviews; direct JDBC and in-memory data remain explicit local development modes. Neon Auth is not used for this username-only flow, so application users do not need Neon invitations or Neon sign-up accounts.
