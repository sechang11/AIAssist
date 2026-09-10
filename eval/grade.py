"""Score one or more runs and print a scorecard.

    python grade.py                       # everything in out/
    python grade.py qwen2.5-7b-instruct.jsonl claude-opus-5.jsonl

Most of this is mechanical on purpose. The failure that actually hurts a user is
the model quietly changing what they committed to, and that is checkable without
a judge: the facts either survive into every column or they do not. Tone and
naturalness are not checkable that way, so those go in a section for you to read.
"""

import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).parent

WEEKDAYS = {
    "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
    "today", "tomorrow", "tonight", "weekend",
}
NUMBERS = re.compile(r"\d+(?::\d+)?")
WORDS = re.compile(r"[a-z0-9']+")


def is_hard(fact):
    """The same rule the app's guarantee uses. A hard fact is one the pipeline
    promises to keep, so anything below 100% here is a bug rather than a
    quality wobble. Soft facts are ordinary nouns nothing protects."""
    return (
        any(c.isdigit() for c in fact)
        or fact.lower() in WEEKDAYS
        or (fact[:1].isupper() and len(fact) > 2)
    )


def load_messages():
    with open(HERE / "messages.jsonl", encoding="utf-8") as f:
        return {json.loads(line)["id"]: json.loads(line) for line in f if line.strip()}


def tidy(text: str) -> str:
    text = re.sub(r"\s+", " ", text).strip()
    text = re.sub(r"\s+([,.!?;:])", r"\1", text)
    return re.sub(r"^[,.;:!?]+\s*", "", text).strip()


def shape_ok(grid):
    """The same rules RemixDraft.validated() applies in the app."""
    if not isinstance(grid, dict):
        return False
    tones = grid.get("tones")
    slots = grid.get("slots")
    if not isinstance(tones, list) or len(tones) < 2:
        return False
    # One slot is legitimate under split mode: some messages really are a single
    # beat, and the splitter decides that in code rather than the model choosing
    # it to make its own life easier.
    if not isinstance(slots, list) or len(slots) < 1:
        return False
    for slot in slots:
        alts = slot.get("alternatives") if isinstance(slot, dict) else None
        if not isinstance(alts, list) or len(alts) != len(tones):
            return False
        if not all(isinstance(a, str) and a.strip() for a in alts):
            return False
    return True


def assemble(grid, column):
    return tidy(" ".join(slot["alternatives"][column] for slot in grid["slots"]))


def weekdays_in(text):
    return {w for w in WORDS.findall(text.lower()) if w in WEEKDAYS}


def numbers_in(text):
    return set(NUMBERS.findall(text))


def jaccard(a, b):
    sa, sb = set(WORDS.findall(a.lower())), set(WORDS.findall(b.lower()))
    if not sa or not sb:
        return 0.0
    return len(sa & sb) / len(sa | sb)


def grade_one(record, message):
    """Returns a dict of findings for a single message."""
    out = {"id": record["id"], "seconds": record.get("seconds", 0.0), "problems": []}
    grid = record.get("grid")

    if not grid or not shape_ok(grid):
        out["valid"] = False
        out["problems"].append(record.get("error") or "grid failed the shape check")
        return out

    out["valid"] = True
    source = message["text"]
    columns = [assemble(grid, c) for c in range(len(grid["tones"]))]
    out["columns"] = columns

    # 1. Do the writer's facts survive into every column? Hard facts are the
    # ones the guarantee covers, so they are a correctness check; soft facts are
    # ordinary nouns, which is a quality signal rather than a promise broken.
    hard_missing, soft_missing = [], []
    for column, text in zip(grid["tones"], columns):
        for fact in message["facts"]:
            if fact.lower() in text.lower():
                continue
            (hard_missing if is_hard(fact) else soft_missing).append(
                f"{column} lost '{fact}'")
    out["hard_kept"] = not hard_missing
    out["soft_kept"] = not soft_missing
    out["facts_kept"] = not (hard_missing or soft_missing)
    out["problems"] += hard_missing + soft_missing

    # 2. Did it invent a day, a time or a number the writer never wrote?
    invented = []
    src_days, src_nums = weekdays_in(source), numbers_in(source)
    for column, text in zip(grid["tones"], columns):
        for day in weekdays_in(text) - src_days:
            invented.append(f"{column} invented '{day}'")
        for num in numbers_in(text) - src_nums:
            invented.append(f"{column} invented '{num}'")
    out["nothing_invented"] = not invented
    out["problems"] += invented

    # 3. Are the alternatives inside a slot actually different from each other?
    duplicates = [
        slot.get("label") or slot.get("id") or "?"
        for slot in grid["slots"]
        if len({a.strip().lower() for a in slot["alternatives"]}) < len(slot["alternatives"])
    ]
    out["alts_distinct"] = not duplicates
    if duplicates:
        out["problems"].append("identical alternatives in: " + ", ".join(duplicates))

    # 4. Do neighbouring beats say the same thing? If they do, mixing repeats.
    worst, where = 0.0, ""
    for column in range(len(grid["tones"])):
        texts = [s["alternatives"][column] for s in grid["slots"]]
        for i in range(len(texts) - 1):
            overlap = jaccard(texts[i], texts[i + 1])
            if overlap > worst:
                worst, where = overlap, f"{grid['tones'][column]}, beats {i + 1} and {i + 2}"
    out["overlap"] = worst
    out["slots_independent"] = worst < 0.4
    if worst >= 0.4:
        out["problems"].append(f"neighbouring beats repeat each other ({where}, {worst:.2f})")

    # 5. A text message should not come back as an essay.
    ratios = [len(c) / max(len(source), 1) for c in columns]
    out["length_ratio"] = sum(ratios) / len(ratios)
    if out["length_ratio"] > 1.8:
        out["problems"].append(f"grew to {out['length_ratio']:.1f}x the original")

    out["clean"] = not out["problems"]
    return out


