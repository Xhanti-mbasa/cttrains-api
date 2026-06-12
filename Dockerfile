# ── Stage 1: Install dependencies ───────────────────────────────────────────
FROM node:22-alpine AS builder

WORKDIR /app

COPY package*.json ./
RUN npm ci --omit=dev

# ── Stage 2: Runtime image ───────────────────────────────────────────────────
FROM node:22-alpine

# dumb-init for proper signal handling
RUN apk add --no-cache dumb-init

WORKDIR /app

# Non-root user for security
RUN addgroup -S cttrains && adduser -S cttrains -G cttrains

COPY --from=builder /app/node_modules ./node_modules
COPY . .

# Snapshot directory must be writable
RUN mkdir -p .timetable-snapshots && chown -R cttrains:cttrains /app

USER cttrains

EXPOSE 3000

HEALTHCHECK --interval=30s --timeout=10s --start-period=15s --retries=3 \
  CMD wget -qO- http://localhost:3000/health || exit 1

ENTRYPOINT ["dumb-init", "--"]
CMD ["node", "src/index.js"]
