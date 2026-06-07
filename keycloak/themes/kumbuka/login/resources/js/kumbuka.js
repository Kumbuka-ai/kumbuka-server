/* kumbuka login theme — CSP-safe enhancers.
 * No inline scripts; everything wires up on DOMContentLoaded against
 * data-* attributes the templates put on the markup.
 *
 * Currently:
 *   1. Password-reveal buttons (data-kc-reveal="<input-id>").
 *   2. Locale switcher — navigates on <select> change. */
(function () {
  "use strict";

  function setupReveal(btn) {
    var targetId = btn.getAttribute("data-kc-reveal");
    if (!targetId) return;
    var input = document.getElementById(targetId);
    if (!input) return;

    var labels = {
      show: btn.getAttribute("data-kc-reveal-show") || "Show password",
      hide: btn.getAttribute("data-kc-reveal-hide") || "Hide password"
    };

    btn.addEventListener("click", function () {
      var isPassword = input.type === "password";
      input.type = isPassword ? "text" : "password";
      btn.setAttribute("aria-pressed", isPassword ? "true" : "false");
      btn.setAttribute("aria-label", isPassword ? labels.hide : labels.show);
    });
  }

  function setupDisableOnSubmit() {
    var forms = document.querySelectorAll("form[data-kc-disable-on-submit]");
    for (var i = 0; i < forms.length; i++) {
      (function (form) {
        form.addEventListener("submit", function () {
          var btnId = form.getAttribute("data-kc-disable-on-submit");
          var btn = btnId ? document.getElementById(btnId) : null;
          if (btn) {
            btn.disabled = true;
          }
        });
      })(forms[i]);
    }
  }

  function setupLocaleSwitcher() {
    var sel = document.getElementById("kc-locale-select");
    if (!sel) return;
    sel.addEventListener("change", function () {
      if (sel.value) {
        window.location = sel.value;
      }
    });
  }

  document.addEventListener("DOMContentLoaded", function () {
    var buttons = document.querySelectorAll("[data-kc-reveal]");
    for (var i = 0; i < buttons.length; i++) {
      setupReveal(buttons[i]);
    }
    setupLocaleSwitcher();
    setupDisableOnSubmit();
  });
})();
