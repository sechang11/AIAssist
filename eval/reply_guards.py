"""A mirror of ReplyGuards.kt, so the probe measures what the phone will show.

    python eval/reply_guards.py       # check this against the shared fixtures

Both sides are checked against eval/reply_guards.json and neither owns it, so
a rule changed in one language and not the other fails on whichever side was
left behind.
"""

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FIXTURES = ROOT / "eval/reply_guards.json"

# No "may": as a month it is rare in a text message and as a modal verb it is
# everywhere, and a false positive here silently drops a good reply.
TIMEY = re.compile(
    r"\b("
    r"\d{1,2}[:.]\d{2}\s*(?:am|pm)?|"
    r"\d{1,2}\s*(?:am|pm)|"
    r"\d{1,2}(?:st|nd|rd|th)|"
    r"monday|tuesday|wednesday|thursday|friday|saturday|sunday|"
    r"january|february|march|april|june|july|august|september|"
    r"october|november|december|"
    r"today|tonight|tomorrow|weekend|noon|midnight"
    r")\b",
    re.IGNORECASE,
)
GREETING = re.compile(r"^\s*(hi|hey|hello|dear)\b[\s,!.]*", re.IGNORECASE)
SIGNOFF = re.compile(
    r"[\s,]*\b(best regards|kind regards|warm regards|regards|sincerely|"
    r"best wishes|yours truly|cheers for now)\b[\s,.!]*$",
    re.IGNORECASE,
)
NOT_ALNUM = re.compile(r"[^a-z0-9 ]")
WHITESPACE = re.compile(r"\s+")

MIN_WORDS = 3
MAX_CHARS = 200
MAX_LABEL_WORDS = 3


def tidy_intent(raw):
    first_clause = raw.split(",")[0].strip().rstrip(".!:;")
    words = [w for w in WHITESPACE.split(first_clause) if w]
    if not words:
        return "reply"
    return " ".join(words[:MAX_LABEL_WORDS]).lower()


def tidy_text(raw):
    return SIGNOFF.sub("", GREETING.sub("", raw.strip())).strip()


def invented_times(incoming, text):
    said = {m.group(0).lower() for m in TIMEY.finditer(incoming)}
    out = []
    for match in TIMEY.finditer(text):
        value = match.group(0).lower()
        if value not in said and value not in out:
            out.append(value)
    return out


def is_stub(text):
    words = [w for w in WHITESPACE.split(text) if w]
    if len(words) >= MIN_WORDS:
        return False
    return "?" not in text


def too_long(text):
    return len(text) > MAX_CHARS


def fingerprint(text):
    return WHITESPACE.sub(" ", NOT_ALNUM.sub(" ", text.lower())).strip()


def keep(incoming, replies, already_offered=(), limit=3):
    seen = {fingerprint(t) for t in already_offered}
    kept = []
    for reply in replies:
        if len(kept) >= limit:
            break
        text = tidy_text(reply["text"])
        if not text or is_stub(text) or too_long(text):
            continue
        if invented_times(incoming, text):
            continue
        print_ = fingerprint(text)
        if not print_ or print_ in seen:
            continue
        seen.add(print_)
        kept.append({"intent": tidy_intent(reply["intent"]), "text": text})
    return kept


def main():
    cases = json.loads(FIXTURES.read_text(encoding="utf-8"))
    failures = []

    for raw, want in cases["tidyIntent"]:
        got = tidy_intent(raw)
        if got != want:
            failures.append(f"tidy_intent({raw!r}) -> {got!r}, wanted {want!r}")
    for raw, want in cases["tidyText"]:
        got = tidy_text(raw)
        if got != want:
            failures.append(f"tidy_text({raw!r}) -> {got!r}, wanted {want!r}")
    for incoming, text, want in cases["inventedTimes"]:
        got = invented_times(incoming, text)
        if got != want:
            failures.append(f"invented_times(..., {text!r}) -> {got}, wanted {want}")
    for text, want in cases["isStub"]:
        got = is_stub(text)
        if got != want:
            failures.append(f"is_stub({text!r}) -> {got}, wanted {want}")
    for text, want in cases["fingerprint"]:
        got = fingerprint(text)
        if got != want:
            failures.append(f"fingerprint({text!r}) -> {got!r}, wanted {want!r}")

    total = sum(len(cases[k]) for k in cases if not k.startswith("_"))
    for failure in failures:
        print("FAIL " + failure)
    print(f"{total - len(failures)}/{total} guard fixtures match")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
