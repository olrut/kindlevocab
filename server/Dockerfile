# Build context is the repository root (see docker-compose.yml): the image also builds web/.

FROM node:22-bookworm-slim AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npx vite build --outDir /out --emptyOutDir

FROM node:22-bookworm-slim AS deps
# better-sqlite3 compiles from source when no prebuilt binary matches. Only this stage has the tools.
RUN apt-get update && apt-get install -y --no-install-recommends python3 make g++ && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY server/package.json server/package-lock.json ./
RUN npm ci --omit=dev

FROM node:22-bookworm-slim
ENV NODE_ENV=production DATA_DIR=/app/data PORT=3000
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY server/package.json server/tsconfig.json ./
COPY server/drizzle ./drizzle
COPY server/src ./src
COPY --from=web /out ./public
RUN mkdir -p /app/data /app/vault && chown node:node /app/data /app/vault
USER node
EXPOSE 3000
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s \
  CMD node -e "fetch('http://localhost:'+(process.env.PORT||3000)+'/api/health').then(r=>process.exit(r.ok?0:1),()=>process.exit(1))"
# One process (no tsx CLI wrapper), so `docker stop`'s SIGTERM reaches the server.
CMD ["node", "--import", "tsx", "src/index.ts"]
