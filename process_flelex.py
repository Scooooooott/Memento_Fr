from __future__ import annotations

import argparse
import csv
import json
import re
import shutil
import subprocess
import sys
import unicodedata
from pathlib import Path
from typing import Iterable

import pandas as pd


CEFR_LEVELS = ["A1", "A2", "B1", "B2", "C1", "C2"]

# Canonical POS labels used by the join. These mappings are deliberately
# explicit so that a new/unexpected source tag cannot silently be conflated.
BEACCO_POS_MAP = {
    "ADJ": "ADJ",
    "ADV": "ADV",
    "DET:ART": "DET",
    "DET:POS": "DET",
    "INT": "INT",
    "KON": "CONJ",
    "NOM": "NOUN",
    "PRO": "PRON",
    "PRP": "PREP",
    "PRP:DET": "PREPDET",
    "VER": "VERB",
}

CRF_POS_MAP = {
    "A": "ADJ",
    "ADV": "ADV",
    "CONJC": "CONJ",
    "CONJS": "CONJ",
    "DET": "DET",
    "DETWH": "DET",
    "N": "NOUN",
    "PREP": "PREP",
    "PREPDET": "PREPDET",
    "PREPPRO": "PRON",
    "PRO": "PRON",
    "V": "VERB",
    "X": "X",
}


def normalize_word(value: object) -> str:
    text = unicodedata.normalize("NFC", str(value).strip().lower())
    return re.sub(r"\s+", " ", text)


def normalized_header(value: object) -> str:
    text = unicodedata.normalize("NFKD", str(value).strip().lower())
    text = "".join(char for char in text if not unicodedata.combining(char))
    return re.sub(r"[^a-z0-9]+", "_", text).strip("_")


def detect_encoding(path: Path) -> str:
    prefix = path.read_bytes()[:4]
    if prefix.startswith(b"\xef\xbb\xbf"):
        return "utf-8-sig"
    if prefix.startswith((b"\xff\xfe", b"\xfe\xff")):
        return "utf-16"
    try:
        path.read_text(encoding="utf-8")
        return "utf-8"
    except UnicodeDecodeError:
        return "cp1252"


def detect_delimiter(path: Path, encoding: str) -> str:
    with path.open("r", encoding=encoding, newline="") as handle:
        sample = handle.read(65536)
    try:
        return csv.Sniffer().sniff(sample, delimiters="\t,;|").delimiter
    except csv.Error:
        return "\t" if path.suffix.lower() == ".tsv" else ","


def read_delimited(path: Path) -> tuple[pd.DataFrame, str, str]:
    encoding = detect_encoding(path)
    delimiter = detect_delimiter(path, encoding)
    frame = pd.read_csv(
        path,
        sep=delimiter,
        encoding=encoding,
        dtype=str,
        keep_default_na=False,
    )
    frame.columns = [str(column).lstrip("\ufeff").strip() for column in frame.columns]
    return frame, encoding, delimiter


def find_column(
    frame: pd.DataFrame,
    candidates: Iterable[str],
    *,
    source_name: str,
    purpose: str,
    required: bool = True,
) -> str | None:
    lookup = {normalized_header(column): column for column in frame.columns}
    for candidate in candidates:
        found = lookup.get(normalized_header(candidate))
        if found is not None:
            return found
    if required:
        raise KeyError(
            f"{source_name}: cannot find {purpose} column. "
            f"Available columns: {list(frame.columns)}"
        )
    return None


def resolve_schema(frame: pd.DataFrame, source_name: str, has_level: bool) -> dict[str, object]:
    schema: dict[str, object] = {
        "word": find_column(
            frame,
            ["word", "lemma", "lemme", "headword", "lexeme", "lexème"],
            source_name=source_name,
            purpose="word/lemma",
        ),
        "pos": find_column(
            frame,
            ["pos", "tag", "part_of_speech", "partofspeech", "categorie", "catégorie"],
            source_name=source_name,
            purpose="POS",
        ),
    }
    frequencies: dict[str, str] = {}
    for level in CEFR_LEVELS:
        frequencies[level] = find_column(
            frame,
            [f"freq_{level}", f"frequency_{level}", f"{level}_frequency", level],
            source_name=source_name,
            purpose=f"{level} frequency",
        )
    schema["frequencies"] = frequencies
    schema["total"] = find_column(
        frame,
        ["freq_total", "total_frequency", "frequency_total", "total_freq", "total"],
        source_name=source_name,
        purpose="total frequency",
        required=False,
    )
    schema["level"] = (
        find_column(
            frame,
            ["level", "cefr_level", "cefr", "niveau", "niveau_cefr", "niveau_cecrl"],
            source_name=source_name,
            purpose="CEFR level",
        )
        if has_level
        else None
    )
    return schema


