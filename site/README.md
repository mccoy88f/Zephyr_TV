# ztv.mccoy88f.link

Zephyr TV's site: the project's presentation, downloads and licences, in Zephyr TV's nine languages; then accounts,
TV sync, Trakt, shared links and the extensions' catalogue (see the plan's phases below). Next.js and PostgreSQL, in
Docker Compose.

## Run it

```sh
cp .env.example .env      # set POSTGRES_PASSWORD and SUPERADMIN_PASSWORD
docker compose up --build # the site on the port Docker gives the app service (see `docker compose ps`)
```

Without Docker, for development: `npm install`, a PostgreSQL in `DATABASE_URL`, then `npm run dev`.

As it starts, the server brings the database up to date (`drizzle/`) and makes the superadmin from
`SUPERADMIN_EMAIL` and `SUPERADMIN_PASSWORD`: created the first time, its password set again when the variable
changes. Sign in at `/<language>/login`; the panel is at `/<language>/admin`.

## Deploy on Coolify

1. New resource → **Docker Compose**, from this repository, base directory `/site`, file `docker-compose.yml`.
2. Environment variables: `POSTGRES_PASSWORD`, `SUPERADMIN_PASSWORD` (required); the others as in `.env.example`.
3. Domain: `https://ztv.mccoy88f.link` on the **app** service, port 3000. Coolify's proxy gives it HTTPS.
4. Turn on the backups of the **db** service's volume (`db-data`).

The app's health check is `GET /healthz` (200 when the database answers).

## Layout

| Where | What |
|---|---|
| `src/app/[locale]/` | The pages, one folder each, under their language |
| `src/app/healthz/` | The health check |
| `src/i18n/` | The languages (next-intl): `messages/<language>.json`, every one with English's keys (`npm run check:i18n`) |
| `src/db/` | The database: schema (Drizzle), connection. `npm run db:generate` writes a migration into `drizzle/` |
| `src/server/` | Server-only: start-up, passwords (Argon2id), sessions, rate limits, GitHub releases |
| `content/credits.json` | The projects, libraries, services and media Zephyr TV uses, with their licences |
| `content/site-licenses.json` | This site's libraries and licences, written by `scripts/site-licenses.mjs` at build time |

## Phases

| Phase | What |
|---|---|
| F0 | This: Docker Compose, PostgreSQL, the languages, presentation, downloads, licences, superadmin sign-in |
| F1 | Shared links with each title's poster as their preview (`/t/<type>/<id>`) |
| F2 | Trakt's sign-in moved here |
| F3 | Accounts (email and password, email verified), Zephyr TV users and avatars, TVs linked with a code, the extensions' catalogue (published after review) |
| F4 | Sync between TVs (add-ons and keys end-to-end encrypted) |
| F5 | Privacy policy, notices from the admin, launch |

Licence: GPL-3.0, as Zephyr TV.
