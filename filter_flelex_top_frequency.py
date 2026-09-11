from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path

import pandas as pd


LIMITS = {
    "A1": 1000,
    "A2": 800,
    "B1": 1200,
    "B2": 2000,
    "C1": 1500,
    "C2": 1000,
}


def dataframe_rows(frame: pd.DataFrame, columns: list[str]) -> list[list[object]]:
    values = frame[columns].astype(object).where(frame[columns].notna(), None)
    return values.values.tolist()


def main() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")

    parser = argparse.ArgumentParser(
        description="Select the highest-frequency unique word+POS entries per CEFR level."
    )
    parser.add_argument(
        "--master",
        default="outputs/flelex-cefr-sorted/flelex_master.csv",
    )
    parser.add_argument(
        "--output-dir",
        default="outputs/flelex-cefr-top-frequency",
    )
    parser.add_argument("--node-exe", default=shutil.which("node") or "node")
    parser.add_argument(
        "--workbook-builder",
        default=str(
            Path(__file__).resolve().parent
            / ".flelex_artifact"
            / "build_flelex_workbook.mjs"
        ),
    )
    args = parser.parse_args()

    root = Path(__file__).resolve().parent
    master_path = (root / args.master).resolve()
    output_dir = (root / args.output_dir).resolve()
    builder_path = Path(args.workbook_builder).resolve()
    for path in (master_path, builder_path):
        if not path.exists():
            raise FileNotFoundError(path)

    master = pd.read_csv(master_path, encoding="utf-8-sig")
    required_columns = {
        "word",
        "pos",
        "assigned_cefr",
        "assigned_level_frequency",
        "total_frequency",
        "normalized_word",
        "normalized_pos",
    }
    missing = sorted(required_columns - set(master.columns))
    if missing:
        raise KeyError(f"Master file is missing required columns: {missing}")

    helper_columns = {
        "normalized_word",
        "normalized_pos",
        "crf_source_row",
        "beacco_source_row",
    }
    output_columns = ["frequency_rank"] + [
        column for column in master.columns if column not in helper_columns
    ]
    output_dir.mkdir(parents=True, exist_ok=True)

    sheet_frames: dict[str, pd.DataFrame] = {}
    print("TOP-FREQUENCY CEFR SELECTION")
    for level, limit in LIMITS.items():
        candidates = master.loc[master["assigned_cefr"].eq(level)].copy()
        candidates = candidates.sort_values(
            ["assigned_level_frequency", "total_frequency", "word"],
            ascending=[False, False, True],
            na_position="last",
            kind="mergesort",
        )
        source_rows = len(candidates)
        candidates = candidates.drop_duplicates(
            ["normalized_word", "normalized_pos"], keep="first"
        )
        duplicate_rows_removed = source_rows - len(candidates)
        if len(candidates) < limit:
            raise ValueError(
                f"{level}: requested {limit} entries but only {len(candidates)} unique "
                "normalized word+POS entries are available"
            )

        selected = candidates.head(limit).copy()
        selected.insert(0, "frequency_rank", range(1, len(selected) + 1))
        if len(selected) != limit:
            raise AssertionError(f"{level}: selected {len(selected)} rows, expected {limit}")
        if selected.duplicated(["normalized_word", "normalized_pos"]).any():
            raise AssertionError(f"{level}: duplicate normalized word+POS remains")
        if not selected["assigned_cefr"].eq(level).all():
            raise AssertionError(f"{level}: assigned_cefr validation failed")

        output_path = output_dir / f"flelex_{level}_top{limit}.csv"
        selected[output_columns].to_csv(
            output_path,
            index=False,
            encoding="utf-8-sig",
        )
        sheet_frames[level] = selected

        cutoff = selected.iloc[-1]
        positive_count = int(selected["assigned_level_frequency"].fillna(0).gt(0).sum())
        zero_count = int(selected["assigned_level_frequency"].fillna(0).eq(0).sum())
        missing_count = int(selected["assigned_level_frequency"].isna().sum())
        print(
            f"{level}: selected={len(selected)}, source={source_rows}, "
            f"duplicate_rows_removed={duplicate_rows_removed}, "
            f"positive_level_frequency={positive_count}, zero_level_frequency={zero_count}, "
            f"missing_level_frequency={missing_count}, "
            f"cutoff_level_frequency={cutoff['assigned_level_frequency']}, "
            f"cutoff_total_frequency={cutoff['total_frequency']}"
        )

    payload = {
        "sheet_order": list(LIMITS),
        "columns": output_columns,
        "sheets": {
            level: dataframe_rows(frame, output_columns)
            for level, frame in sheet_frames.items()
        },
    }
    payload_path = output_dir / ".flelex_top_frequency_payload.json"
    with payload_path.open("w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, separators=(",", ":"))

    workbook_path = output_dir / "flelex_cefr_top_frequency.xlsx"
    preview_dir = (
        Path.home()
        / "AppData"
        / "Local"
        / "Temp"
        / "flelex_top_frequency_previews"
    )
    completed = subprocess.run(
        [
            args.node_exe,
            str(builder_path),
            str(payload_path),
            str(workbook_path),
            str(preview_dir),
        ],
        text=True,
        encoding="utf-8",
        check=False,
    )
    if completed.returncode != 0:
        raise RuntimeError(
            f"Workbook builder failed with exit code {completed.returncode}; "
            f"payload retained at {payload_path}"
        )
    payload_path.unlink()

    print("\nGenerated files:")
    for level, limit in LIMITS.items():
        print(output_dir / f"flelex_{level}_top{limit}.csv")
    print(workbook_path)
    print(f"Workbook preview directory: {preview_dir}")


if __name__ == "__main__":
    main()
