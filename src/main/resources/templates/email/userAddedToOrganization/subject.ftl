<#-- @ftlvariable name="" type="com.terraformation.backend.email.model.UserAddedToOrganization" -->
<#if admin.fullName?has_content>
    ${admin.fullName} has added you to ${organization.name} on Terraware
<#else>
    An admin has added you to ${organization.name} on Terraware
</#if>
