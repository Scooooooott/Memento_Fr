"""Verify 0.2.0 -> 0.3.0 in-place upgrade on the project emulator before destructive UI tests."""
import hashlib
import json
from pathlib import Path
import sqlite3
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ADB = ROOT / ".tools/android-sdk/platform-tools/adb.exe"
SERIAL = "emulator-5556"
PACKAGE = "com.scott.frenchvocab"
OUT = ROOT / "docs/qa/0.3.0"
OUT.mkdir(parents=True, exist_ok=True)


def adb(*args):
    return subprocess.run([str(ADB), "-s", SERIAL, *args], check=True, capture_output=True).stdout


def snapshot(name):
    path = OUT / f"upgrade-{name}.db"
    path.write_bytes(adb("exec-out", "run-as", PACKAGE, "cat", "databases/french_user.db"))
    with sqlite3.connect(path) as db:
        data = {table: db.execute(f"SELECT {columns} FROM {table} ORDER BY {order}").fetchall()
                for table, columns, order in [
                    ("settings", "id,daily_new_limit,book_id,auto_play,tts_fallback,show_ipa,show_english,show_spanish", "id"),
                    ("learning_card", "*", "lexeme_uid"), ("review_log", "*", "id"),
                    ("study_session", "*", "id"), ("study_session_item", "*", "session_id,position"),
                    ("favorites", "*", "lexeme_uid"), ("sqlite_sequence", "*", "name")
                ]}
        version = db.execute("PRAGMA user_version").fetchone()[0]
        session = db.execute("SELECT id,position,revealed FROM study_session WHERE completed_at IS NULL").fetchone()
        chinese = db.execute("SELECT show_chinese FROM settings").fetchone()[0] if version == 2 else None
    return data, version, session, chinese


old_package = adb("shell", "dumpsys", "package", PACKAGE).decode()
assert "versionName=0.2.0" in old_package, "Run before installing the new APK or resetting test data"
adb("shell", "am", "force-stop", PACKAGE)
before, version, session, _ = snapshot("before")
assert version == 1 and session and session[2] == 1, "Expected a revealed, unfinished v1 session"
assert before["learning_card"] and before["review_log"], "Upgrade evidence needs actual learning history"
apk = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
installed = adb("install", "-r", str(apk)).decode()
assert "Success" in installed
assert "versionName=0.3.0" in adb("shell", "dumpsys", "package", PACKAGE).decode()
adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
deadline = time.monotonic() + 30
while time.monotonic() < deadline:
    adb("shell", "uiautomator", "dump", "/sdcard/words-upgrade-qa.xml")
    xml = adb("exec-out", "cat", "/sdcard/words-upgrade-qa.xml")
    texts = {node.get("text") for node in ET.fromstring(xml).iter("node")}
    if {"忘记", "模糊", "记得", "很熟"}.issubset(texts):
        break
    time.sleep(0.3)
else:
    raise AssertionError("The saved revealed answer did not reappear after installing the upgrade")
after, new_version, new_session, chinese = snapshot("after")
assert new_version == 2 and chinese == 1
assert session == new_session and before == after, "Upgrade changed existing learning history or session"
(OUT / "upgrade-restored.xml").write_bytes(xml)
(OUT / "upgrade-restored.png").write_bytes(adb("exec-out", "screencap", "-p"))
summary = {
    "result": "PASS", "from_app": "0.2.0", "to_app": "0.3.0", "schema": [version, new_version],
    "apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "old_columns_unchanged": list(before),
    "session_id": session[0], "position": session[1], "answer_revealed": True, "show_chinese": chinese,
    "old_rows_sha256": hashlib.sha256(json.dumps(before, ensure_ascii=False).encode()).hexdigest(),
    "new_rows_sha256": hashlib.sha256(json.dumps(after, ensure_ascii=False).encode()).hexdigest(),
}
(OUT / "apk-upgrade.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps(summary, ensure_ascii=False))
