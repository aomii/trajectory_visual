"""Check manuscript references, figures, tables, privacy, and obsolete baselines."""
from pathlib import Path
import re

paper = Path(__file__).resolve().parents[3] / "claude_15_论文完整初稿_04_260929.md"
text = paper.read_text(encoding="utf-8")
body, sep, refs = text.partition("# 参考文献")
assert sep and "## 摘要" in body and "## Abstract" in body
figures = re.findall(r"!\[[^]]*\]\((figures/[^)]+)\)", body)
assert len(figures) == 4 and all((paper.parent / p).is_file() for p in figures)
tables = re.findall(r"^\*\*表 (6-\d+[a-z]?) ", body, re.M)
assert len(tables) == len(set(tables)) == 26, tables
first = list(dict.fromkeys(int(x) for x in re.findall(r"\[(\d+)\]", body)))
assert first == list(range(1, 36)), first
entries = [int(x) for x in re.findall(r"^\[(\d+)\] ", refs, re.M)]
assert entries == list(range(1, 36)), entries
assert "6.7.4" not in body
assert not re.search(r"合成标签|模拟标注|DPS代码|Trajic代码|五种方法|四条基线", body)
assert "TRAJECTORY-EVAL-20260929-113310" in body
assert "TRAJECTORY-EVAL-20260929-115937" in body
assert "2026-09-26 补充批次" not in body
assert "TRAJECTORY-EVAL-20260926-092558" not in body
assert "1,938.68" in body and "596.90" in body and "125.61" in body
assert "0.0072" in body and "0.0124" in body and "0.0212" in body
assert "| 2 h | 12.45" in body and "| 4 h | 11.84" in body
assert not re.search(r"[京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼][A-Z][A-Z0-9]{5}", body)
print(f"PASS: {len(figures)} figures, {len(tables)} tables, {len(entries)} ordered references")
