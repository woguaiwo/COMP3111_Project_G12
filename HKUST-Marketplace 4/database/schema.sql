-- HKUST Marketplace - Neon/PostgreSQL starter schema
--
-- User profiles do not contain a password column. Password hashes and
-- revocable JWT sessions live in separate tables owned by the backend API.

begin;

create type app_user_role as enum ('BUYER', 'SELLER', 'ADMIN');
create type listing_type as enum ('SELL', 'LEND', 'GIVEAWAY');
create type listing_status as enum ('PENDING_REVIEW', 'LIVE', 'SOLD', 'REJECTED', 'CLOSED');
create type deal_status as enum ('PENDING', 'ACCEPTED', 'COMPLETED', 'RETURNED', 'CANCELLED');

create table app_users (
    id bigint generated always as identity primary key,
    username varchar(50) not null unique,
    full_name varchar(120) not null,
    role app_user_role not null,
    residence_hall varchar(120),
    bio text,
    preferred_meetup varchar(120),
    is_active boolean not null default true,
    average_rating numeric(2, 1) not null default 0
        check (average_rating >= 0 and average_rating <= 5),
    rating_count integer not null default 0
        check (rating_count >= 0),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

-- Credential storage for the username/password marketplace API. The table is
-- never queried directly by the JavaFX client.
create table app_credentials (
    user_id bigint primary key references app_users(id) on delete cascade,
    password_hash text not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table app_sessions (
    jti uuid primary key,
    user_id bigint not null references app_users(id) on delete cascade,
    expires_at timestamptz not null,
    revoked_at timestamptz,
    created_at timestamptz not null default now()
);

create table listings (
    id bigint generated always as identity primary key,
    seller_id bigint not null references app_users(id),
    title varchar(160) not null,
    description text,
    category varchar(80) not null,
    type listing_type not null,
    status listing_status not null default 'PENDING_REVIEW',
    item_condition varchar(40) not null,
    price numeric(10, 2),
    lending_days integer,
    meetup_location varchar(160) not null,
    reviewed_at timestamptz,
    rejection_reason text,
    view_count integer not null default 0 check (view_count >= 0),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    check (
        (type = 'SELL' and price is not null and price >= 0)
        or (type <> 'SELL' and price is null)
    ),
    check (
        (type = 'LEND' and lending_days is not null and lending_days > 0)
        or (type <> 'LEND' and lending_days is null)
    )
);

create table listing_photos (
    id bigint generated always as identity primary key,
    listing_id bigint not null references listings(id) on delete cascade,
    storage_path text not null,
    sort_order integer not null default 0 check (sort_order >= 0),
    created_at timestamptz not null default now()
);

create table deals (
    id bigint generated always as identity primary key,
    listing_id bigint not null references listings(id),
    buyer_id bigint not null references app_users(id),
    seller_id bigint not null references app_users(id),
    deal_type listing_type not null,
    status deal_status not null default 'PENDING',
    offer_price numeric(10, 2) check (offer_price is null or offer_price >= 0),
    borrow_start date,
    borrow_end date,
    rated boolean not null default false,
    created_at timestamptz not null default now(),
    completed_at timestamptz,
    check (borrow_end is null or borrow_start is null or borrow_end >= borrow_start)
);

create table favourites (
    buyer_id bigint not null references app_users(id) on delete cascade,
    listing_id bigint not null references listings(id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (buyer_id, listing_id)
);

create table reviews (
    id bigint generated always as identity primary key,
    listing_id bigint not null references listings(id),
    seller_id bigint not null references app_users(id),
    buyer_id bigint not null references app_users(id),
    rating integer not null check (rating between 1 and 5),
    comment text not null,
    seller_reply text,
    is_hidden boolean not null default false,
    created_at timestamptz not null default now()
);

create table notifications (
    id bigint generated always as identity primary key,
    recipient_id bigint not null references app_users(id) on delete cascade,
    title varchar(160) not null,
    message text not null,
    category varchar(40) not null,
    is_read boolean not null default false,
    created_at timestamptz not null default now()
);

create index listings_status_created_idx on listings (status, created_at desc);
create index listings_seller_idx on listings (seller_id);
create index deals_buyer_idx on deals (buyer_id, created_at desc);
create index deals_seller_idx on deals (seller_id, created_at desc);
create index reviews_seller_idx on reviews (seller_id, created_at desc);
create index notifications_recipient_idx on notifications (recipient_id, is_read, created_at desc);
create index app_sessions_user_expiry_idx on app_sessions (user_id, expires_at);
create index app_sessions_active_expiry_idx on app_sessions (expires_at) where revoked_at is null;

create or replace function set_updated_at()
returns trigger
language plpgsql
as $$
begin
    new.updated_at = now();
    return new;
end;
$$;

create trigger app_users_set_updated_at
before update on app_users
for each row execute function set_updated_at();

create trigger app_credentials_set_updated_at
before update on app_credentials
for each row execute function set_updated_at();

create trigger listings_set_updated_at
before update on listings
for each row execute function set_updated_at();

-- Keep the database closed until the authentication/API layer has policies.
-- A trusted backend connection can still use these tables. If Neon Data API
-- is enabled later, add explicit JWT/RLS policies before exposing any table.
alter table app_users enable row level security;
alter table app_credentials enable row level security;
alter table app_sessions enable row level security;
alter table listings enable row level security;
alter table listing_photos enable row level security;
alter table deals enable row level security;
alter table favourites enable row level security;
alter table reviews enable row level security;
alter table notifications enable row level security;

commit;
