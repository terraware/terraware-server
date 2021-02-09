IMAGE_NAME=seedbank-server

DEFAULT: image

prepare:
	cd .. && ./gradlew assemble
	mkdir -p ../build/docker
	cp Dockerfile entrypoint.sh ../build/docker
	cd ../build/docker && jar -xf ../libs/*.jar

image: prepare
	docker build -f Dockerfile -t $(IMAGE_NAME) ../build/docker

run: image
	docker-compose -f docker-compose-dev.yml up
