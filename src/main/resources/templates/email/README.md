# Email templates

Each subdirectory contains two files `body.html` and `body.txt` that are used to construct the HTML and plaintext versions of outgoing email messages.

You can edit the plaintext files directly. However, the HTML is generated from MJML sources in the `email` directory at the top of the repo. Edit those files and then run `make` in that directory to update the HTML files here.