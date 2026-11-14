# Marketplace API

The JavaFX application talks to the `marketplaceapi` Neon Function. The
function owns the Neon connection string, password verification, HMAC JWT
signing key, and revocable session table. Clients only receive the public
function URL and a short-lived access token.

The function is deployed to the Neon `dev` branch with:

```powershell
neon functions deploy marketplaceapi `
  --project-id <project-id> --branch dev `
  --src backend/functions/marketplace-api --runtime nodejs24 --wait `
  --env JWT_SECRET=<random-secret>
```

Neon injects `DATABASE_URL` into the function. Generate a fresh `JWT_SECRET`
for every deployed environment and keep it out of Git. Apply
`database/migrations/002_app_sessions.sql` before deploying the function.

For local JavaFX runs, use the deployed URL placed in `NeonConfig`. Set
`MARKETPLACE_MODE=jdbc` or `MARKETPLACE_MODE=demo` in an ignored local
environment file only when developing without the public API.
