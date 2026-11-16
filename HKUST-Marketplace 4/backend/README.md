# Marketplace backend notes

The JavaFX application uses the public Neon Data API and Managed Neon Auth.
Neon Auth handles password verification and sessions. The Data API exposes only
the protected `public.marketplace_api(action, payload)` PostgreSQL function; the
marketplace tables and credentials are never granted to the client.

The former `marketplaceapi` Neon Function is retained as a legacy backend but is
not selected by the client. The current deployment is configured with:

```powershell
neon data-api create `
  --project-id <project-id> --branch dev --database neondb `
  --auth-provider neon_auth --db-schemas public --openapi-mode disabled
```

Apply [`database/migrations/003_data_api_marketplace.sql`](../database/migrations/003_data_api_marketplace.sql)
with the Neon SQL Editor or a trusted migration connection, then refresh the
Data API schema cache. Do not enable default table grants; only the RPC should
be callable by authenticated users.

The public Data API and Neon Auth URLs are safe to embed in the client. Set
`MARKETPLACE_MODE=jdbc` or `MARKETPLACE_MODE=demo` in an ignored local
environment file only when developing without the public API.
