<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('totp'); section>

  <#if section = "header">
    <#if auth?has_content && auth.showUsername()>
      <div class="kc-context">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M7 11V8a5 5 0 0 1 10 0v3"/><path d="M5 11h14v9H5z"/><path d="M12 15v2"/></svg>
        <span>${msg("loginTitleHtml")} <b>${(auth.attemptedUsername!"")}</b></span>
      </div>
    </#if>
    <span class="kc-eyebrow">${msg("kumbukaEyebrowOtp")}</span>
    <h1 class="kc-title">${msg("loginOtpOneTime")}</h1>
    <p class="kc-lead">${msg("loginOtpInstruction","kumbuka.ai")}</p>

  <#elseif section = "form">
    <form id="kc-otp-login-form" class="kc-form" data-kc-disable-on-submit="kc-otp-submit"
          action="${url.loginAction}" method="post" novalidate style="margin-top:26px">

      <#-- If the user enrolled multiple OTP credentials, KC supplies a radio list. -->
      <#if otpLogin.userOtpCredentials?? && otpLogin.userOtpCredentials?size gt 1>
        <div class="kc-field">
          <span class="kc-label">${msg("loginOtpDevice")}</span>
          <div class="kc-choices">
            <#list otpLogin.userOtpCredentials as otpCredential>
              <label class="kc-choice" style="cursor:pointer">
                <span class="ch-ico"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="5" y="3" width="14" height="18" rx="2"/><path d="M12 17h.01"/></svg></span>
                <span class="ch-name">${otpCredential.userLabel!"Authenticator"}</span>
                <input type="radio" name="selectedCredentialId" value="${otpCredential.id}"
                       <#if otpCredential.id == otpLogin.selectedCredentialId>checked</#if>>
              </label>
            </#list>
          </div>
        </div>
      </#if>

      <div class="kc-field">
        <label class="kc-label" for="otp">${msg("loginOtpCode")}</label>
        <input id="otp" name="otp" class="kc-input mono kc-otp-input" type="text"
               inputmode="numeric" autocomplete="one-time-code" maxlength="8"
               autofocus spellcheck="false" placeholder="000000"
               aria-invalid="<#if messagesPerField.existsError('totp')>true</#if>">
        <#if messagesPerField.existsError('totp')>
          <span class="kc-input-error" id="input-error-otp-code" aria-live="polite">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 8v4"/><path d="M12 16h.01"/></svg>
            ${kcSanitize(messagesPerField.get('totp'))?no_esc}
          </span>
        </#if>
      </div>

      <button id="kc-otp-submit" class="kc-btn primary" name="login" type="submit">
        <span>${msg("doLogIn")}</span>
        <svg class="arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 12h14"/><path d="M13 5l7 7-7 7"/></svg>
      </button>
    </form>

    <div class="kc-card-foot">
      <a class="kc-link" href="${url.loginAction}?try_another_way=true">${msg("doTryAnotherWay")}</a>
    </div>
  </#if>
</@layout.registrationLayout>
