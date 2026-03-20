# Build stage
FROM node:20-alpine AS build
WORKDIR /app
COPY frontend/package*.json ./
RUN rm -f package-lock.json && npm install --legacy-peer-deps
COPY frontend/ .
ARG VITE_SHORT_LINK_BASE_URL
ENV VITE_SHORT_LINK_BASE_URL=$VITE_SHORT_LINK_BASE_URL
RUN npm run build

# Production stage
FROM nginx:stable-alpine
COPY --from=build /app/dist /usr/share/nginx/html
# Copy custom nginx config if needed (e.g. for SPA routing)
# COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
