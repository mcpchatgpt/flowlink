#!/usr/bin/env python3
"""Administrative CLI for FlowLink Server."""
import argparse, json, sys
from pathlib import Path
from flowlink_core import (CONFIG_PATH, STATE_PATH, VERSION, FlowLinkError,
    app_update, create_enrollment, effective_ports, load_json, maintain,
    publish_apk, remove_device, rotate_ports, wireguard_status)

def output(value: object) -> None:
    print(json.dumps(value, indent=2, sort_keys=True))

def main() -> None:
    parser = argparse.ArgumentParser(prog="flowlinkctl")
    parser.add_argument("--version", action="version", version=VERSION)
    sub = parser.add_subparsers(dest="command", required=True)
    enroll = sub.add_parser("create-enrollment")
    enroll.add_argument("--name", default="Android")
    enroll.add_argument("--ttl", type=int, default=900)
    sub.add_parser("list-devices")
    remove = sub.add_parser("remove-device")
    remove.add_argument("device_id")
    rotate = sub.add_parser("rotate-ports")
    rotate.add_argument("--force", action="store_true")
    ports = sub.add_parser("effective-ports")
    ports.add_argument("--plain", action="store_true")
    sub.add_parser("maintain")
    sub.add_parser("status")
    publish = sub.add_parser("publish-apk")
    publish.add_argument("apk", type=Path)
    publish.add_argument("--version-code", type=int, required=True)
    publish.add_argument("--version-name", required=True)
    publish.add_argument("--mandatory", action="store_true")
    sub.add_parser("app-update")
    args = parser.parse_args()
    config = load_json(CONFIG_PATH)
    try:
        if args.command == "create-enrollment":
            output(create_enrollment(args.name, args.ttl))
        elif args.command == "list-devices":
            state = load_json(STATE_PATH)
            output({key: {field: value for field, value in item.items()
                           if field != "token_hash"}
                    for key, item in state["devices"].items()})
        elif args.command == "remove-device":
            remove_device(args.device_id)
            output({"ok": True, "removed": args.device_id})
        elif args.command == "rotate-ports":
            output(rotate_ports(args.force))
        elif args.command == "effective-ports":
            values = effective_ports(load_json(STATE_PATH))
            print(" ".join(map(str, values)) if args.plain else json.dumps(values))
        elif args.command == "maintain":
            output(maintain())
        elif args.command == "status":
            state = load_json(STATE_PATH)
            output({"version": VERSION,
                    "config_version": state["config_version"],
                    "devices": len(state["devices"]),
                    "ports": effective_ports(state),
                    "next_rotation_at": state["next_rotation_at"],
                    "wireguard": wireguard_status(config)})
        elif args.command == "publish-apk":
            output(publish_apk(args.apk, args.version_code,
                               args.version_name, args.mandatory))
        elif args.command == "app-update":
            output(app_update())
    except FlowLinkError as exc:
        output({"ok": False, "error": str(exc)})
        sys.exit(1)

if __name__ == "__main__":
    main()
