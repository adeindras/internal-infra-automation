FROM docker.io/jenkins/inbound-agent:latest-jdk21

ARG DEBIAN_FRONTEND=noninteractive

WORKDIR /home/jenkins

USER root

# Install tools
RUN curl -fsSL https://deb.nodesource.com/setup_22.x -o nodesource_setup.sh && bash nodesource_setup.sh && rm nodesource_setup.sh
RUN apt-get update && apt-get upgrade -y && apt-get install -y python3 python3-venv python3-pip git unzip jq nodejs pipx gettext-base && apt-get clean
RUN /bin/bash -c "$(curl -fsSL https://steampipe.io/install/steampipe.sh)"
RUN curl -L -o /usr/local/bin/terragrunt https://github.com/gruntwork-io/terragrunt/releases/download/v0.75.5/terragrunt_linux_amd64 && chmod +x /usr/local/bin/terragrunt
RUN curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip" && unzip awscliv2.zip && ./aws/install && rm awscliv2.zip
RUN curl -LO "https://dl.k8s.io/release/v1.31.7/bin/linux/amd64/kubectl" && install ./kubectl /usr/local/bin/kubectl && rm ./kubectl
RUN curl -L -o /usr/bin/yq https://github.com/mikefarah/yq/releases/latest/download/yq_linux_amd64 && chmod +x /usr/bin/yq
RUN curl -LO https://github.com/minamijoyo/hcledit/releases/download/v0.2.13/hcledit_0.2.13_linux_amd64.tar.gz && tar -xzv -C /tmp -f hcledit_0.2.13_linux_amd64.tar.gz hcledit && install /tmp/hcledit /usr/local/bin/hcledit && rm /tmp/hcledit
RUN curl -O -fsSL https://go.dev/dl/go1.24.1.linux-amd64.tar.gz && tar -C /usr/local -xzf go1.24.1.linux-amd64.tar.gz && rm go1.24.1.linux-amd64.tar.gz 
RUN curl -LO https://github.com/fluxcd/flux2/releases/download/v0.37.0/flux_0.37.0_linux_amd64.tar.gz && tar -xzv -C /tmp -f flux_0.37.0_linux_amd64.tar.gz && install /tmp/flux /usr/local/bin/flux && rm /tmp/flux

USER jenkins

RUN git clone --depth=1 https://github.com/tfutils/tfenv.git /home/jenkins/.tfenv

ENV PATH="/home/jenkins/.tfenv/bin:$PATH"
RUN tfenv install 1.2.9
RUN tfenv use 1.2.9

ENV PATH="/home/jenkins/.local/bin:$PATH"
ENV PATH="/usr/local/go/bin:$PATH"

RUN pipx ensurepath
