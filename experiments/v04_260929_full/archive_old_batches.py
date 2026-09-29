"""Archive superseded local MySQL experiment batches without direct waybill identifiers."""
import csv
import re
from pathlib import Path

import pymysql
import yaml

root = Path(__file__).resolve().parents[2]
out = Path(__file__).resolve().parent / "old_batches_archive"
out.mkdir(exist_ok=True)
cfg = yaml.safe_load((root / "trajectory-visual-server/src/main/resources/application-local.yml").read_text(encoding="utf-8"))
ds = cfg["spring"]["datasource"]
url = ds["url"]
match = re.match(r"jdbc:mysql://([^:/?]+)(?::(\d+))?/([^?]+)", url)
if not match:
    raise RuntimeError("Unexpected local datasource URL format")
conn = pymysql.connect(host=match.group(1), port=int(match.group(2) or 3306),
                       user=ds["username"], password=ds["password"], database=match.group(3),
                       charset="utf8mb4", cursorclass=pymysql.cursors.DictCursor)

def write_csv(name, rows):
    if not rows:
        return
    with (out / name).open("w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)

with conn.cursor() as cur:
    cur.execute("SELECT * FROM trajectory_eval_run WHERE id <= %s ORDER BY id", (19,))
    runs = cur.fetchall()
    ids = [r["id"] for r in runs]
    write_csv("runs.csv", runs)
    print("Archived old batches:", [(r["run_no"], r["status"]) for r in runs])
    if ids:
        placeholders = ",".join(["%s"] * len(ids))
        for table in ("trajectory_eval_algorithm_result", "trajectory_eval_ablation_result",
                      "trajectory_eval_param_result"):
            cur.execute(f"SELECT * FROM {table} WHERE run_id IN ({placeholders}) ORDER BY run_id,id", ids)
            write_csv(table + ".csv", cur.fetchall())
        cur.execute(f"SELECT * FROM trajectory_eval_waybill_result WHERE run_id IN ({placeholders}) ORDER BY run_id,algorithm_code,waybill_id", ids)
        details = cur.fetchall()
        anon = {}
        for row in details:
            original = row.pop("waybill_id")
            row.pop("waybill_no")
            row.pop("id")
            row["case_index"] = anon.setdefault(original, len(anon) + 1)
        write_csv("waybill_metrics_anonymous.csv", details)
        print("Anonymous per-waybill metric rows:", len(details), "cases:", len(anon))
conn.close()
