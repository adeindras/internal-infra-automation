VERSION=v1.0.0-infraapps-v4
IMAGEREPO=268237257165.dkr.ecr.us-east-2.amazonaws.com/jenkinscd-agent
IMAGETAG=$(IMAGEREPO):$(VERSION)

build:
	./tgversionlist.sh
	./tfversionlist.sh
	docker build -t $(IMAGETAG) .

push:
	docker push $(IMAGETAG)