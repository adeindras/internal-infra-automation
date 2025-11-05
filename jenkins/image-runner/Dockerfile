FROM 268237257165.dkr.ecr.us-east-2.amazonaws.com/jenkinscd-agent:v1.0.0
RUN apk update
RUN apk add --no-cache python3
RUN mkdir -p .tgenv .tfenv .hcledit && \
    curl -sSL https://github.com/tgenv/tgenv/archive/refs/tags/v1.2.1.tar.gz | tar xz --strip 1 -C .tgenv && \
    curl -sSL https://github.com/tfutils/tfenv/archive/refs/tags/v3.0.0.tar.gz | tar xz --strip 1 -C .tfenv && \
    mkdir .tfenv/versions
RUN curl -sSL https://github.com/minamijoyo/hcledit/releases/download/v0.2.11/hcledit_0.2.11_linux_amd64.tar.gz > hcledit.tar.gz && \
    tar -xvf hcledit.tar.gz -C .hcledit/ && \
    rm -rf hcledit.tar.gz
ENV PATH="/.tgenv/bin:/.tfenv/bin:/.hcledit:${PATH}"
ENV TGENV_AUTO_INSTALL=true
ENV TFENV_AUTO_INSTALL=true
COPY ./tfversion.list ./tfversion.list
COPY ./tfinstall.sh ./tfinstall.sh
RUN ./tfinstall.sh
RUN tfenv install latest; tfenv use latest
COPY ./tgversion.list ./tgversion.list
COPY ./tginstall.sh ./tginstall.sh
RUN ./tginstall.sh
RUN tgenv install latest; tgenv use latest



