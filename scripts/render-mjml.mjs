#!/usr/bin/env node
/**
 * Project-specific MJML renderer that registers our custom components before calling the MJML API.
 * Some plugins have circular dependencies that leave them half-initialized if mjml-core loads them
 * automatically; this script loads everything in the right order, then renders one or more MJML
 * templates in a single process.
 */

import { createRequire } from "module";
import path from "path";
import fs from "fs";
import { minify } from 'html-minifier-next';
import mjmlCoreModule from "mjml-core";
import mjmlValidator from "mjml-validator";
import mjmlPreset from "mjml-preset-core";

const require = createRequire(import.meta.url);
const plugins = [
  require("mjml-bullet-list/lib/MjList"),
  require("mjml-bullet-list/lib/MjLi"),
];

const mjml = mjmlCoreModule.default || mjmlCoreModule;

mjmlCoreModule.assignComponents(mjmlCoreModule.components, mjmlPreset.components);
mjmlValidator.registerDependencies(mjmlPreset.dependencies);

plugins
  .map((moduleExport) => moduleExport.default || moduleExport)
  .forEach((component) => {
    mjmlCoreModule.registerComponent(component, { registerDependencies: true });
    mjmlValidator.registerDependencies(component.dependencies || {});
  });

const args = process.argv.slice(2);
if (args.length < 3) {
  throw new Error(
    "Usage: render-mjml.js <inputDirectory> <outputDirectory> <subdir/file.mjml> [...]",
  );
}

const inputDir = path.resolve(args[0]);
const outputDir = path.resolve(args[1]);
const mjmlFiles = args.slice(2);

for (const mjmlFile of mjmlFiles) {
  if (!mjmlFile.endsWith(".mjml")) {
    throw new Error(`${mjmlFile} does not end with .mjml.`);
  }

  const inputPath = path.resolve(inputDir, mjmlFile);
  const outputRelative = mjmlFile.slice(0, -".mjml".length);
  const outputPath = path.resolve(outputDir, outputRelative);

  const source = fs.readFileSync(inputPath, "utf8");
  const { html, errors } = mjml(source, { filePath: inputPath });

  const minifiedHtml = await minify(html, {
      collapseWhitespace: true,
      // Treat Freemarker tags like <#if> as text, not as HTML
      ignoreCustomFragments: [ /<#[^>]+>/, /<\/#[^>]+>/ ],
      minifyCSS: false,
      removeEmptyAttributes: true,
  });

  if (errors && errors.length > 0) {
    const messages = errors
      .map((error) => error.formattedMessage || error.message || String(error))
      .join("\n");
    throw new Error(`MJML render failed for ${inputPath}:\n${messages}`);
  }

  fs.mkdirSync(path.dirname(outputPath), { recursive: true });
  fs.writeFileSync(outputPath, minifiedHtml, "utf8");
}
