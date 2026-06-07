<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=false; section>

  <#if section = "header">
    <#if auth?has_content && auth.showUsername()>
      <div class="kc-context">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M7 11V8a5 5 0 0 1 10 0v3"/><path d="M5 11h14v9H5z"/></svg>
        <span>${msg("loginTitleHtml")} <b>${(auth.attemptedUsername!"")}</b></span>
      </div>
    </#if>
    <span class="kc-eyebrow">${msg("kumbukaEyebrowLogout")}</span>
    <h1 class="kc-title">${msg("logoutConfirmTitle")}</h1>
    <p class="kc-lead">${msg("logoutConfirmHeader")}</p>

  <#elseif section = "form">
    <form id="kc-logout-confirm-form" class="kc-form" action="${url.logoutConfirmAction}" method="post"
          style="margin-top:26px">
      <input type="hidden" name="session_code" value="${logoutConfirm.code}">
      <button class="kc-btn primary" id="kc-logout" type="submit" name="confirmLogout" value="true">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M15 4h4a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1h-4"/><path d="M10 17l5-5-5-5"/><path d="M15 12H3"/></svg>
        <span>${msg("doLogout")}</span>
      </button>
      <#if logoutConfirm.skipLink>
        <#-- nothing -->
      <#else>
        <#if (client.baseUrl)?has_content>
          <a class="kc-btn block" href="${client.baseUrl}">
            <span>${msg("backToApplication")}</span>
          </a>
        </#if>
      </#if>
    </form>
  </#if>
</@layout.registrationLayout>
