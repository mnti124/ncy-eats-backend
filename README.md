# NYC Eats — API (Spring Boot)

REST API for the NYC Eats restaurant tracker. Java 21, Spring Boot 3.5, Postgres (Supabase in production).
The web app (`nyc-eats-web`) talks to this API.

## Requirements

- **JDK 21** — `brew install openjdk@21` (or Temurin from adoptium.net). Check with `java -version`.
- **Maven 3.9+** — `brew install maven`. Check with `mvn -v`.
- **Docker** (optional) — only for the local test database below.

---

## Run locally

You need a Postgres database. Pick one:

### Option A — local Postgres in Docker (quickest, no accounts)

```bash
docker compose up -d          # starts Postgres on :5432 and applies supabase/schema.sql
mvn spring-boot:run           # API on http://localhost:8080
```

No env vars needed — the defaults in `application.yml` point at this database.
Wipe the data any time with `docker compose down -v`.

### Option B — your Supabase database

1. Create a project at supabase.com (choose an **East US** region).
2. **SQL Editor → New query** → paste `supabase/schema.sql` → **Run**.
3. Click **Connect** (top bar) → **Session pooler**. You'll see something like
   `postgresql://postgres.abcdefgh:[YOUR-PASSWORD]@aws-0-us-east-1.pooler.supabase.com:5432/postgres`
4. Fill in `.env`:
   ```bash
   cp .env.example .env
   ```
   ```
   DATABASE_URL="jdbc:postgresql://aws-0-us-east-1.pooler.supabase.com:5432/postgres?sslmode=require"
   DATABASE_USERNAME=postgres.abcdefgh
   DATABASE_PASSWORD=your-db-password
   ```
   Use the **session pooler**, not the direct connection — the direct host is IPv6-only and often unreachable.
5. Run:
   ```bash
   set -a; source .env; set +a
   mvn spring-boot:run
   ```

### Check it works

```bash
curl localhost:8080/actuator/health          # {"status":"UP"}
curl localhost:8080/api/restaurants          # []

# add a place with a first visit
curl -X POST localhost:8080/api/restaurants -H 'Content-Type: application/json' \
  -d '{"name":"Joe'\''s Pizza","cuisine":"pizza","address":"7 Carmine St","borough":"Manhattan",
       "latitude":40.7306,"longitude":-74.0022,"visit":{"rating":5,"comment":"Classic slice"}}'

# restaurants near Washington Square Park (calls OpenStreetMap)
curl "localhost:8080/api/places/nearby?lat=40.7308&lng=-73.9973"
```

Run the unit tests with `mvn test`.

---

## Deploy (Render)

Vercel can't host a Java server, so the API goes on Render (free tier works). Railway or Fly.io also work with the same Dockerfile.

1. Push this folder to its own GitHub repo.
2. Render → **New → Blueprint** → select the repo. It reads `render.yaml` and builds the `Dockerfile`.
3. When prompted, fill in `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` (the Supabase values from Option B). Leave `EDIT_KEY` blank.
4. Once live, open `https://<your-service>.onrender.com/actuator/health` → `{"status":"UP"}`.
5. Copy that base URL (`https://<your-service>.onrender.com`) — the web app needs it as `API_URL`.

> **Free tier note:** Render sleeps the service after ~15 minutes idle. The next request takes 30–60 s
> while it wakes; the web app shows a "waking up" message. The paid Starter plan stays awake.

---

## Configuration

| Env var | Default | Purpose |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/postgres` | JDBC URL |
| `DATABASE_USERNAME` | `postgres` | DB user (`postgres.<ref>` on Supabase pooler) |
| `DATABASE_PASSWORD` | `postgres` | DB password |
| `PORT` | `8080` | HTTP port (Render sets this) |
| `ALLOWED_ORIGINS` | `http://localhost:3000,https://*.vercel.app` | CORS (only matters if a browser calls the API directly) |
| `EDIT_KEY` | *(blank)* | If set, POST/PATCH/DELETE require header `X-Edit-Key` with this value |
| `OSM_USER_AGENT` | `nyc-eats/1.0 …` | Identifies you to OpenStreetMap (used for search/geocoding) — add your email |
| `GEOAPIFY_API_KEY` | *(blank)* | Free key from [geoapify.com](https://www.geoapify.com/) — powers "nearby restaurants" |
| `DB_POOL_SIZE` | `5` | Connection pool size (keep small on Supabase free tier) |

## Database

`supabase/schema.sql` creates two tables:

- **restaurants** — `id`, `name`, `address`, `borough` (one of the 5), `cuisine`, `latitude`, `longitude`, `osm_id` (unique; prevents duplicates when picking from "near me"), timestamps
- **visits** — `id`, `restaurant_id` → restaurants (cascade delete), `rating` 1–5, `comment`, `visited_on`, timestamps

Row-level security is turned on with no policies, so Supabase's public API key can't read or write
these tables — only this API (connecting as `postgres`) can.

## API

| Method | Path | Body / notes |
|---|---|---|
| GET | `/api/restaurants?q=&borough=&sort=recent\|rating\|visits\|name` | All places with visits and averages |
| GET | `/api/restaurants/{id}` | One place with full history |
| POST | `/api/restaurants` | `{name, cuisine?, address?, borough?, latitude?, longitude?, osmId?, visit:{rating, comment?, visitedOn?}}` — if `osmId` already exists, adds a visit (`existing: true`) |
| PATCH | `/api/restaurants/{id}` | `{name?, cuisine?, address?, borough?}`; `""` clears a field |
| DELETE | `/api/restaurants/{id}` | Removes place and visits |
| POST | `/api/restaurants/{id}/visits` | `{rating, comment?, visitedOn?}` |
| PATCH | `/api/visits/{id}` | `{rating?, comment?, visitedOn?}` |
| DELETE | `/api/visits/{id}` | 204 if it was the last visit (place removed too) |
| GET | `/api/stats` | Totals, average, per-borough counts, top cuisine |
| GET | `/api/places/nearby?lat=&lng=&radius=` | Food places nearby (OpenStreetMap), nearest first |
| GET | `/api/places/search?q=&lat=&lng=` | Name search limited to NYC |

## Project layout

```
src/main/java/com/nyceats/
├── restaurant/   entities, repositories, service, controller, DTOs
├── places/       OpenStreetMap lookups (Overpass + Nominatim)
├── config/       CORS and the optional edit-key guard
└── web/          error handling
```
