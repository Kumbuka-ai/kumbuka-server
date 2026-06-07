<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=false; section>

  <#if section = "header">
    <span class="kc-eyebrow">${msg("kumbukaEyebrowTerms")}</span>
    <h1 class="kc-title">${msg("termsTitle")}</h1>

  <#elseif section = "form">
    <#-- ${termsText} comes from the realm's terms-and-conditions HTML field. -->
    <div class="kc-terms" tabindex="0" style="margin-top:24px">
      ${kcSanitize(msg("termsText"))?no_esc}
    </div>

    <form id="kc-terms-form" class="kc-form" action="${url.loginAction}" method="post">
      <button class="kc-btn primary" name="accept" id="kc-accept" type="submit">
        <span>${msg("doAccept")}</span>
        <svg class="arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 12.5l4.5 4.5L19 7"/></svg>
      </button>
      <button class="kc-btn block" name="cancel" id="kc-decline" type="submit">
        <span>${msg("doDecline")}</span>
      </button>
    </form>
  </#if>
</@layout.registrationLayout>
