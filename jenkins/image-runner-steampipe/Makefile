VERSION=v1.0.0-steampipe-v2
IMAGEREPO=268237257165.dkr.ecr.us-east-2.amazonaws.com/jenkinscd-agent
IMAGETAG=$(IMAGEREPO):$(VERSION)

build:
	# ./tgversionlist.sh
	docker build -t $(IMAGETAG) .

push:
	docker push $(IMAGETAG)
