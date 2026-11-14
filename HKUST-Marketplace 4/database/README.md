# Neon database exploration

The JavaFX skeleton currently uses `DummyDataService`, so the UI still works without a network connection. This folder defines the PostgreSQL schema that can become the shared central database when the repository/API layer is added.

The current code now has a transition layer: when a local `DATABASE_URL` is available, `DummyDataService` delegates to `JdbcMarketplaceDataService` and persists the existing users, listings, deals, notifications, and supported reviews in Neon. Without that variable, the original offline demo remains available.

## Recommended shape

```text
JavaFX client → HTTPS API or trusted Java backend → Neon PostgreSQL
```

Do not put a Neon owner password or an unrestricted database URL in a released desktop application. A quick JDBC prototype can read `DATABASE_URL` from the local environment, but the final application should keep the database connection on a server or a Neon Function and expose only authenticated operations to JavaFX.

Neon also provides a PostgREST-compatible Data API. That is suitable for a client-facing design only after Neon Auth or another JWT provider and PostgreSQL RLS policies are configured. The schema enables RLS with no policies so an accidentally exposed Data API starts closed.

## Project members are not application users

Neon project members are developers or administrators who need access to the Neon Console, branches, SQL Editor, and connection settings. Invite only the teammates who maintain this project.

Application users are buyers, sellers, and administrators who use the marketplace. They do not need a Neon account or a Neon Console invitation. They should sign in through the marketplace authentication flow, while a backend/API performs authorized database operations for them.

If the JavaFX client connects directly to Neon with a shared connection string, every person who receives the app effectively receives database credentials. That is acceptable only as a short-lived, local team prototype; it is not suitable for distributing the app to people outside the development team.

## One-time Neon setup

1. Create one Neon project for the group and invite the teammates who need access.
2. Keep the default `main` branch as the shared environment. Create a `dev` branch for schema experiments; branch changes are isolated and must be applied through a reviewed migration before they reach `main`.
3. Open the Neon SQL Editor and run [`schema.sql`](schema.sql).
4. Copy the connection strings from the Neon Console. Use the pooled URL for application traffic and the direct URL for migrations or administrative SQL. Keep both values outside Git.
5. Add the values to a local `.env` or IDE run configuration using the names in [`.env.example`](.env.example).

Neon connection strings should use TLS (the Console normally includes `sslmode=require`). Never paste a real connection string into source code, README files, screenshots, or GitHub issues.

## Local CLI access without sharing credentials

The workspace does not automatically have access to a team's Neon account. If local tooling needs to inspect or manage this project, authenticate the Neon CLI on the same development machine:

```powershell
npm i -g neon@latest
neon login
neon projects list
neon link
neon checkout dev
```

The browser login stores the CLI authorization locally. Do not paste the token or a database connection string into chat. If an automated integration ever needs an API key, use a project-scoped key, keep it in a local secret store or ignored environment file, and revoke it when it is no longer needed.

## Recommended project creation choices

For a team based around HKUST, choose **AWS Asia Pacific (Singapore)**. It is the closest available Neon region in the current region list; choose the same region as the API server later if the server is deployed elsewhere. Neon fixes a project's region when it is created, so choose it carefully.

Enable **Postgres** (required) and **Neon Auth / Managed Better Auth** (recommended for the marketplace's real sign-up and sign-in flow). Leave **Object Storage**, **Functions**, and **AI Gateway** disabled for the first database milestone. Enable the **Data API** later, together with Auth and RLS policies, if we decide that JavaFX should call Neon over HTTPS instead of going through a separate backend.

## Mapping to the current skeleton

| Current model/service | PostgreSQL table |
| --- | --- |
| `User` | `app_users` |
| `Listing` | `listings` and `listing_photos` |
| `Deal` | `deals` |
| favourites | `favourites` |
| `Review` | `reviews` |
| `Notification` | `notifications` |
| `DummyDataService` | repository/API layer to be implemented |

Authentication is intentionally absent from `app_users`. Use Neon Auth or a backend authentication service and link its user identifier to the profile row; do not copy the current demo plaintext passwords into Neon.

The JDBC transition adapter stores PBKDF2 password hashes in `app_credentials` only for local team development, so the current JavaFX login screens can continue to work while the Neon Auth/API flow is built. Do not use this direct desktop-to-database mode for a publicly distributed application: the final deployment should keep database credentials on a backend and use Neon Auth/Data API with explicit RLS policies.

## Run the JavaFX app with Neon persistence

After linking the workspace to the Neon `dev` branch, pull only the Postgres variables into the ignored local file:

```powershell
neon env pull --service postgres --file database/.env
.\mvnw.cmd javafx:run
```

The first run against an empty `dev` database copies the demo records into Neon. Later runs read and write the shared rows. To return to offline mode, remove or rename the local `database/.env`; no source changes are needed.

## What remains before a public deployment

The current controllers use the compatibility facade so the UI can switch between demo and Neon mode without a large screen rewrite. The next hardening step is to move the JDBC calls behind an authenticated HTTP API, connect the JavaFX login flow to Neon Auth, and add explicit RLS policies before enabling the Data API. Until then, treat direct JDBC mode as a team development prototype and keep the `database/.env` file local.
