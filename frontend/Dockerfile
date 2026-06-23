# Stage 1: Build the Angular application
FROM node:20-alpine AS builder

WORKDIR /app

# Copy package config and install dependencies
COPY frontend/package*.json ./
RUN npm ci

# Copy the rest of the source code and build
COPY frontend/ .
RUN npm run build -- --configuration=production --base-href /face-registry/

# Stage 2: Serve the application using Nginx
FROM nginx:alpine

# Install openssl to generate self-signed certificate
RUN apk add --no-cache openssl

# Generate self-signed certificate
RUN mkdir -p /etc/nginx/ssl && \
    openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
    -keyout /etc/nginx/ssl/nginx.key \
    -out /etc/nginx/ssl/nginx.crt \
    -subj "/C=BR/ST=SC/L=Florianopolis/O=Batistell/OU=Labs/CN=localhost"

# Copy custom Nginx configuration to support SPA routing (redirect all to index.html)
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf

# Copy build artifacts to Nginx html directory
COPY --from=builder /app/dist/frontend/browser /usr/share/nginx/html
COPY --from=builder /app/dist/frontend/browser /usr/share/nginx/html/face-registry

EXPOSE 80 443

CMD ["nginx", "-g", "daemon off;"]
