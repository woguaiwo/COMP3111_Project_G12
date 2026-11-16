# Neon database exploration

The JavaFX client uses `DummyDataService` as a compatibility facade. In the shared deployment it calls Neon Data API and Managed Neon Auth; this folder defines the PostgreSQL schema and RPC used by that path. Set `MARKETPLACE_MODE=jdbc` for a local JDBC run or `MARKETPLACE_MODE=demo` for an offline UI preview.

The public path is `HttpMarketplaceDataService -> Neon Auth + Data API RPC -> Neon PostgreSQL`. A local `DATABASE_URL` selects the JDBC adapter only when `MARKETPLACE_MODE=jdbc` is explicitly set. The original in-memory demo is selected with `MARKETPLACE_MODE=demo`.

## Recommended shape

```text
JavaFX client → HTTPS API or trusted Java backend → Neon PostgreSQL
```

Do not put a Neon owner password or an unrestricted database URL in a released desktop application. A quick JDBC prototype can read `DATABASE_URL` from the local environment, but the final application should keep the database connection on a server or a Neon Function and expose only authenticated operations to JavaFX.

Neon Data API is enabled for the `public` schema with the Neon Auth provider and OpenAPI disabled. The schema keeps RLS enabled and grants the Data API only the `marketplace_api` SECURITY DEFINER function. That function reads the authenticated JWT subject from the request claims, maps it to `app_users.auth_user_id`, and returns only DTO-shaped JSON.

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

Enable **Postgres** (required), **Data API**, and **Better Auth / Neon Auth**. The current client does not need Object Storage, AI Gateway, Realtime, or Functions.

## Mapping to the current skeleton

| Current model/service | PostgreSQL table |
| --- | --- |
| `User` | `app_users` |
| `Listing` | `listings` and `listing_photos` |
| `Deal` | `deals` |
| favourites | `favourites` |
| `Review` | `reviews` |
| `Notification` | `notifications` |
| `DummyDataService` | compatibility facade over HTTP/JDBC/in-memory modes |

Neon Auth owns password hashes and sessions. The migration adds `app_users.auth_user_id` and the RPC maps each Auth account to the marketplace profile. Application users never need Neon project membership.

The JDBC adapter still stores PBKDF2 hashes in `app_credentials` for explicit local team development. Do not distribute its connection string. Public clients use Neon Auth and the Data API JWT instead.

## Run the JavaFX app with Neon persistence

After linking the workspace to the Neon `dev` branch, pull only the Postgres variables into the ignored local file:

```powershell
neon env pull --service postgres --file database/.env
.\mvnw.cmd javafx:run
```

The first run against an empty `dev` database copies the demo records into Neon. Later runs read and write the shared rows. For an offline UI run, set `MARKETPLACE_MODE=demo` in an ignored environment file or IDE run configuration; no source changes are needed.

## Public-user deployment

Apply `migrations/003_data_api_marketplace.sql`, refresh the Data API schema, and distribute the JavaFX client. End users register and log in inside the application; they do not need a Neon Console account or an invitation to the Neon organization. Keep `database/.env` and `backend/.env.local` local and ignored.
