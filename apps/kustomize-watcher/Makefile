SHELL:=/bin/bash
.DEFAULT_GOAL := help
 
ECR_URI=public.ecr.aws/p4y6x9f1
VERSION=0.0.1
TAG=kustomize-watcher

EXECUTABLES = docker
K := $(foreach cmd,$(EXECUTABLES),\
        $(if $(shell which $(cmd)),,$(error "$(cmd) not found in PATH, Please install $(cmd)")))

build: ## build watcher as container
	@docker build -t $(ECR_URI)/$(TAG):$(VERSION) .

run: ## run watcher
	@docker run \
	-p 8080:8080 \
	--rm -it $(ECR_URI)/$(TAG):$(VERSION)
	
help:
	@awk 'BEGIN {FS = ":.*?## "} /^[a-zA-Z_-]+:.*?## / {printf "\033[36m%30s\033[0m : %s\n", $$1, $$2}' $(MAKEFILE_LIST)