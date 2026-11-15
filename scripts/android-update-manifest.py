"""Generate the public Android update manifest after building the signed APK."""
import argparse
import hashlib
import json
import re
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("apk", type=Path)
parser.add_argument("--version", required=True)
parser.add_argument("--version-code", required=True, type=int)
parser.add_argument("--notes", required=True)
args = parser.parse_args()
if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", args.version) or args.version_code <= 0:
    parser.error("Use a numeric stable version and a positive version code.")
name = f"SFLink_{args.version}_android.apk"
if args.apk.name != name:
    parser.error(f"Expected APK named {name}.")
size = args.apk.stat().st_size
if not 0 < size <= 512 * 1024 * 1024:
    parser.error("APK size is outside the supported range.")
manifest = {
    "version": args.version,
    "versionCode": args.version_code,
    "url": f"https://github.com/NskBR/SFLink-APP/releases/download/v{args.version}/{name}",
    "size": size,
    "sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
    "notes": args.notes,
}
destination = Path(__file__).resolve().parents[1] / "updates" / "android.json"
destination.parent.mkdir(exist_ok=True)
destination.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(destination)
