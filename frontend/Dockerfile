FROM golang:1.26.10-alpine@sha256:5cb67f19c5e7994dcb80c074f7cfca8aeeada45bbdca035f73df269cefdcce61 AS caddy-build
WORKDIR /src
RUN go mod init mailguard-proxy \
    && go get github.com/caddyserver/caddy/v2/cmd/caddy@v2.11.7 golang.org/x/net@v0.60.0 \
    && CGO_ENABLED=0 go build -trimpath -o /caddy github.com/caddyserver/caddy/v2/cmd/caddy

FROM node:24-alpine AS build
WORKDIR /src
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM alpine:3.24
RUN apk add --no-cache ca-certificates
COPY --from=caddy-build /caddy /usr/bin/caddy
COPY deploy/proxy/Caddyfile /etc/caddy/Caddyfile
COPY --from=build /src/dist /srv
WORKDIR /srv
USER 10001:10001
EXPOSE 8080 8443
ENTRYPOINT ["/usr/bin/caddy"]
CMD ["run", "--config", "/etc/caddy/Caddyfile", "--adapter", "caddyfile"]
