VERSION=kafka-2.13-3.7.2_openjdk-11.0.2
IMAGEREPO=144436415367.dkr.ecr.us-west-2.amazonaws.com/rightsize-kafka-mirrormaker
IMAGETAG=$(IMAGEREPO):$(VERSION)

build:
	docker build -t $(IMAGETAG) .

push:
	docker push $(IMAGETAG)