def score(path, messages):
    records = [json.loads(line) for line in open(path, encoding="utf-8") if line.strip()]
    graded = [grade_one(r, messages[r["id"]]) for r in records if r["id"] in messages]
    model = records[0]["model"] if records else path.stem
    total = len(graded) or 1

    def pct(key):
        return 100.0 * sum(1 for g in graded if g.get(key)) / total

    times = sorted(g["seconds"] for g in graded)
    return {
        "model": model,
        "path": path,
        "graded": graded,
        "n": len(graded),
        "valid": pct("valid"),
        "hard_kept": pct("hard_kept"),
        "soft_kept": pct("soft_kept"),
        "nothing_invented": pct("nothing_invented"),
        "alts_distinct": pct("alts_distinct"),
        "slots_independent": pct("slots_independent"),
        "clean": pct("clean"),
        "median_s": times[len(times) // 2] if times else 0.0,
        "slowest_s": times[-1] if times else 0.0,
    }


def main():
    messages = load_messages()
    names = sys.argv[1:]
    paths = [HERE / "out" / n for n in names] if names else sorted((HERE / "out").glob("*.jsonl"))
    paths = [p for p in paths if p.exists()]

    if not paths:
        print("Nothing in out/ yet. Run run.py first.")
        return

    results = [score(p, messages) for p in paths]

    header = (f"{'model':<36}{'valid':>7}{'hard':>7}{'soft':>7}{'no-add':>8}"
              f"{'distinct':>10}{'indep':>7}{'CLEAN':>8}{'med s':>7}{'max s':>7}")
    print("\n" + header)
    print("-" * len(header))
    for r in results:
        print(f"{r['model'][:35]:<36}{r['valid']:>6.0f}%{r['hard_kept']:>6.0f}%"
              f"{r['soft_kept']:>6.0f}%{r['nothing_invented']:>7.0f}%"
              f"{r['alts_distinct']:>9.0f}%{r['slots_independent']:>6.0f}%"
              f"{r['clean']:>7.0f}%{r['median_s']:>7.1f}{r['slowest_s']:>7.1f}")
    print("\nCLEAN is the headline: no problem of any kind on that message.")
    print("valid = right shape | hard = days, times, numbers and names survived")
    print("soft = ordinary nouns survived, a quality signal the guarantee does not cover")
    print("no-add = invented no day, time or number | indep = neighbouring beats do not repeat\n")

    for r in results:
        broken = [g for g in r["graded"] if g["problems"]]
        if not broken:
            continue
        print(f"\n=== {r['model']} | {len(broken)} of {r['n']} messages have problems ===")
        for g in broken:
            print(f"\n  {g['id']}")
            for problem in g["problems"]:
                print(f"    - {problem}")

    print("\n\n=== Read these yourself ===")
    print("No script can tell you whether a no is still a no. These are the messages")
    print("where the writer took a position, so check the columns did not move it.\n")
    for r in results:
        stance_ids = [m for m, v in messages.items() if v["stance"] in ("no", "maybe", "yes-if")]
        for g in r["graded"]:
            if g["id"] not in stance_ids or not g.get("columns"):
                continue
            source = messages[g["id"]]
            print(f"  [{r['model']}] {g['id']}  (should read as: {source['stance']})")
            print(f"      was:  {source['text']}")
            for tone, column in zip(("1", "2", "3"), g["columns"]):
                print(f"      {tone}:    {column}")
            print()


if __name__ == "__main__":
    main()
