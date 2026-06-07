# ---------------------------------------------------------------------------
# justfile — common dev targets for kumbuka.ai
#   Install just:  brew install just
#   List targets:  just --list
# ---------------------------------------------------------------------------

set dotenv-load := true
set positional-arguments

# Default action: show available targets.
default:
    @just --list

# Bring up the full stack (postgres + keycloak + backend + frontend + caddy).
# Until Phase 2/6 land, the `app` profile services will fail to build — use
# `infra` for the parts that exist today.
up:
    docker compose --profile app up -d --build

# Bring up only infrastructure (postgres + keycloak + caddy). Useful in Phase 0/1.
infra:
    docker compose up -d postgres keycloak caddy

# Stop everything (preserves volumes).
down:
    docker compose down

# Stop everything AND drop volumes (destroys Keycloak + kumbuka data).
clean:
    docker compose down -v

# Tail logs for all services (or pass a service: `just logs kumbuka-backend`).
logs *args:
    docker compose logs -f {{args}}

# Show running containers.
ps:
    docker compose ps

# Restart a single service: `just restart kumbuka-backend`
restart service:
    docker compose restart {{service}}

# Open a psql shell against the kumbuka database.
psql:
    docker compose exec postgres psql -U $KUMBUKA_DB_USER -d $KUMBUKA_DB_NAME

# Open a psql shell against the keycloak database.
psql-keycloak:
    docker compose exec postgres psql -U $KEYCLOAK_DB_USER -d $KEYCLOAK_DB_NAME

# Run backend tests.
test:
    cd backend && mvn -B -ntp test

# Run backend integration tests (Testcontainers; Phase 11).
test-it:
    cd backend && mvn -B -ntp verify -Pintegration

# Validate Caddyfile syntax.
caddy-validate:
    docker run --rm -v $PWD/Caddyfile:/etc/caddy/Caddyfile caddy:2-alpine caddy validate --config /etc/caddy/Caddyfile

# Print the OAuth Protected Resource Metadata (sanity check, once backend is up).
prm:
    curl -fsS https://$KUMBUKA_DOMAIN/.well-known/oauth-protected-resource | jq .
