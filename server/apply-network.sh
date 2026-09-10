#!/usr/bin/env bash
set -euo pipefail
# The health timer and systemd network unit can invoke this script at the same
# time during installation or recovery. Serialize nft table replacement so
# one invocation cannot delete a table while the other is adding rules.
exec 9>/run/lock/flowlink-network.lock
flock -x 9
WAN_IF=$(ip route show default | awk 'NR==1 {print $5}')
WG_PORT=$(python3 -c 'import json; print(json.load(open("/etc/flowlink/node.json"))["listen_port"])')
WG_SUBNET=$(python3 -c 'import json; print(json.load(open("/etc/flowlink/node.json"))["subnet"])')
HTTPS_PORT=$(python3 -c 'import json; print(json.load(open("/etc/flowlink/node.json"))["https_port"])')
PORTS=$(/opt/flowlink-server/flowlinkctl.py effective-ports --plain)
STABLE_PORTS=$(python3 -c 'import json; print(" ".join(map(str,json.load(open("/etc/flowlink/node.json"))["stable_ports"])))')
MANAGED_FILE=/var/lib/flowlink/managed-udp-ports
test -n "$WAN_IF"
install -d -m 0700 /var/lib/flowlink
OLD_PORTS=$(cat "$MANAGED_FILE" 2>/dev/null || true)
for port in $PORTS; do
  ufw allow "$port/udp" >/dev/null
done
ufw allow "$HTTPS_PORT/tcp" >/dev/null
for old_port in $OLD_PORTS; do
  keep=false
  for current_port in $PORTS $STABLE_PORTS; do
    if [ "$old_port" = "$current_port" ]; then keep=true; fi
  done
  if [ "$keep" = false ]; then
    ufw --force delete allow "$old_port/udp" >/dev/null || true
  fi
done
printf '%s\n' "$PORTS" > "$MANAGED_FILE"
chmod 0600 "$MANAGED_FILE"
nft delete table ip flowlink_nat 2>/dev/null || true
nft add table ip flowlink_nat
nft 'add chain ip flowlink_nat prerouting { type nat hook prerouting priority dstnat; policy accept; }'
nft 'add chain ip flowlink_nat postrouting { type nat hook postrouting priority srcnat; policy accept; }'
for port in $PORTS; do
  if [ "$port" != "$WG_PORT" ]; then
    nft add rule ip flowlink_nat prerouting iifname "$WAN_IF" udp dport "$port" redirect to "$WG_PORT"
  fi
done
nft add rule ip flowlink_nat postrouting ip saddr "$WG_SUBNET" oifname "$WAN_IF" masquerade
sysctl -q -w net.ipv4.ip_forward=1
