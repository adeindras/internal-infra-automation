FROM ghcr.io/yannh/kubeconform:latest-alpine AS kubeconform

FROM golang:1.21.4-alpine AS bitbucket-downloader
WORKDIR /app
COPY ./bitbucket-downloader/go.mod ./bitbucket-downloader/go.sum ./
RUN go mod download
COPY ./bitbucket-downloader ./
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -o bitbucket-downloader

FROM alpine:3.20.3
RUN echo 'http://dl-cdn.alpinelinux.org/alpine/v3.9/main' >> /etc/apk/repositories
RUN echo 'http://dl-cdn.alpinelinux.org/alpine/v3.9/community' >> /etc/apk/repositories
RUN apk add --update --no-cache mongodb-tools aws-cli jq yq curl openssh git bash gettext python3 py3-pip mongodb yaml-cpp=0.6.2-r2 postgresql-client

RUN curl --proto '=https' --tlsv1.2 -fsSL https://get.opentofu.org/install-opentofu.sh -o install-opentofu.sh && \
    chmod +x install-opentofu.sh && \
    ./install-opentofu.sh --install-method apk && \
    rm -f install-opentofu.sh

ENV REDIS_DUMP_GO_VERSION=v0.8.2
RUN curl -LO https://github.com/yannh/redis-dump-go/releases/download/${REDIS_DUMP_GO_VERSION}/redis-dump-go-linux-amd64.tar.gz && \
    tar -zxvf redis-dump-go-linux-amd64.tar.gz && \
    mv redis-dump-go /usr/local/bin

ENV KUBECTL_VERSION=1.31.0
RUN curl -LO https://dl.k8s.io/release/v${KUBECTL_VERSION}/bin/linux/amd64/kubectl \
   && chmod +x kubectl \
   && mv kubectl /usr/local/bin \
   && mkdir -p ~/.kube \
   && touch ~/.kube/config \
   && chmod 777 ~/.kube/config \
   && kubectl version --client
RUN curl -sSLO "https://tuf.kubedog.werf.io/targets/releases/0.9.12/linux-amd64/bin/kubedog" \
   && chmod +x kubedog && mv kubedog /usr/local/bin
COPY --from=kubeconform /kubeconform /bin/kubeconform
COPY --from=bitbucket-downloader /app/bitbucket-downloader /bin/bitbucket-downloader
COPY ./aws-config /root/.aws/config.template


COPY --from=redis:7-alpine3.20 /usr/local/bin/redis-cli /usr/local/bin/redis-cli

RUN mkdir -p .tgenv .tfenv .hcledit && \
    curl -sSL https://github.com/tgenv/tgenv/archive/refs/tags/v1.2.1.tar.gz | tar xz --strip 1 -C .tgenv && \
    curl -sSL https://github.com/tfutils/tfenv/archive/refs/tags/v3.0.0.tar.gz | tar xz --strip 1 -C .tfenv && \
    mkdir .tfenv/versions
RUN curl -sSL https://github.com/minamijoyo/hcledit/releases/download/v0.2.11/hcledit_0.2.11_linux_amd64.tar.gz > hcledit.tar.gz && \
    tar -xvf hcledit.tar.gz -C .hcledit/ && \
    rm -rf hcledit.tar.gz
ENV PATH="/.tgenv/bin:/.tfenv/bin:/.hcledit:${PATH}"
RUN curl -s https://fluxcd.io/install.sh | FLUX_VERSION=0.41.2 bash
ENV TGENV_AUTO_INSTALL=true
ENV TFENV_AUTO_INSTALL=true
RUN tfenv install latest; tfenv use latest
COPY ./tgversion.list ./tgversion.list
COPY ./tginstall.sh ./tginstall.sh
RUN ./tginstall.sh
RUN tgenv install latest; tgenv use latest
RUN git config --global user.email "" && \
    git config --global user.name "engineering-platform-pause-resume"



