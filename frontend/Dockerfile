FROM node:22-alpine AS builder

# Install dependencies in a separate layer for better caching
WORKDIR /app
RUN chmod -R 777 /app

# Copy package files first for better layer caching
COPY package.json package-lock.json ./

# Accept NODE_OPTIONS as build arg for memory tuning
ARG NODE_OPTIONS=--max-old-space-size=4096
ENV NODE_OPTIONS=$NODE_OPTIONS

# Install dependencies - this layer will be cached unless package files change
# Use single socket and prefer offline to avoid concurrency issues in QEMU
RUN npm ci --maxsockets=1 --prefer-offline --no-audit 2>&1 || \
    (echo "npm ci failed, retrying..." && npm ci --maxsockets=1 --no-audit) && \
    npm cache clean --force

# Copy source code - this layer changes most often
COPY . .

# Build the application
RUN npm run build

FROM nginx:1.27-alpine

# Copy custom nginx configuration first
COPY nginx-default.conf /etc/nginx/conf.d/default.conf

# Set up nginx directories and user in single layer
RUN addgroup -S appgroup && adduser -S appuser -G appgroup && \
    mkdir -p /var/cache/nginx /var/run /var/log/nginx /run && \
    chown -R appuser:appgroup /var/cache/nginx /var/run /var/log/nginx /run

WORKDIR /usr/share/nginx/html
RUN rm -rf ./*

# Copy built assets from builder stage
COPY --from=builder /app/dist .

USER appuser
EXPOSE 80

# Set environment variable to reduce nginx startup verbosity
ENV NGINX_ENTRYPOINT_QUIET_LOGS=1

CMD ["nginx", "-g", "daemon off;"]