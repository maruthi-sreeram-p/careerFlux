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

# Installed as a template, not a config. The official entrypoint expands
# /etc/nginx/templates/*.template into /etc/nginx/conf.d/ at container start.
COPY nginx.conf /etc/nginx/templates/default.conf.template

# Substitute ONLY our own names. The default behaviour is to substitute every
# environment variable, which would replace nginx's own $host, $remote_addr and
# $uri with empty strings and produce a config that starts and misbehaves.
ENV NGINX_ENVSUBST_FILTER="^CAREERFLUX_"

# Defaults are the Compose values, so nothing about `docker compose up` changes.
#
# The backslashes are load-bearing: `\$host` and `\$scheme` reach the image as
# the literal two-word strings nginx expects to evaluate at request time. Without
# them Docker would expand them as build arguments at build time and bake in an
# empty string, and nginx would send an empty Host header to the backend.
ENV CAREERFLUX_BACKEND_ORIGIN="http://backend:8080" \
    CAREERFLUX_BACKEND_HOST="\$host" \
    CAREERFLUX_FORWARDED_PROTO="\$scheme" \
    CAREERFLUX_LISTEN_PORT="80"

# The config includes /etc/nginx/realip/*.conf so an operator can declare a
# trusted proxy without editing the image. The directory has to exist; the
# wildcard matching nothing is fine, a missing directory is not.
RUN mkdir -p /etc/nginx/realip

EXPOSE 80
