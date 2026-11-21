#!/usr/bin/env python3
"""Run opt-in extraction comparisons on one ADB-connected Android device."""

import argparse
import os
import shlex
import subprocess
from pathlib import Path
from urllib.parse import urlsplit

PACKAGE = "com.codetic.saoreo"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
TEST = PACKAGE + ".YoutubeBenchmarkTest"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("url", help="An authorized public YouTube video URL")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial")
    parser.add_argument("--output", type=Path, default=Path("youtube-benchmark"))
    parser.add_argument("--mode", choices=["WRAPPER", "PROFILE", "STANDALONE"], default="PROFILE")
    parser.add_argument("--trial", choices=["DEFAULT", "WEB", "TV"], default="DEFAULT")
    parser.add_argument("--attempts", type=int, default=3, choices=range(1, 6))
    parser.add_argument("--download", action="store_true", help="Also test an authorized real transfer")
    args = parser.parse_args()
    url = urlsplit(args.url)
    host = url.hostname or ""
    if url.scheme != "https" or url.username or not (host == "youtu.be" or host == "youtube.com" or host.endswith(".youtube.com")):
        parser.error("Use an HTTPS YouTube URL")
    adb = [args.adb] + (["-s", args.serial] if args.serial else [])
    args.output.mkdir(mode=0o700, parents=True, exist_ok=True)

    def capture(name, count=1, bypass=True, download=False):
        subprocess.run(adb + ["shell", "am", "force-stop", PACKAGE], check=True, timeout=15)
        remote = [
            "am", "instrument", "-w", "-r", "-e", "class", TEST,
            "-e", "saoreo_benchmark_url", args.url,
            "-e", "saoreo_mode", args.mode, "-e", "saoreo_trial", args.trial,
            "-e", "saoreo_runs", str(count), "-e", "saoreo_bypass_app_cache", str(bypass).lower(),
            "-e", "saoreo_download", str(download).lower(), RUNNER,
        ]
        target = args.output / (name + ".txt")
        fd = os.open(target, os.O_CREAT | os.O_WRONLY | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as log:
            result = subprocess.run(adb + ["shell", shlex.join(remote)],
                                    stdout=log, stderr=subprocess.STDOUT,
                                    timeout=30 + 75 * count + (180 if download else 0))
        report = target.read_text(errors="replace")
        if result.returncode or "benchmark.result_" not in report:
            raise SystemExit(f"Android test did not capture a result. Read {target}")
        labels = [line.strip() for line in report.splitlines()
                  if " benchmark.result_" in line or " download." in line]
        print(f"{target.name}: " + " | ".join(labels), flush=True)

    for attempt in range(1, args.attempts + 1):
        capture(f"cold-{attempt}")
    # Run 1 is the primer for both files. Runs 2 onward share an app process.
    capture("warm-uncached", count=args.attempts + 1, download=args.download)
    capture("warm-cached", count=args.attempts + 1, bypass=False)


if __name__ == "__main__":
    main()
