FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive
ENV TZ=Etc/UTC

RUN apt-get update && apt-get install curl wget python3 python3-pip python3-venv python3-virtualenv tzdata unzip git moreutils gettext gettext-base iputils-ping dnsutils netcat-openbsd socat -y && apt-get clean
RUN wget -q https://github.com/turbot/steampipe/releases/latest/download/steampipe_linux_amd64.tar.gz -O \
    steampipe.tar.gz && tar -xzvf steampipe.tar.gz && mv steampipe /usr/local/bin/steampipe && rm steampipe.tar.gz

RUN wget -q https://github.com/mikefarah/yq/releases/download/v4.44.6/yq_linux_amd64 -O yq && chmod +x ./yq && mv ./yq /usr/local/bin/yq
RUN wget -q https://github.com/jqlang/jq/releases/download/jq-1.7.1/jq-linux-amd64 -O jq && chmod +x ./jq && mv ./jq /usr/local/bin/jq

RUN curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip" && unzip awscliv2.zip && ./aws/install && rm -f awscliv2.zip && rm -rf /aws

RUN wget -q https://github.com/prometheus/prom2json/releases/download/v1.4.1/prom2json-1.4.1.linux-amd64.tar.gz \
    && tar -xvf prom2json-1.4.1.linux-amd64.tar.gz \
    && mv prom2json-1.4.1.linux-amd64/prom2json /usr/local/bin/prom2json \
    && rm -rf prom2json-1.4.1.linux-amd64 \
    && rm prom2json-1.4.1.linux-amd64.tar.gz

ENV KUBECTL_VERSION=1.31.0
RUN curl -LO https://dl.k8s.io/release/v${KUBECTL_VERSION}/bin/linux/amd64/kubectl \
   && chmod +x kubectl \
   && mv kubectl /usr/local/bin \
   && mkdir -p ~/.kube \
   && touch ~/.kube/config \
   && chmod 666 ~/.kube/config \
   && kubectl version --client

RUN mkdir -p .hcledit
RUN curl -sSL https://github.com/minamijoyo/hcledit/releases/download/v0.2.11/hcledit_0.2.11_linux_amd64.tar.gz > hcledit.tar.gz && \
    tar -xvf hcledit.tar.gz -C .hcledit/ && \
    rm -rf hcledit.tar.gz

ENV PATH="/.hcledit:${PATH}"

RUN usermod -l jenkins ubuntu \
    && groupmod -n jenkins ubuntu \
    && usermod -m -d /home/jenkins jenkins

USER jenkins

RUN mkdir -p ~/.kube    \
    && touch ~/.kube/config    \
    && chmod 666 ~/.kube/config    \
    && kubectl version --client

RUN steampipe -v \
    && steampipe plugin install steampipe \
    && steampipe plugin install aws \
    && steampipe plugin install kubernetes \
    && steampipe plugin list
