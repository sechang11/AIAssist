"""Exercise the reply suggester against a live Ollama, using the app's own prompt.

    python eval/replies.py --host http://192.168.0.45:11434 --model qwen2.5:7b-instruct

Replies are not rewrites, so nothing in grade.py applies: there is no original
to be faithful to. The failure mode is the opposite one. A reply can invent a
reason you never had, commit you to a time you never agreed, or offer three
phrasings of one answer dressed up as a choice. Those are what this checks.

The prompt is read out of ReplySuggester.kt rather than copied, so this cannot
drift from what the phone sends.
"""

import argparse
import json
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

import reply_guards

ROOT = Path(__file__).resolve().parent.parent
KOTLIN = ROOT / "app/src/main/java/com/aitextassistant/generate/ReplySuggester.kt"

# Words that only belong in a reply if the incoming message put them there.
# A model that reaches for these is inventing a life for the sender.
INVENTED = re.compile(
    r"\b(work|working|busy|swamped|meeting|traffic|sick|ill|tired|family|kids?|"
    r"appointment|shift|train|bus|car|dentist|doctor|holiday|vacation|"
    r"made plans|other plans|already have plans|my schedule)\b",
    re.IGNORECASE,
)
# Deciding the reader is the one who erred, from a message that did not say so.
BLAME = re.compile(
    r"\b(i messed up|i was wrong|my fault|i'?m sorry it|i apologi[sz]e|"
    r"i shouldn'?t have|i didn'?t mean to)\b",
    re.IGNORECASE,
)
TIME = re.compile(r"\b(\d{1,2}([:.]\d{2})?\s*(am|pm)?|noon|midnight|tomorrow|tonight|"
                  r"monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b", re.IGNORECASE)
GREETING = re.compile(r"^\s*(hi|hey|hello|dear)\b", re.IGNORECASE)
SIGNOFF = re.compile(r"\b(best regards|kind regards|sincerely|best wishes)\b", re.IGNORECASE)

# Stance is the point. Two replies a person could both send are one choice.
YES = re.compile(r"\b(yes|yeah|yep|sure|sounds good|works for me|im in|i'm in|ok|okay|"
                 r"definitely|count me in|see you)\b", re.IGNORECASE)
NO = re.compile(r"\b(no|nah|cant|can't|cannot|sorry|afraid not|wont|won't|"
                r"not able|pass|another time)\b", re.IGNORECASE)
ASK = re.compile(r"\?")
DEFER = re.compile(r"\b(let me|ill check|i'll check|get back to you|come back to you|"
                   r"not sure yet|check and)\b", re.IGNORECASE)


def app_prompt(count):
    """The system prompt exactly as the app builds it."""
    source = KOTLIN.read_text(encoding="utf-8")
    block = source.split('fun system(count: Int): String = """', 1)[1]
    block = block.split('""".trimIndent()', 1)[0]
    lines = block.split("\n")
    # trimIndent: drop blank first and last lines, strip the common indent.
    while lines and not lines[0].strip():
        lines.pop(0)
    while lines and not lines[-1].strip():
        lines.pop()
    indent = min((len(ln) - len(ln.lstrip()) for ln in lines if ln.strip()), default=0)
    text = "\n".join(ln[indent:] if ln.strip() else "" for ln in lines)
    return text.replace("$count", str(count))


def app_user(incoming, avoid):
    base = "Message they received:\n<<<\n" + incoming + "\n>>>"
    if not avoid:
        return base
    return base + "\n\nAlready offered, so do not repeat or lightly reword any of these:\n" + \
        "\n".join("- " + a for a in avoid)


def schema(count):
    # Key order matters and python dicts keep it; see OllamaClient.obj.
    return {
        "type": "object",
        "required": ["reading", "replies"],
        "properties": {
            "reading": {"type": "string"},
            "replies": {
                "type": "array", "minItems": count, "maxItems": count,
                "items": {
                    "type": "object",
                    "required": ["intent", "text"],
                    "properties": {"intent": {"type": "string"}, "text": {"type": "string"}},
                },
            },
        },
    }


def ask(host, model, incoming, avoid, count, temperature=0.9, spare=2):
    """One call, guarded exactly as the app guards it.

    [spare] is the headroom: how many more than will be shown to ask for, so
    the guards have something to drop. It costs quality, because a model asked
    for five replies to a message with three sensible ones pads the list, and
    the padding is not sorted to the end.
    """
    asked = count + spare
    body = {
        "model": model,
        "stream": False,
        "options": {"temperature": temperature, "num_predict": 600},
        "format": schema(asked),
        "messages": [
            {"role": "system", "content": app_prompt(asked)},
            {"role": "user", "content": app_user(incoming, avoid)},
        ],
    }
    request = urllib.request.Request(
        host.rstrip("/") + "/api/chat",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=180) as response:
            content = json.load(response)["message"]["content"]
    except (urllib.error.URLError, OSError) as error:
        return None, f"unreachable: {error}"
    try:
        start, end = content.index("{"), content.rindex("}")
        parsed = json.loads(content[start:end + 1])
    except (ValueError, json.JSONDecodeError):
        return None, "unparseable: " + content[:120]
    replies = [
        {"intent": (r.get("intent") or "reply").strip(), "text": (r.get("text") or "").strip()}
        for r in parsed.get("replies", [])
        if (r.get("text") or "").strip()
    ]
    # Count guard drops separately from the ones simply past the limit.
    survivors = reply_guards.keep(incoming, replies, avoid, limit=len(replies))
    kept = survivors[:count]
    if kept:
        kept[0]["reading"] = (parsed.get("reading") or "").strip()
        kept[0]["dropped"] = len(replies) - len(survivors)
        kept[0]["short"] = max(0, count - len(survivors))
    return kept, None


