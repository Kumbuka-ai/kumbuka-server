<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=false; section>

  <#if section = "header">
    <div class="kc-hero-ico bad" aria-hidden="true">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3L2 20h20z"/><path d="M12 9v5"/><path d="M12 17h.01"/></svg>
    </div>
    <span class="kc-eyebrow">${msg("kumbukaEyebrowError")}</span>
    <h1 class="kc-title">${msg("errorTitle")}</h1>
    <#if message?has_content>
      <p class="kc-lead">${kcSanitize(message.summary)?no_esc}</p>
    </#if>

  <#elseif section = "form">
    <div style="margin-top:26px">
      <#if skipLink??>
        <#-- no link -->
      <#else>
        <#if client?? && client.baseUrl?has_content>
          <a class="kc-btn primary" href="${client.baseUrl}">
            <span>${msg("backToApplication")}</span>
            <svg class="arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 12h14"/><path d="M13 5l7 7-7 7"/></svg>
          </a>
        <#elseif client?? && client.attributes.policyUri??>
          <a class="kc-btn" href="${client.attributes.policyUri}">${msg("backToApplication")}</a>
        </#if>
      </#if>
    </div>
  </#if>
</@layout.registrationLayout>
