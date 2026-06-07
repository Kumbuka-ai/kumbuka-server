<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=false; section>

  <#if section = "header">
    <#-- Pick a hero glyph + eyebrow tied to the message kind. -->
    <#if pageRedirectUri?has_content>
      <div class="kc-hero-ico ok" aria-hidden="true">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12.5l4.5 4.5L19 7"/></svg>
      </div>
      <span class="kc-eyebrow">${msg("kumbukaEyebrowInfo")}</span>
    <#elseif messageHeader??>
      <div class="kc-hero-ico" aria-hidden="true">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M12 7h.01"/><path d="M11 11h1v6h1"/></svg>
      </div>
      <span class="kc-eyebrow">${msg("kumbukaEyebrowInfo")}</span>
    <#else>
      <div class="kc-hero-ico" aria-hidden="true">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M12 7h.01"/><path d="M11 11h1v6h1"/></svg>
      </div>
      <span class="kc-eyebrow">${msg("kumbukaEyebrowInfo")}</span>
    </#if>

    <h1 class="kc-title">
      <#if messageHeader??>
        ${messageHeader}
      <#elseif message?has_content>
        ${kcSanitize(message.summary)?no_esc}
      <#else>
        ${msg("infoMessageTitle")}
      </#if>
    </h1>

    <#if messageHeader?? && message?has_content>
      <p class="kc-lead">${kcSanitize(message.summary)?no_esc}</p>
    </#if>

  <#elseif section = "form">
    <#if requiredActions??>
      <p class="kc-lead">
        <#list requiredActions>
          <#items as reqActionItem>
            ${msg("requiredAction.${reqActionItem}")}<#sep>, </#sep>
          </#items>
        </#list>
      </p>
    </#if>

    <div style="margin-top:26px">
      <#if skipLink??>
        <#-- caller asked us not to render a CTA -->
      <#else>
        <#if pageRedirectUri?has_content>
          <a class="kc-btn primary" href="${pageRedirectUri}">
            <span>${msg("backToApplication")}</span>
            <svg class="arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 12h14"/><path d="M13 5l7 7-7 7"/></svg>
          </a>
        <#elseif actionUri?has_content>
          <a class="kc-btn primary" href="${actionUri}">
            <span>${msg("proceedWithAction")}</span>
            <svg class="arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 12h14"/><path d="M13 5l7 7-7 7"/></svg>
          </a>
        <#elseif client?? && client.baseUrl?has_content>
          <a class="kc-btn primary" href="${client.baseUrl}">
            <span>${msg("backToApplication")}</span>
            <svg class="arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 12h14"/><path d="M13 5l7 7-7 7"/></svg>
          </a>
        </#if>
      </#if>
    </div>
  </#if>
</@layout.registrationLayout>