def parse_numeric(series: pd.Series, *, source_name: str, column_name: str) -> pd.Series:
    stripped = series.astype(str).str.strip()
    parsed = pd.to_numeric(stripped.str.replace(",", ".", regex=False), errors="coerce")
    invalid = stripped.ne("") & parsed.isna()
    if invalid.any():
        examples = stripped[invalid].drop_duplicates().head(5).tolist()
        print(
            f"WARNING: {source_name}.{column_name} contains {int(invalid.sum())} "
            f"non-numeric values; converted to NaN. Examples: {examples}"
        )
    return parsed


def prepare_source(
    frame: pd.DataFrame,
    schema: dict[str, object],
    *,
    source: str,
    pos_map: dict[str, str],
) -> pd.DataFrame:
    word_column = str(schema["word"])
    pos_column = str(schema["pos"])
    prepared = pd.DataFrame(index=frame.index)
    prepared[f"{source}_source_row"] = frame.index + 2
    prepared[f"{source}_word"] = frame[word_column].astype(str)
    prepared[f"{source}_pos"] = frame[pos_column].astype(str)
    prepared["normalized_word"] = prepared[f"{source}_word"].map(normalize_word)

    raw_pos = prepared[f"{source}_pos"].astype(str).str.strip().str.upper()
    unmapped = sorted(set(raw_pos) - set(pos_map))
    if unmapped:
        raise ValueError(f"{source}: unmapped POS values: {unmapped}")
    prepared["normalized_pos"] = raw_pos.map(pos_map)

    frequency_columns = schema["frequencies"]
    assert isinstance(frequency_columns, dict)
    for level in CEFR_LEVELS:
        original = str(frequency_columns[level])
        prepared[f"{source}_{level}"] = parse_numeric(
            frame[original], source_name=source, column_name=original
        )

    total_column = schema["total"]
    if total_column is None:
        prepared[f"{source}_total"] = pd.Series(float("nan"), index=frame.index)
    else:
        prepared[f"{source}_total"] = parse_numeric(
            frame[str(total_column)], source_name=source, column_name=str(total_column)
        )

    level_column = schema["level"]
    if level_column is not None:
        prepared[f"{source}_level"] = (
            frame[str(level_column)].astype(str).str.strip().str.upper().replace("", pd.NA)
        )
    return prepared


def print_file_inspection(
    path: Path,
    frame: pd.DataFrame,
    encoding: str,
    delimiter: str,
) -> None:
    print("\n" + "=" * 88)
    print(f"Source file: {path.resolve()}")
    print(f"Encoding: {encoding}; delimiter: {delimiter!r}; rows: {len(frame)}")
    print(f"Columns: {list(frame.columns)}")
    print("First 5 rows:")
    print(frame.head(5).to_string(index=False))


def print_pos_mapping(name: str, frame: pd.DataFrame, pos_column: str, mapping: dict[str, str]) -> None:
    values = sorted(frame[pos_column].astype(str).str.strip().str.upper().unique().tolist())
    print(f"\n{name} POS values: {values}")
    print(f"{name} POS mapping:")
    for value in values:
        print(f"  {value} -> {mapping.get(value, '[UNMAPPED]')}")


