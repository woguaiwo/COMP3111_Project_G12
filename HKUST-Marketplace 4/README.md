# HKUST Marketplace — JavaFX UI skeleton

This folder contains the JavaFX application for the COMP3111 HKUST Marketplace project. It is a JavaFX + Maven application with FXML views, a shared stylesheet, HKUST branding, and a switchable in-memory/Neon data layer. No database is required to preview the screens.

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

Without a local Neon configuration, all data is in memory and resets when the application closes. With `database/.env` configured, the app uses the shared `dev` database and writes password hashes to the transitional `app_credentials` table. The direct JDBC mode is for team development; a public deployment should move database access behind an authenticated API.

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

## Neon database mode

The compatibility facade still exposes `DummyDataService` to the existing controllers, but it delegates to Neon when `DATABASE_URL` is available. The first run against an empty `dev` branch bootstraps the demo records. Follow [`database/README.md`](database/README.md) for CLI linking and local env setup; the schema is in [`database/schema.sql`](database/schema.sql). Never put a real Neon connection string in Git or in a released JavaFX client.

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

The screens demonstrate the intended UI and interaction flow. Neon persistence now covers the current users, listings, deals, notifications, and supported reviews. The remaining production step is to move direct JDBC calls behind an authenticated API and finish the Neon Auth/Data API RLS integration.
