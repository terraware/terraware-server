<#-- @ftlvariable name="" type="com.terraformation.backend.email.model.UserAddedToProject" -->
<#if admin.fullName?has_content>
    ${admin.fullName} has added you to project ${project.name} on Terraware
<#else>
    An admin has added you to project ${project.name} on Terraware
</#if>
