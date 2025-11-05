FROM golang:1.24 AS bitbucket-downloader
WORKDIR /app
COPY ./bitbucket-downloader/go.mod ./bitbucket-downloader/go.sum ./
RUN go mod download
COPY ./bitbucket-downloader ./
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -o bitbucket-downloader

FROM golang:1.24 AS builder
RUN go install go.k6.io/xk6/cmd/xk6@latest
RUN xk6 build --with github.com/avitalique/xk6-file@latest

FROM alpine:latest
COPY --from=builder /go/k6 /usr/bin/k6
COPY --from=bitbucket-downloader /app/bitbucket-downloader /bin/bitbucket-downloader
RUN apk update && apk add --no-cache aws-cli jq yq curl openssh git bash gettext python3 py3-pip util-linux

ENTRYPOINT ["k6"]
