<#-- @ftlvariable name="" type="com.terraformation.backend.email.model.DeliverableReadyForReview" -->
Deliverable was submitted by ${participantName}

A deliverable was submitted by ${participantName} and is ready for review for approval.

${deliverableUrl}
Project: ${deliverable.projectName}
Deliverable: ${deliverable.name}
Category: ${deliverable.category}
------------------------------

${strings("notification.email.text.footer", manageSettingsUrl)}
