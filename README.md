# FlowLink

FlowLink is a private Android VPN built on WireGuard. It adds one-time device
enrollment, signed HTTPS configuration delivery, automatic UDP-port recovery,
multiple VPS profiles, in-place endpoint updates and verified Android app
updates.

## Repository layout

- `server/`: Ubuntu service, CLI, health repair and a 4+8 UDP port pool.
- `android/`: FlowLink Android application.
- `tunnel/`: minimal Apache-2.0 WireGuard Android tunnel fork adding
  `wgSetConfig` for in-place endpoint updates.
- `install.sh`: Ubuntu bootstrap installer.

## Install an Ubuntu VPS

From a checked-out repository:

```bash
sudo FLOWLINK_NODE_ID=vps-1 ./install.sh
```

For this private GitHub repository, a one-command authenticated install is:

```bash
export FLOWLINK_GITHUB_TOKEN='your-read-token'
curl -fsSL -H "Authorization: Bearer $FLOWLINK_GITHUB_TOKEN" \
  https://raw.githubusercontent.com/mcpchatgpt/flowlink/main/install.sh \
  | sudo -E FLOWLINK_GITHUB_TOKEN="$FLOWLINK_GITHUB_TOKEN" bash
```

The installer preserves existing WireGuard keys, TLS keys, enrolled devices
and state when run again. Set `FLOWLINK_RECONFIGURE=1` only when intentionally
replacing node configuration.

After installation:

```bash
flowlinkctl status
flowlinkctl create-enrollment --name "My Android"
cat /etc/flowlink/tls/fingerprint.sha256
```

Enter the VPS address, TLS fingerprint and one-time enrollment token in the
Android app. Each VPS is registered independently; FlowLink keeps only one VPN
tunnel active and can fail over among registered profiles.

## Publish an Android update

Build and sign the APK with the same signing key as the installed app, then:

```bash
flowlinkctl publish-apk FlowLink.apk --version-code 2 --version-name 0.2.0
```

Clients check periodically, download the APK through certificate-pinned HTTPS,
verify its SHA-256 digest and notify the user to approve installation. Android
does not allow a normal application to install updates silently.

## Development

Server tests:

```bash
cd server
python3 -m unittest -v
```

Android release build:

```bash
cd android
./gradlew :app:assembleRelease
```

The release signing properties are intentionally excluded from Git. See
`android/keystore.properties.example` and keep the production key offline or
on the controlled build host.

## Security notes

- Android private keys are generated and encrypted on the device.
- Server device tokens are stored as SHA-256 hashes.
- Enrollment and configuration responses are HMAC-authenticated.
- Each VPS certificate is pinned by SHA-256 fingerprint.
- No WireGuard, TLS, enrollment, API or Android signing private key belongs in
  this repository.

The vendored WireGuard tunnel sources retain their Apache-2.0 license in
`tunnel/COPYING`.
