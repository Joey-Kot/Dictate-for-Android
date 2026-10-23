#!/usr/bin/env python3
"""Apply the pinned CVE-2026-91403 blocklist to the JDK used for builds.

Source: https://docs.azul.com/core/emudhra-workaround
The file uses the OpenJDK SHA-256 blocked.certs format. Preserve existing
blocks so a newer JDK never loses certificates that it already distrusts.
Restart Java/Gradle processes after applying this to an existing installation.
"""

import hashlib
import pathlib
import re
import shutil
import sys


def fingerprints(data):
    lines = data.decode("ascii").splitlines()
    if lines.count("Algorithm=SHA-256") != 1:
        raise ValueError("Expected an OpenJDK SHA-256 certificate blocklist")
    entries = set()
    for line in lines:
        line = line.strip()
        if not line or line.startswith(("#", "!")) or line == "Algorithm=SHA-256":
            continue
        if re.fullmatch(r"[0-9A-F]{64}", line) is None:
            raise ValueError("Unexpected certificate blocklist entry")
        entries.add(line)
    return entries


def main():
    if len(sys.argv) != 2:
        raise SystemExit("Usage: harden-jdk-certificates.py JAVA_HOME")
    source = pathlib.Path(__file__).resolve().parent / "patches/emudhra-block-unix.certs"
    update = source.read_bytes()
    expected = "2596bb6dda83c66d96c5a252d7b2ae1f24cc1ae3f1890223b4f3591a8d52bf10"
    if hashlib.sha256(update).hexdigest() != expected:
        raise SystemExit("Certificate blocklist checksum mismatch")
    target = pathlib.Path(sys.argv[1]) / "lib/security/blocked.certs"
    original = target.read_bytes()
    required = fingerprints(update)
    previous = fingerprints(original)
    missing = required - previous
    if missing:
        backup = target.with_name(target.name + ".bak-emudhra")
        if not backup.exists():
            shutil.copy2(target, backup)
        target.write_bytes(original.rstrip(b"\r\n") + b"\n" +
                           ("\n".join(sorted(missing)) + "\n").encode("ascii"))
    if not (required | previous) <= fingerprints(target.read_bytes()):
        raise SystemExit("Certificate blocklist verification failed")
    print(f"{target}: added {len(missing)} blocks; all {len(required)} required blocks present")


if __name__ == "__main__":
    main()
