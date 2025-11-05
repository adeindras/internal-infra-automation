IMAGE_TAG=v0.1.0
IMAGE_REGISTRY=144436415367.dkr.ecr.us-west-2.amazonaws.com/infra-ccu-info

build:
	docker build -t $(IMAGE_REGISTRY):$(IMAGE_TAG) .

run:
	docker run -it --rm -e ENV=test -e CCU=199 -p 8888:8888 $(IMAGE_REGISTRY):$(IMAGE_TAG)

push: 
	docker push $(IMAGE_REGISTRY):$(IMAGE_TAG)