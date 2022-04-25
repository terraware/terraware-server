# Email templates

This directory contains templates for server-generated email messages. Each template is in its own subdirectory.

Each subdirectory can contain three template files:

- `subject.ftl` is the subject line of the email message.
- `body.txt.ftl` is the plaintext body of the message. If it doesn't exist, the message will only be sent as HTML.
- `body.ftlh.mjml` is the source for the HTML version. It isn't actual HTML; the HTML is generated from [MJML](https://mjml.io/). If it doesn't exist, the message will only be sent as plaintext.

At least one of the `body` files must exist.

The MJML-to-HTML conversion happens as part of the build process; if you look in `build/resources/main/templates/email` after building the code, you'll see the HTML versions.

Both the plaintext and the MJML files may include placeholders that get replaced with real values at runtime. The placeholders use [FreeMarker](https://freemarker.apache.org/) expression syntax, like `${variable.name}`.

## Specifying model classes

IntelliJ provides autocomplete and error checking of FreeMarker expressions, but because the code computes the template filenames dynamically, IntelliJ can't automatically determine which model object is used to render which template.

Therefore, the first line of every template file should be an IntelliJ directive to tell it which model class the template uses. See [the IntelliJ documentation](https://www.jetbrains.com/help/idea/template-data-languages.html#special-comments) for details about the directives, but briefly, you'll want

```
<#-- @ftlvariable name="" type="com.terraformation.backend.email.model.YourModelClass" -->
```

at the top of the plaintext templates and

```
<!-- <#-- @ftlvariable name="" type="com.terraformation.backend.email.model.UserAddedToOrganization" --> -->
```

at the top of the MJML files. (Wrapping the directive in `<!--` is needed because otherwise the file would be invalid MJML.)

## Configuring MJML and FreeMarker support in the IntelliJ editor

IntelliJ Ultimate comes with FreeMarker support built in, though you might need to enable the plugin. There is also [a third-party plugin](https://plugins.jetbrains.com/plugin/16418-mjml-support) to support MJML editing; it can be installed from the plugin marketplace.

To configure IntelliJ to know what to do with MJML files that have FreeMarker directives, you need to define a new file type mapping. These instructions are valid for IntelliJ 2021.3.3:

1. Make sure the MJML and FreeMarker plugins are installed.
2. Go to Preferences > Editor > File Types.
3. Select the "FreeMarker template" file type.
4. Click the "+" button to add a new file name pattern.
5. For the pattern, enter `*.ftlh.mjml`.
6. For the template data language, select "mjml" (it might also be listed as "MailJet Markup Language").
7. Click OK.

Now when you edit the MJML email templates, IntelliJ will know that they contain FreeMarker expressions.
