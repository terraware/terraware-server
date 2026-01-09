<#-- @ftlvariable name="" type="com.terraformation.backend.email.model.DeliverableReadyForReview" -->
Deliverable was submitted by ${projectName}

A deliverable was submitted by ${projectName} and is ready for review for approval.

${deliverableUrl}
Project: ${deliverable.projectName}
Deliverable: ${deliverable.name}
Category: ${deliverable.category}
------------------------------

${strings("notification.email.text.footer", manageSettingsUrl)}
