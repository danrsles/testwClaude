#!/bin/bash
# Redeploys the API on its EC2 instance: pull the newest image, restart the
# container, and wait until it answers. CI sends this through SSM Run Command
# after publishing a new danrsles/wants-api:latest (see the deploy-api job in
# .github/workflows/docker-publish.yml); it runs as root on the instance.
#
# Exits non-zero, failing the CI job, if the API is not healthy within the time
# limit. There is no automatic rollback: latest has already moved on.
#
# No `set -x`: .env holds the database password and the origin secret.
set -euo pipefail

cd /opt/wants

docker compose pull app
# Recreates the container only if the image changed. Spring takes some seconds
# to start, so requests during the switch get a 502 from CloudFront.
docker compose up -d app

# The API rejects requests without CloudFront's header, so the health check
# sends it too. .env is written in shell syntax by the boot script.
set -a
# shellcheck disable=SC1091
. ./.env
set +a

for attempt in $(seq 1 40); do
  if curl -fsS -o /dev/null --max-time 5 \
      -H "X-Origin-Secret: ${ORIGIN_SECRET}" http://localhost:8080/wants; then
    echo "API healthy after ${attempt} check(s), running image $(docker inspect --format '{{.Image}}' "$(docker compose ps -q app)")"
    # Old images are no longer needed; keeps the 20 GB disk from filling up.
    docker image prune -f > /dev/null
    exit 0
  fi
  sleep 3
done

echo "API did not become healthy within 2 minutes. Last log lines:" >&2
docker compose logs --tail 60 app >&2
exit 1
