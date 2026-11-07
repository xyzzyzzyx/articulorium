# Articulorium discovery layer: the server-rendered Angular application (Angular SSR) on Node.js.
# Build context: this directory (docker build discovery-ui).

FROM node:24.21.0-bookworm-slim AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM node:24.21.0-bookworm-slim
ENV NODE_ENV=production \
    PORT=4000 \
    DISCOVERY_API_URL=http://gateway:9000/discover/api/
WORKDIR /app
# the server bundle includes its dependencies: no node_modules at run time
COPY --from=build /app/dist/discovery-ui ./dist/discovery-ui
USER node
EXPOSE 4000
HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=5 \
  CMD node -e "fetch('http://127.0.0.1:' + process.env.PORT + '/healthz').then(r => process.exit(r.ok ? 0 : 1)).catch(() => process.exit(1))"
CMD ["node", "dist/discovery-ui/server/server.mjs"]
