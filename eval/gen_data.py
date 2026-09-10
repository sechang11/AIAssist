"""Build a training set for the beat rewriter, using a large open model as
teacher and the pipeline's own rules as the filter.

Three stages, all resumable, because any of them can be interrupted:

  1. Invent source messages. Not by asking for "500 text messages", which
     returns 500 variations of "sorry I'm late", but by crossing a taxonomy
     (intent, relationship, register, length, how many facts are buried in it)
     and asking for a handful per cell. You choose the distribution instead of
     inheriting one.

  2. Run each through split-then-paraphrase with the teacher, and keep only the
     beats where nothing load-bearing was lost. The teacher is wrong sometimes,
     and a bad pair poisons training worse than a missing one does.

  3. Pack what survived into chat triples the student can learn directly.

    python gen_data.py messages --model qwen2.5:7b-instruct --per-cell 4
    python gen_data.py beats    --model qwen2.5:7b-instruct
    python gen_data.py pack

Requests run concurrently: served one at a time this takes hours, and Ollama
batches happily as long as OLLAMA_NUM_PARALLEL is raised to match --workers.
Nothing here talks to a paid API.
"""

import argparse
import itertools
import json
import random
import re
import sys
import threading
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import pipeline  # noqa: E402

HERE = Path(__file__).parent
DATA = HERE / "data"
HOST = "http://localhost:11434"

INTENTS = [
    "confirming a plan", "declining an invitation", "putting off a decision",
    "chasing something politely", "apologising for being late",
    "thanking someone", "passing on bad news", "practical logistics",
    "setting a boundary", "saying yes but with a condition",
    "asking a favour", "a mild complaint", "congratulating someone",
    "checking in on someone", "cancelling at short notice",
]
RELATIONSHIPS = [
    "a close friend", "a partner", "a parent", "a sibling", "a work colleague",
    "your boss", "your landlord", "a neighbour", "a plumber or builder",
    "a group chat of friends",
]
REGISTERS = [
    "all lowercase with almost no punctuation, the way people actually text",
    "normal casual texting with ordinary punctuation",
    "careful and polite but still conversational",
    "properly formal, the way you would write to an institution",
]
LENGTHS = ["under eight words", "about fifteen words", "thirty to forty words"]
FACTS = [
    "no specific facts at all",
    "exactly one time or day in it",
    "a person's name in it",
    "a number, price or house number in it",
    "at least three separate facts: a name, a time and a number",
]

WRITE_LOCK = threading.Lock()


def post(payload, timeout=600):
    request = urllib.request.Request(
        HOST + "/api/chat",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read())["message"]["content"]


def parse_obj(raw):
    start, end = raw.find("{"), raw.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        parsed = json.loads(raw[start:end + 1])
    except json.JSONDecodeError:
        return None
    return parsed if isinstance(parsed, dict) else None


def normalise(text):
    return re.sub(r"[^a-z0-9 ]", "", text.lower()).strip()


# ---- stage 1: source messages ----------------------------------------------

MESSAGE_SYSTEM = """You invent realistic text messages for a writing tool's test data.

Write messages a real person would actually send from their phone. Real people
are terse, use contractions, skip apostrophes, and assume shared context. They
do not explain themselves or write topic sentences.

Never write a message that reads like an example. No placeholder names like John
Smith, no "Lorem", no meta-commentary. Vary sentence shapes: some messages are
one clause, some are three run together, some end in a question.

Return a JSON object with one key, "messages", holding an array of strings."""

MESSAGE_SCHEMA = {
    "type": "object",
    "required": ["messages"],
    "properties": {"messages": {"type": "array", "items": {"type": "string"}}},
}


def stage_messages(model, per_cell, limit, workers):
    DATA.mkdir(exist_ok=True)
    out_path = DATA / "sources.jsonl"

    seen = set()
    if out_path.exists():
        for line in out_path.open(encoding="utf-8"):
            seen.add(normalise(json.loads(line)["text"]))
    # The eval set must never leak into training or the score means nothing.
    for line in (HERE / "messages.jsonl").open(encoding="utf-8"):
        seen.add(normalise(json.loads(line)["text"]))

    cells = list(itertools.product(INTENTS, RELATIONSHIPS, REGISTERS, LENGTHS, FACTS))
    random.Random(7).shuffle(cells)
    cells = cells[:limit]
    print(f"{len(cells)} cells x {per_cell} messages, {workers} workers")

    def fetch(cell):
        intent, who, register, length, facts = cell
        ask = (
            f"Write {per_cell} different text messages, all of them {intent}, "
            f"sent to {who}. Style: {register}. Length: {length}. "
            f"Each message must have {facts}."
        )
        try:
            raw = post({
                "model": model, "stream": False,
                "options": {"temperature": 1.0, "top_p": 0.95, "num_predict": 500},
                "format": MESSAGE_SCHEMA,
                "messages": [
                    {"role": "system", "content": MESSAGE_SYSTEM},
                    {"role": "user", "content": ask},
                ],
            })
        except Exception:
            return cell, []
        return cell, (parse_obj(raw) or {}).get("messages", [])

    done = 0
    with out_path.open("a", encoding="utf-8") as out, ThreadPoolExecutor(workers) as pool:
        for future in as_completed(pool.submit(fetch, c) for c in cells):
            cell, messages = future.result()
            intent, who, register, length, facts = cell
            with WRITE_LOCK:
                for text in messages:
                    text = " ".join(str(text).split()).strip()
                    if not (8 <= len(text) <= 400):
                        continue
                    key = normalise(text)
                    if key in seen:
                        continue
                    seen.add(key)
                    out.write(json.dumps({
                        "text": text, "intent": intent, "who": who,
                        "register": register, "length": length, "facts": facts,
                    }) + "\n")
                out.flush()
                done += 1
                if done % 20 == 0:
                    print(f"  {done}/{len(cells)} cells, {len(seen)} distinct", flush=True)

    print(f"wrote {out_path}")


