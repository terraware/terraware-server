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
	docker-compose -f docker-compose.yml up

$(BUILD_DIR):
	mkdir -p $(BUILD_DIR)

$(BUILD_DIR)/%: %
	cp $< $@
