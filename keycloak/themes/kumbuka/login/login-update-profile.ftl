<#import "template.ftl" as layout>
<#import "user-profile-commons.ftl" as userProfileCommons>
<@layout.registrationLayout displayMessage=!messagesPerField.exists('global') displayRequiredFields=true; section>

  <#if section = "header">
    <span class="kc-eyebrow">${msg("kumbukaEyebrowUpdateProfile")}</span>
    <h1 class="kc-title">${msg("loginProfileTitle")}</h1>
    <p class="kc-lead">${msg("loginProfileText")!""}</p>

  <#elseif section = "form">
    <form id="kc-update-profile-form" class="kc-form"
          action="${url.loginAction}" method="post" novalidate style="margin-top:26px">

      <#-- The realm's user-profile config drives the field set; we just style. -->
      <@userProfileCommons.userProfileFormFields/>

      <#if isAppInitiatedAction??>
        <input type="hidden" id="stateChecker" name="stateChecker" value="${stateChecker}"/>
      </#if>

      <button class="kc-btn primary" type="submit">
        <span>${msg("doSubmit")}</span>
        <svg class="arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 12h14"/><path d="M13 5l7 7-7 7"/></svg>
      </button>
      <#if isAppInitiatedAction??>
        <button class="kc-btn" type="submit" name="cancel-aia" value="true">${msg("doCancel")}</button>
      </#if>
    </form>
  </#if>
</@layout.registrationLayout>
