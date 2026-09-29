"""Read-only Crossref metadata audit of numbered manuscript references."""
from concurrent.futures import ThreadPoolExecutor
import json
import re
from urllib.request import Request, urlopen
from urllib.parse import quote
from pathlib import Path

paper = Path(__file__).resolve().parents[3] / "claude_15_论文完整初稿_04_260929.md"
refs = []
for line in paper.read_text(encoding="utf-8").splitlines():
    m = re.match(r"\[(\d+)\] (.+?) DOI: ([^ ]+)\.?$", line)
    if m:
        refs.append((int(m.group(1)), m.group(2), m.group(3).rstrip(".")))

def fetch(item):
    num, printed, doi = item
    try:
        url = "https://api.crossref.org/works/" + quote(doi, safe="/")
        request = Request(url, headers={"User-Agent": "PaperAudit/1.0 (academic metadata check)"})
        with urlopen(request, timeout=12) as response:
            record = json.load(response)["message"]
        title = record.get("title", [""])[0]
        authors = ", ".join(x.get("family", "") for x in record.get("author", [])[:3])
        year = record.get("published", {}).get("date-parts", [[None]])[0][0]
        return num, doi, year, authors, title, printed
    except Exception as exc:
        return num, doi, "ERROR", "", type(exc).__name__, printed

with ThreadPoolExecutor(max_workers=4) as pool:
    for num, doi, year, authors, title, printed in sorted(pool.map(fetch, refs)):
        print(f"[{num}] {doi} | {year} | {authors} | {title}")
