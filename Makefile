.PHONY: local docker docker-full stop build test

local:
	./scripts/run-local.sh

docker:
	./scripts/run-docker.sh quick

docker-full:
	./scripts/run-docker.sh full

stop:
	./scripts/stop.sh

build:
	mvn -q -DskipTests package
	$(MAKE) -C frontend build

test:
	mvn -q test
