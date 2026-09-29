"""Compact, identifier-free summary of a completed 2026-09-29 MySQL export."""
from pathlib import Path
import csv

base = Path(__file__).resolve().parent / "db_export"

def rows(name):
    with (base / name).open(encoding="utf-8-sig", newline="") as f:
        return list(csv.DictReader(f))

runs = rows("runs.csv")
for run in runs:
    print("RUN", run["id"], run["run_no"], run["status"], run["waybill_count"])

for row in rows("trajectory_eval_algorithm_result.csv"):
    print("ALG", row["run_id"], row["algorithm_code"],
          "points", row["kept_point_count"], "payload", row["storage_bytes"],
          "CRlossy", row["cr_lossy_avg"], "CRe2e", row["cr_e2e_global"],
          "PED", row["ped_avg"], "SED", row["sed_avg"],
          "SR", row["sr_avg"], "unit", row["semantic_unit_complete_rate"],
          "span", row["stay_duration_preserve_rate"])

for row in rows("trajectory_eval_ablation_result.csv"):
    print("ABL", row["run_id"], row["ablation_code"],
          "CR", row["cr_total_avg"], "SR", row["sr_avg"],
          "unit", row["semantic_unit_complete_rate"],
          "span", row["stay_duration_preserve_rate"],
          "payload", row["storage_bytes"])
for row in rows("trajectory_eval_param_result.csv"):
    print("PARAM", row["run_id"], row["param_type"], row["param_value"],
          row["algorithm_code"], row["cr_lossy_avg"], row["cr_total_avg"],
          row["sr_avg"], row["partial_read_ratio_avg"])
