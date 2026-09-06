import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import release


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.apk = self.directory / "lightshelf-v1.4.3.apk"
        self.apk.write_bytes(b"newly built apk")
        self.info = {"pkg": release.PACKAGE, "code": 3, "version": "1.4.3", "lib": "1.4", "nsfw": 1}

    def test_tag_formats(self):
        for valid in ("v1.4.3", "v12.34.567"):
            self.assertEqual(release.validate_tag(valid), valid[1:])
        for invalid in ("1.4.3", "v1.4", "v1.4.3-beta", "v1.4.3+build", "v1.4.3/extra", "v1.4.3\n", "v１.4.3"):
            with self.subTest(tag=invalid), self.assertRaises(ValueError):
                release.validate_tag(invalid)

    def test_apk_metadata_validation(self):
        badging = f"package: name='{release.PACKAGE}' versionCode='3' versionName='1.4.3'"
        manifest = ('A: android:name="tachiyomix.extensionLib" (Raw: "tachiyomix.extensionLib")\n'
                    'A: android:value="1.4" (Raw: "1.4")\n'
                    'A: android:name="tachiyomi.extension.nsfw" (Raw: "tachiyomi.extension.nsfw")\n'
                    'A: android:value=(type 0x10)0x1')
        with patch.object(release, "build_tool", side_effect=lambda name: name):
            with patch.object(release, "run", side_effect=[badging, manifest]):
                self.assertEqual(release.apk_info(self.apk, "v1.4.3"), self.info)
            compiled = manifest.replace('android:value="1.4" (Raw: "1.4")', 'android:value=(type 0x4)0x3fb33333')
            with patch.object(release, "run", side_effect=[badging, compiled]):
                self.assertEqual(release.apk_info(self.apk, "v1.4.3"), self.info)
            with patch.object(release, "run", return_value=badging), self.assertRaises(ValueError):
                release.apk_info(self.apk, "v1.4.4")
            with patch.object(release, "run", side_effect=[badging, manifest.replace('"1.4"', '"1.5"')]):
                with self.assertRaises(ValueError):
                    release.apk_info(self.apk, "v1.4.3")

    def test_missing_secrets_do_not_create_output(self):
        destination = self.directory / "signed"
        with patch.dict(release.os.environ, {}, clear=True), patch.object(release, "apk_info", return_value=self.info):
            with self.assertRaisesRegex(ValueError, "Missing signing secrets"):
                release.prepare(self.apk, "v1.4.3", destination)
        self.assertFalse(destination.exists())

    def test_wrong_or_multiple_signers_are_rejected(self):
        for certs in ("b" * 64, "a" * 64 + "\nSigner #2 certificate SHA-256 digest: " + "b" * 64):
            output = "Signer #1 certificate SHA-256 digest: " + certs
            with patch.object(release, "apk_info", return_value=self.info), \
                    patch.object(release, "build_tool", return_value="apksigner"), \
                    patch.object(release, "run", return_value=output):
                with self.assertRaisesRegex(ValueError, "signing certificate"):
                    release.verify_apk(self.apk, "v1.4.3", "a" * 64)

    def test_old_versions_and_non_increasing_codes(self):
        self.assertEqual(release.index_action(None, self.info), "update")
        self.assertEqual(release.index_action(self.info, self.info), "same")
        self.assertEqual(release.index_action(self.info, self.info | {"code": 2, "version": "1.4.2"}), "older")
        self.assertEqual(release.index_action(self.info, self.info | {"code": 4, "version": "1.4.4"}), "update")
        for change in ({"version": "1.4.4"}, {"code": 4}):
            with self.assertRaises(ValueError):
                release.index_action(self.info, self.info | change)

    def test_indexes_agree_and_resources_exist(self):
        metadata = self.info | {"source": release.source_info()}
        destination = self.directory / "repo"
        release.generate_repo(self.apk, metadata, destination)
        old = release.read_json(destination / "index.min.json")[0]
        new = release.read_json(destination / "index-v2.json")["extensionList"]["extensions"][0]
        self.assertEqual(release.read_json(destination / "index.json"), [old])
        for old_key, new_key in (("pkg", "packageName"), ("version", "versionName"), ("code", "versionCode")):
            self.assertEqual(old[old_key], new[new_key])
        self.assertEqual(old["sources"][0]["id"], new["sources"][0]["id"])
        self.assertEqual(old["sources"][0]["baseUrl"], new["sources"][0]["homeUrl"])
        self.assertEqual(old["nsfw"], int(new["contentWarning"] == "NSFW"))
        self.assertEqual((destination / "apk" / old["apk"]).read_bytes(), self.apk.read_bytes())
        for url in new["resources"].values():
            relative = url.split("/repo/", 1)[1]
            self.assertTrue((destination / relative).is_file())
        self.assertTrue((destination / "icon" / f"{release.PACKAGE}.png").read_bytes().startswith(b"\x89PNG"))

    def mock_existing_release(self, draft=False, include_digest=True, bad_digest=False):
        """Model interrupted and completed uploads without contacting GitHub."""
        previous = b"previously signed apk bytes"
        download = self.directory / "download"
        download.mkdir()
        assets = [{"name": self.apk.name}]
        if include_digest:
            assets.append({"name": self.apk.name + ".sha256"})
        listing = json.dumps([[{"tag_name": "v1.4.3", "draft": draft, "assets": assets}]])

        def gh(repo, *args):
            if args[:2] == ("release", "download"):
                name = args[args.index("--pattern") + 1]
                target = Path(args[args.index("--dir") + 1]) / name
                if name.endswith(".apk"):
                    target.write_bytes(previous)
                else:
                    target.write_text("bad checksum" if bad_digest else release.checksum(download / self.apk.name))

        return listing, gh, download, previous

    def test_rerun_reuses_public_apk_without_upload(self):
        listing, handler, download, previous = self.mock_existing_release()
        with patch.object(release, "run", return_value=listing), \
                patch.object(release, "gh", side_effect=handler) as gh, \
                patch.object(release, "verify_apk") as verify:
            selected, draft = release.release_assets("owner/repo", "v1.4.3", self.apk, "fingerprint", self.info, download)
        self.assertFalse(draft)
        self.assertEqual(selected.read_bytes(), previous)
        verify.assert_called_once_with(selected, "v1.4.3", "fingerprint", self.info)
        self.assertTrue(all(call.args[1:3] == ("release", "download") for call in gh.call_args_list))

    def test_partial_draft_recovers_missing_checksum(self):
        listing, handler, download, previous = self.mock_existing_release(draft=True, include_digest=False)
        with patch.object(release, "run", return_value=listing), \
                patch.object(release, "gh", side_effect=handler) as gh, patch.object(release, "verify_apk"):
            selected, draft = release.release_assets("owner/repo", "v1.4.3", self.apk, "fp", self.info, download)
        self.assertTrue(draft)
        self.assertEqual(selected.read_bytes(), previous)
        uploads = [call.args for call in gh.call_args_list if call.args[1:3] == ("release", "upload")]
        self.assertEqual(len(uploads), 1)
        self.assertTrue(uploads[0][-1].endswith(".sha256"))
        self.assertEqual(Path(uploads[0][-1]).read_text(), release.checksum(selected))

    def test_incomplete_public_release_is_not_modified(self):
        listing, handler, download, _ = self.mock_existing_release(include_digest=False)
        with patch.object(release, "run", return_value=listing), patch.object(release, "gh") as gh:
            with self.assertRaisesRegex(ValueError, "incomplete"):
                release.release_assets("owner/repo", "v1.4.3", self.apk, "fp", self.info, download)
        gh.assert_not_called()

    def test_rerun_rejects_bad_checksum_and_signer(self):
        listing, handler, download, _ = self.mock_existing_release(bad_digest=True)
        with patch.object(release, "run", return_value=listing), \
                patch.object(release, "gh", side_effect=handler), patch.object(release, "verify_apk"):
            with self.assertRaisesRegex(ValueError, "checksum"):
                release.release_assets("owner/repo", "v1.4.3", self.apk, "fp", self.info, download)
        with patch.object(release, "run", return_value=listing), \
                patch.object(release, "gh", side_effect=handler), \
                patch.object(release, "verify_apk", side_effect=ValueError("wrong signer")):
            with self.assertRaisesRegex(ValueError, "wrong signer"):
                release.release_assets("owner/repo", "v1.4.3", self.apk, "fp", self.info, download)

    def test_publish_recovers_failed_push_and_preserves_newer_index(self):
        # Use a real isolated Git remote, replacing only the GitHub API and APK verifier.
        original_run = release.run
        remote = self.directory / "remote.git"
        original_run("git", "init", "--bare", "--initial-branch=repo", remote)
        output = self.directory / "output"
        output.mkdir()
        original_bytes = self.apk.read_bytes()
        signed = output / self.apk.name
        signed.write_bytes(original_bytes)
        release.write_json(output / "metadata.json", self.info | {"source": release.source_info()})
        releases = {}
        fail_push = True

        def commands(*args, **kwargs):
            nonlocal fail_push
            if args[:2] == ("gh", "api"):
                return json.dumps([[{"tag_name": tag, "draft": value["draft"],
                                     "assets": [{"name": name} for name in value["assets"]]}
                                    for tag, value in releases.items()]])
            if args[:4] == ("git", "remote", "add", "origin"):
                args = (*args[:4], remote)
            if args[:2] == ("git", "push"):
                self.assertFalse(releases["v1.4.3"]["draft"], "Release must be public before the index")
                if fail_push:
                    fail_push = False
                    raise RuntimeError("simulated push failure")
            return original_run(*args, **kwargs)

        def github(repo, command, operation, tag, *args):
            self.assertEqual(command, "release")
            if operation == "create":
                releases[tag] = {"draft": True, "assets": {}}
            elif operation == "upload":
                file = Path(args[0])
                self.assertNotIn(file.name, releases[tag]["assets"])
                releases[tag]["assets"][file.name] = file.read_bytes()
            elif operation == "download":
                name = args[args.index("--pattern") + 1]
                directory = Path(args[args.index("--dir") + 1])
                (directory / name).write_bytes(releases[tag]["assets"][name])
            elif operation == "edit":
                releases[tag]["draft"] = False
                releases[tag]["latest"] = "--latest=true" in args
            else:
                self.fail(f"Unexpected GitHub operation: {operation}")

        def head():
            return original_run("git", "--git-dir", remote, "rev-parse", "refs/heads/repo")

        with patch.object(release, "run", side_effect=commands), patch.object(release, "gh", side_effect=github), \
                patch.object(release, "verify_apk"), patch.dict(release.os.environ, {"GH_TOKEN": "test-only-token"}):
            with self.assertRaisesRegex(RuntimeError, "push failure"):
                release.publish(output, "v1.4.3", "KimmyXYC/mihon-lightshelf")
            signed.write_bytes(b"different rebuild bytes")
            release.publish(output, "v1.4.3", "KimmyXYC/mihon-lightshelf")
            first_head = head()
            indexed = original_run("git", "--git-dir", remote, "show", f"repo:apk/{signed.name}")
            self.assertEqual(indexed.encode(), original_bytes)
            release.publish(output, "v1.4.3", "KimmyXYC/mihon-lightshelf")
            self.assertEqual(head(), first_head, "Rerun should leave the repo commit unchanged")
            (output / "lightshelf-v1.4.2.apk").write_bytes(b"historical apk")
            release.write_json(output / "metadata.json", self.info | {
                "code": 2, "version": "1.4.2", "source": release.source_info(),
            })
            release.publish(output, "v1.4.2", "KimmyXYC/mihon-lightshelf")
            self.assertFalse(releases["v1.4.2"]["draft"])
            self.assertFalse(releases["v1.4.2"]["latest"])
            self.assertEqual(head(), first_head, "Historical release must not downgrade the index")


if __name__ == "__main__":
    unittest.main()
