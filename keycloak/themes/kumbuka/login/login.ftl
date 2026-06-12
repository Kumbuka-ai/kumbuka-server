<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('username','password') displayInfo=(realm.password && realm.registrationAllowed && !registrationDisabled??); section>

  <#if section = "header">
    <#-- Identity-first password step (usernameHidden): show whose account this
         is + a restart link. The Organizations browser flow renders THIS template
         with usernameHidden=true for the password page (there is no separate
         Password Form authenticator), so the affordance belongs here. -->
    <#if usernameHidden?? && auth?has_content && auth.showUsername() && !auth.showResetCredentials()>
      <div class="kc-context">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/></svg>
        <span>${msg("kumbukaSigningInAs")} <b>${auth.attemptedUsername}</b>
          &nbsp;·&nbsp;<a class="kc-link muted" href="${url.loginRestartFlowUrl}">${msg("kumbukaNotYou")}</a></span>
      </div>
    <#else>
      <div class="kc-context">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M9 15l6-6"/><path d="M10 7l1-1a4 4 0 0 1 6 6l-1 1"/><path d="M14 17l-1 1a4 4 0 0 1-6-6l1-1"/></svg>
        <span>${msg("kumbukaContinueTo")} <b>${(realm.displayName!"kumbuka memory console")?no_esc}</b></span>
      </div>
    </#if>
    <span class="kc-eyebrow">${msg("kumbukaEyebrowSignIn")}</span>
    <h1 class="kc-title">${msg("loginAccountTitle",(realm.displayName!""))}</h1>

  <#elseif section = "form">
    <#if realm.password>
      <form id="kc-form-login" class="kc-form" data-kc-disable-on-submit="kc-login"
            action="${url.loginAction}" method="post" novalidate>
        <#if !usernameHidden??>
          <div class="kc-field">
            <label class="kc-label" for="username">
              <#if !realm.loginWithEmailAllowed>${msg("username")}
              <#elseif !realm.registrationEmailAsUsername>${msg("usernameOrEmail")}
              <#else>${msg("email")}</#if>
            </label>
            <input id="username" name="username" class="kc-input mono" type="text"
                   value="${(login.username!'')}"
                   autocomplete="username"
                   autofocus
                   spellcheck="false"
                   aria-invalid="<#if messagesPerField.existsError('username','password')>true</#if>"
                   placeholder="you@${(realm.name!"kumbuka.ai")}">
            <#if messagesPerField.existsError('username','password')>
              <span class="kc-input-error" id="input-error" aria-live="polite">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 8v4"/><path d="M12 16h.01"/></svg>
                ${kcSanitize(messagesPerField.getFirstError('username','password'))?no_esc}
              </span>
            </#if>
          </div>
        </#if>

        <div class="kc-field">
          <label class="kc-label" for="password">
            ${msg("password")}
            <#if realm.resetPasswordAllowed>
              <a class="kc-link" tabindex="-1" href="${url.loginResetCredentialsUrl}">${msg("doForgotPassword")}</a>
            </#if>
          </label>
          <div class="kc-input-wrap">
            <input id="password" name="password" class="kc-input" type="password"
                   autocomplete="current-password"
                   aria-invalid="<#if messagesPerField.existsError('username','password')>true</#if>"
                   placeholder="••••••••••">
            <button type="button" class="kc-reveal"
                    data-kc-reveal="password"
                    data-kc-reveal-show="${msg("showPassword")}"
                    data-kc-reveal-hide="${msg("hidePassword")}"
                    aria-label="${msg("showPassword")}"
                    aria-pressed="false">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7"/><circle cx="12" cy="12" r="3"/></svg>
            </button>
          </div>
        </div>

        <#if realm.rememberMe && !usernameHidden??>
          <div class="kc-form-options">
            <label class="kc-checkbox">
              <input id="rememberMe" name="rememberMe" type="checkbox" <#if login.rememberMe??>checked</#if>>
              <span class="box" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12.5l4.5 4.5L19 7"/></svg></span>
              <span class="lbl">${msg("rememberMe")}</span>
            </label>
          </div>
        </#if>

        <input type="hidden" id="id-hidden-input" name="credentialId"
               <#if auth.selectedCredential?has_content>value="${auth.selectedCredential}"</#if>/>

        <button id="kc-login" class="kc-btn primary" name="login" type="submit">
          <span>${msg("doLogIn")}</span>
          <svg class="arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 12h14"/><path d="M13 5l7 7-7 7"/></svg>
        </button>
      </form>
    </#if>

  <#elseif section = "info">
    <#if realm.password && realm.registrationAllowed && !registrationDisabled??>
      ${msg("noAccount")} <a class="kc-link" href="${url.registrationUrl}">${msg("doRegister")}</a>
    <#else>
      ${msg("kumbukaNoRegistrationCopy")} <a class="kc-link" href="mailto:">${msg("kumbukaRequestAccess")}</a>.
    </#if>

  </#if>
</@layout.registrationLayout>
