<#--
 kumbuka.ai — login theme layout.

 Defines the `registrationLayout` macro that every page template
 (login.ftl, login-otp.ftl, …) uses. Sections are the same ones the
 base keycloak.v2 theme uses:

   - "header"           — eyebrow + title (and optional kc-context line)
   - "form"             — main form / content
   - "info"             — secondary info under the card body
   - "socialProviders"  — IdP buttons (rendered only if social.providers??)

 We do not call `<#include "common/keycloak/web_modules/.../*">`. The
 styling is fully owned by `resources/css/kumbuka.css`, the only JS is
 our own CSP-safe `resources/js/kumbuka.js`.
-->
<#macro registrationLayout bodyClass=""
                            displayInfo=false
                            displayMessage=true
                            displayRequiredFields=false
                            displayWide=false>
<!DOCTYPE html>
<html lang="${locale.currentLanguageTag!"en"}" class="${properties.kcHtmlClass!}">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <meta name="robots" content="noindex,nofollow">
  <title>${msg("loginTitle",(realm.displayName!""))}</title>
  <link rel="icon" type="image/svg+xml" href="${url.resourcesPath}/img/favicon.svg">
  <#if properties.styles?has_content>
    <#list properties.styles?split(' ') as style>
      <link href="${url.resourcesPath}/${style}" rel="stylesheet" />
    </#list>
  </#if>
  <#if properties.scripts?has_content>
    <#list properties.scripts?split(' ') as script>
      <script src="${url.resourcesPath}/${script}" type="text/javascript" defer></script>
    </#list>
  </#if>
</head>
<body class="${bodyClass} ${properties.kcLoginClass!}">

<div class="kc-page">
  <div class="kc-shell">

    <#-- Brand block above the card. -->
    <div class="kc-brand">
      <img src="${url.resourcesPath}/img/kumbuka-mark.svg" alt="" width="38" height="38">
      <div>
        <div class="wordmark">kumbuka<span class="dot">.ai</span></div>
        <span class="sub">${msg("kumbukaBrandSub")}</span>
      </div>
    </div>

    <div class="kc-card<#if displayWide> wide</#if>">

      <#-- Header: caller provides eyebrow + title (and optional kc-context). -->
      <#nested "header">

      <#-- Global messages (success/info/warn/error). Field-level errors
           are rendered inline by the form templates via messagesPerField. -->
      <#if displayMessage && message?has_content
            && (message.type != 'warning' || !isAppInitiatedAction??)>
        <div class="kc-alert ${(message.type == 'success')?then('success','')} ${(message.type == 'warning')?then('warn','')} ${(message.type == 'error')?then('error','')} ${(message.type == 'info')?then('info','')}"
             role="alert">
          <#if message.type == 'success'><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 12.5l4.5 4.5L19 7"/></svg></#if>
          <#if message.type == 'warning'><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 3L2 20h20z"/><path d="M12 9v5"/><path d="M12 17h.01"/></svg></#if>
          <#if message.type == 'error'><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 3L2 20h20z"/><path d="M12 9v5"/><path d="M12 17h.01"/></svg></#if>
          <#if message.type == 'info'><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 8h.01"/><path d="M11 12h1v5h1"/></svg></#if>
          <span>${kcSanitize(message.summary)?no_esc}</span>
        </div>
      </#if>

      <#-- Main content. -->
      <#nested "form">

      <#-- Identity providers / SSO. -->
      <#if social?? && social.providers?? && (social.providers?size > 0)>
        <#nested "socialProviders">
        <div class="kc-divider">${msg("identity-provider-login-label","or continue with")}</div>
        <div class="kc-social">
          <#list social.providers as p>
            <a class="kc-btn" id="social-${p.alias}" href="${p.loginUrl}">
              <span class="prov-ico" aria-hidden="true">
                <#if p.iconClasses?has_content>
                  <i class="${p.iconClasses}"></i>
                <#else>
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/></svg>
                </#if>
              </span>
              <span>${p.displayName!p.alias}</span>
            </a>
          </#list>
        </div>
      </#if>

      <#-- Card-foot (info section). -->
      <#if displayInfo>
        <div class="kc-card-foot">
          <#nested "info">
        </div>
      </#if>

      <#-- Required-fields hint (used by login-update-profile.ftl). -->
      <#if displayRequiredFields>
        <div class="kc-card-foot" style="text-align:left">
          <span class="kc-mono" style="font-size:11px;color:var(--c-faint)">*</span>
          <span style="font-size:12.5px;color:var(--c-muted)">${msg("requiredFields")}</span>
        </div>
      </#if>

    </div>

    <#-- Footer: brand meta + KC native locale picker. -->
    <div class="kc-foot">
      <span class="meta" aria-hidden="true">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z"/><path d="M9 12l2 2 4-4"/></svg>
        ${msg("kumbukaSecuredBy")}
      </span>

      <#if realm.internationalizationEnabled
            && locale.supported?? && (locale.supported?size > 1)>
        <span class="kc-locale">
          <#-- Real <select> wired to KC's locale switch URLs. -->
          <label for="kc-locale-select" class="pf-u-screen-reader">${msg("languages")}</label>
          <select id="kc-locale-select">
            <#list locale.supported as l>
              <option value="${l.url}"
                <#if locale.currentLanguageTag == l.languageTag>selected</#if>>${l.label}</option>
            </#list>
          </select>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M6 9l6 6 6-6"/></svg>
        </span>
      </#if>
    </div>

  </div>
</div>

</body>
</html>
</#macro>
