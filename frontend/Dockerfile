FROM node:20-alpine AS builder

# Install dependencies in a separate layer for better caching
WORKDIR /app

# Copy package files first for better layer caching
COPY package.json package-lock.json ./

# Install dependencies - this layer will be cached unless package files change
RUN npm ci --silent && \
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