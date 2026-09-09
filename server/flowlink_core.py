#!/usr/bin/env python3
"""Shared state and privileged operations for FlowLink Server."""
from __future__ import annotations
import base64, contextlib, fcntl, hashlib, ipaddress, json, os, secrets
import shutil, subprocess, tempfile, time, uuid
from pathlib import Path
from typing import Iterator

VERSION = "0.3.2"
CONFIG_PATH = Path("/etc/flowlink/node.json")
STATE_PATH = Path("/var/lib/flowlink/state.json")
LOCK_PATH = Path("/var/lib/flowlink/state.lock")
HEALTH_PATH = Path("/var/lib/flowlink/health.json")
RELEASE_DIR = Path("/var/lib/flowlink/releases")
UPDATE_PATH = RELEASE_DIR / "update.json"
APK_PATH = RELEASE_DIR / "FlowLink-latest.apk"

PORT_POLICY_VERSION = 3
DEFAULT_STABLE_PORTS = [443, 2053, 8443, 51820]
DEFAULT_ROTATING_PORT_COUNT = 8
DEFAULT_ROTATION_INTERVAL_SECONDS = 21600
DEFAULT_PORT_GRACE_SECONDS = 43200

class FlowLinkError(Exception):
    pass

def load_json(path: Path) -> dict:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)

def atomic_json(path: Path, value: dict, mode: int = 0o600) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=path.name + ".", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            json.dump(value, handle, indent=2, sort_keys=True)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(temporary, mode)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)

def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()

def publish_apk(source: Path, version_code: int, version_name: str,
                mandatory: bool = False) -> dict:
    if not source.is_file():
        raise FlowLinkError("apk_not_found")
    if version_code < 1 or not version_name or len(version_name) > 40:
        raise FlowLinkError("invalid_app_version")
    RELEASE_DIR.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix="FlowLink-", suffix=".apk",
                                     dir=RELEASE_DIR)
    os.close(fd)
    try:
        shutil.copyfile(source, temporary)
        os.chmod(temporary, 0o644)
        os.replace(temporary, APK_PATH)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    manifest = {
        "ok": True,
        "version_code": version_code,
        "version_name": version_name,
        "sha256": file_sha256(APK_PATH),
        "size": APK_PATH.stat().st_size,
        "url": "/downloads/FlowLink-latest.apk",
        "mandatory": bool(mandatory),
        "published_at": int(time.time()),
    }
    atomic_json(UPDATE_PATH, manifest, 0o644)
    return manifest

def app_update() -> dict:
    if not UPDATE_PATH.exists() or not APK_PATH.exists():
        return {"ok": True, "available": False}
    manifest = load_json(UPDATE_PATH)
    if manifest.get("sha256") != file_sha256(APK_PATH):
        raise FlowLinkError("apk_checksum_mismatch")
    return {**manifest, "available": True}

def initial_state(config: dict) -> dict:
    now = int(time.time())
    return {"schema": 2, "config_version": 1,
            "active_ports": list(dict.fromkeys(config["ports"])),
            "grace_ports": [],
            "next_rotation_at": now + int(config["rotation_interval_seconds"]),
            "devices": {}, "enrollments": {}, "repairs": 0,
            "created_at": now, "updated_at": now}

def migrate_config() -> dict:
    """Upgrade old nodes without replacing their identity, keys or devices."""
    config = load_json(CONFIG_PATH)
    old_version = int(config.get("version", 1))
    if old_version >= PORT_POLICY_VERSION:
        return {"changed": False, "version": old_version}
    config["version"] = PORT_POLICY_VERSION
    config["ports"] = list(DEFAULT_STABLE_PORTS)
    config["stable_ports"] = list(DEFAULT_STABLE_PORTS)
    config["rotating_port_count"] = DEFAULT_ROTATING_PORT_COUNT
    config["rotating_port_range"] = [20000, 60000]
    config["rotation_interval_seconds"] = DEFAULT_ROTATION_INTERVAL_SECONDS
    config["port_grace_seconds"] = DEFAULT_PORT_GRACE_SECONDS
    atomic_json(CONFIG_PATH, config)
    rotation = rotate_ports(force=True)
    return {"changed": True, "from_version": old_version,
            "version": PORT_POLICY_VERSION, "rotation": rotation}

