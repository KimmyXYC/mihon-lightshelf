#!/usr/bin/env python3
"""Sign LightShelf APKs and publish a consistent Mihon repository (Python stdlib only)."""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "eu.kanade.tachiyomi.extension.zh.lightshelf"
TAG = re.compile(r"v[0-9]+\.[0-9]+\.[0-9]+", re.ASCII)


def run(*args, env=None, cwd=None):
    return subprocess.run([str(arg) for arg in args], env=env, cwd=cwd, check=True,
                          text=True, stdout=subprocess.PIPE).stdout.strip()


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def write_json(path, data, compact=False):
    Path(path).write_text(json.dumps(data, ensure_ascii=False, indent=None if compact else 2,
                                    separators=(",", ":") if compact else None) + "\n", encoding="utf-8")


def validate_tag(tag):
    if not TAG.fullmatch(tag):
        raise ValueError("Release tag must be v<major>.<minor>.<patch> without a suffix")
    return tag[1:]


def build_tool(name):
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise ValueError("Set ANDROID_HOME to the Android SDK")
    return Path(sdk) / "build-tools/35.0.0" / name


def apk_info(apk, tag):
    version = validate_tag(tag)
    badging = run(build_tool("aapt"), "dump", "badging", apk)
    match = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging, re.M)
    if not match or match[1] != PACKAGE or match[3] != version or int(match[2]) <= 0:
        raise ValueError("APK package/version does not match LightShelf and the release tag")
    # Legacy clients interpret the first two components as the extension API version.
    manifest = run(build_tool("aapt"), "dump", "xmltree", apk, "AndroidManifest.xml")
    lib = re.search(r'"tachiyomix.extensionLib"[^\n]*\n[^\n]*android:value[^=]*=(.*)', manifest)
    lib_version = None
    if lib:
        string_value = re.match(r'"([0-9]+\.[0-9]+)"', lib[1])
        float_value = re.match(r'\(type 0x4\)0x([0-9a-fA-F]+)', lib[1])
        if string_value:
            lib_version = string_value[1]
        elif float_value:
            # aapt encodes android:value="1.4" as an IEEE 754 float.
            lib_version = format(struct.unpack("!f", int(float_value[1], 16).to_bytes(4, "big"))[0], ".6g")
    nsfw = re.search(r'"tachiyomi.extension.nsfw"[^\n]*\n[^\n]*\(type 0x10\)0x([01])', manifest)
    if lib_version != version.rsplit(".", 1)[0] or not nsfw:
        raise ValueError("APK must declare matching extension API and NSFW metadata")
    return {"pkg": match[1], "code": int(match[2]), "version": match[3], "lib": lib_version, "nsfw": int(nsfw[1])}


def verify_apk(apk, tag, fingerprint, expected=None):
    info = apk_info(apk, tag)
    result = run(build_tool("apksigner"), "verify", "--verbose", "--print-certs", apk)
    certificates = re.findall(r"^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$", result, re.M)
    if [cert.lower() for cert in certificates] != [fingerprint]:
        raise ValueError("APK signing certificate differs from repo.json")
    if expected is not None and info != expected:
        raise ValueError("Existing Release APK metadata differs from this tag's build")
    run(build_tool("zipalign"), "-c", "-P", "16", "4", apk)
    return info


def source_info():
    source_dir = ROOT / "extension/src/main/kotlin/eu/kanade/tachiyomi/extension/zh/lightshelf"
    source = (source_dir / "LightShelf.kt").read_text()
    api = (source_dir / "LightShelfApi.kt").read_text()
    name = re.search(r'override val name = "([^"\n]+)"', source)
    lang = re.search(r'override val lang = "([^"\n]+)"', source)
    website = re.search(r'const val WEBSITE = "([^"\n]+)"', api)
    if not name or not lang or not website or "override val id" in source:
        raise ValueError("Source metadata extraction needs updating for this source implementation")
    version_id = re.search(r"override val versionId = ([0-9]+)", source)
    key = f"{name[1].lower()}/{lang[1]}/{version_id[1] if version_id else 1}"
    source_id = int.from_bytes(hashlib.md5(key.encode()).digest()[:8], "big") & ((1 << 63) - 1)
    return {"name": name[1], "lang": lang[1], "baseUrl": website[1], "id": source_id}


def checksum(apk):
    return f"{hashlib.sha256(Path(apk).read_bytes()).hexdigest()}  {Path(apk).name}\n"


