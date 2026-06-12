<#--
 kumbuka.ai — identity-first login, step 1 (username / email).

 KC's Organizations / identity-first flow splits sign-in into two pages:
 this one collects the identifier, login-password.ftl collects the secret.
 The keycloak.v2 parent ships its own login-username.ftl built on the
 PatternFly field/buttons macros — which our CSS does not style — so we
 override it with the brand markup. Field names, the action URL and the
 messagesPerField contract are kept identical to the parent so the flow
 is unchanged; only the presentation differs.

 Note: the parent's header renders `${r"${msg(\"loginAccountTitle\")}"}` with no
 argument, so our `loginAccountTitle=Sign in to {0}` bundle entry left a
 literal `{0}`. We pass `realm.displayName` here, the same as login.ftl.
-->
<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('username') displayInfo=(realm.password && realm.registrationAllowed && !registrationDisabled??); section>

  <#if section = "header">
    <div class="kc-context">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M9 15l6-6"/><path d="M10 7l1-1a4 4 0 0 1 6 6l-1 1"/><path d="M14 17l-1 1a4 4 0 0 1-6-6l1-1"/></svg>
      <span>${msg("kumbukaContinueTo")} <b>${(realm.displayName!"kumbuka memory console")?no_esc}</b></span>
    </div>
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
                   autocomplete="username webauthn"
                   autofocus
                   spellcheck="false"
                   aria-invalid="<#if messagesPerField.existsError('username')>true</#if>"
                   placeholder="you@${(realm.name!"kumbuka.ai")}">
            <#if messagesPerField.existsError('username')>
              <span class="kc-input-error" id="input-error" aria-live="polite">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 8v4"/><path d="M12 16h.01"/></svg>
                ${kcSanitize(messagesPerField.getFirstError('username'))?no_esc}
              </span>
            </#if>
          </div>
        </#if>

        <#if realm.rememberMe && !usernameHidden??>
          <div class="kc-form-options">
            <label class="kc-checkbox">
              <input id="rememberMe" name="rememberMe" type="checkbox" <#if login.rememberMe??>checked</#if>>
              <span class="box" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12.5l4.5 4.5L19 7"/></svg></span>
              <span class="lbl">${msg("rememberMe")}</span>
            </label>
          </div>
        </#if>

        <button id="kc-login" class="kc-btn primary" name="login" type="submit">
          <span>${msg("doContinue")}</span>
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
