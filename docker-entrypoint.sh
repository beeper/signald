#!/bin/bash
set -euo pipefail

if [[ "${SIGNALD_DATABASE:-}" == "postgres"* ]] && [[ -f "/signald/signald.db" ]]; then
  echo "signald is configured to use a postgres database, but a sqlite file was found. running migration before starting"
  /bin/signaldctl db-move "${SIGNALD_DATABASE}" /signald/signald.db || (echo "database move failed, leaving container running for 10 minutes" && sleep 600 && exit 1)
fi

/bin/signald "$@"
