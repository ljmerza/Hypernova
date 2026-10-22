#!/bin/sh
# The container's first step (Dockerfile): the integration (hooks, status line, the claude function)
# is put in with THIS volume's own secrets, each time the container starts, so a fresh volume gets
# its own identity and a rebuilt image heals whatever the old one wrote. Then the command.
set -u
if ! hypernova integrate >/dev/null 2>&1; then
  echo "hypernova: couldn't set up the hooks and the claude function; run hypernova doctor" >&2
fi
exec "$@"
