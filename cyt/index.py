"""
Индекс списков CleanYourTV (github.com/Gathin23/cleanyourtv, MIT) для ТВ Турбо.
Для каждой модели: папка, описание, бренд и шапка debloat.txt (там кодовые имена, прошивка) —
по ним ТВ Турбо на телевизоре находит свою модель.
Запуск: python3 index.py <папка с исходниками cleanyourtv>  > cyt-index.json
"""
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1]) / "examples"
out = []
for d in sorted(root.iterdir()):
    deb = d / "debloat.txt"
    if not deb.is_file():
        continue
    tv, brand = d.name, ""
    meta = d / "tv.json"
    if meta.is_file():
        try:
            j = json.loads(meta.read_text(encoding="utf-8"))
            tv, brand = j.get("tv", tv), j.get("brand", "")
        except Exception:
            pass
    head = []
    for line in deb.read_text(encoding="utf-8", errors="replace").splitlines()[:12]:
        if not line.startswith("#"):
            break
        head.append(line.lstrip("# ").strip())
    out.append({"folder": d.name, "tv": tv, "brand": brand, "head": " ".join(head)[:600]})
json.dump(out, sys.stdout, ensure_ascii=False, separators=(",", ":"))
print(len(out), "models", file=sys.stderr)
