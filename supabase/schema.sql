-- =====================================================================
-- NYC Eats — Supabase / Postgres schema
-- Run this once in Supabase: Dashboard → SQL Editor → New query → Run.
-- Safe to re-run (uses IF NOT EXISTS / OR REPLACE).
-- =====================================================================

create extension if not exists pgcrypto;  -- gen_random_uuid()

-- ---------------------------------------------------------------------
-- restaurants: one row per place. Visits (ratings + comments) hang off it,
-- so going back to the same spot adds history instead of a duplicate.
-- ---------------------------------------------------------------------
create table if not exists public.restaurants (
    id          uuid primary key default gen_random_uuid(),
    name        text not null check (char_length(name) between 1 and 200),
    address     text,
    borough     text check (borough in ('Manhattan','Brooklyn','Queens','Bronx','Staten Island')),
    cuisine     text,
    latitude    double precision check (latitude  between -90  and 90),
    longitude   double precision check (longitude between -180 and 180),
    osm_id      text unique,                 -- OpenStreetMap id when picked from "nearby", prevents duplicates
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);

create index if not exists restaurants_name_idx    on public.restaurants (lower(name));
create index if not exists restaurants_borough_idx on public.restaurants (borough);

-- ---------------------------------------------------------------------
-- visits: each time you went. 1–5 stars, optional comment, date of visit.
-- ---------------------------------------------------------------------
create table if not exists public.visits (
    id             uuid primary key default gen_random_uuid(),
    restaurant_id  uuid not null references public.restaurants(id) on delete cascade,
    rating         integer not null check (rating between 1 and 5),
    comment        text check (char_length(comment) <= 2000),
    visited_on     date not null default current_date,
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now()
);

create index if not exists visits_restaurant_idx on public.visits (restaurant_id);
create index if not exists visits_visited_on_idx on public.visits (visited_on desc);

-- ---------------------------------------------------------------------
-- keep updated_at fresh
-- ---------------------------------------------------------------------
create or replace function public.set_updated_at()
returns trigger language plpgsql as $$
begin
    new.updated_at = now();
    return new;
end $$;

drop trigger if exists restaurants_set_updated_at on public.restaurants;
create trigger restaurants_set_updated_at
    before update on public.restaurants
    for each row execute function public.set_updated_at();

drop trigger if exists visits_set_updated_at on public.visits;
create trigger visits_set_updated_at
    before update on public.visits
    for each row execute function public.set_updated_at();

-- ---------------------------------------------------------------------
-- Security: the Spring Boot API connects as the `postgres` role over JDBC,
-- which bypasses RLS. Enabling RLS with NO policies means Supabase's public
-- REST/anon key cannot read or write these tables directly — all traffic
-- must go through the API.
-- ---------------------------------------------------------------------
alter table public.restaurants enable row level security;
alter table public.visits      enable row level security;

-- Optional: a few sample rows so the app isn't empty on first load.
-- Delete this block if you want to start clean.
-- insert into public.restaurants (name, address, borough, cuisine, latitude, longitude)
-- values ('Joe''s Pizza', '7 Carmine St', 'Manhattan', 'pizza', 40.730566, -74.002164);