def merge_sources(crf: pd.DataFrame, beacco: pd.DataFrame) -> tuple[pd.DataFrame, dict[str, int]]:
    join_keys = ["normalized_word", "normalized_pos"]
    beacco_duplicate_keys = beacco.duplicated(join_keys, keep=False)
    if beacco_duplicate_keys.any():
        sample = beacco.loc[
            beacco_duplicate_keys,
            join_keys + ["beacco_word", "beacco_pos", "beacco_source_row"],
        ].head(20)
        raise ValueError(
            "Beacco has duplicate normalized word+POS keys, so a safe many-to-one join "
            f"cannot be performed. Sample:\n{sample.to_string(index=False)}"
        )

    merged = crf.merge(
        beacco,
        how="left",
        on=join_keys,
        sort=False,
        validate="many_to_one",
    )
    if len(merged) != len(crf):
        raise AssertionError(
            f"Direct join inflated rows: CRF={len(crf)}, merged={len(merged)}"
        )

    direct_mask = merged["beacco_source_row"].notna()
    merged["match_method"] = "crf_only"
    merged.loc[direct_mask, "match_method"] = "word_pos"

    crf_word_counts = crf["normalized_word"].value_counts()
    beacco_word_counts = beacco["normalized_word"].value_counts()
    unique_beacco_by_word = beacco.loc[
        beacco["normalized_word"].map(beacco_word_counts).eq(1)
    ].set_index("normalized_word")

    fallback_mask = (
        ~direct_mask
        & merged["normalized_word"].map(crf_word_counts).eq(1)
        & merged["normalized_word"].map(beacco_word_counts).eq(1)
    )
    beacco_columns = [
        column
        for column in beacco.columns
        if column not in {"normalized_word", "normalized_pos"}
    ]
    for column in beacco_columns:
        merged.loc[fallback_mask, column] = merged.loc[
            fallback_mask, "normalized_word"
        ].map(unique_beacco_by_word[column])
    merged.loc[fallback_mask, "match_method"] = "unique_word"

    used_beacco_rows = set(
        pd.to_numeric(merged["beacco_source_row"], errors="coerce")
        .dropna()
        .astype(int)
        .tolist()
    )
    beacco_only = beacco.loc[~beacco["beacco_source_row"].isin(used_beacco_rows)].copy()
    beacco_only["match_method"] = "beacco_only"
    for column in crf.columns:
        if column not in beacco_only.columns:
            beacco_only[column] = pd.NA

    master = pd.concat([merged, beacco_only], ignore_index=True, sort=False)
    expected_master_rows = len(crf) + len(beacco_only)
    if len(master) != expected_master_rows or len(master) > len(crf) + len(beacco):
        raise AssertionError(
            "Union row-count check failed: "
            f"master={len(master)}, expected={expected_master_rows}, "
            f"source_sum={len(crf) + len(beacco)}"
        )

    stats = {
        "direct_matches": int(direct_mask.sum()),
        "fallback_matches": int(fallback_mask.sum()),
        "beacco_only": int(len(beacco_only)),
        "direct_join_rows": int(len(merged)),
        "expected_master_rows": int(expected_master_rows),
    }
    return master, stats


def assign_levels(master: pd.DataFrame) -> pd.DataFrame:
    result = master.copy()
    beacco_level = result["beacco_level"].astype("string").str.strip().str.upper()
    valid_beacco = beacco_level.isin(CEFR_LEVELS)
    invalid_beacco = beacco_level.notna() & ~valid_beacco
    if invalid_beacco.any():
        invalid_values = sorted(beacco_level[invalid_beacco].dropna().unique().tolist())
        print(f"WARNING: invalid Beacco CEFR levels ignored: {invalid_values}")

    result["assigned_cefr"] = pd.Series(pd.NA, index=result.index, dtype="string")
    result["level_source"] = "unresolved"
    result.loc[valid_beacco, "assigned_cefr"] = beacco_level[valid_beacco]
    result.loc[valid_beacco, "level_source"] = "beacco"

    for level in CEFR_LEVELS:
        choose = result["assigned_cefr"].isna() & result[f"crf_{level}"].fillna(0).gt(0)
        result.loc[choose, "assigned_cefr"] = level
        result.loc[choose, "level_source"] = "crf_first_attested"

    result["assigned_level_frequency"] = pd.Series(float("nan"), index=result.index)
    for level in CEFR_LEVELS:
        at_level = result["assigned_cefr"].eq(level)
        source_frequency = pd.to_numeric(
            result.loc[at_level, f"crf_{level}"], errors="coerce"
        ).combine_first(
            pd.to_numeric(result.loc[at_level, f"beacco_{level}"], errors="coerce")
        )
        result.loc[at_level, "assigned_level_frequency"] = source_frequency

    result["total_frequency"] = pd.to_numeric(
        result["crf_total"], errors="coerce"
    ).combine_first(pd.to_numeric(result["beacco_total"], errors="coerce"))
    result["word"] = result["crf_word"].where(
        result["crf_word"].notna() & result["crf_word"].astype(str).str.strip().ne(""),
        result["beacco_word"],
    )
    result["pos"] = result["normalized_pos"]
    return result


def dataframe_rows(frame: pd.DataFrame, columns: list[str]) -> list[list[object]]:
    values = frame[columns].astype(object).where(frame[columns].notna(), None)
    return values.values.tolist()


