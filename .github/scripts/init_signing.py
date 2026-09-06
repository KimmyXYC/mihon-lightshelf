#!/usr/bin/env python3
"""Create a permanent signing identity and optionally configure GitHub Secrets."""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, default=Path.home() / ".local/share/mihon-lightshelf/signing")
    parser.add_argument("--github-repo", help="Upload the four signing Secrets to OWNER/REPO")
    args = parser.parse_args()
    os.umask(0o077)
    directory = args.directory.resolve()
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    directory.chmod(0o700)
    credentials_file = directory / "credentials.json"
    keystore = directory / "lightshelf.jks"
    if not credentials_file.exists() and not keystore.exists():
        credentials = {
            "SIGNING_KEY_ALIAS": "lightshelf",
            "SIGNING_STORE_PASSWORD": secrets.token_urlsafe(48),
            "SIGNING_KEY_PASSWORD": secrets.token_urlsafe(48),
        }
        with credentials_file.open("x") as file:
            json.dump(credentials, file, indent=2)
            file.write("\n")
        subprocess.run([
            "keytool", "-genkeypair", "-keystore", str(keystore), "-storetype", "JKS",
            "-alias", credentials["SIGNING_KEY_ALIAS"], "-keyalg", "RSA", "-keysize", "4096",
            "-sigalg", "SHA256withRSA", "-validity", "10000",
            "-dname", "CN=LightShelf, OU=Android Extensions, O=KimmyXYC",
            "-storepass:env", "SIGNING_STORE_PASSWORD", "-keypass:env", "SIGNING_KEY_PASSWORD",
        ], env=os.environ | credentials, check=True)
    elif not credentials_file.exists() or not keystore.exists():
        raise SystemExit("Incomplete signing backup; recover it instead of replacing the identity.")
    credentials = json.loads(credentials_file.read_text())
    credentials_file.chmod(0o600)
    keystore.chmod(0o600)
    certificate = subprocess.run([
        "keytool", "-exportcert", "-keystore", str(keystore),
        "-alias", credentials["SIGNING_KEY_ALIAS"], "-storepass:env", "SIGNING_STORE_PASSWORD",
    ], env=os.environ | credentials, check=True, stdout=subprocess.PIPE).stdout
    (directory / "certificate.der").write_bytes(certificate)
    fingerprint = hashlib.sha256(certificate).hexdigest()
    (directory / "certificate.sha256").write_text(fingerprint + "\n")
    if args.github_repo:
        values = credentials | {"SIGNING_KEYSTORE_BASE64": base64.b64encode(keystore.read_bytes()).decode()}
        for name, value in values.items():
            subprocess.run(["gh", "secret", "set", name, "--repo", args.github_repo],
                           input=value, text=True, check=True)
            print(f"Configured {name}")
    print(f"Signing backup: {directory}")
    print(f"Certificate SHA-256: {fingerprint}")


if __name__ == "__main__":
    main()
