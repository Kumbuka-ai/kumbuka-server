<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=false; section>

  <#if section = "header">
    <div class="kc-hero-ico" aria-hidden="true">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M12 7v6l3 2"/></svg>
    </div>
    <span class="kc-eyebrow">${msg("kumbukaEyebrowExpired")}</span>
    <h1 class="kc-title">${msg("pageExpiredTitle")}</h1>
    <p class="kc-lead">${msg("pageExpiredMsg1")}<br>${msg("pageExpiredMsg2")}</p>

  <#elseif section = "form">
    <div style="display:flex;flex-direction:column;gap:10px;margin-top:24px">
      <a class="kc-btn primary" href="${url.loginRestartFlowUrl}">
        <span>${msg("doClickHere")}</span>
        <svg class="arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 12h14"/><path d="M13 5l7 7-7 7"/></svg>
      </a>
      <a class="kc-btn" href="${url.loginAction}">
        <span>${msg("doContinue")}</span>
      </a>
    </div>
  </#if>
</@layout.registrationLayout>
