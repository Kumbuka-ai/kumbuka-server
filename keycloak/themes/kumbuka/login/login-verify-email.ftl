<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=false; section>

  <#if section = "header">
    <div class="kc-hero-ico accent" aria-hidden="true">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M3 7l9 6 9-6"/><rect x="3" y="6" width="18" height="12" rx="1"/></svg>
    </div>
    <span class="kc-eyebrow">${msg("kumbukaEyebrowVerifyEmail")}</span>
    <h1 class="kc-title">${msg("emailVerifyTitle")}</h1>
    <p class="kc-lead">${msg("emailVerifyInstruction1",user.email)}</p>

  <#elseif section = "form">
    <p class="kc-lead" style="margin-top:16px">
      ${msg("emailVerifyInstruction2")}
      <a class="kc-link" href="${url.loginAction}">${msg("doClickHere")}</a>
      ${msg("emailVerifyInstruction3")}
    </p>

    <div class="kc-assure" role="note">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="5" y="11" width="14" height="9" rx="1"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/></svg>
      <p>${msg("kumbukaPrivateAssurance")}</p>
    </div>
  </#if>
</@layout.registrationLayout>
