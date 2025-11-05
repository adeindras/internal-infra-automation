VERSION=ags-test-v2.4
IMAGEREPO=268237257165.dkr.ecr.us-east-2.amazonaws.com/jenkinscd-agent
IMAGETAG=$(IMAGEREPO):$(VERSION)

build:
	docker build -t $(IMAGETAG) .

push:
	docker push $(IMAGETAG)