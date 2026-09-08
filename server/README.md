# FlowLink Server 0.3.0

Ubuntu server for a private Android WireGuard client with automatic endpoint
recovery.

## Features

- One WireGuard interface with several public UDP aliases.
- One-time device enrollment; Android private keys never leave Android.
- Per-device bearer tokens stored as SHA-256 hashes on the server.
- HMAC-signed configuration responses.
- HTTPS configuration delivery on TCP 443.
- IP-SAN bootstrap certificate with a SHA-256 fingerprint.
- Stable ports plus automatically rotated ports.
- Two-day old/new port overlap.
- One-minute health monitor restoring WireGuard peers, NAT and firewall state.
- Atomic, locked state updates.
- Existing OpenVPN is not modified.
- Signed APK update manifest and HTTPS APK delivery.
- Idempotent installer that preserves keys, devices and node configuration.

## Install on Ubuntu

Clone this repository, then run:

    sudo FLOWLINK_NODE_ID=vps-1 ./install.sh

Optional variables:

- `FLOWLINK_NODE_ID`: unique display name for this VPS.
- `FLOWLINK_ENDPOINT`: public IPv4 or DNS endpoint; auto-detected by default.
- `FLOWLINK_RECONFIGURE=1`: intentionally replace `/etc/flowlink/node.json`.

Re-running the installer upgrades program files while preserving WireGuard keys,
TLS keys, devices and state.

## Commands

    flowlinkctl status
    flowlinkctl create-enrollment --name "My Android"
    flowlinkctl list-devices
    flowlinkctl rotate-ports --force
    flowlinkctl remove-device DEVICE_ID
    flowlinkctl publish-apk FlowLink.apk --version-code 2 --version-name 0.2.0
    flowlinkctl app-update

The enrollment token is valid for 15 minutes by default. Android generates its
own WireGuard key pair and sends only its public key to POST /v1/enroll.

The node currently uses a self-signed certificate because no domain is assigned.
The Android application must pin its SHA-256 fingerprint during enrollment.

Normal Android applications cannot silently install an APK. FlowLink downloads
and verifies updates automatically, then opens Android's package installer for
the final user confirmation.
