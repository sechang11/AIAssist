"""Rewrite one message and print the grid, the way the phone would show it.

    python eval/try.py "ok cool see you then"
    python eval/try.py --model qwen2.5:1.5b-instruct "cant do friday sorry"

For looking at the thing rather than at a score. Reads the same pipeline the
app ports, so a fragment that comes back unchanged here comes back unchanged
on the phone, for the same reason.
"""

import argparse
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import pipeline  # noqa: E402

DEFAULT = [
    "ok cool see you then",
    "cant make friday sorry",
    "thanks so much for sorting that out",
    "can we push it to 7:30 instead of 6? traffic is going to be awful",
]


def asker(host, model, temperature):
    """build_grid hands this (whole, fragment); the prompts come from pipeline."""
    def ask(whole, fragment):
        body = {
            "model": model,
            "stream": False,
            "format": pipeline.schema(),
            "options": {"temperature": temperature, "num_predict": 300},
            "messages": [
                {"role": "system", "content": pipeline.system()},
                {"role": "user", "content": pipeline.user(whole, fragment)},
            ],
        }
        request = urllib.request.Request(
            host.rstrip("/") + "/api/chat",
            data=json.dumps(body).encode(),
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(request, timeout=180) as response:
            content = json.load(response)["message"]["content"]
        start, end = content.find("{"), content.rfind("}")
        if start < 0 or end <= start:
            return None
        try:
            return json.loads(content[start:end + 1])
        except json.JSONDecodeError:
            return None
    return ask


def show(message, result, tones):
    print(f"\n> {message}")
    grid = (result or {}).get("slots") or []
    if not grid:
        print("    (nothing came back)")
        return
    width = max(len(slot["label"]) for slot in grid) + 2
    unchanged = 0
    for slot in grid:
        alternatives = slot["alternatives"]
        distinct = len({a.strip().lower() for a in alternatives})
        if distinct == 1:
            unchanged += 1
        print(f"    {slot['label']:<{width}}", end="")
        for tone, alternative in zip(tones, alternatives):
            print(f"\n      {tone:<8} {alternative}", end="")
        print("  <- all three identical" if distinct == 1 else "")
    reverted = (result or {}).get("_reverted") or 0
    retried = (result or {}).get("_retried") or 0
    if unchanged:
        print(f"    !! {unchanged}/{len(grid)} beats came back unrewritten")
    if reverted or retried:
        print(f"    ({retried} beats retried, {reverted} rewrites reverted by the guarantee)")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("messages", nargs="*", default=None)
    parser.add_argument("--host", default="http://192.168.0.45:11434")
    parser.add_argument("--model", default="qwen2.5:7b-instruct")
    parser.add_argument("--temperature", type=float, default=0.7)
    args = parser.parse_args()

    ask = asker(args.host, args.model, args.temperature)
    print(f"{args.model} at {args.host}")
    for message in (args.messages or DEFAULT):
        try:
            grid = pipeline.build_grid(message, ask)
        except (urllib.error.URLError, OSError) as error:
            print(f"\n> {message}\n    unreachable: {error}")
            continue
        show(message, grid, pipeline.TONES)
    return 0


if __name__ == "__main__":
    sys.exit(main())
