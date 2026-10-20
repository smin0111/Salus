# syntax=docker/dockerfile:1.7

# 사용자용 웹 화면(Expo web)을 정적 파일로 만들어 nginx로 내보냅니다.
# src/secrets.js(gitignored)가 있어야 빌드됩니다. 웹 번들에 들어가는 값은 브라우저에 공개되는 클라이언트 ID뿐이어야 합니다.
FROM node:20-alpine AS build

WORKDIR /app
COPY package*.json ./
RUN --mount=type=cache,target=/root/.npm \
    npm ci
COPY . .

# API를 같은 주소의 /api로 부르게 합니다(nginx가 백엔드로 넘김).
ARG EXPO_PUBLIC_API_BASE_URL=/api
ENV EXPO_PUBLIC_API_BASE_URL=$EXPO_PUBLIC_API_BASE_URL
RUN npx expo export --platform web --output-dir dist

FROM nginxinc/nginx-unprivileged:1.27-alpine

COPY --chown=nginx:nginx deploy/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build --chown=nginx:nginx /app/dist /usr/share/nginx/html

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8080/ >/dev/null || exit 1
