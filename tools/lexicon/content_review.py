"""Load and apply approved human content-review batches."""
from __future__ import annotations

import copy
from hashlib import sha256
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SENSE_FIELDS = {"english", "spanish", "chinese"}
EXAMPLE_FIELDS = {"sense_index", "french", "english", "spanish", "chinese"}


def file_sha256(path: Path) -> str:
    return sha256(path.read_bytes()).hexdigest()


def word_sha256(word: dict) -> str:
    payload = json.dumps(
        word,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return sha256(payload).hexdigest()


def load_jsonl(path: Path) -> list[dict]:
    rows = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            rows.append(json.loads(line))
        except json.JSONDecodeError as error:
            raise RuntimeError(f"Invalid JSONL at {path}:{line_number}: {error}") from error
    return rows


def _repo_path(value: str) -> Path:
    path = (ROOT / value).resolve()
    if not path.is_relative_to(ROOT.resolve()):
        raise RuntimeError(f"Review artifact must stay inside the repository: {value}")
    return path


def _verify_translation(values: dict, fields: set[str], label: str) -> None:
    if set(values) != fields:
        raise RuntimeError(f"Unexpected fields for {label}: {sorted(values)}")
    for key in fields - {"sense_index"}:
        value = values[key]
        if not isinstance(value, str) or not value.strip():
            raise RuntimeError(f"Missing {key} for {label}")


def load_review_registry(registry_path: Path) -> dict:
    registry = json.loads(registry_path.read_text(encoding="utf-8"))
    if registry.get("schema_version") != 1:
        raise RuntimeError(f"Unsupported content-review registry: {registry_path}")
    if not registry.get("batches"):
        raise RuntimeError(f"Content-review registry has no batches: {registry_path}")
    return registry


def apply_review_registry(
    words: list[dict],
    registry_path: Path,
    *,
    require_original_hash: bool,
) -> tuple[list[dict], dict]:
    """Return reviewed words and a machine-readable application report.

    ``require_original_hash`` is used when changing the checked formal source.
    Generator refreshes deliberately disable it: the stable UID plus the
    approved artifact hashes are the contract, and the reviewed fields win.
    """
    registry_path = registry_path.resolve()
    registry = load_review_registry(registry_path)
    reviewed = copy.deepcopy(words)
    by_uid = {word["uid"]: word for word in reviewed}
    if len(by_uid) != len(reviewed):
        raise RuntimeError("Source contains duplicate word UIDs")
    original_hashes = {uid: word_sha256(word) for uid, word in by_uid.items()}
    seen_uids: set[str] = set()
    changed_uids: list[str] = []
    batch_reports = []
    total_senses = 0
    total_examples = 0

    for batch in registry["batches"]:
        meanings_path = _repo_path(batch["meanings_file"])
        examples_path = _repo_path(batch["examples_file"])
        artifacts = [
            (meanings_path, batch["meanings_sha256"]),
            (examples_path, batch["examples_sha256"]),
        ]
        if batch.get("spot_check_file"):
            artifacts.append((_repo_path(batch["spot_check_file"]), batch["spot_check_sha256"]))
        for path, expected in artifacts:
            actual = file_sha256(path)
            if actual != expected:
                raise RuntimeError(f"Review artifact hash mismatch for {path}: {actual} != {expected}")

        meanings = load_jsonl(meanings_path)
        examples = load_jsonl(examples_path)
        meaning_by_uid = {row["uid"]: row for row in meanings}
        example_by_uid = {row["uid"]: row for row in examples}
        if len(meaning_by_uid) != len(meanings) or len(example_by_uid) != len(examples):
            raise RuntimeError(f"Duplicate UID in review batch {batch['batch_id']}")
        if not set(meaning_by_uid).issubset(example_by_uid):
            raise RuntimeError(f"Meaning patch lacks completed example review in {batch['batch_id']}")
        overlap = seen_uids.intersection(example_by_uid)
        if overlap:
            raise RuntimeError(f"UID appears in multiple review batches: {sorted(overlap)[:3]}")

        for uid, example_row in example_by_uid.items():
            if uid not in by_uid:
                raise RuntimeError(f"Reviewed UID is absent from generated source: {uid}")
            if uid in meaning_by_uid and meaning_by_uid[uid]["original_word_sha256"] != example_row["original_word_sha256"]:
                raise RuntimeError(f"Meaning/example baseline hash differs for {uid}")
            senses = (
                meaning_by_uid[uid]["proposed_senses"]
                if uid in meaning_by_uid
                else by_uid[uid]["senses"]
            )
            for index, sense in enumerate(senses):
                _verify_translation(sense, SENSE_FIELDS, f"{uid} sense {index}")
            raw_examples = example_row["examples"]
            if example_row["reviewed_sense_count"] != len(senses) or len(raw_examples) != len(senses):
                raise RuntimeError(f"Sense/example count mismatch for {uid}")
            cleaned_examples = []
            for index, example in enumerate(raw_examples):
                cleaned = {key: value for key, value in example.items() if key != "source"}
                _verify_translation(cleaned, EXAMPLE_FIELDS, f"{uid} example {index}")
                if cleaned["sense_index"] != index:
                    raise RuntimeError(f"Non-contiguous sense_index for {uid}: {cleaned['sense_index']}")
                cleaned_examples.append(cleaned)

            desired_matches = by_uid[uid]["senses"] == senses and by_uid[uid]["examples"] == cleaned_examples
            if require_original_hash and original_hashes[uid] != example_row["original_word_sha256"] and not desired_matches:
                raise RuntimeError(
                    f"Formal source changed since review for {uid}: "
                    f"{original_hashes[uid]} != {example_row['original_word_sha256']}"
                )
            if not desired_matches:
                by_uid[uid]["senses"] = copy.deepcopy(senses)
                by_uid[uid]["examples"] = cleaned_examples
                changed_uids.append(uid)
            seen_uids.add(uid)
            total_senses += len(senses)
            total_examples += len(cleaned_examples)

        batch_reports.append({
            "batch_id": batch["batch_id"],
            "review_date": batch["review_date"],
            "words": len(example_by_uid),
            "meaning_patches": len(meaning_by_uid),
            "senses": sum(
                len(meaning_by_uid[uid]["proposed_senses"] if uid in meaning_by_uid else by_uid[uid]["senses"])
                for uid in example_by_uid
            ),
            "examples": sum(len(row["examples"]) for row in examples),
        })

    ordered = [by_uid[word["uid"]] for word in reviewed]
    report = {
        "registry": registry_path.relative_to(ROOT).as_posix(),
        "registry_sha256": file_sha256(registry_path),
        "content_version": registry["content_version"],
        "review_date": registry["review_date"],
        "reviewed_words": len(seen_uids),
        "changed_words": len(set(changed_uids)),
        "reviewed_senses": total_senses,
        "reviewed_examples": total_examples,
        "target_uids": [word["uid"] for word in reviewed if word["uid"] in seen_uids],
        "batches": batch_reports,
    }
    return ordered, report
