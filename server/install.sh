#!/usr/bin/env bash
set -euo pipefail
if [ "$EUID" -ne 0 ]; then
  echo "Run as root"
  exit 1
fi
SOURCE_DIR="$(cd "$(dirname "$0")" && pwd)"
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y wireguard-tools nftables python3 openssl ufw curl ca-certificates
install -d -m 0755 /opt/flowlink-server
install -d -m 0700 /etc/flowlink /etc/flowlink/tls /etc/wireguard /var/lib/flowlink
for file in flowlink_server.py flowlink_core.py flowlinkctl.py apply-network.sh; do
  if [ "$SOURCE_DIR/$file" != "/opt/flowlink-server/$file" ]; then
    install -m 0755 "$SOURCE_DIR/$file" "/opt/flowlink-server/$file"
  else
    chmod 0755 "/opt/flowlink-server/$file"
  fi
done
if [ "$SOURCE_DIR/README.md" != "/opt/flowlink-server/README.md" ]; then
  install -m 0644 "$SOURCE_DIR/README.md" /opt/flowlink-server/README.md
else
  chmod 0644 /opt/flowlink-server/README.md
fi
install -m 0644 "$SOURCE_DIR/"*.service "$SOURCE_DIR/"*.timer /etc/systemd/system/
if [ ! -f /etc/wireguard/flwg0.key ]; then
  umask 077
  wg genkey > /etc/wireguard/flwg0.key
  wg pubkey < /etc/wireguard/flwg0.key > /etc/wireguard/flwg0.pub
fi
if [ ! -f /etc/wireguard/flwg0.conf ]; then
  PRIVATE_KEY=$(cat /etc/wireguard/flwg0.key)
  cat > /etc/wireguard/flwg0.conf <<EOF
[Interface]
Address = 10.77.0.1/24
ListenPort = 51820
PrivateKey = $PRIVATE_KEY
MTU = 1380
EOF
  chmod 0600 /etc/wireguard/flwg0.conf
fi
PUBLIC_KEY=$(cat /etc/wireguard/flwg0.pub)
PUBLIC_IP=${FLOWLINK_ENDPOINT:-$(ip -4 route get 1.1.1.1 | awk '{for(i=1;i<=NF;i++) if($i=="src"){print $(i+1); exit}}')}
NODE_ID=${FLOWLINK_NODE_ID:-$(hostname -s)}
NEW_CONFIG=0
if [ ! -f /etc/flowlink/node.json ] || [ "${FLOWLINK_RECONFIGURE:-0}" = "1" ]; then
NEW_CONFIG=1
cat > /etc/flowlink/node.json <<EOF
{
  "version": 3,
  "node_id": "$NODE_ID",
  "endpoint": "$PUBLIC_IP",
  "interface": "flwg0",
  "listen_port": 51820,
  "https_port": 443,
  "ports": [443, 2053, 8443, 51820],
  "stable_ports": [443, 2053, 8443, 51820],
  "rotating_port_count": 8,
  "rotating_port_range": [20000, 60000],
  "rotation_interval_seconds": 21600,
  "port_grace_seconds": 43200,
  "subnet": "10.77.0.0/24",
  "dns": ["1.1.1.1", "8.8.8.8"],
  "mtu": 1380,
  "persistent_keepalive": 25,
  "server_public_key": "$PUBLIC_KEY"
}
EOF
chmod 0600 /etc/flowlink/node.json
fi
if [ ! -f /etc/flowlink/tls/server.key ]; then
  umask 077
  if [[ "$PUBLIC_IP" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    TLS_SAN="IP:$PUBLIC_IP"
  else
    TLS_SAN="DNS:$PUBLIC_IP"
  fi
  openssl req -x509 -newkey rsa:3072 -sha256 -nodes -days 825 \
    -keyout /etc/flowlink/tls/server.key \
    -out /etc/flowlink/tls/server.crt \
    -subj "/CN=$PUBLIC_IP" \
    -addext "subjectAltName=$TLS_SAN" \
    -addext "keyUsage=digitalSignature,keyEncipherment" \
    -addext "extendedKeyUsage=serverAuth"
fi
chmod 0600 /etc/flowlink/tls/server.key /etc/flowlink/tls/server.crt
openssl x509 -in /etc/flowlink/tls/server.crt -noout -fingerprint -sha256 \
  | cut -d= -f2 > /etc/flowlink/tls/fingerprint.sha256
chmod 0644 /etc/flowlink/tls/fingerprint.sha256
cat > /etc/sysctl.d/99-flowlink.conf <<EOF
net.ipv4.ip_forward=1
EOF
sysctl --system >/dev/null
systemctl daemon-reload
systemctl enable --now wg-quick@flwg0.service
/opt/flowlink-server/flowlinkctl.py migrate-config >/dev/null
if [ "$NEW_CONFIG" = "1" ]; then
  /opt/flowlink-server/flowlinkctl.py rotate-ports --force >/dev/null
fi
/opt/flowlink-server/flowlinkctl.py maintain >/dev/null
systemctl enable --now flowlink-network.service
systemctl enable --now flowlink-server.service
systemctl enable --now flowlink-health.timer
systemctl restart flowlink-server.service flowlink-network.service
ln -sf /opt/flowlink-server/flowlinkctl.py /usr/local/bin/flowlinkctl
echo "FlowLink Server 0.3.1 installed"
echo "HTTPS API: https://$PUBLIC_IP:443/"
echo "TLS fingerprint: $(cat /etc/flowlink/tls/fingerprint.sha256)"
