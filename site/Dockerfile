# ztv.mccoy88f.link: Zephyr TV's site. Built in three steps, so the image holds only the server Next.js traces as
# needed, the static files and the database migrations; it runs as an unprivileged user.

FROM node:22-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

FROM node:22-alpine AS build
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
ENV NEXT_TELEMETRY_DISABLED=1
RUN npm run check:i18n && npm run build

FROM node:22-alpine AS run
WORKDIR /app
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 PORT=3000 HOSTNAME=0.0.0.0
RUN addgroup -S ztv && adduser -S ztv -G ztv
COPY --from=build --chown=ztv:ztv /app/.next/standalone ./
COPY --from=build --chown=ztv:ztv /app/.next/static ./.next/static
COPY --from=build --chown=ztv:ztv /app/public ./public
COPY --from=build --chown=ztv:ztv /app/drizzle ./drizzle
USER ztv
EXPOSE 3000
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD wget -qO- http://127.0.0.1:3000/healthz || exit 1
CMD ["node", "server.js"]
