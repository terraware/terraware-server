<#-- @ftlvariable name="" type="com.terraformation.backend.email.model.WelcomeToTerraware" -->
${strings("notification.user.welcomeToTerraware.email.body.1")}

<#if user.firstName??>${strings("notification.user.welcomeToTerraware.email.body.2", user.firstName)}<#else>${strings("notification.user.welcomeToTerraware.email.body.2.noname")}</#if>

${strings("notification.user.welcomeToTerraware.email.body.3", user.email)}

${strings("notification.user.welcomeToTerraware.email.body.4")}

${strings("notification.user.welcomeToTerraware.email.buttonLabel")}
${webAppUrl}

------------------------------

${strings("notification.email.text.footer", manageSettingsUrl)}
