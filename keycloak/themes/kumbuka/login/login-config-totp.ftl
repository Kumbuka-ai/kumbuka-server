<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('totp','userLabel'); section>

  <#if section = "header">
    <span class="kc-eyebrow">${msg("kumbukaEyebrowConfigOtp")}</span>
    <h1 class="kc-title">${msg("loginTotpTitle")}</h1>
    <p class="kc-lead">${msg("loginTotpStep1")}</p>

  <#elseif section = "form">

    <ol class="kc-steps" style="margin-top:24px">
      <li>${msg("loginTotpStep1")} — <#list totp.supportedApplications as app><b>${app}</b><#sep>, </#list>.</li>
      <li>${msg("loginTotpStep2")}</li>
      <li>${msg("loginTotpStep3")}</li>
    </ol>

    <div class="kc-totp">
      <#if mode?? && mode = "manual">
        <#-- manual mode: show the secret prominently, no QR -->
        <div class="t-body">
          <h2>${msg("loginTotpManualStep2")}</h2>
          <p>${msg("loginTotpManualStep3")}</p>
          <div class="kc-secret">${totp.totpSecretEncoded}</div>
          <p style="margin-top:14px"><a class="kc-link" href="${totp.qrUrl}">${msg("loginTotpScanBarcode")}</a></p>
        </div>
      <#else>
        <#-- QR-code mode (default). The base ships a base64 PNG. -->
        <img class="kc-qr" src="data:image/png;base64, ${totp.totpSecretQrCode}"
             alt="${msg("loginTotpScanBarcode")}" width="132" height="132">
        <div class="t-body">
          <h2>${msg("loginTotpScanBarcode")}</h2>
          <p>${msg("loginTotpUnableToScan")}</p>
          <div class="kc-secret" aria-label="${msg("loginTotpManualStep2")}">${totp.totpSecretEncoded}</div>
          <p style="margin-top:14px"><a class="kc-link" href="${totp.manualUrl}">${msg("loginTotpUnableToScan")}</a></p>
        </div>
      </#if>
    </div>

    <form id="kc-totp-settings-form" class="kc-form" data-kc-disable-on-submit="kc-totp-submit"
          action="${url.loginAction}" method="post" novalidate>

      <div class="kc-field">
        <label class="kc-label" for="totp">
          ${msg("authenticatorCode")}
          <span class="opt">${msg("loginOtpDevice")}</span>
        </label>
        <input id="totp" name="totp" class="kc-input mono kc-otp-input" type="text"
               inputmode="numeric" autocomplete="one-time-code" maxlength="8"
               autofocus spellcheck="false" placeholder="000000"
               aria-invalid="<#if messagesPerField.existsError('totp')>true</#if>">
        <#if messagesPerField.existsError('totp')>
          <span class="kc-input-error" aria-live="polite">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 8v4"/><path d="M12 16h.01"/></svg>
            ${kcSanitize(messagesPerField.get('totp'))?no_esc}
          </span>
        </#if>
      </div>

      <div class="kc-field">
        <label class="kc-label" for="userLabel">
          ${msg("loginTotpDeviceName")}
          <span class="opt">${msg("optional")}</span>
        </label>
        <input id="userLabel" name="userLabel" class="kc-input" type="text"
               <#if totp.otpCredentials?size gte 1>value="${msg('loginTotpDeviceName')}"</#if>
               placeholder="e.g. iPhone"
               aria-invalid="<#if messagesPerField.existsError('userLabel')>true</#if>">
        <#if messagesPerField.existsError('userLabel')>
          <span class="kc-input-error" aria-live="polite">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 8v4"/><path d="M12 16h.01"/></svg>
            ${kcSanitize(messagesPerField.get('userLabel'))?no_esc}
          </span>
        </#if>
      </div>

      <#-- Logout-on-cancel flag (KC requires this hidden for the back-button to work). -->
      <#if isAppInitiatedAction??>
        <input type="hidden" id="logout-sessions" name="logout-sessions" value="on">
      </#if>

      <input type="hidden" id="totpSecret" name="totpSecret" value="${totp.totpSecret}">
      <#if mode??><input type="hidden" id="mode" value="${mode}"></#if>

      <button id="kc-totp-submit" class="kc-btn primary" type="submit" name="submitAction">
        <span>${msg("doSubmit")}</span>
        <svg class="arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 12.5l4.5 4.5L19 7"/></svg>
      </button>
      <#if isAppInitiatedAction??>
        <button class="kc-btn" type="submit" name="cancel-aia" value="true">${msg("doCancel")}</button>
      </#if>
    </form>

  </#if>
</@layout.registrationLayout>
