#!/usr/bin/env python3
"""Copy an APK to this workspace's configured SSH destinations without exposing credentials."""

import argparse
import datetime
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import uuid


DEFAULT_CONFIG = Path.home() / ".config/bingo/apk-delivery.json"


def load_config(path):
    if path.stat().st_mode & 0o077:
        raise ValueError("APK delivery config must be private: chmod 600 " + str(path))
    value = json.loads(path.read_text())
    if not value.get("destinations"):
        raise ValueError("APK delivery config contains no destinations")
    return value


def askpass():
    config = load_config(Path(os.environ["BINGO_APK_DELIVERY_CONFIG"]))
    target = config["destinations"][int(os.environ["BINGO_APK_DELIVERY_TARGET"])]
    sys.stdout.write(target["password"] + "\n")


def transfer(apk, digest, config_path, target, index):
    name = target.get("name", target["host"])
    host, user = target["host"], target["user"]
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9.-]*", host):
        raise ValueError("Invalid SSH host in local configuration")
    if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_.-]*", user):
        raise ValueError("Invalid SSH username in local configuration")
    directory = target["directory"].rstrip("/")
    if not directory.startswith("/"):
        raise ValueError("APK destination must be an absolute directory")
    destination = directory + "/Bingo.apk"
    staging = directory + "/.Bingo.apk." + uuid.uuid4().hex + ".part"
    # stdin carries only APK bytes. Credentials are supplied to ssh's askpass pipe.
    remote = (
        "set -eu; mkdir -p -- " + shlex.quote(directory) + "; "
        "staging=" + shlex.quote(staging) + "; "
        "trap 'rm -f -- \"$staging\"' EXIT; "
        'cat > "$staging"; '
        'actual=$(sha256sum -- "$staging"); '
        '[ "${actual%% *}" = ' + shlex.quote(digest) + ' ]; '
        'mv -f -- "$staging" ' + shlex.quote(destination) + "; "
        "trap - EXIT; sha256sum -- " + shlex.quote(destination)
    )
    command = [
        "ssh", "-T", "-p", str(int(target.get("port", 22))),
        "-o", "StrictHostKeyChecking=accept-new",
        "-o", "ConnectTimeout=10",
        "-o", "ServerAliveInterval=15",
        "-o", "ServerAliveCountMax=2",
        "-o", "NumberOfPasswordPrompts=1",
        user + "@" + host, remote,
    ]
    environment = os.environ.copy()
    environment.update({
        "SSH_ASKPASS": str(Path(__file__).resolve()),
        "SSH_ASKPASS_REQUIRE": "force",
        "DISPLAY": environment.get("DISPLAY") or "bingo-apk-delivery",
        "BINGO_APK_DELIVERY_ASKPASS": "1",
        "BINGO_APK_DELIVERY_CONFIG": str(config_path),
        "BINGO_APK_DELIVERY_TARGET": str(index),
    })
    result = {"name": name, "host": host, "destination": destination, "sha256": digest}
    try:
        with apk.open("rb") as source:
            process = subprocess.run(command, stdin=source, stdout=subprocess.PIPE,
                                     stderr=subprocess.PIPE, env=environment,
                                     start_new_session=True, timeout=180)
        output = process.stdout.decode(errors="replace").strip().splitlines()
        if process.returncode or not output or output[-1].split()[0] != digest:
            error = process.stderr.decode(errors="replace").strip() or "Remote checksum verification failed"
            raise RuntimeError(error[-800:])
        result["status"] = "verified"
        print(f"{name}: overwritten {destination} (SHA-256 verified)", flush=True)
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        message = str(error)
        password = target.get("password", "")
        if password:
            message = message.replace(password, "<redacted>")
        result.update(status="failed", error=message)
        print(f"{name}: APK delivery failed: {message}", file=sys.stderr, flush=True)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--config", type=Path,
                        default=Path(os.environ.get("BINGO_APK_DELIVERY_CONFIG", str(DEFAULT_CONFIG))))
    args = parser.parse_args()
    apk = args.apk.resolve()
    if not apk.is_file() or apk.suffix.lower() != ".apk":
        parser.error("APK does not exist: " + str(apk))
    config_path = args.config.expanduser().resolve()
    config = load_config(config_path)
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    lock_path = config_path.parent / "apk-delivery.lock"
    with lock_path.open("a") as lock:
        os.chmod(lock_path, 0o600)
        fcntl.flock(lock, fcntl.LOCK_EX)
        results = []
        for index, target in enumerate(config["destinations"]):
            try:
                results.append(transfer(apk, digest, config_path, target, index))
            except (KeyError, ValueError) as error:
                results.append({"name": target.get("name", str(index)), "status": "failed", "error": str(error)})
                print("Invalid APK delivery destination: " + str(error), file=sys.stderr)
        receipt = {"at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                   "apk": str(apk), "sha256": digest, "bytes": apk.stat().st_size,
                   "destinations": results}
        receipt_path = config_path.parent / "apk-delivery-latest.json"
        descriptor = os.open(receipt_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(descriptor, "w") as output:
            json.dump(receipt, output, indent=2)
            output.write("\n")
        if any(item["status"] != "verified" for item in results):
            print("APK exists locally; remote delivery is incomplete. Retry tools/deliver_apk.py with this APK.", file=sys.stderr)
            return 1
    return 0


if __name__ == "__main__":
    if os.environ.get("BINGO_APK_DELIVERY_ASKPASS") == "1":
        try:
            askpass()
        except Exception:
            sys.exit(1)
    else:
        try:
            sys.exit(main())
        except (OSError, ValueError, KeyError) as error:
            print("APK delivery configuration error: " + str(error), file=sys.stderr)
            sys.exit(1)
