# FlowLink Android 0.2.0

Private Android client for FlowLink Server 0.2.0.

## Implemented

- One-time enrollment against the server HTTPS API.
- WireGuard key pair generation on Android.
- AES-GCM secret storage backed by Android Keystore.
- Certificate SHA-256 pinning for the current FlowLink server.
- HMAC validation of enrollment and configuration responses.
- Cached endpoint and port configuration.
- Automatic configuration refresh every six hours and after failures.
- Eight-second tunnel health monitoring.
- Automatic sequential port recovery.
- Recovery after Wi-Fi/mobile-network changes.
- Foreground service and optional reconnect after reboot.
- Multiple independently enrolled VPS profiles with automatic failover.
- In-place WireGuard endpoint and UDP-port updates on the active VPS.
- Certificate-pinned APK update download with SHA-256 verification.
- Focused connection dashboard with separate add-VPS and server-management screens.

## First pairing

1. On every VPS, read its TLS fingerprint and create a one-time token:

       cat /etc/flowlink/tls/fingerprint.sha256
       flowlinkctl create-enrollment --name "My Android"

2. Enter the server IP or DNS name, fingerprint and token in FlowLink.
3. Tap Add and register, approve the Android VPN permission, and tap Connect.

The enrollment token is not stored. The WireGuard private key and per-device
API token are encrypted locally.

## Recovery boundary

Port changes on one VPS use the FlowLink WireGuard fork's in-place userspace
configuration update and preserve the Android TUN interface. Switching VPS
profiles rebuilds the tunnel because server keys and tunnel addresses may
differ. If every registered VPS is unreachable, FlowLink continues retrying.

App updates are downloaded and verified automatically. Android's package
installer still requires the user to approve installation.
