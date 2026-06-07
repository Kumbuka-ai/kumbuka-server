<#--
 kumbuka.ai — invite / set-up-credentials email (html).
 Triggered when an admin runs "Execute actions email" with
 UPDATE_PASSWORD + VERIFY_EMAIL on a freshly-created user.
 No password is set here; the recipient creates one via ${link}.
-->
<!doctype html>
<html lang="en" xmlns="http://www.w3.org/1999/xhtml">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="x-ua-compatible" content="ie=edge">
<title>${msg("executeActionsSubject")}</title>
<!--[if mso]><style>* {font-family: Arial, sans-serif !important;}</style><![endif]-->
</head>
<body style="margin:0;padding:0;background:#1b2330;">
<div style="display:none;max-height:0;overflow:hidden;opacity:0;">${msg("executeActionsBodyText")}</div>

<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#1b2330;">
<tr><td align="center" style="padding:32px 16px;">
  <table role="presentation" width="600" cellpadding="0" cellspacing="0" style="width:600px;max-width:600px;">

    <tr><td style="background:#0F1620;padding:22px 32px;border:1px solid rgba(244,241,234,0.12);border-bottom:0;">
      <table role="presentation" cellpadding="0" cellspacing="0"><tr>
        <td style="padding-right:11px;"><img src="${url.resourcesUrl}/img/kumbuka-mark.png" width="32" height="32" alt="kumbuka" style="display:block;border:0;"></td>
        <td style="font-family:'Space Grotesk',Arial,sans-serif;font-size:18px;font-weight:600;color:#F4F1EA;letter-spacing:-0.01em;">kumbuka<span style="color:#FF5B1F;">.ai</span></td>
      </tr></table>
    </td></tr>

    <tr><td style="background:#F4F1EA;padding:36px 32px 30px;border:1px solid rgba(244,241,234,0.12);border-top:0;">
      <p style="margin:0 0 14px;font-family:'JetBrains Mono',monospace;font-size:11px;letter-spacing:0.14em;text-transform:uppercase;color:#FF5B1F;">// you're invited</p>
      <h1 style="margin:0 0 14px;font-family:'Space Grotesk',Arial,sans-serif;font-size:26px;font-weight:600;letter-spacing:-0.02em;color:#141820;line-height:1.1;">${msg("executeActionsSubject")}</h1>
      <p style="margin:0 0 18px;font-family:'Inter',Arial,sans-serif;font-size:15px;line-height:1.55;color:rgba(15,22,32,0.78);">
        ${msg("executeActionsBodyHtml")?no_esc}
      </p>
      <p style="margin:0 0 26px;font-family:'Inter',Arial,sans-serif;font-size:14px;line-height:1.55;color:rgba(15,22,32,0.55);">
        No password has been set yet — you'll create your sign-in credentials on the next screen.
      </p>

      <table role="presentation" cellpadding="0" cellspacing="0"><tr>
        <td bgcolor="#FF5B1F" style="background:#FF5B1F;">
          <a href="${link}" style="display:inline-block;padding:14px 26px;font-family:'JetBrains Mono',Arial,sans-serif;font-size:12px;font-weight:600;letter-spacing:0.08em;text-transform:uppercase;color:#1a0d05;text-decoration:none;">${msg("executeActionsCta")} →</a>
        </td>
      </tr></table>

      <p style="margin:24px 0 0;font-family:'Inter',Arial,sans-serif;font-size:12.5px;line-height:1.55;color:rgba(15,22,32,0.55);">
        ${msg("executeActionsExpires",linkExpirationFormatter(linkExpiration))} If the button doesn't work, paste this link into your browser:
      </p>
      <p style="margin:8px 0 0;font-family:'JetBrains Mono',monospace;font-size:12px;color:#2D4059;word-break:break-all;">${link}</p>

      <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="margin-top:26px;border:1px dashed rgba(15,22,32,0.18);background:#EBE6DB;"><tr>
        <td style="padding:13px 15px;font-family:'Inter',Arial,sans-serif;font-size:12px;line-height:1.5;color:rgba(15,22,32,0.55);">
          ${msg("kumbukaPrivateAssurance")}
        </td>
      </tr></table>
    </td></tr>

    <tr><td style="padding:18px 32px 0;">
      <p style="margin:0;font-family:'JetBrains Mono',monospace;font-size:10px;letter-spacing:0.06em;color:rgba(244,241,234,0.5);">
        ${msg("kumbukaPrivacyFooter")}
      </p>
    </td></tr>

  </table>
</td></tr>
</table>
</body>
</html>
