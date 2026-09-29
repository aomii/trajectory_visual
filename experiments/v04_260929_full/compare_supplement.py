"""Report changed summary CSVs between the archived and fresh full reruns."""
from pathlib import Path
import csv

here = Path(__file__).resolve().parent
old = here.parent / "v04_260925_full" / "supplement"
new = here / "supplement"

for file in sorted(new.glob("*.csv")):
    if "per_waybill" in file.name or "per_query" in file.name or file.name == "manifest.csv":
        continue
    prior = old / file.name
    if not prior.exists():
        print("NEW", file.name)
        continue
    with file.open(encoding="utf-8-sig", newline="") as f:
        rows = list(csv.DictReader(f))
    with prior.open(encoding="utf-8-sig", newline="") as f:
        previous = list(csv.DictReader(f))
    if rows == previous:
        print("SAME", file.name, len(rows), "rows")
    else:
        print("CHANGED", file.name, len(previous), "→", len(rows), "rows")
        for i, (a, b) in enumerate(zip(previous, rows)):
            if a != b:
                print(" first row change", i, a, "→", b)
                break