def stance(text):
    """A coarse bucket. Wrong sometimes; useful in aggregate."""
    if ASK.search(text) and not YES.search(text):
        return "ask"
    if DEFER.search(text):
        return "defer"
    if NO.search(text):
        return "no"
    if YES.search(text):
        return "yes"
    return "other"


def inspect(incoming, replies):
    """Everything wrong with one set of three, as a list of complaints.

    Some of these duplicate rules ReplyGuards already enforces, and those should
    now be silent. One firing means the guard has a hole, which is worth more
    than the redundancy costs. The rest are judgements code cannot make well,
    and they are a tripwire rather than a target: read the replies.
    """
    faults = []
    said = INVENTED.findall(incoming)
    said = {w.lower() for w in said}
    for reply in replies:
        text = reply["text"]
        new = {w.lower() for w in INVENTED.findall(text)} - said
        if new:
            faults.append(f"invented {sorted(new)}: {text!r}")
        if BLAME.search(text) and not BLAME.search(incoming):
            faults.append(f"took the blame unprompted: {text!r}")
        if GREETING.search(text):
            faults.append(f"greeting: {text!r}")
        if len(reply["intent"].split()) > 3 or "," in reply["intent"]:
            faults.append(f"label is a sentence: {reply['intent']!r}")
        if SIGNOFF.search(text):
            faults.append(f"sign-off: {text!r}")
        if len(text) > 160:
            faults.append(f"{len(text)} chars, too long to text: {text!r}")
        if reply_guards.is_stub(text):
            faults.append(f"a stub, not a reply: {text!r}")
    buckets = [stance(r["text"]) for r in replies]
    distinct = len(set(buckets))
    if distinct < 2 and replies:
        faults.append(f"all {len(replies)} take the same line ({buckets[0]})")
    return faults, buckets


INCOMING = [
    "hey are you free saturday? few of us going to the pub quiz",
    "can you cover my shift on thursday? ill owe you one",
    "the landlord says he's putting the rent up by 200 a month from january",
    "sorry i think i left my charger at yours, any chance you can bring it tomorrow",
    "we need to talk about what happened yesterday",
    "hi, just checking whether you still want the sofa? someone else has asked",
    "running late, maybe 20 mins, so sorry",
    "did you get a chance to look at that document i sent over last week?",
    "my mum died on sunday. funeral is the 14th if you can make it",
    "you free for a call at some point today",
]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="http://192.168.0.45:11434")
    parser.add_argument("--model", default="qwen2.5:7b-instruct")
    parser.add_argument("--count", type=int, default=3)
    parser.add_argument("--spare", type=int, default=2,
                        help="how many extra to ask for, as headroom for the guards")
    parser.add_argument("--more", action="store_true",
                        help="also ask for three more, checking they are new")
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args()

    total, clean, short_sets, all_faults = 0, 0, 0, []
    for incoming in INCOMING:
        replies, error = ask(args.host, args.model, incoming, [], args.count, spare=args.spare)
        if error:
            print(f"FAIL {incoming[:40]!r}: {error}")
            all_faults.append(error)
            continue
        if len(replies) != args.count:
            all_faults.append(f"got {len(replies)} replies, wanted {args.count}")
        faults, buckets = inspect(incoming, replies)
        total += 1
        # The one that matters most: a set with a gap in it. The guards drop
        # bad replies whether or not there is headroom to replace them, so
        # without enough spare the reader simply gets fewer cards.
        if len(replies) < args.count:
            short_sets += 1
        if not faults:
            clean += 1
        if not args.quiet:
            print(f"\n> {incoming}")
            if replies and replies[0].get("reading"):
                dropped = replies[0].get("dropped") or 0
                short = replies[0].get("short") or 0
                cut = f", {dropped} dropped by the guards" if dropped else ""
                cut += f", {short} SHORT" if short else ""
                print(f"    ({replies[0]['reading']}{cut})")
            for reply, bucket in zip(replies, buckets):
                print(f"    [{bucket:5}] {reply['intent']:<18} {reply['text']}")
            for fault in faults:
                print(f"    !! {fault}")
        all_faults += faults

        if args.more:
            seen = [r["text"] for r in replies]
            again, error = ask(args.host, args.model, incoming, seen, args.count, spare=args.spare)
            if error or not again:
                all_faults.append("second round failed: " + str(error))
                continue
            repeats = [r["text"] for r in again if r["text"] in seen]
            if repeats:
                all_faults.append(f"three more repeated {len(repeats)}: {repeats}")
            if not args.quiet:
                print("    -- three more --")
                for reply in again:
                    mark = "REPEAT " if reply["text"] in seen else ""
                    print(f"    [{mark}] {reply['intent']:<18} {reply['text']}")

    print(f"\n{args.model} spare={args.spare}: {clean}/{total} clean, "
          f"{short_sets}/{total} short of {args.count}, "
          f"{len(all_faults)} complaints")
    return 0 if total else 1


if __name__ == "__main__":
    sys.exit(main())
