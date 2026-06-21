# Multi-stage build: Node build → nginx static serve.
FROM node:20-alpine AS build
WORKDIR /app
COPY package.json ./
COPY pnpm-lock.yaml* package-lock.json* ./
RUN if [ -f pnpm-lock.yaml ]; then \
      corepack enable && pnpm install --frozen-lockfile; \
    else \
      npm install; \
    fi
COPY . .
RUN if [ -f pnpm-lock.yaml ]; then pnpm build; else npm run build; fi

FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
