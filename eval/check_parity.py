"""Verify the Kotlin app and this directory agree, across the language boundary.

    python eval/check_parity.py

Two things have to match or the numbers here stop describing the app:

  - The prompt. PromptParityTest pins it inside Kotlin, but only Kotlin, so
    nothing there notices if pipeline.py changes. This closes that loop.
  - The beat splitter, whose output decides how many calls the app makes and
    what each one sees.

Run it after touching either side. Exits non-zero on a mismatch, so it can go
in a hook or CI.
"""

import difflib
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "eval"))

import pipeline  # noqa: E402

# The golden block holds no escaped quotes, so plain quoted strings are enough.
# If a future prompt needs one, this has to learn about escapes.
QUOTED = re.compile(r'^\s*"([^"]*)",\s*$', re.MULTILINE)


def check_prompt():
    path = ROOT / "app/src/test/java/com/aitextassistant/generate/PromptParityTest.kt"
    source = path.read_text(encoding="utf-8")
    block = source.split("listOf(", 1)[1].split(").joinToString", 1)[0]
    golden = "\n".join(QUOTED.findall(block))

    expected = pipeline.system()
    if golden == expected:
        print(f"ok    prompt: {len(expected.splitlines())} lines identical")
        return True

    print("FAIL  prompt differs between pipeline.py and PromptParityTest.kt")
    print("\n".join(difflib.unified_diff(
        expected.split("\n"), golden.split("\n"),
        "eval/pipeline.py", "PromptParityTest.kt", lineterm="", n=1,
    )))
    return False


def check_splitter():
    """The Kotlin splitter is checked by its own unit tests; this checks that
    those tests still describe what pipeline.py does, by running the Python one
    over the cases the Kotlin tests assert."""
    cases = [
        ("hey so sorry i didnt get back to you sooner, this week has been mental. "
         "friday still works for me if thats ok? really looking forward to it", 4),
        ("ok cool see you then", 1),
        ("not sure yet, depends on my shift", 1),
        ("the survey came back with damp in the back bedroom. oh well.", 1),
        ("the first thing happened. the second thing happened. the third thing happened.", 3),
    ]
    ok = True
    for text, expected in cases:
        got = len(pipeline.split_beats(text))
        if got != expected:
            print(f"FAIL  splitter: {text[:44]!r} gave {got} beats, Kotlin tests expect {expected}")
            ok = False
    if ok:
        print(f"ok    splitter: {len(cases)} shared cases agree")
    return ok


def check_messages():
    """Every eval message needs the fields grade.py reads."""
    ok = True
    seen = set()
    for line in (ROOT / "eval/messages.jsonl").read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        row = json.loads(line)
        for field in ("id", "text", "facts", "stance"):
            if field not in row:
                print(f"FAIL  messages: {row.get('id', '?')} is missing {field}")
                ok = False
        if row["id"] in seen:
            print(f"FAIL  messages: duplicate id {row['id']}")
            ok = False
        seen.add(row["id"])
    if ok:
        print(f"ok    messages: {len(seen)} well formed, no duplicate ids")
    return ok


if __name__ == "__main__":
    results = [check_prompt(), check_splitter(), check_messages()]
    sys.exit(0 if all(results) else 1)
