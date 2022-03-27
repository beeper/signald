#!/usr/bin/env bash
set -exuo pipefail
chown -R signald:signald /signald
su -c "/bin/entrypoint.sh -d /signald -s /signald/signald.sock" signald