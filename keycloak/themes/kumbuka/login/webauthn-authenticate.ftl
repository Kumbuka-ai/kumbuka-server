<#import "template.ftl" as layout>
<@layout.registrationLayout; section>

  <#if section = "header">
    <#if auth?has_content && auth.showUsername()>
      <div class="kc-context">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M7 11V8a5 5 0 0 1 10 0v3"/><path d="M5 11h14v9H5z"/><path d="M12 15v2"/></svg>
        <span>${msg("loginTitleHtml")} <b>${(auth.attemptedUsername!"")}</b></span>
      </div>
    </#if>
    <div class="kc-hero-ico accent" aria-hidden="true">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="9" r="3"/><path d="M5 20a7 7 0 0 1 14 0"/></svg>
    </div>
    <span class="kc-eyebrow">${msg("kumbukaEyebrowWebauthn")}</span>
    <h1 class="kc-title">${msg("webauthn-login-title-passwordless")}</h1>

  <#elseif section = "form">

    <#-- KC's WebAuthn ceremony forms — left in their canonical structure. -->
    <#if authenticators??>
      <form id="authn_select" class="kc-form">
        <#list authenticators.authenticators as authenticator>
          <input type="hidden" name="authn_use_chk" value="${authenticator.credentialId}"/>
        </#list>
      </form>
    </#if>

    <div id="modal">
      <input type="hidden" id="isUserIdentified" value="${isUserIdentified}"/>
    </div>

    <form id="webauth" class="kc-form" action="${url.loginAction}" method="post"
          style="margin-top:26px">
      <input type="hidden" id="clientDataJSON" name="clientDataJSON"/>
      <input type="hidden" id="authenticatorData" name="authenticatorData"/>
      <input type="hidden" id="signature" name="signature"/>
      <input type="hidden" id="credentialId" name="credentialId"/>
      <input type="hidden" id="userHandle" name="userHandle"/>
      <input type="hidden" id="error" name="error"/>
      <button class="kc-btn primary" type="button" id="authenticateWebAuthnButton" autofocus>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="9" r="3"/><path d="M5 20a7 7 0 0 1 14 0"/></svg>
        <span>${msg("webauthn-doAuthenticate")}</span>
      </button>
    </form>

    <#-- Registered authenticators (display-only). -->
    <#if authenticators?? && authenticators.authenticators?size gt 0>
      <div class="kc-choices" style="margin-top:22px;margin-bottom:0">
        <#list authenticators.authenticators as authenticator>
          <div class="kc-choice" style="cursor:default">
            <span class="ch-ico">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="6" y="3" width="12" height="18" rx="2"/><path d="M11 18h2"/></svg>
            </span>
            <span>
              <span class="ch-name">${authenticator.label!"Security key"}</span>
              <#if authenticator.createdAt??>
                <span class="ch-sub">${msg("webauthn-createdAt-label")} ${authenticator.createdAt}</span>
              </#if>
            </span>
          </div>
        </#list>
      </div>
    </#if>

    <div class="kc-card-foot">
      <a class="kc-link" href="${url.loginAction}?try_another_way=true">${msg("doTryAnotherWay")}</a>
    </div>

    <script type="module">
      import { authenticateByWebAuthn } from "${url.resourcesCommonPath}/js/webauthnAuthenticate.js";
      const args = {
          isUserIdentified : '${isUserIdentified}',
          challenge : '${challenge}',
          userVerification : '${userVerification}',
          rpId : '${rpId}',
          createTimeout : ${createTimeout?c},
          errmsg : "${msg('webauthn-unsupported-browser-text')?no_esc}"
      };
      document.getElementById("authenticateWebAuthnButton").addEventListener("click", () => authenticateByWebAuthn(args));
    </script>

  </#if>
</@layout.registrationLayout>
