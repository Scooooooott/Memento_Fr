"""Build test-only fixtures from the frozen development source; never edit release content."""
from pathlib import Path
import sqlite3
from lexicon import build_content

root = Path(__file__).resolve().parents[1]
assets = root / "app/src/androidTest/assets"
fixtures = assets / "content-fixtures"
fixtures.mkdir(parents=True, exist_ok=True)
# Select the retained 40-word source in this process only; leave the production
# generator, its full-book default, and all editorial sources untouched.
build_content.SOURCE = root / "data/curated/french_dev.json"
build_content.build(fixtures / "legacy.db")
legacy = (fixtures / "legacy.db").read_bytes()
upgrade = assets / "french_content.db"
upgrade.write_bytes(legacy)
with sqlite3.connect(upgrade) as db:
    db.execute("UPDATE content_meta SET value='qa-upgrade' WHERE key='content_version'")
    db.execute("UPDATE sense SET english='to be (QA updated content)' WHERE lexeme_uid='fr:être:verb:1'")
for name, version, examples in [("chinese.db", 2, True), ("chinese-v1.db", 1, True), ("chinese-senses-only.db", 2, False)]:
    path = fixtures / name
    path.write_bytes(legacy)
    with sqlite3.connect(path) as db:
        db.execute(f"PRAGMA user_version={version}")
        db.execute("ALTER TABLE sense ADD COLUMN chinese TEXT")
        db.execute("UPDATE sense SET chinese='是；处于' WHERE lexeme_uid='fr:être:verb:1'")
        if examples:
            db.execute("ALTER TABLE example ADD COLUMN chinese TEXT")
            db.execute("UPDATE example SET chinese='我在家。' WHERE sense_id IN (SELECT sense_id FROM sense WHERE lexeme_uid='fr:être:verb:1')")
        db.execute("UPDATE content_meta SET value=? WHERE key='content_version'", (name,))
(fixtures / "future.db").write_bytes(legacy)
with sqlite3.connect(fixtures / "future.db") as db:
    db.execute("PRAGMA user_version=3")
print("Test-only fixtures:", fixtures)
