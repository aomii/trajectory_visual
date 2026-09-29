"""Delete only superseded local MySQL evaluation batches after verified full reruns.

Default is a dry run. Pass --execute only after inspecting output and log results.
"""
from pathlib import Path
import argparse
import csv
import re
import pymysql
import yaml

base = Path(__file__).resolve().parent
repo = base.parents[1]
cfg = yaml.safe_load((repo / "trajectory-visual-server/src/main/resources/application-local.yml").read_text(encoding="utf-8"))
ds = cfg["spring"]["datasource"]
match = re.match(r"jdbc:mysql://([^:/?]+)(?::(\d+))?/([^?]+)", ds["url"])
if not match:
    raise RuntimeError("Unrecognized local MySQL URL")
parser = argparse.ArgumentParser()
parser.add_argument("--execute", action="store_true")
args = parser.parse_args()

archive = base / "old_batches_archive" / "runs.csv"
with archive.open(encoding="utf-8-sig", newline="") as f:
    archived = {int(row["id"]) for row in csv.DictReader(f)}
if archived != set(range(1, 20)):
    raise RuntimeError(f"Old-batch archive incomplete: {sorted(archived)}")
if not (base / "supplement/table6_18_hull_summary.md").is_file():
    raise RuntimeError("Supplement full rerun not complete")
if "BUILD SUCCESS" not in (base / "main_maven.log").read_text(encoding="utf-8", errors="replace"):
    raise RuntimeError("Main full rerun did not finish successfully")
if "BUILD SUCCESS" not in (base / "supplement_maven.log").read_text(encoding="utf-8", errors="replace"):
    raise RuntimeError("Supplement full rerun did not finish successfully")

conn = pymysql.connect(host=match.group(1), port=int(match.group(2) or 3306),
                       user=ds["username"], password=ds["password"], database=match.group(3),
                       charset="utf8mb4", cursorclass=pymysql.cursors.DictCursor, autocommit=False)
old_ids = list(range(1, 21))
placeholders = ",".join(["%s"] * len(old_ids))
with conn.cursor() as cur:
    cur.execute("SELECT id, status, waybill_count FROM trajectory_eval_run WHERE id BETWEEN 21 AND 26 ORDER BY id")
    fresh = cur.fetchall()
    if [r["id"] for r in fresh] != list(range(21, 27)) or any(r["status"] != "SUCCESS" for r in fresh):
        raise RuntimeError(f"New six-batch suite incomplete: {fresh}")
    if any(r["waybill_count"] not in (0, 5224) for r in fresh):
        raise RuntimeError(f"Unexpected full-run waybill counts: {fresh}")
    cur.execute("SELECT algorithm_code, waybill_count FROM trajectory_eval_algorithm_result WHERE run_id=22 ORDER BY algorithm_code")
    methods = cur.fetchall()
    if [(r["algorithm_code"], r["waybill_count"]) for r in methods] != [("DP", 5224), ("PROPOSED", 5224), ("TD-TR", 5224)]:
        raise RuntimeError(f"Fresh baseline set incomplete: {methods}")
    cur.execute("SELECT COUNT(*) AS n FROM trajectory_eval_ablation_result WHERE run_id=25")
    if cur.fetchone()["n"] != 5:
        raise RuntimeError("Fresh ablation suite incomplete")
    cur.execute("SELECT param_type, param_value, algorithm_code FROM trajectory_eval_param_result WHERE run_id=26")
    params = cur.fetchall()
    if len(params) != 21 or not {(r["param_type"], r["param_value"]) for r in params}.issuperset({("block_window", "7200"), ("block_window", "14400")}):
        raise RuntimeError(f"Fresh parameter suite incomplete: {len(params)} rows")
    cur.execute("SELECT id FROM trajectory_eval_run WHERE id IN (" + placeholders + ") ORDER BY id", old_ids)
    found = [r["id"] for r in cur.fetchall()]
    if found != old_ids:
        raise RuntimeError(f"Unexpected old run ID set: {found}")
    cur.execute("SELECT table_name FROM information_schema.columns WHERE table_schema=DATABASE() AND column_name='run_id' AND table_name LIKE 'trajectory_eval_%' ORDER BY table_name")
    # information_schema 的字典键可能由驱动返回为 TABLE_NAME。
    tables = [next(iter(r.values())) for r in cur.fetchall()]
    for table in tables:
        if not re.fullmatch(r"trajectory_eval_[a-z_]+", table):
            raise RuntimeError(f"Unexpected dependent table: {table}")
        cur.execute(f"SELECT COUNT(*) AS n FROM {table} WHERE run_id IN ({placeholders})", old_ids)
        print(table, cur.fetchone()["n"], "rows to remove")
    print("trajectory_eval_run", len(old_ids), "runs to remove:", found)
    if args.execute:
        try:
            for table in tables:
                cur.execute(f"DELETE FROM {table} WHERE run_id IN ({placeholders})", old_ids)
            cur.execute("DELETE FROM trajectory_eval_run WHERE id IN (" + placeholders + ")", old_ids)
            if cur.rowcount != len(old_ids):
                raise RuntimeError("Run deletion count mismatch")
            conn.commit()
            print("Committed removal of superseded runs 1-20")
        except Exception:
            conn.rollback()
            raise
    else:
        conn.rollback()
        print("Dry run only; no rows removed")
conn.close()
