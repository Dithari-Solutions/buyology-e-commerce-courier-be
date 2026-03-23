#!/usr/bin/env bash
# deploy/setup-server.sh
#
# One-time server bootstrap for buyology-courier.
# Run this ONCE on a fresh VPS as root before the first CI/CD deploy.
#
# Usage:
#   chmod +x deploy/setup-server.sh
#   scp deploy/setup-server.sh root@<server>:/tmp/
#   ssh root@<server> bash /tmp/setup-server.sh

set -euo pipefail

DEPLOY_PATH="/opt/buyology-e-commerce-courier-be"

echo "=== buyology-courier server bootstrap ==="

# ── 1. Install Docker if missing ─────────────────────────────────────────────
if ! command -v docker &>/dev/null; then
  echo "[1/4] Installing Docker..."
  apt-get update -qq
  apt-get install -y -qq ca-certificates curl gnupg lsb-release

  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
    | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
  chmod a+r /etc/apt/keyrings/docker.gpg

  echo \
    "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
     https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" \
    > /etc/apt/sources.list.d/docker.list

  apt-get update -qq
  apt-get install -y -qq docker-ce docker-ce-cli containerd.io \
    docker-buildx-plugin docker-compose-plugin

  systemctl enable --now docker
  echo "[1/4] Docker installed."
else
  echo "[1/4] Docker already installed: $(docker --version)"
fi

# ── 2. Create deploy directory ───────────────────────────────────────────────
echo "[2/4] Creating deploy directory: $DEPLOY_PATH"
mkdir -p "$DEPLOY_PATH"
chmod 750 "$DEPLOY_PATH"

# ── 3. Create placeholder .env (CI/CD will overwrite on first deploy) ─────────
echo "[3/4] Writing placeholder .env..."
if [ ! -f "$DEPLOY_PATH/.env" ]; then
  cat > "$DEPLOY_PATH/.env" << 'ENVEOF'
# This file is managed by CI/CD. Do not edit manually.
# It will be overwritten on the next GitHub Actions deploy run.
SPRING_PROFILES_ACTIVE=prod
IMAGE_TAG=buyology-courier:local
DB_URL=jdbc:postgresql://postgres:5432/buyology_courier
DB_USERNAME=CHANGE_ME
DB_PASSWORD=CHANGE_ME
DB_POOL_SIZE=30
DB_POOL_MIN_IDLE=10
RABBITMQ_HOST=rabbitmq
RABBITMQ_USERNAME=CHANGE_ME
RABBITMQ_PASSWORD=CHANGE_ME
REDIS_HOST=redis
REDIS_PASSWORD=CHANGE_ME
JWT_ISSUER_URI=http://localhost:8080/realms/buyology
AUTH_JWT_SECRET=CHANGE_ME_AT_LEAST_32_CHARS_LONG
AUTH_ADMIN_JWT_AUDIENCE=buyology-courier-service
CORS_ALLOWED_ORIGINS=https://dev.dithari.com
ENVEOF
  chmod 600 "$DEPLOY_PATH/.env"
  echo "[3/4] Placeholder .env created."
else
  echo "[3/4] .env already exists — skipping."
fi

# ── 4. Verify docker compose is available ────────────────────────────────────
echo "[4/4] Verifying docker compose..."
docker compose version

echo ""
echo "=== Bootstrap complete ==="
echo ""
echo "Next steps:"
echo "  1. Configure GitHub Actions secrets (see list below)"
echo "  2. Push to main — CI/CD will deploy automatically"
echo ""
echo "Required GitHub Actions secrets:"
echo "  DEPLOY_HOST              - this server's IP or hostname"
echo "  DEPLOY_USER              - SSH user (e.g. root)"
echo "  DEPLOY_SSH_KEY           - SSH private key (add public key to ~/.ssh/authorized_keys)"
echo "  DB_URL                   - jdbc:postgresql://postgres:5432/buyology_courier"
echo "  DB_USERNAME              - postgres user (e.g. buyology)"
echo "  DB_PASSWORD              - postgres password"
echo "  RABBITMQ_USERNAME        - RabbitMQ user"
echo "  RABBITMQ_PASSWORD        - RabbitMQ password"
echo "  REDIS_PASSWORD           - Redis password (leave empty to disable auth)"
echo "  JWT_ISSUER_URI           - Keycloak issuer URL"
echo "  AUTH_JWT_SECRET          - min 32-char HMAC secret for courier JWTs"
echo "  AUTH_ADMIN_JWT_AUDIENCE  - e.g. buyology-courier-service"
echo "  CORS_ALLOWED_ORIGINS     - comma-separated allowed origins"