def prepare(apk, tag, output):
    info = apk_info(apk, tag)
    names = ("SIGNING_KEYSTORE_BASE64", "SIGNING_STORE_PASSWORD", "SIGNING_KEY_ALIAS", "SIGNING_KEY_PASSWORD")
    missing = [name for name in names if not os.environ.get(name)]
    if missing:
        raise ValueError("Missing signing secrets: " + ", ".join(missing))
    output.mkdir(parents=True, exist_ok=True)
    signed = output / f"lightshelf-{tag}.apk"
    fingerprint = read_json(ROOT / "repo.json")["meta"]["signingKeyFingerprint"]
    with tempfile.TemporaryDirectory(prefix="lightshelf-signing-") as directory:
        temporary = Path(directory)
        key = temporary / "signing.jks"
        key.write_bytes(base64.b64decode(os.environ["SIGNING_KEYSTORE_BASE64"], validate=True))
        key.chmod(0o600)
        aligned = temporary / "aligned.apk"
        run(build_tool("zipalign"), "-P", "16", "-f", "4", apk, aligned)
        run(build_tool("apksigner"), "sign", "--ks", key,
            "--ks-key-alias", os.environ["SIGNING_KEY_ALIAS"],
            "--ks-pass", "env:SIGNING_STORE_PASSWORD", "--key-pass", "env:SIGNING_KEY_PASSWORD",
            "--v4-signing-enabled", "false", "--out", signed, aligned)
    verify_apk(signed, tag, fingerprint, info)
    signed.with_suffix(".apk.sha256").write_text(checksum(signed))
    write_json(output / "metadata.json", info | {"source": source_info()})
    print(f"Verified signed APK: {signed}")


def index_action(current, incoming):
    if current is None:
        return "update"
    if incoming["code"] < current["code"]:
        return "older"
    if incoming["code"] == current["code"]:
        if incoming["version"] != current["version"]:
            raise ValueError("versionCode must increase when versionName changes")
        return "same"
    if tuple(map(int, incoming["version"].split("."))) <= tuple(map(int, current["version"].split("."))):
        raise ValueError("A higher versionCode must also have a higher versionName")
    return "update"


def generate_repo(apk, metadata, destination):
    descriptor = read_json(ROOT / "repo.json")
    base = descriptor["index_v2"].removesuffix("/index-v2.json")
    source = metadata["source"]
    destination.mkdir(parents=True, exist_ok=True)
    (destination / "apk").mkdir(exist_ok=True)
    (destination / "icon").mkdir(exist_ok=True)
    shutil.copy2(apk, destination / "apk" / apk.name)
    shutil.copy2(ROOT / ".github/assets/lightshelf.png", destination / "icon" / f"{PACKAGE}.png")
    legacy = [{"name": "Tachiyomi: " + source["name"], "pkg": metadata["pkg"], "apk": apk.name,
               "lang": source["lang"], "code": metadata["code"], "version": metadata["version"],
               "nsfw": metadata["nsfw"], "sources": [source]}]
    modern = {
        "name": descriptor["meta"]["name"], "badgeLabel": "LS",
        "signingKey": descriptor["meta"]["signingKeyFingerprint"],
        "contact": {"website": descriptor["meta"]["website"], "discord": None},
        "extensionListUrl": None,
        "extensionList": {"extensions": [{
            "name": source["name"], "packageName": metadata["pkg"],
            "resources": {"apkUrl": f"{base}/apk/{apk.name}", "iconUrl": f"{base}/icon/{PACKAGE}.png"},
            "extensionLib": metadata["lib"], "versionCode": metadata["code"],
            "versionName": metadata["version"], "contentWarning": "NSFW" if metadata["nsfw"] else "SAFE",
            "sources": [{"id": source["id"], "name": source["name"], "language": source["lang"],
                         "homeUrl": source["baseUrl"], "mirrorUrls": [], "message": None}],
        }]},
    }
    write_json(destination / "repo.json", descriptor)
    write_json(destination / "index.json", legacy)
    write_json(destination / "index.min.json", legacy, compact=True)
    write_json(destination / "index-v2.json", modern)


def gh(repo, *args):
    return run("gh", *args, "--repo", repo)


def release_assets(repo, tag, apk, fingerprint, expected, temporary):
    # Listing avoids treating auth/network errors as a missing release.
    releases = json.loads(run("gh", "api", "--paginate", "--slurp", f"repos/{repo}/releases?per_page=100"))
    existing = next((item for page in releases for item in page if item["tag_name"] == tag), None)
    if existing is None:
        gh(repo, "release", "create", tag, "--verify-tag", "--draft", "--title", f"LightShelf {tag}", "--generate-notes")
        existing = {"draft": True, "assets": []}
    assets = {asset["name"] for asset in existing["assets"]}
    digest_name = apk.name + ".sha256"
    if not existing["draft"] and not {apk.name, digest_name} <= assets:
        raise ValueError("Published Release is incomplete; refusing to replace public assets")
    if apk.name in assets:
        gh(repo, "release", "download", tag, "--pattern", apk.name, "--dir", str(temporary))
        apk = temporary / apk.name
        verify_apk(apk, tag, fingerprint, expected)
    else:
        gh(repo, "release", "upload", tag, str(apk))
    if digest_name in assets:
        gh(repo, "release", "download", tag, "--pattern", digest_name, "--dir", str(temporary))
        if (temporary / digest_name).read_text() != checksum(apk):
            raise ValueError("Release checksum does not match its signed APK")
    else:
        digest = temporary / digest_name
        digest.write_text(checksum(apk))
        gh(repo, "release", "upload", tag, str(digest))
    return apk, existing["draft"]