def build_workbook_payload(
    sheet_frames: dict[str, pd.DataFrame], columns: list[str], payload_path: Path
) -> None:
    payload = {
        "columns": columns,
        "sheets": {
            sheet_name: dataframe_rows(frame, columns)
            for sheet_name, frame in sheet_frames.items()
        },
    }
    with payload_path.open("w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, separators=(",", ":"))


def run_workbook_builder(
    *,
    node_exe: str,
    builder_path: Path,
    payload_path: Path,
    workbook_path: Path,
    preview_dir: Path,
) -> None:
    command = [
        node_exe,
        str(builder_path),
        str(payload_path),
        str(workbook_path),
        str(preview_dir),
    ]
    completed = subprocess.run(command, text=True, encoding="utf-8", check=False)
    if completed.returncode != 0:
        raise RuntimeError(f"Workbook builder failed with exit code {completed.returncode}")


def main() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description="Merge and sort FLELex Beacco and CRF data.")
    parser.add_argument("--beacco", default="FleLex_TT_Beacco.tsv")
    parser.add_argument("--crf", default="FleLex_CRF.csv")
    parser.add_argument("--output-dir", default="outputs/flelex-cefr-sorted")
    parser.add_argument("--node-exe", default=shutil.which("node") or "node")
    parser.add_argument(
        "--workbook-builder",
        default=str(Path(__file__).resolve().parent / ".flelex_artifact" / "build_flelex_workbook.mjs"),
    )
    args = parser.parse_args()

    root = Path(__file__).resolve().parent
    beacco_path = (root / args.beacco).resolve()
    crf_path = (root / args.crf).resolve()
    output_dir = (root / args.output_dir).resolve()
    builder_path = Path(args.workbook_builder).resolve()

    for path in (beacco_path, crf_path, builder_path):
        if not path.exists():
            raise FileNotFoundError(path)

    beacco_raw, beacco_encoding, beacco_delimiter = read_delimited(beacco_path)
    crf_raw, crf_encoding, crf_delimiter = read_delimited(crf_path)
    print_file_inspection(beacco_path, beacco_raw, beacco_encoding, beacco_delimiter)
    print_file_inspection(crf_path, crf_raw, crf_encoding, crf_delimiter)

    beacco_schema = resolve_schema(beacco_raw, "Beacco", has_level=True)
    crf_schema = resolve_schema(crf_raw, "CRF", has_level=False)
    print_pos_mapping("Beacco", beacco_raw, str(beacco_schema["pos"]), BEACCO_POS_MAP)
    print_pos_mapping("CRF", crf_raw, str(crf_schema["pos"]), CRF_POS_MAP)

    beacco = prepare_source(
        beacco_raw, beacco_schema, source="beacco", pos_map=BEACCO_POS_MAP
    )
    crf = prepare_source(crf_raw, crf_schema, source="crf", pos_map=CRF_POS_MAP)

    master, join_stats = merge_sources(crf, beacco)
    master = assign_levels(master)

    user_columns = [
        "word",
        "pos",
        "assigned_cefr",
        "level_source",
        "assigned_level_frequency",
        "total_frequency",
        *[f"crf_{level}" for level in CEFR_LEVELS],
        "crf_total",
        *[f"beacco_{level}" for level in CEFR_LEVELS],
        "beacco_total",
        "beacco_level",
        "match_method",
        "crf_word",
        "crf_pos",
        "beacco_word",
        "beacco_pos",
    ]
    master_columns = user_columns + [
        "normalized_word",
        "normalized_pos",
        "crf_source_row",
        "beacco_source_row",
    ]

    invalid_assigned = master["assigned_cefr"].notna() & ~master["assigned_cefr"].isin(
        CEFR_LEVELS
    )
    if invalid_assigned.any():
        raise AssertionError(
            "assigned_cefr contains invalid values: "
            f"{sorted(master.loc[invalid_assigned, 'assigned_cefr'].unique().tolist())}"
        )

    duplicate_mask = master.duplicated(["normalized_word", "normalized_pos"], keep=False)
    duplicate_key_count = int(
        master.loc[duplicate_mask, ["normalized_word", "normalized_pos"]]
        .drop_duplicates()
        .shape[0]
    )

    output_dir.mkdir(parents=True, exist_ok=True)
    sorted_frames: dict[str, pd.DataFrame] = {}
    for level in CEFR_LEVELS:
        level_frame = master.loc[master["assigned_cefr"].eq(level)].copy()
        level_frame = level_frame.sort_values(
            ["assigned_level_frequency", "total_frequency", "word"],
            ascending=[False, False, True],
            na_position="last",
            kind="mergesort",
        )
        sorted_frames[level] = level_frame
        level_frame[user_columns].to_csv(
            output_dir / f"flelex_{level}.csv", index=False, encoding="utf-8-sig"
        )

    unresolved = master.loc[master["assigned_cefr"].isna()].copy()
    unresolved = unresolved.sort_values(
        ["total_frequency", "word"], ascending=[False, True], na_position="last", kind="mergesort"
    )
    unresolved[user_columns].to_csv(
        output_dir / "unresolved.csv", index=False, encoding="utf-8-sig"
    )

    master = master.sort_values(
        ["assigned_cefr", "assigned_level_frequency", "total_frequency", "word"],
        ascending=[True, False, False, True],
        na_position="last",
        kind="mergesort",
    )
    master[master_columns].to_csv(
        output_dir / "flelex_master.csv", index=False, encoding="utf-8-sig"
    )

    level_counts = {level: len(sorted_frames[level]) for level in CEFR_LEVELS}
    partition_total = sum(level_counts.values()) + len(unresolved)
    if partition_total != len(master):
        raise AssertionError(
            f"Partition check failed: levels+unresolved={partition_total}, master={len(master)}"
        )

    workbook_sheets = {**sorted_frames, "Unresolved": unresolved}
    payload_path = output_dir / ".flelex_workbook_payload.json"
    workbook_path = output_dir / "flelex_cefr_sorted.xlsx"
    preview_dir = Path.home() / "AppData" / "Local" / "Temp" / "flelex_cefr_previews"
    build_workbook_payload(workbook_sheets, user_columns, payload_path)
    run_workbook_builder(
        node_exe=args.node_exe,
        builder_path=builder_path,
        payload_path=payload_path,
        workbook_path=workbook_path,
        preview_dir=preview_dir,
    )
    payload_path.unlink()

    crf_first_attested = int(master["level_source"].eq("crf_first_attested").sum())
    unresolved_count = int(master["assigned_cefr"].isna().sum())
    print("\n" + "=" * 88)
    print("FINAL STATISTICS")
    print(f"CRF original rows: {len(crf_raw)}")
    print(f"Beacco original rows: {len(beacco_raw)}")
    print(f"Matched by normalized_word + normalized_pos: {join_stats['direct_matches']}")
    print(f"Matched by unique normalized_word fallback: {join_stats['fallback_matches']}")
    print(f"Beacco-only rows appended to the union: {join_stats['beacco_only']}")
    print(f"No Beacco level; assigned by CRF first-attested: {crf_first_attested}")
    print(f"Unresolved: {unresolved_count}")
    for level in CEFR_LEVELS:
        print(f"{level} entries: {level_counts[level]}")
    print(f"Master rows: {len(master)}")
    print(
        f"Partition check: {sum(level_counts.values())} CEFR rows + {unresolved_count} "
        f"unresolved = {partition_total} master rows"
    )
    print(
        "Duplicate normalized word+POS check: "
        f"{int(duplicate_mask.sum())} rows across {duplicate_key_count} duplicate keys"
    )
    print("Invalid assigned_cefr check: 0")
    print(
        "Join inflation check: PASS "
        f"(direct join rows={join_stats['direct_join_rows']} equals CRF rows; "
        f"master rows={len(master)} equals CRF rows + unmatched Beacco rows)"
    )
    if duplicate_mask.any():
        print("Duplicate-key sample (source entries retained, not deduplicated):")
        print(
            master.loc[
                duplicate_mask,
                ["word", "pos", "crf_source_row", "beacco_source_row", "match_method"],
            ]
            .head(20)
            .to_string(index=False)
        )

    sample_columns = [
        "word",
        "pos",
        "assigned_cefr",
        "level_source",
        "assigned_level_frequency",
        "total_frequency",
    ]
    print("\n" + "=" * 88)
    print("RANDOM REVIEW SAMPLES (10 PER LEVEL)")
    for index, level in enumerate(CEFR_LEVELS):
        sample = sorted_frames[level].sample(
            n=min(10, len(sorted_frames[level])), random_state=20260910 + index
        )
        print(f"\n[{level}]")
        print(sample[sample_columns].to_string(index=False))

    print("\nGenerated files:")
    for path in sorted(output_dir.glob("flelex_*.csv")):
        print(path)
    print(output_dir / "unresolved.csv")
    print(workbook_path)
    print(f"Workbook preview directory: {preview_dir}")


if __name__ == "__main__":
    main()
