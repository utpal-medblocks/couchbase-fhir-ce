FROM node:20-alpine AS builder
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
WORKDIR /usr/share/nginx/html
RUN rm -rf ./*
COPY --from=builder /app/dist .

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
RUN mkdir -p /var/cache/nginx /var/run /var/log/nginx \
    && chown -R appuser:appgroup /var/cache/nginx /var/run /var/log/nginx

RUN mkdir -p /run && chown -R appuser:appgroup /run

USER appuser
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