def publish(output, tag, repository):
    validate_tag(tag)
    descriptor = read_json(ROOT / "repo.json")
    if descriptor["meta"]["website"] != f"https://github.com/{repository}":
        raise ValueError("Publishing repository differs from repo.json")
    fingerprint = descriptor["meta"]["signingKeyFingerprint"]
    metadata = read_json(output / "metadata.json")
    expected = {key: value for key, value in metadata.items() if key != "source"}
    apk = output / f"lightshelf-{tag}.apk"
    verify_apk(apk, tag, fingerprint, expected)
    token = os.environ.get("GH_TOKEN")
    if not token:
        raise ValueError("GH_TOKEN is required for publishing")
    authorization = base64.b64encode(f"x-access-token:{token}".encode()).decode()
    git_env = os.environ | {"GIT_CONFIG_COUNT": "1", "GIT_CONFIG_KEY_0": "http.https://github.com/.extraheader",
                            "GIT_CONFIG_VALUE_0": f"AUTHORIZATION: basic {authorization}"}
    with tempfile.TemporaryDirectory(prefix="lightshelf-publish-") as directory:
        temporary = Path(directory)
        checkout = temporary / "repo"
        checkout.mkdir()

        def git(*args):
            return run("git", *args, cwd=checkout, env=git_env)

        git("init", "--initial-branch=repo")
        git("remote", "add", "origin", f"https://github.com/{repository}.git")
        if git("ls-remote", "--heads", "origin", "refs/heads/repo"):
            git("fetch", "--depth=1", "origin", "refs/heads/repo")
            git("reset", "--hard", "FETCH_HEAD")
        current = read_json(checkout / "index.min.json")[0] if (checkout / "index.min.json").exists() else None
        action = index_action(current, metadata)
        selected, draft = release_assets(repository, tag, apk, fingerprint, expected, temporary)
        if action == "same":
            previous_apk = checkout / "apk" / current["apk"]
            if not previous_apk.exists() or previous_apk.read_bytes() != selected.read_bytes():
                raise ValueError("Same versionCode already indexes a different APK")
        if draft:
            gh(repository, "release", "edit", tag, "--draft=false", "--latest=" + str(action != "older").lower())
        if action == "older":
            print("Historical Release published; newer repository index retained")
            return
        generate_repo(selected, metadata, checkout)
        git("add", "repo.json", "index.json", "index.min.json", "index-v2.json", "apk", "icon")
        if git("status", "--porcelain"):
            git("-c", "user.name=github-actions[bot]", "-c", "user.email=41898282+github-actions[bot]@users.noreply.github.com",
                "commit", "-m", f"Publish LightShelf {tag}")
            git("push", "origin", "HEAD:refs/heads/repo")
        print(f"Release {tag} and repo branch are up to date")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    validate = commands.add_parser("validate-tag")
    validate.add_argument("tag")
    sign = commands.add_parser("prepare")
    sign.add_argument("--apk", required=True, type=Path)
    sign.add_argument("--tag", required=True)
    sign.add_argument("--output", required=True, type=Path)
    generate = commands.add_parser("generate-repo")
    generate.add_argument("--output", required=True, type=Path)
    generate.add_argument("--tag", required=True)
    generate.add_argument("--destination", required=True, type=Path)
    release = commands.add_parser("publish")
    release.add_argument("--output", required=True, type=Path)
    release.add_argument("--tag", required=True)
    release.add_argument("--repository", required=True)
    args = parser.parse_args()
    if args.command == "validate-tag":
        print(validate_tag(args.tag))
    elif args.command == "prepare":
        prepare(args.apk.resolve(), args.tag, args.output.resolve())
    elif args.command == "generate-repo":
        metadata = read_json(args.output / "metadata.json")
        apk = args.output / f"lightshelf-{args.tag}.apk"
        verify_apk(apk, args.tag, read_json(ROOT / "repo.json")["meta"]["signingKeyFingerprint"],
                   {key: value for key, value in metadata.items() if key != "source"})
        generate_repo(apk, metadata, args.destination)
    elif args.command == "publish":
        publish(args.output.resolve(), args.tag, args.repository)


if __name__ == "__main__":
    main()
