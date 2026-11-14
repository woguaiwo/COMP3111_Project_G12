-- Add the temporary JavaFX/JDBC credential table to an existing project.
-- New databases get this table from schema.sql directly.

begin;

create table if not exists app_credentials (
    user_id bigint primary key references app_users(id) on delete cascade,
    password_hash text not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

drop trigger if exists app_credentials_set_updated_at on app_credentials;
create trigger app_credentials_set_updated_at
before update on app_credentials
for each row execute function set_updated_at();

alter table app_credentials enable row level security;

commit;
