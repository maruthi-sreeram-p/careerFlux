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

# The config includes /etc/nginx/realip/*.conf so an operator can declare a
# trusted proxy without editing the image. The directory has to exist; the
# wildcard matching nothing is fine, a missing directory is not.
RUN mkdir -p /etc/nginx/realip

EXPOSE 80
