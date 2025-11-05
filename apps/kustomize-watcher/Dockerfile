FROM golang:1.22-alpine3.20 AS builder

ENV CGO_ENABLED=0
ENV GOOS=linux
ENV GOARCH=amd64

RUN mkdir /build
ADD . /build
WORKDIR /build
RUN go build -a -o kustomize-watcher .

# generate clean, final image for end users
FROM alpine:3.11.3
COPY --from=builder /build/kustomize-watcher .

# executable
ENTRYPOINT  [ "./kustomize-watcher" ]