#!/usr/bin/env node
/**
 * Wrapper for the MJML command-line interface that loads plugins. The plugins we use have some
 * circular dependencies that, if they're loaded normally, cause them to be half-initialized
 * when they're registered with MJML. The wrapper script loads everything in the right order
 * such that it can register fully-initialized plugins.
 */

const plugins = [
    require("mjml-bullet-list/lib/MjList"),
    require("mjml-bullet-list/lib/MjLi"),
];

// Load the vendored copies of the MJML core libraries that mjml-cli uses.
const { createRequire } = require("module");
const mjmlCliRequire = createRequire(require.resolve("mjml-cli/package.json"));
const cliCore = mjmlCliRequire("mjml-core");
const cliValidator = mjmlCliRequire("mjml-validator");
const cliPreset = mjmlCliRequire("mjml-preset-core");

cliCore.assignComponents(cliCore.components, cliPreset.components);
cliValidator.registerDependencies(cliPreset.dependencies);

plugins
    .map((moduleExport) => moduleExport.default || moduleExport)
    .forEach((component) => {
        cliCore.registerComponent(component);
        cliValidator.registerDependencies(component.dependencies || {});
    });

// This needs to come last.
require("mjml-cli");
