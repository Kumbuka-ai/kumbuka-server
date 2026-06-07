# syntax=docker/dockerfile:1.7
# ---------------------------------------------------------------------------
# kumbuka-keycloak — production image
#
# Bakes the kumbuka theme directly into the Keycloak distribution so the
# container starts cold without a bind-mount and theme caching can stay on
# for normal performance. Use the `prod-kc` compose profile to swap this in
# for the bind-mount-based `keycloak` service used during local dev.
# ---------------------------------------------------------------------------

FROM quay.io/keycloak/keycloak:26.0 AS builder

# The build step lets Keycloak pre-compile its provider index so the first
# request after start is fast. (`kc.sh build` is a no-op for theme-only
# changes but keeps prod consistent with KC's recommended bake pattern.)
ENV KC_DB=postgres

# Theme files — copied into the image so caching can stay enabled.
COPY --chown=keycloak:keycloak themes/kumbuka /opt/keycloak/themes/kumbuka

RUN /opt/keycloak/bin/kc.sh build

# ---------------------------------------------------------------------------

FROM quay.io/keycloak/keycloak:26.0

COPY --from=builder /opt/keycloak/ /opt/keycloak/

ENTRYPOINT ["/opt/keycloak/bin/kc.sh", "start", "--optimized", "--import-realm"]
