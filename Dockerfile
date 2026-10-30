# Hypernova's daemon on Ubuntu 24.04: the quick way to try it (docs/LINUX.md). The daemon runs in
# the foreground as the container's command; nothing is published to the network by default.
#
#   docker build -t hypernova .
#   docker run -d --name hypernova --hostname my-hypernova --restart unless-stopped \
#     --log-opt max-size=10m -v hypernova-home:/home/hypernova hypernova
#   docker exec -it hypernova claude            # sign in to Claude once
#   docker exec -it hypernova hypernova pair    # the pairing code
#
# Updating: docker build again, remove the container, run it again with the same volume (the new
# image's daemon runs; your phones and settings stay).
#
# The phone reaches the daemon only over the local route (the LAN listener, port 47323), so on a
# Linux host run it with --network host: a phone on the same network, or on a VPN into it, then
# pairs and connects directly. --build-arg CLAUDE=no leaves Claude Code out.
FROM ubuntu:24.04

ARG DEBIAN_FRONTEND=noninteractive
# What the install script would ask sudo for: curl, openssl, git, build tools for node-pty, ps and
# lsof. Installed here as root, so the script finds them all and needs no sudo. tini
# is the container's first process: it reaps the shared terminals' holders that end after their
# starter did (they're handed to process 1), which Node as process 1 wouldn't.
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl tar gzip xz-utils openssl git \
      build-essential python3 procps lsof less nano tini \
 && rm -rf /var/lib/apt/lists/*

# Claude Code and the daemon belong to a normal user, never root.
RUN useradd --create-home --shell /bin/bash hypernova \
 && mkdir -p /opt/hypernova/bin && chown -R hypernova:hypernova /opt/hypernova
USER hypernova
WORKDIR /home/hypernova
# The program (the daemon, its Node.js, the command) lives in the image under /opt/hypernova; only
# state (paired phones, settings, keys, Claude's login) lives in /home/hypernova, which the
# documented volume covers. A rebuilt image with the same volume therefore runs its new daemon.
ENV HYPERNOVA_APP=/opt/hypernova/app HYPERNOVA_NODE_ROOT=/opt/hypernova/node HYPERNOVA_NODE_PATH=/opt/hypernova/node-path HYPERNOVA_LAUNCHER=/opt/hypernova/bin/hypernova HYPERNOVA_CONTAINER=1
# Spelled out: an image imported from Ubuntu's base tarball has no PATH of its own to extend.
ENV PATH=/opt/hypernova/bin:/home/hypernova/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

COPY --chown=hypernova:hypernova daemon/package.json daemon/package-lock.json /tmp/hypernova/daemon/
COPY --chown=hypernova:hypernova daemon/src /tmp/hypernova/daemon/src
COPY --chown=hypernova:hypernova android/app/src/main/res/font/jetbrains_mono.ttf /tmp/hypernova/Fonts/JetBrainsMono.ttf
COPY --chown=hypernova:hypernova scripts/install-linux.sh /tmp/hypernova/scripts/install-linux.sh
COPY --chown=hypernova:hypernova scripts/docker-entrypoint.sh /opt/hypernova/entrypoint.sh

# --no-integration: the hooks carry the daemon's secret, so they're put in at each volume's first
# start (the entrypoint), never baked into the image: no two containers share an identity.
ARG CLAUDE=yes
RUN bash /tmp/hypernova/scripts/install-linux.sh --from /tmp/hypernova --yes --no-integration $([ "$CLAUDE" = no ] && echo --no-claude) \
 && rm -rf /tmp/hypernova \
 && rm -f /home/hypernova/.local/share/hypernova/daemon.json /home/hypernova/.local/share/hypernova/admin-token /home/hypernova/.local/share/hypernova/hook-secret

# hypernova run: the daemon in the foreground, its log on the container's output too. Healthy when
# the phone API answers on the container's own loopback (nothing is published). Run it with
# `--restart unless-stopped` (and `--log-opt max-size=10m` for the container's own log).
ENTRYPOINT ["/usr/bin/tini", "--", "/opt/hypernova/entrypoint.sh"]
CMD ["hypernova", "run"]
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s CMD curl -fsS --max-time 4 http://127.0.0.1:47321/v1/health >/dev/null || exit 1