# ---- stage 2: teacher beats -------------------------------------------------

def stage_beats(model, limit, workers):
    sources = [json.loads(l) for l in (DATA / "sources.jsonl").open(encoding="utf-8")]
    out_path = DATA / "beats.jsonl"

    done_pairs = set()
    if out_path.exists():
        for line in out_path.open(encoding="utf-8"):
            row = json.loads(line)
            done_pairs.add((row["source"], row["fragment"]))

    jobs = []
    for source in sources[:limit]:
        whole = source["text"]
        for fragment in pipeline.split_beats(whole):
            if (whole, fragment) not in done_pairs:
                jobs.append((whole, fragment))
    print(f"{len(sources)} sources -> {len(jobs)} beats to fetch, {workers} workers")

    def fetch(job):
        whole, fragment = job
        try:
            raw = post({
                "model": model, "stream": False,
                "options": {"temperature": 0.6, "num_predict": 300},
                "format": pipeline.schema(),
                "messages": [
                    {"role": "system", "content": pipeline.system()},
                    {"role": "user", "content": pipeline.user(whole, fragment)},
                ],
            })
        except Exception:
            return job, None
        return job, parse_obj(raw)

    kept = dropped = seen = 0
    with out_path.open("a", encoding="utf-8") as out, ThreadPoolExecutor(workers) as pool:
        for future in as_completed(pool.submit(fetch, j) for j in jobs):
            (whole, fragment), answer = future.result()
            alternatives = (answer or {}).get("alternatives") or []

            # The filter. A teacher answer that lost a name or a time is exactly
            # the behaviour we are trying to train out, so it must not be taught.
            bad = (
                len(alternatives) != len(pipeline.TONES)
                or any(pipeline.lost_tokens(fragment, a) for a in alternatives)
                or len({a.strip().lower() for a in alternatives}) < len(alternatives)
            )
            with WRITE_LOCK:
                seen += 1
                if bad:
                    dropped += 1
                else:
                    out.write(json.dumps({
                        "source": whole, "fragment": fragment,
                        "label": (answer.get("label") or "part")[:40],
                        "optional": bool(answer.get("optional", False)),
                        "alternatives": alternatives,
                    }) + "\n")
                    kept += 1
                if seen % 100 == 0:
                    out.flush()
                    print(f"  {seen}/{len(jobs)}  kept {kept}  dropped {dropped}", flush=True)

    total = kept + dropped
    print(f"kept {kept}, dropped {dropped} ({100 * dropped / max(total, 1):.0f}% rejected)")


# ---- stage 3: pack for training --------------------------------------------

# Even told not to, the teacher sometimes writes an example rather than a
# message: "Dear [Partner's Name], regret to inform...". Training on those
# teaches the student to emit placeholders, so they go before packing.
_PLACEHOLDER = re.compile(
    r"\[[^\]]{0,40}\]|<[^>]{0,40}>|\b(your name|recipient|insert|placeholder|lorem)\b",
    re.IGNORECASE,
)


def stage_pack():
    beats = [json.loads(l) for l in (DATA / "beats.jsonl").open(encoding="utf-8")]

    before = len(beats)
    beats = [
        b for b in beats
        if not _PLACEHOLDER.search(b["source"])
        and not any(_PLACEHOLDER.search(a) for a in b["alternatives"])
    ]
    print(f"dropped {before - len(beats)} beats containing placeholders")

    random.Random(11).shuffle(beats)
    split = max(1, int(len(beats) * 0.05))
    holdout, train = beats[:split], beats[split:]

    for name, rows in (("train.jsonl", train), ("holdout.jsonl", holdout)):
        with (DATA / name).open("w", encoding="utf-8") as out:
            for beat in rows:
                out.write(json.dumps({"messages": [
                    {"role": "system", "content": pipeline.system()},
                    {"role": "user", "content": pipeline.user(beat["source"], beat["fragment"])},
                    {"role": "assistant", "content": json.dumps({
                        "label": beat["label"],
                        "optional": beat["optional"],
                        "alternatives": beat["alternatives"],
                    }, ensure_ascii=False)},
                ]}) + "\n")
        print(f"wrote {DATA / name}: {len(rows)} examples")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("stage", choices=["messages", "beats", "pack"])
    ap.add_argument("--model", default="qwen2.5:7b-instruct")
    ap.add_argument("--per-cell", type=int, default=4)
    ap.add_argument("--limit", type=int, default=200)
    ap.add_argument("--workers", type=int, default=6)
    args = ap.parse_args()

    if args.stage == "messages":
        stage_messages(args.model, args.per_cell, args.limit, args.workers)
    elif args.stage == "beats":
        stage_beats(args.model, args.limit, args.workers)
    else:
        stage_pack()


if __name__ == "__main__":
    main()
