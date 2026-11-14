-- Server-side session records for the marketplace JWT API.
-- JWTs are signed and short-lived, while this table makes logout and
-- administrative revocation effective immediately.

begin;

create table if not exists app_sessions (
    jti uuid primary key,
    user_id bigint not null references app_users(id) on delete cascade,
    expires_at timestamptz not null,
    revoked_at timestamptz,
    created_at timestamptz not null default now()
);

create index if not exists app_sessions_user_expiry_idx
    on app_sessions (user_id, expires_at);

create index if not exists app_sessions_active_expiry_idx
    on app_sessions (expires_at)
    where revoked_at is null;

alter table app_sessions enable row level security;

commit;
