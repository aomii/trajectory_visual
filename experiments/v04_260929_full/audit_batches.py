"""List local evaluation batches and dependent row counts without personal identifiers."""
from pathlib import Path
import re
import pymysql
import yaml

repo = Path(__file__).resolve().parents[2]
cfg = yaml.safe_load((repo / "trajectory-visual-server/src/main/resources/application-local.yml").read_text(encoding="utf-8"))
ds = cfg["spring"]["datasource"]
match = re.match(r"jdbc:mysql://([^:/?]+)(?::(\d+))?/([^?]+)", ds["url"])
if not match:
    raise RuntimeError("Unrecognized local MySQL URL")
conn = pymysql.connect(host=match.group(1), port=int(match.group(2) or 3306),
                       user=ds["username"], password=ds["password"], database=match.group(3),
                       charset="utf8mb4", cursorclass=pymysql.cursors.DictCursor)
tables = ("trajectory_eval_algorithm_result", "trajectory_eval_waybill_result",
          "trajectory_eval_ablation_result", "trajectory_eval_param_result",
          "trajectory_eval_error_record")
with conn.cursor() as cur:
    cur.execute("SELECT id, run_no, run_name, status, waybill_count, started_at FROM trajectory_eval_run ORDER BY id")
    runs = cur.fetchall()
    for run in runs:
        counts = []
        for table in tables:
            cur.execute(f"SELECT COUNT(*) AS n FROM {table} WHERE run_id=%s", (run["id"],))
            counts.append(cur.fetchone()["n"])
        print(run["id"], run["run_no"], run["status"], run["waybill_count"], counts, run["run_name"])
conn.close()
