<#import "template.ftl" as layout>
<@layout.registrationLayout displayInfo=false; section>

  <#if section = "header">
    <span class="kc-eyebrow">${msg("kumbukaEyebrowChooseMethod")}</span>
    <h1 class="kc-title">${msg("loginChooseAuthenticator")}</h1>

  <#elseif section = "form">

    <form id="kc-select-credential-form" class="kc-form" action="${url.loginAction}" method="post">
      <div class="kc-choices" style="margin-top:26px">
        <#list auth.authenticationSelections as authenticationSelection>
          <button class="kc-choice" type="submit"
                  name="authenticationExecution"
                  value="${authenticationSelection.authExecId}">
            <span class="ch-ico">
              <#if authenticationSelection.iconCssClass?has_content
                    && authenticationSelection.iconCssClass?contains("webauthn")>
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="9" r="3"/><path d="M5 20a7 7 0 0 1 14 0"/></svg>
              <#elseif authenticationSelection.iconCssClass?has_content
                    && authenticationSelection.iconCssClass?contains("mobile")>
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="7" y="3" width="10" height="18" rx="2"/><path d="M11 18h2"/></svg>
              <#elseif authenticationSelection.iconCssClass?has_content
                    && authenticationSelection.iconCssClass?contains("password")>
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="9" cy="12" r="3"/><path d="M12 12h9"/><path d="M18 10v4"/></svg>
              <#else>
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="4" y="4" width="16" height="16" rx="1"/><path d="M9 11l3 3 6-6"/></svg>
              </#if>
            </span>
            <span>
              <span class="ch-name">${msg('${authenticationSelection.displayName}')}</span>
              <span class="ch-sub">${msg('${authenticationSelection.helpText}')}</span>
            </span>
            <span class="ch-arrow" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M9 6l6 6-6 6"/></svg>
            </span>
          </button>
        </#list>
      </div>
    </form>

  </#if>
</@layout.registrationLayout>
