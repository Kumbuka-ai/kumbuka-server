<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('username') displayInfo=true; section>

  <#if section = "header">
    <span class="kc-eyebrow">${msg("kumbukaEyebrowReset")}</span>
    <h1 class="kc-title">${msg("emailForgotTitle")}</h1>
    <p class="kc-lead">${msg("emailInstruction")}</p>

  <#elseif section = "form">
    <form id="kc-reset-password-form" class="kc-form" data-kc-disable-on-submit="kc-reset-submit"
          action="${url.loginAction}" method="post" novalidate style="margin-top:26px">
      <div class="kc-field">
        <label class="kc-label" for="username">
          <#if !realm.loginWithEmailAllowed>${msg("username")}
          <#elseif !realm.registrationEmailAsUsername>${msg("usernameOrEmail")}
          <#else>${msg("email")}</#if>
        </label>
        <input id="username" name="username" class="kc-input mono" type="text"
               autocomplete="username" autofocus spellcheck="false"
               placeholder="you@${(realm.name!"kumbuka.ai")}"
               value="${(auth.attemptedUsername!'')}"
               aria-invalid="<#if messagesPerField.existsError('username')>true</#if>">
        <#if messagesPerField.existsError('username')>
          <span class="kc-input-error" aria-live="polite">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 8v4"/><path d="M12 16h.01"/></svg>
            ${kcSanitize(messagesPerField.get('username'))?no_esc}
          </span>
        </#if>
      </div>
      <button id="kc-reset-submit" class="kc-btn primary" type="submit">
        <span>${msg("doSubmit")}</span>
        <svg class="arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 12h14"/><path d="M13 5l7 7-7 7"/></svg>
      </button>
    </form>

  <#elseif section = "info">
    <a class="kc-link" href="${url.loginUrl}">${kcSanitize(msg("backToLogin"))?no_esc}</a>
  </#if>
</@layout.registrationLayout>
