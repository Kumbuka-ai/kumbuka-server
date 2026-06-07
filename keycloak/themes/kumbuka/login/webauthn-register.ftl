<#import "template.ftl" as layout>
<@layout.registrationLayout; section>

  <#if section = "header">
    <div class="kc-hero-ico accent" aria-hidden="true">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="9" r="3"/><path d="M5 20a7 7 0 0 1 14 0"/></svg>
    </div>
    <span class="kc-eyebrow">${msg("kumbukaEyebrowRegisterWebauthn")}</span>
    <h1 class="kc-title">${msg("webauthn-registration-title")}</h1>

  <#elseif section = "form">

    <form id="register" class="kc-form" action="${url.loginAction}" method="post"
          style="margin-top:26px">
      <div class="kc-field">
        <label class="kc-label" for="authenticatorLabel">
          ${msg("webauthn-authenticator-label")}
          <span class="opt">${msg("optional")}</span>
        </label>
        <input id="authenticatorLabel" name="authenticatorLabel" class="kc-input" type="text"
               placeholder="e.g. iPhone, YubiKey"
               value="${authenticatorLabel!''}">
      </div>

      <#-- KC's WebAuthn ceremony fields. Filled in by JS after navigator.credentials.create(). -->
      <input type="hidden" id="clientDataJSON" name="clientDataJSON"/>
      <input type="hidden" id="attestationObject" name="attestationObject"/>
      <input type="hidden" id="publicKeyCredentialId" name="publicKeyCredentialId"/>
      <input type="hidden" id="authenticatorLabel" name="authenticatorLabel"/>
      <input type="hidden" id="transports" name="transports"/>
      <input type="hidden" id="error" name="error"/>

      <button class="kc-btn primary" id="registerWebAuthn" type="button">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 5v14"/><path d="M5 12h14"/></svg>
        <span>${msg("doRegisterSecurityKey")}</span>
      </button>

      <#if isSetRetry?? && isSetRetry>
        <button type="button" class="kc-btn" id="registerWebAuthnRetry">${msg("doTryAgain")}</button>
        <script type="module">
          document.getElementById('registerWebAuthnRetry').addEventListener('click', () => document.getElementById('registerWebAuthn').click());
        </script>
      </#if>

      <#if isAppInitiatedAction??>
        <button class="kc-btn" type="submit" name="cancel-aia" value="true">${msg("doCancel")}</button>
      </#if>
    </form>

    <div class="kc-assure" role="note">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="9" r="3"/><path d="M5 20a7 7 0 0 1 14 0"/></svg>
      <p>The key never leaves this device. kumbuka only stores a public credential — it can verify you, but it can't impersonate you.</p>
    </div>

    <script type="module">
      import { registerByWebAuthn } from "${url.resourcesCommonPath}/js/webauthnRegister.js";
      const args = {
          challenge : '${challenge}',
          userid : '${userid}',
          username : '${username}',
          signatureAlgorithms : '${signatureAlgorithms}',
          rpEntityName : '${rpEntityName}',
          rpId : '${rpId}',
          attestationConveyancePreference : '${attestationConveyancePreference}',
          authenticatorAttachment : '${authenticatorAttachment}',
          requireResidentKey : '${requireResidentKey}',
          userVerificationRequirement : '${userVerificationRequirement}',
          createTimeout : ${createTimeout?c},
          excludeCredentialIds : '${excludeCredentialIds}',
          initLabel : "${msg('webauthn-registration-init-label')?no_esc}",
          initLabelPrompt : "${msg('webauthn-registration-init-label-prompt')?no_esc}",
          errmsg : "${msg('webauthn-unsupported-browser-text')?no_esc}"
      };
      document.getElementById('registerWebAuthn').addEventListener('click', () => registerByWebAuthn(args));
    </script>
  </#if>
</@layout.registrationLayout>
