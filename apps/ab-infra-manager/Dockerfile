ARG GOVERSION=1.23.0

############################################
### BUILDER
############################################
FROM golang:${GOVERSION}-alpine AS builder
RUN apk add --no-cache git
WORKDIR /src
COPY ./go.mod ./go.sum ./
RUN go mod download
COPY ./ ./

# Build the executable
RUN CGO_ENABLED=0 go build -v \
  -installsuffix 'static' \
	-o /app


############################################
### FINAL IMAGE
############################################
FROM gcr.io/distroless/static AS final
USER nonroot:nonroot
COPY --from=builder --chown=nonroot:nonroot /app /app
ENTRYPOINT ["/app"]