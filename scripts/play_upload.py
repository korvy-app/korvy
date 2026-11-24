#!/usr/bin/env python3
"""Upload an .aab to a Google Play track with the Android Publisher API.

Key: ~/.config/play/piano-notes-play.json (the same service account as Piano Notes; never committed).
Runs in the venv at ~/.local/share/play-venv (google-auth, requests); use scripts/play-upload.sh.

  play_upload.py check                          # can the key reach the app? lists tracks
  play_upload.py upload AAB --track TRACK --notes FILE [--draft]

TRACK: internal, alpha (closed testing), beta (open testing), production, or a custom closed track name.
--draft leaves the release as a draft to review and roll out in Play Console.
"""
import argparse, os, sys

import requests
from google.auth.transport.requests import Request
from google.oauth2 import service_account

PACKAGE = os.environ.get("PLAY_PACKAGE", "app.korvy.launcher")
KEY = os.path.expanduser(os.environ.get("PLAY_KEY", "~/.config/play/piano-notes-play.json"))
API = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PACKAGE}"
UPLOAD = f"https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/{PACKAGE}"


def session():
    creds = service_account.Credentials.from_service_account_file(
        KEY, scopes=["https://www.googleapis.com/auth/androidpublisher"])
    creds.refresh(Request())
    s = requests.Session()
    s.headers["Authorization"] = f"Bearer {creds.token}"
    return s


def ok(r):
    if r.status_code >= 300:
        try:
            msg = r.json()["error"]["message"]
        except Exception:
            msg = r.text[:500]
        sys.exit(f"Play API error {r.status_code}: {msg}")
    return r.json() if r.content else {}


def check(s):
    edit = ok(s.post(f"{API}/edits"))["id"]
    try:
        for t in ok(s.get(f"{API}/edits/{edit}/tracks")).get("tracks", []):
            rel = [f"{r.get('name', '?')} {r.get('versionCodes', [])} {r['status']}" for r in t.get("releases", [])]
            print(f"{t['track']:<12} {'; '.join(rel) or '(empty)'}")
    finally:
        s.delete(f"{API}/edits/{edit}")
    print("Key works: the service account can read the app.")


def upload(s, aab, track, notes_file, draft):
    notes = open(notes_file).read().strip()
    if len(notes) > 500:
        sys.exit(f"Release notes are {len(notes)} characters; Play allows 500.")
    edit = ok(s.post(f"{API}/edits"))["id"]
    try:
        print(f"Uploading {os.path.basename(aab)} ({os.path.getsize(aab) // 1_000_000} MB)…")
        with open(aab, "rb") as f:
            bundle = ok(s.post(f"{UPLOAD}/edits/{edit}/bundles", params={"uploadType": "media"},
                               headers={"Content-Type": "application/octet-stream"}, data=f, timeout=900))
        code = bundle["versionCode"]
        print(f"Uploaded version code {code}")
        release = {"versionCodes": [str(code)], "status": "draft" if draft else "completed",
                   "releaseNotes": [{"language": "en-US", "text": notes}]}
        ok(s.put(f"{API}/edits/{edit}/tracks/{track}", json={"track": track, "releases": [release]}))
        ok(s.post(f"{API}/edits/{edit}:commit"))
        edit = None
        print(f"Committed to '{track}' as {'a draft' if draft else 'a release (sent for review)'}.")
    finally:
        if edit:
            s.delete(f"{API}/edits/{edit}")


def main():
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("check")
    u = sub.add_parser("upload")
    u.add_argument("aab")
    u.add_argument("--track", required=True)
    u.add_argument("--notes", required=True)
    u.add_argument("--draft", action="store_true")
    a = p.parse_args()
    s = session()
    if a.cmd == "check":
        check(s)
    else:
        upload(s, a.aab, a.track, a.notes, a.draft)


if __name__ == "__main__":
    main()
