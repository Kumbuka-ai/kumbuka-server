<#--
 kumbuka.ai — identity-first login, step 2 (password).

 Second page of the identity-first / Organizations flow: the identifier
 was collected by login-username.ftl, this page collects the password.
 Overrides the keycloak.v2 parent (PatternFly field/buttons macros our CSS
 doesn't style). Field name, action URL and the messagesPerField contract
 match the parent so the flow is unchanged.

 The "signing in as <user>" line + restart link is the brand equivalent of
 keycloak.v2's `<@username/>` affordance (template.ftl), which our custom
 template.ftl does not carry. It is rendered only when Keycloak says the
 attempted username should be shown.
-->
<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('password'); section>

  <#if section = "header">
    <#if auth?has_content && auth.showUsername() && !auth.showResetCredentials()>
      <div class="kc-context">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/></svg>
        <span>${msg("kumbukaSigningInAs")} <b>${auth.attemptedUsername}</b>
          &nbsp;·&nbsp;<a class="kc-link muted" href="${url.loginRestartFlowUrl}">${msg("kumbukaNotYou")}</a></span>
      </div>
    </#if>
    <span class="kc-eyebrow">${msg("kumbukaEyebrowSignIn")}</span>
    <h1 class="kc-title">${msg("kumbukaPasswordTitle")}</h1>

  <#elseif section = "form">
    <form id="kc-form-login" class="kc-form" data-kc-disable-on-submit="kc-login"
          action="${url.loginAction}" method="post" novalidate>
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
                 autofocus
                 aria-invalid="<#if messagesPerField.existsError('password')>true</#if>"
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
        <#if messagesPerField.existsError('password')>
          <span class="kc-input-error" id="input-error" aria-live="polite">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 8v4"/><path d="M12 16h.01"/></svg>
            ${kcSanitize(messagesPerField.getFirstError('password'))?no_esc}
          </span>
        </#if>
      </div>

      <button id="kc-login" class="kc-btn primary" name="login" type="submit">
        <span>${msg("doLogIn")}</span>
        <svg class="arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 12h14"/><path d="M13 5l7 7-7 7"/></svg>
      </button>
    </form>

  </#if>
</@layout.registrationLayout>
