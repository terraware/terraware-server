#!/usr/bin/env node

// This needs to be loaded before the plugins to avoid circular dependencies if they try
// to require it themselves.
require('mjml');

// If you add more plugins, also update the list of packages that gets passed to the
// mjml command in .mjmlconfig in the repo root directory.
require('mjml-bullet-list');

// This needs to come last.
require('mjml-cli');
