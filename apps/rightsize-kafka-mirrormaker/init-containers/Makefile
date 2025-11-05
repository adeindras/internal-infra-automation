# Get the current timestamp (e.g., 20251024-052000) when make is invoked
CURRENT_TIMESTAMP := $(shell date +%Y%m%d-%H%M%S)

VERSION   			:= init-containers-ubuntu-22.04-$(CURRENT_TIMESTAMP)
VERSION_ALPINE   	:= init-containers-alpine-3.22.2-$(CURRENT_TIMESTAMP)
IMAGEREPO 			:= 144436415367.dkr.ecr.us-west-2.amazonaws.com/rightsize-kafka-mirrormaker
IMAGETAG  			:= $(IMAGEREPO):$(VERSION)
IMAGETAG_ALPINE  	:= $(IMAGEREPO):$(VERSION_ALPINE)

.PHONY: build-ubuntu build-alpine build-push-ubuntu build-push-alpine

build-ubuntu:
	@echo "Building image: $(IMAGETAG)"
	docker build -t $(IMAGETAG) -f ./Dockerfile.ubuntu .

build-alpine:
	@echo "Building image: $(IMAGETAG_ALPINE)"
	docker build -t $(IMAGETAG_ALPINE) -f ./Dockerfile.alpine .

build-push-ubuntu:
	@echo "Building image: $(IMAGETAG)"
	docker build -t $(IMAGETAG) -f ./Dockerfile.ubuntu .
	@echo "Pushing image: $(IMAGETAG)"
	docker push $(IMAGETAG)

build-push-alpine:
	@echo "Building image: $(IMAGETAG_ALPINE)"
	docker build -t $(IMAGETAG_ALPINE) -f ./Dockerfile.alpine .
	@echo "Pushing image: $(IMAGETAG_ALPINE)"
	docker push $(IMAGETAG_ALPINE)