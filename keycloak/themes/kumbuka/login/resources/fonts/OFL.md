# Self-hosted webfonts — third-party licenses

The three webfont families bundled with this theme are licensed under the
**SIL Open Font License v1.1** (OFL-1.1). Their full text is reproduced in
each project's upstream `OFL.txt`; this file is a pointer to the upstream
source and copyright holders.

| Family | Weights bundled | Upstream | Copyright |
|---|---|---|---|
| Inter | 400, 500, 600 | <https://github.com/rsms/inter> | Copyright (c) The Inter Project Authors |
| Space Grotesk | 500, 600, 700 | <https://github.com/floriankarsten/space-grotesk> | Copyright (c) The Space Grotesk Project Authors |
| JetBrains Mono | 400, 500, 600 | <https://github.com/JetBrains/JetBrainsMono> | Copyright (c) 2020 The JetBrains Mono Project Authors |

Files are `woff2` only, **subset latin + latin-ext** as fetched from the
Google Webfonts Helper (<https://gwfh.mranftl.com/>). Variable-weight axes
were not preserved — each weight is a separate file so `@font-face`
declarations stay deterministic and the bundle stays under 400 KB.

The SIL OFL-1.1 grants redistribution under three rules:

1. Redistributed font binaries may not themselves be sold by themselves.
2. The license must travel with the files (this file + the upstream `OFL.txt`).
3. Reserved Font Names (the family names above) must not be reused for
   modified versions.

We redistribute the unmodified upstream binaries inside the kumbuka theme
strictly for self-hosting on the Keycloak login pages. No font is
re-sold, renamed, or shipped as a standalone bundle.