@contextlib.contextmanager
def locked_state(config: dict) -> Iterator[dict]:
    LOCK_PATH.parent.mkdir(parents=True, exist_ok=True)
    with LOCK_PATH.open("a+", encoding="utf-8") as lock:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
        state = load_json(STATE_PATH) if STATE_PATH.exists() else initial_state(config)
        yield state
        state["updated_at"] = int(time.time())
        atomic_json(STATE_PATH, state)
        fcntl.flock(lock.fileno(), fcntl.LOCK_UN)

def token_hash(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()

def validate_public_key(value: str) -> str:
    try:
        decoded = base64.b64decode(value, validate=True)
    except Exception as exc:
        raise FlowLinkError("invalid_public_key") from exc
    if len(decoded) != 32:
        raise FlowLinkError("invalid_public_key")
    return value

def run(command: list[str], check: bool = True) -> subprocess.CompletedProcess:
    result = subprocess.run(command, capture_output=True, text=True, timeout=20)
    if check and result.returncode != 0:
        raise FlowLinkError("command_failed:" + command[0])
    return result

def effective_ports(state: dict, now: int | None = None) -> list[int]:
    current = int(time.time()) if now is None else now
    ports = list(state["active_ports"])
    ports.extend(int(x["port"]) for x in state.get("grace_ports", [])
                 if int(x["expires_at"]) > current)
    return list(dict.fromkeys(ports))

def _allocate_address(config: dict, state: dict) -> str:
    network = ipaddress.ip_network(config["subnet"])
    used = {item["address"].split("/")[0] for item in state["devices"].values()}
    for host in list(network.hosts())[1:]:
        if str(host) not in used:
            return f"{host}/32"
    raise FlowLinkError("address_pool_exhausted")

def create_enrollment(name: str, ttl_seconds: int = 900) -> dict:
    config, raw, now = load_json(CONFIG_PATH), secrets.token_urlsafe(32), int(time.time())
    ttl = max(60, min(ttl_seconds, 86400))
    with locked_state(config) as state:
        state["enrollments"][token_hash(raw)] = {
            "name": name[:80], "expires_at": now + ttl,
            "used_by": None, "public_key": None}
    return {"token": raw, "expires_at": now + ttl}

def enroll_device(enrollment_token: str, device_name: str, public_key: str) -> dict:
    config = load_json(CONFIG_PATH)
    public_key, now = validate_public_key(public_key), int(time.time())
    with locked_state(config) as state:
        record = state["enrollments"].get(token_hash(enrollment_token))
        if not record or int(record["expires_at"]) < now:
            raise FlowLinkError("invalid_or_expired_enrollment")
        if record.get("used_by"):
            raise FlowLinkError("enrollment_already_used")
        if any(x["public_key"] == public_key for x in state["devices"].values()):
            raise FlowLinkError("public_key_already_registered")
        device_id, device_token = uuid.uuid4().hex, secrets.token_urlsafe(32)
        address = _allocate_address(config, state)
        run(["wg", "set", config["interface"], "peer", public_key,
             "allowed-ips", address])
        state["devices"][device_id] = {
            "name": (device_name or record["name"])[:80],
            "public_key": public_key, "address": address,
            "token_hash": token_hash(device_token), "enabled": True,
            "created_at": now, "last_config_at": 0}
        record["used_by"], record["public_key"] = device_id, public_key
        state["config_version"] += 1
        return {"device_id": device_id, "device_token": device_token,
                "address": address, "config_version": state["config_version"]}

def authenticate_device(raw_token: str) -> tuple[str, dict, dict]:
    config, wanted = load_json(CONFIG_PATH), token_hash(raw_token)
    with locked_state(config) as state:
        for device_id, device in state["devices"].items():
            if device.get("enabled") and secrets.compare_digest(device["token_hash"], wanted):
                device["last_config_at"] = int(time.time())
                return device_id, dict(device), dict(state)
    raise FlowLinkError("unauthorized")

def rotate_ports(force: bool = False) -> dict:
    config, now = load_json(CONFIG_PATH), int(time.time())
    with locked_state(config) as state:
        if not force and now < int(state["next_rotation_at"]):
            return {"changed": False, "ports": effective_ports(state, now)}
        stable, old = list(map(int, config["stable_ports"])), list(map(int, state["active_ports"]))
        reserved = set(stable) | {int(config["https_port"]), 1194}
        dynamic, (low, high) = [], map(int, config["rotating_port_range"])
        while len(dynamic) < int(config["rotating_port_count"]):
            candidate = low + secrets.randbelow(high - low + 1)
            if candidate not in reserved and candidate not in dynamic:
                dynamic.append(candidate)
        expires = now + int(config["port_grace_seconds"])
        grace = [x for x in state.get("grace_ports", [])
                 if int(x["expires_at"]) > now and int(x["port"]) not in stable + dynamic]
        grace.extend({"port": p, "expires_at": expires}
                     for p in old if p not in stable + dynamic)
        state["active_ports"], state["grace_ports"] = stable + dynamic, grace
        state["next_rotation_at"] = now + int(config["rotation_interval_seconds"])
        state["config_version"] += 1
        return {"changed": True, "active_ports": state["active_ports"],
                "effective_ports": effective_ports(state, now),
                "grace_until": expires}

def remove_device(device_id: str) -> None:
    config = load_json(CONFIG_PATH)
    with locked_state(config) as state:
        device = state["devices"].get(device_id)
        if not device:
            raise FlowLinkError("device_not_found")
        run(["wg", "set", config["interface"], "peer",
             device["public_key"], "remove"])
        del state["devices"][device_id]
        state["config_version"] += 1

def wireguard_status(config: dict) -> dict:
    result = run(["wg", "show", config["interface"], "dump"], check=False)
    if result.returncode or not result.stdout.strip():
        return {"up": False, "interface": config["interface"], "peers": 0}
    lines, latest, rx, tx = result.stdout.strip().splitlines(), 0, 0, 0
    for line in lines[1:]:
        fields = line.split("\t")
        if len(fields) >= 7:
            latest = max(latest, int(fields[4] or 0))
            rx += int(fields[5] or 0)
            tx += int(fields[6] or 0)
    return {"up": True, "interface": config["interface"],
            "peers": max(0, len(lines) - 1), "latest_handshake": latest,
            "rx_bytes": rx, "tx_bytes": tx}

def maintain() -> dict:
    config, issues, repairs = load_json(CONFIG_PATH), [], 0
    service = f"wg-quick@{config['interface']}.service"
    if run(["systemctl", "is-active", "--quiet", service], check=False).returncode:
        run(["systemctl", "restart", service])
        issues.append("wireguard_restarted")
        repairs += 1
    rotation = rotate_ports(force=False)
    run(["/opt/flowlink-server/apply-network.sh"])
    with locked_state(config) as state:
        for device in state["devices"].values():
            if device.get("enabled"):
                result = run(["wg", "set", config["interface"], "peer",
                              device["public_key"], "allowed-ips",
                              device["address"]], check=False)
                if result.returncode:
                    issues.append("peer_reapply_failed")
        state["repairs"] = int(state.get("repairs", 0)) + repairs
        ports, version = effective_ports(state), state["config_version"]
    status = {"ok": not any(x.endswith("_failed") for x in issues),
              "version": VERSION, "checked_at": int(time.time()),
              "issues": issues, "repairs_this_run": repairs,
              "config_version": version, "effective_ports": ports,
              "rotation": rotation, "wireguard": wireguard_status(config)}
    atomic_json(HEALTH_PATH, status, 0o644)
    return status
