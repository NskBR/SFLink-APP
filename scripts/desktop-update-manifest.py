"""Generate the public Windows update manifest after building the setup."""
import argparse
import hashlib
import json
import re
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("setup", type=Path)
parser.add_argument("--version", required=True)
parser.add_argument("--notes", required=True)
args = parser.parse_args()
if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", args.version):
    parser.error("Use a numeric stable version.")
name = f"SFLink_{args.version}_x64-setup.exe"
if args.setup.name != name:
    parser.error(f"Expected Setup named {name}.")
size = args.setup.stat().st_size
if not 0 < size <= 512 * 1024 * 1024:
    parser.error("Setup size is outside the supported range.")
manifest = {
    "version": args.version,
    "url": f"https://github.com/NskBR/SFLink-APP/releases/download/v{args.version}/{name}",
    "size": size,
    "sha256": hashlib.sha256(args.setup.read_bytes()).hexdigest(),
    "notes": args.notes,
}
destination = Path(__file__).resolve().parents[1] / "updates" / "desktop.json"
destination.parent.mkdir(exist_ok=True)
destination.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(destination)
