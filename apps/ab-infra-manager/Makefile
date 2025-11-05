SHELL:=/bin/bash
.DEFAULT_GOAL := help
 
ECR_URI=144436415367.dkr.ecr.us-west-2.amazonaws.com
VERSION=1.2.1
TAG=ab-infra-manager
LOG_LEVEL=debug
AWS_PROFILE=unknown
AWS_USAGE_SCRAPE_INTERVAL=24h # scraped every n hours. Default: 24 hours.
AWS_USAGE_TIME_RANGE=336h # metrics time range. Default: 2 weeks (336 hours).

EXECUTABLES = docker
K := $(foreach cmd,$(EXECUTABLES),\
        $(if $(shell which $(cmd)),,$(error "$(cmd) not found in PATH, Please install $(cmd)")))

build: ## build watcher as container
	@docker build -t $(ECR_URI)/$(TAG):$(VERSION) .

run: ## run service locally
	AWS_PROFILE=$(AWS_PROFILE) LOG_LEVEL=$(LOG_LEVEL) \
	AWS_USAGE_SCRAPE_INTERVAL=$(AWS_USAGE_SCRAPE_INTERVAL) \
	AWS_USAGE_TIME_RANGE=$(AWS_USAGE_TIME_RANGE) \
	CCU=100 KUSTOMIZATION_RESOURCES="linkerd,emissary-ingress,alb-controller,karpenter" LIVE=true go run main.go

docker-run: ## run service locally
	docker run -it --rm -e CCU=100 \
	-e KUSTOMIZATION_RESOURCES="linkerd,emissary-ingress,alb-controller,karpenter" \
	-e LIVE=true $(ECR_URI)/$(TAG):$(VERSION)
	
help:
	@awk 'BEGIN {FS = ":.*?## "} /^[a-zA-Z_-]+:.*?## / {printf "\033[36m%30s\033[0m : %s\n", $$1, $$2}' $(MAKEFILE_LIST)
