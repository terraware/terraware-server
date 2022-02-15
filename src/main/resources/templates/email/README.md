# Email templates

This directory contains templates for server-generated email messages. Each template is in its own subdirectory.

There are two versions of each email message: a plaintext version and an HTML version.

`body.txt` is the plaintext version.

`body.mjml` is the source for the HTML version. It isn't actual HTML; the HTML is generated from [MJML](https://mjml.io/).

The MJML-to-HTML conversion happens as part of the build process; if you look in `build/resources/main/templates/email` after building the code, you'll see the HTML versions.

## Variable substitution

Both the plaintext and the MJML files may include placeholders that get replaced with real values at runtime. The placeholders use Freemarker expression syntax, like `${variable.name}`.

In the current implementation, messages aren't actually rendered with Freemarker per se; the server just does a bit of simple string substitution. The plan is to introduce Freemarker if at some point we need more powerful email rendering capabilities, and by using the right syntax ahead of time, we won't need to change the existing templates.
