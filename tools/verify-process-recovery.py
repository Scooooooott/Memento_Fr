"""Force-stop recovery acceptance on the project-owned emulator; preserves row-level evidence."""
import hashlib
import json
import argparse
from pathlib import Path
import sqlite3
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ADB = ROOT / ".tools/android-sdk/platform-tools/adb.exe"
SERIAL = "emulator-5556"
PACKAGE = "com.scott.frenchvocab"
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--evidence-dir", default="docs/qa/latest")
OUT = (ROOT / parser.parse_args().evidence_dir).resolve()
if not OUT.is_relative_to(ROOT):
    raise ValueError("Evidence must stay inside the project")
(OUT / "screenshots").mkdir(parents=True, exist_ok=True)

def adb(*args, check=True):
    return subprocess.run([str(ADB), "-s", SERIAL, *args], check=check, capture_output=True).stdout

def answer_screen():
    adb("shell", "uiautomator", "dump", "/sdcard/words-recovery-qa.xml")
    xml = adb("exec-out", "cat", "/sdcard/words-recovery-qa.xml")
    texts = {node.get("text") for node in ET.fromstring(xml).iter("node")}
    return {"忘记", "模糊", "记得", "很熟"}.issubset(texts), xml

def launch():
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        ready, xml = answer_screen()
        if ready:
            return xml
        time.sleep(0.3)
    raise AssertionError("Saved answer and all four rating controls did not reappear")

def snapshot(name):
    file = OUT / f"recovery-{name}.db"
    file.write_bytes(adb("exec-out", "run-as", PACKAGE, "cat", "databases/french_user.db"))
    with sqlite3.connect(file) as db:
        data = {table: db.execute(f"SELECT * FROM {table} ORDER BY {order}").fetchall() for table, order in [
            ("learning_card", "lexeme_uid"), ("review_log", "id"), ("study_session", "id"),
            ("study_session_item", "session_id,position"), ("settings", "id"), ("favorites", "lexeme_uid")
        ]}
        session = db.execute("SELECT id,position,revealed FROM study_session WHERE completed_at IS NULL").fetchone()
        assert session is not None and session[2] == 1, "Fixture must leave an active, revealed card"
    return data, session

test = adb("shell", "am", "instrument", "-w", "-r", "-e", "class",
           PACKAGE + ".AppFlowInstrumentedTest#completeLearningFlowWithRotationDetailAndSettings",
           PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner").decode("utf-8")
(OUT / "process-recovery-setup.log").write_text(test, encoding="utf-8")
assert "OK (1 test)" in test, test[-3000:]
(OUT / "recovery-before.xml").write_bytes(launch())
before, session = snapshot("before")
pid_before = adb("shell", "pidof", PACKAGE).decode().strip()
adb("shell", "am", "force-stop", PACKAGE)
assert not adb("shell", "pidof", PACKAGE, check=False).strip(), "Process must actually terminate"
(OUT / "recovery-after.xml").write_bytes(launch())
pid_after = adb("shell", "pidof", PACKAGE).decode().strip()
assert pid_after and pid_after != pid_before
after, restored = snapshot("after")
assert before == after and session == restored, "Process restart changed durable learning state"
(OUT / "screenshots/08-process-restored.png").write_bytes(adb("exec-out", "screencap", "-p"))
report = {
    "result": "PASS", "serial": SERIAL, "pid_before": pid_before, "pid_after": pid_after,
    "active_session_id": session[0], "position": session[1], "answer_revealed": True,
    "tables_unchanged": list(before),
    "before_sha256": hashlib.sha256(json.dumps(before, ensure_ascii=False).encode()).hexdigest(),
    "after_sha256": hashlib.sha256(json.dumps(after, ensure_ascii=False).encode()).hexdigest(),
}
(OUT / "process-recovery.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps(report, ensure_ascii=False))
