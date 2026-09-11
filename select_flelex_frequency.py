from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path

import pandas as pd


LEVEL_CAPS = {
    "A1": 1000,
    "A2": 800,
    "B1": 1200,
    "B2": 2000,
    "C1": 1500,
    "C2": 1000,
}


def dataframe_rows(frame: pd.DataFrame) -> list[list[object]]:
    values = frame.astype(object).where(frame.notna(), None)
    return values.values.tolist()


def run_workbook_builder(
    node_exe: str,
    builder_path: Path,
    payload_path: Path,
    workbook_path: Path,
    preview_dir: Path,
) -> None:
    completed = subprocess.run(
        [
            node_exe,
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
        raise RuntimeError(f"Workbook builder failed with exit code {completed.returncode}")


def main() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")

    parser = argparse.ArgumentParser(
        description="Select the highest positive-frequency entries from each FLELex CEFR table."
    )
    parser.add_argument("--input-dir", default="outputs/flelex-cefr-sorted")
    parser.add_argument("--output-dir", default="outputs/flelex-frequency-selection")
    parser.add_argument("--node-exe", default=shutil.which("node") or "node")
    parser.add_argument(
        "--workbook-builder",
        default=str(
            Path(__file__).resolve().parent
            / ".flelex_artifact"
            / "build_flelex_selection_workbook.mjs"
        ),
    )
    args = parser.parse_args()

    root = Path(__file__).resolve().parent
    input_dir = (root / args.input_dir).resolve()
    output_dir = (root / args.output_dir).resolve()
    builder_path = Path(args.workbook_builder).resolve()
    if not input_dir.is_dir():
        raise FileNotFoundError(input_dir)
    if not builder_path.is_file():
        raise FileNotFoundError(builder_path)

    selected_frames: dict[str, pd.DataFrame] = {}
    statistics: dict[str, dict[str, float | int]] = {}
    common_columns: list[str] | None = None

    for level, cap in LEVEL_CAPS.items():
        input_path = input_dir / f"flelex_{level}.csv"
        if not input_path.is_file():
            raise FileNotFoundError(input_path)

        frame = pd.read_csv(input_path, encoding="utf-8-sig")
        required = {"word", "assigned_cefr", "assigned_level_frequency", "total_frequency"}
        missing = sorted(required - set(frame.columns))
        if missing:
            raise KeyError(f"{input_path.name}: missing columns {missing}")
        if common_columns is None:
            common_columns = frame.columns.tolist()
        elif frame.columns.tolist() != common_columns:
            raise ValueError(f"{input_path.name}: columns differ from the other CEFR files")

        invalid_levels = set(frame["assigned_cefr"].dropna().astype(str)) - {level}
        if invalid_levels:
            raise ValueError(
                f"{input_path.name}: assigned_cefr contains unexpected values {sorted(invalid_levels)}"
            )

        frame["assigned_level_frequency"] = pd.to_numeric(
            frame["assigned_level_frequency"], errors="coerce"
        )
        frame["total_frequency"] = pd.to_numeric(frame["total_frequency"], errors="coerce")
        positive = frame.loc[frame["assigned_level_frequency"].gt(0)].copy()
        positive = positive.sort_values(
            ["assigned_level_frequency", "total_frequency", "word"],
            ascending=[False, False, True],
            na_position="last",
            kind="mergesort",
        )
        selected = positive.head(cap).copy()

        expected_count = min(cap, len(positive))
        if len(selected) != expected_count:
            raise AssertionError(
                f"{level}: selected {len(selected)}, expected {expected_count}"
            )
        if not selected["assigned_level_frequency"].gt(0).all():
            raise AssertionError(f"{level}: non-positive frequency entered the selection")

        selected_frames[level] = selected
        cutoff = float(selected["assigned_level_frequency"].iloc[-1]) if len(selected) else float("nan")
        statistics[level] = {
            "source_rows": int(len(frame)),
            "positive_rows": int(len(positive)),
            "cap": int(cap),
            "selected_rows": int(len(selected)),
            "cutoff_frequency": cutoff,
        }

    assert common_columns is not None
    output_dir.mkdir(parents=True, exist_ok=True)
    for level, frame in selected_frames.items():
        frame.to_csv(
            output_dir / f"flelex_{level}_frequency_selected.csv",
            index=False,
            encoding="utf-8-sig",
        )

    payload_path = output_dir / ".flelex_selection_payload.json"
    payload = {
        "columns": common_columns,
        "sheets": {
            level: dataframe_rows(selected_frames[level][common_columns])
            for level in LEVEL_CAPS
        },
    }
    with payload_path.open("w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, separators=(",", ":"))

    workbook_path = output_dir / "flelex_frequency_selected.xlsx"
    preview_dir = (
        Path.home() / "AppData" / "Local" / "Temp" / "flelex_frequency_selection_previews"
    )
    run_workbook_builder(
        args.node_exe,
        builder_path,
        payload_path,
        workbook_path,
        preview_dir,
    )
    payload_path.unlink()

    print("\nFREQUENCY SELECTION STATISTICS")
    print("level  source  positive  cap  selected  cutoff_frequency")
    for level, stats in statistics.items():
        print(
            f"{level:>2}  {stats['source_rows']:>6}  {stats['positive_rows']:>8}  "
            f"{stats['cap']:>4}  {stats['selected_rows']:>8}  "
            f"{stats['cutoff_frequency']:.4f}"
        )
    print(f"\nOutput directory: {output_dir}")
    for level in LEVEL_CAPS:
        print(output_dir / f"flelex_{level}_frequency_selected.csv")
    print(workbook_path)
    print(f"Workbook preview directory: {preview_dir}")


if __name__ == "__main__":
    main()
