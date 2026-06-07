<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('password','password-confirm'); section>

  <#if section = "header">
    <span class="kc-eyebrow">${msg("kumbukaEyebrowUpdatePassword")}</span>
    <h1 class="kc-title">${msg("updatePasswordTitle")}</h1>

  <#elseif section = "form">
    <form id="kc-passwd-update-form" class="kc-form" data-kc-disable-on-submit="kc-passwd-submit"
          action="${url.loginAction}" method="post" novalidate style="margin-top:26px">

      <div class="kc-field">
        <label class="kc-label" for="password-new">${msg("passwordNew")}</label>
        <div class="kc-input-wrap">
          <input id="password-new" name="password-new" class="kc-input" type="password"
                 autocomplete="new-password"
                 aria-invalid="<#if messagesPerField.existsError('password','password-confirm')>true</#if>"
                 placeholder="••••••••••">
          <button type="button" class="kc-reveal"
                  data-kc-reveal="password-new"
                  data-kc-reveal-show="${msg("showPassword")}"
                  data-kc-reveal-hide="${msg("hidePassword")}"
                  aria-label="${msg("showPassword")}" aria-pressed="false">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7"/><circle cx="12" cy="12" r="3"/></svg>
          </button>
        </div>
        <#if messagesPerField.existsError('password')>
          <span class="kc-input-error" aria-live="polite">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 8v4"/><path d="M12 16h.01"/></svg>
            ${kcSanitize(messagesPerField.get('password'))?no_esc}
          </span>
        </#if>
      </div>

      <div class="kc-field">
        <label class="kc-label" for="password-confirm">${msg("passwordConfirm")}</label>
        <div class="kc-input-wrap">
          <input id="password-confirm" name="password-confirm" class="kc-input" type="password"
                 autocomplete="new-password"
                 aria-invalid="<#if messagesPerField.existsError('password-confirm')>true</#if>"
                 placeholder="••••••••••">
          <button type="button" class="kc-reveal"
                  data-kc-reveal="password-confirm"
                  data-kc-reveal-show="${msg("showPassword")}"
                  data-kc-reveal-hide="${msg("hidePassword")}"
                  aria-label="${msg("showPassword")}" aria-pressed="false">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7"/><circle cx="12" cy="12" r="3"/></svg>
          </button>
        </div>
        <#if messagesPerField.existsError('password-confirm')>
          <span class="kc-input-error" aria-live="polite">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 8v4"/><path d="M12 16h.01"/></svg>
            ${kcSanitize(messagesPerField.get('password-confirm'))?no_esc}
          </span>
        </#if>
      </div>

      <#if isAppInitiatedAction??>
        <label class="kc-checkbox" style="margin-top:-2px">
          <input id="logout-sessions" name="logout-sessions" value="on" type="checkbox" checked>
          <span class="box" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12.5l4.5 4.5L19 7"/></svg></span>
          <span class="lbl">${msg("logoutOtherSessions")}</span>
        </label>
      </#if>

      <button id="kc-passwd-submit" class="kc-btn primary" type="submit">
        <span>${msg("doSubmit")}</span>
        <svg class="arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 12.5l4.5 4.5L19 7"/></svg>
      </button>

      <#if isAppInitiatedAction??>
        <button class="kc-btn" type="submit" name="cancel-aia" value="true">${msg("doCancel")}</button>
      </#if>
    </form>

    <div class="kc-assure" role="note">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="5" y="11" width="14" height="9" rx="1"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/></svg>
      <p>${msg("kumbukaPrivateAssurance")}</p>
    </div>
  </#if>
</@layout.registrationLayout>
