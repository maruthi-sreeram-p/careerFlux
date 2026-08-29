# =============================================================================
# CareerFlux frontend
#
# Built to static assets, then served by nginx. No Node in the final image.
# =============================================================================

FROM node:22-alpine AS build
WORKDIR /build

COPY package.json package-lock.json* ./
RUN npm ci --no-audit --no-fund

COPY . .
RUN npm run build


FROM nginx:1.27-alpine AS runtime

COPY --from=build /build/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
