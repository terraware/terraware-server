MJML_EXE=node_modules/mjml/bin/mjml
TEMPLATE_DIR=../src/main/resources/templates/email

MJML_FILES=*.mjml

all: $(patsubst %.mjml,$(TEMPLATE_DIR)/%/body.html,$(MJML_FILES))

$(TEMPLATE_DIR)/%/body.html: %.mjml $(MJML_EXE)
	$(MJML_EXE) --config.minify true --config.beautify false -o $@ $<

$(MJML_EXE):
	yarn
