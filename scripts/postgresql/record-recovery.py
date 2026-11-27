"""Record a verified PostgreSQL recovery point and its matching release artifacts."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re


def file_digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def lsn_value(value):
    if not re.fullmatch(r"[0-9A-F]+/[0-9A-F]+", value):
        raise ValueError("Invalid PostgreSQL LSN")
    segment, offset = value.split("/")
    return (int(segment, 16) << 32) | int(offset, 16)


def artifact(path):
    return {"name": path.name, "sha256": file_digest(path)}


def record(args):
    point = json.loads(args.point.read_text(encoding="utf-8"))
    backup = json.loads(args.backup_manifest.read_text(encoding="utf-8"))
    reconcile = json.loads(args.reconciliation.read_text(encoding="utf-8"))
    if str(backup["System-Identifier"]) != str(point["systemIdentifier"]):
        raise ValueError("Base backup and recovery point are from different PostgreSQL clusters")
    if lsn_value(point["lsn"]) <= max(lsn_value(item["End-LSN"]) for item in backup["WAL-Ranges"]):
        raise ValueError("The recovery point must be after the base backup WAL range")
    if not re.fullmatch(r"[0-9A-F]{24}", point["walSegment"]):
        raise ValueError("Invalid WAL segment name")
    if args.archived_segment.name != point["walSegment"] or args.archived_segment.stat().st_size == 0:
        raise ValueError("The specified archived segment must contain the recovery point")
    if reconcile["status"] != "PASS" or not re.fullmatch(r"[0-9a-f]{64}", reconcile["reconciliationSha256"]):
        raise ValueError("An accepted historical import reconciliation report is required")
    if (point["database"], point["snapshotId"], point["reconciliationSha256"]) != (
        reconcile["targetDatabase"], reconcile["snapshotId"], reconcile["reconciliationSha256"]
    ):
        raise ValueError("Recovery point and reconciliation refer to different database snapshots")
    timestamp = datetime.fromisoformat(point["timestamp"])
    if timestamp.utcoffset() is None:
        raise ValueError("The recovery point timestamp must include an offset")

    result = {
        "recordedAt": datetime.now(timezone.utc).isoformat(),
        "postgresql": {
            "systemIdentifier": str(point["systemIdentifier"]),
            "database": point["database"],
            "restorePoint": point["restorePoint"],
            "lsn": point["lsn"],
            "timestamp": timestamp.astimezone(timezone.utc).isoformat(),
            "baseBackupId": args.backup_manifest.parent.name,
            "baseBackupManifest": artifact(args.backup_manifest),
            "archivedWal": artifact(args.archived_segment),
        },
        "attachmentsSnapshotId": args.attachments_snapshot_id,
        "attachmentsManifest": artifact(args.attachments_manifest),
        "aiKeyVersion": args.ai_key_version,
        "releaseManifest": artifact(args.release_manifest),
        "reconciliation": {
            "snapshotId": reconcile["snapshotId"],
            "reconciliationSha256": reconcile["reconciliationSha256"],
            "report": artifact(args.reconciliation),
        },
    }
    with args.output.open("x", encoding="utf-8") as output:
        json.dump(result, output, ensure_ascii=False, indent=2)
        output.write("\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for path in ("point", "backup-manifest", "archived-segment", "attachments-manifest",
                 "release-manifest", "reconciliation", "output"):
        parser.add_argument(f"--{path}", type=Path, required=True)
    parser.add_argument("--attachments-snapshot-id", required=True)
    parser.add_argument("--ai-key-version", required=True)
    args = parser.parse_args()
    try:
        record(args)
    except (KeyError, OSError, TypeError, ValueError) as error:
        parser.exit(1, f"Cannot record recovery point: {error}\n")


if __name__ == "__main__":
    main()
