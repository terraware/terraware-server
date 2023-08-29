IMAGE_NAME=terraware-server

BUILD_DIR=../build/docker
FILES=\
	$(BUILD_DIR)/Dockerfile

DEFAULT: image

prepare: $(BUILD_DIR) $(FILES)
	cd .. && ./gradlew assemble
	cd $(BUILD_DIR) && jar -xf `ls -t ../libs/*.jar | grep -v plain.jar | head -1`

image: prepare
	docker build -f Dockerfile -t $(IMAGE_NAME) $(BUILD_DIR)

run: image
    # This will not work on Windows
	@if [ -f .env ]; then\
		docker-compose -f docker-compose.yml --env-file .env up;\
	else\
		docker-compose -f docker-compose.yml up;\
	fi

run-postgres:
	docker-compose -f docker-compose.yml up postgres

run-server: image
	docker-compose -f docker-compose.yml up terraware-server

$(BUILD_DIR):
	mkdir -p $(BUILD_DIR)

$(BUILD_DIR)/%: %
	cp $< $@
