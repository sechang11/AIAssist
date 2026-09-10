"""Ask a model the question no script can answer: is it still the same message?

    python judge.py --judge qwen2.5:14b-instruct

The mechanical checks in grade.py see structure and hard facts. They cannot see
meaning, and that blind spot is not academic. Observed, all scored clean:

  "sorry i cant make saturday, ive got my sisters wedding"
    -> "i hope you have a fantastic time at your sister's wedding!"
       The wedding is why the writer cannot come. This hands it to the reader.

  "yes if we can finish by 4, otherwise ill have to skip it"
    -> "i'd love to join but unfortunately can't make it due to other commitments"
       A conditional yes became a flat no, with an invented reason.

Every hard token survived both, so nothing complained.

This reads each assembled column against the original and asks whether the
commitment, the facts and the roles are intact. Three caveats worth keeping in
view: the judge is itself a model and can be wrong; judging output from the
same family it came from flatters it; and a judge is a measuring instrument
rather than a guarantee, so nothing here belongs in the app.
"""

import argparse
import json
import sys
import threading
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
import pipeline  # noqa: E402

HOST = "http://localhost:11434"
LOCK = threading.Lock()

SYSTEM = """You check whether a rewritten text message still says what the original said.

You are not judging style, tone, warmth or wording. Those are meant to change.
You are judging whether it is still the same message.

Answer "changed" if any of these is true:
- What the writer committed to has moved. A no became a maybe, a maybe became a
  yes, a conditional yes lost its condition, a question became a statement.
- Who is doing what has swapped. The writer's own commitment or reason has been
  handed to the reader, or the reader's to the writer.
- A fact appeared that the writer never wrote: a reason, a time, a name, an
  excuse, a promise.
- Something the writer clearly meant to convey has gone entirely.

Answer "same" if the meaning, the commitment and the roles all survive, however
differently it is phrased, and however much shorter or more formal it is.

Reply with JSON: {"verdict": "same" or "changed", "why": "at most twelve words"}"""

SCHEMA = {
    "type": "object",
    "required": ["verdict", "why"],
    "properties": {
        "verdict": {"type": "string", "enum": ["same", "changed"]},
        "why": {"type": "string"},
    },
}


def ask(model, original, rewrite):
    body = {
        "model": model, "stream": False,
        "options": {"temperature": 0.0, "num_predict": 120},
        "format": SCHEMA,
        "messages": [
            {"role": "system", "content": SYSTEM},
            {"role": "user", "content":
                "Original:\n<<<\n" + original + "\n>>>\n\nRewrite:\n<<<\n" + rewrite + "\n>>>"},
        ],
    }
    request = urllib.request.Request(
        HOST + "/api/chat",
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=300) as response:
        raw = json.loads(response.read())["message"]["content"]
    start, end = raw.find("{"), raw.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        return json.loads(raw[start:end + 1])
    except json.JSONDecodeError:
        return None


def tidy(text):
    import re
    text = re.sub(r"\s+", " ", text).strip()
    text = re.sub(r"\s+([,.!?;:])", r"\1", text)
    return re.sub(r"^[,.;:!?]+\s*", "", text).strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--judge", default="qwen2.5:14b-instruct")
    ap.add_argument("--workers", type=int, default=6)
    ap.add_argument("runs", nargs="*", help="files in out/ (default: all)")
    args = ap.parse_args()

    sources = {}
    for line in (HERE / "messages.jsonl").read_text(encoding="utf-8").splitlines():
        if line.strip():
            row = json.loads(line)
            sources[row["id"]] = row

    paths = ([HERE / "out" / n for n in args.runs] if args.runs
             else sorted((HERE / "out").glob("*.jsonl")))
    paths = [p for p in paths if p.exists()]

    print(f"judge: {args.judge}\n")
    summary = []

    for path in paths:
        jobs = []
        for line in path.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            rec = json.loads(line)
            grid, mid = rec.get("grid"), rec["id"]
            if not grid or mid not in sources:
                continue
            for column in range(len(grid["tones"])):
                assembled = tidy(" ".join(s["alternatives"][column] for s in grid["slots"]))
                jobs.append((mid, grid["tones"][column], assembled))

        same = changed = failed = 0
        offences = []
        with ThreadPoolExecutor(args.workers) as pool:
            futures = {
                pool.submit(ask, args.judge, sources[m]["text"], a): (m, t, a)
                for m, t, a in jobs
            }
            for future in as_completed(futures):
                mid, tone, assembled = futures[future]
                try:
                    verdict = future.result()
                except Exception:
                    verdict = None
                with LOCK:
                    if not verdict:
                        failed += 1
                    elif verdict.get("verdict") == "same":
                        same += 1
                    else:
                        changed += 1
                        if len(offences) < 6:
                            offences.append((mid, tone, verdict.get("why", ""), assembled))

        total = same + changed
        pct = 100 * same / max(total, 1)
        model = path.stem
        summary.append((model, pct, changed, total, failed))
        print(f"{model:<20} {pct:5.1f}% judged unchanged   ({changed} of {total} flagged"
              + (f", {failed} unanswered" if failed else "") + ")")
        for mid, tone, why, assembled in sorted(offences):
            print(f"     {mid} [{tone}] {why}")
            print(f"       was: {sources[mid]['text'][:88]}")
            print(f"       got: {assembled[:88]}")
        print()

    print("\n" + "=" * 66)
    for model, pct, changed, total, _ in sorted(summary, key=lambda r: -r[1]):
        print(f"  {model:<22} {pct:5.1f}% faithful")
    print("\nA judge is an instrument, not a guarantee. It is a model, it can be")
    print("wrong, and judging one Qwen with another flatters the family.")


if __name__ == "__main__":
    main()
