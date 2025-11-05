VERSION=pause-and-resume-v1.0.5
IMAGEREPO=268237257165.dkr.ecr.us-east-2.amazonaws.com/jenkinscd-agent
IMAGETAG=$(IMAGEREPO):$(VERSION)

build:
	./tgversionlist.sh
	docker build -t $(IMAGETAG) .

push:
	aws ecr get-login-password --region us-east-2 | docker login --username AWS --password-stdin 268237257165.dkr.ecr.us-east-2.amazonaws.com
	docker push $(IMAGETAG)