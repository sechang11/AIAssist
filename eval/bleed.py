"""How often does one beat's rewrite swallow the beat next to it?

    python eval/bleed.py --model qwen2.5:7b-instruct

Splitting a message into beats and rewriting each one alone has a failure the
per-beat guards cannot see, because each of them only ever looks at one beat.
A rewrite that is faithful to its own fragment can still pull in the content of
the fragment after it, and then the assembled message says the same thing
twice:

    can we push it to 7:30 instead of 6? | traffic is going to be awful
    -> "can we make it 7:30 instead of 6? i know the traffic will be crazy,"
    -> "i hope the traffic isn't too bad, but it's looking rough."

Both beats pass every check in pipeline.py. Together they are not a message a
person would send. This counts how often that happens before deciding whether
it is worth a guard.
"""

import argparse
import json
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import pipeline  # noqa: E402

HERE = Path(__file__).parent
WORD = re.compile(r"[a-z']+")

# Words too ordinary to mean a beat was copied. Everything shorter than five
# letters is already excluded, which removes most of the work.
COMMON = {
    "about", "after", "again", "already", "always", "another", "anyway",
    "around", "because", "before", "being", "could", "doing", "enough",
    "every", "getting", "going", "gonna", "happy", "honestly", "hopefully",
    "isn't", "it's", "just", "know", "later", "let's", "little", "maybe",
    "might", "moment", "much", "needs", "never", "other", "please", "really",
    "right", "should", "since", "sorry", "still", "sure", "thank", "thanks",
    "their", "there", "these", "thing", "things", "think", "those", "time",
    "today", "would", "your", "you're", "we'll", "i'll", "i've", "don't",
    "can't", "won't", "that's", "there's", "here's", "which", "while",
}


def content_words(text):
    return {w for w in WORD.findall(text.lower()) if len(w) >= 5 and w not in COMMON}


def bled(fragment, rewrite, others):
    """Words the rewrite took from a different beat and this one never had."""
    mine = content_words(fragment)
    theirs = set()
    for other in others:
        theirs |= content_words(other)
    return sorted((content_words(rewrite) & theirs) - mine)


def asker(host, model, temperature):
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


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="http://192.168.0.45:11434")
    parser.add_argument("--model", default="qwen2.5:7b-instruct")
    parser.add_argument("--temperature", type=float, default=0.7)
    parser.add_argument("--limit", type=int, default=0)
    args = parser.parse_args()

    with open(HERE / "messages.jsonl", encoding="utf-8") as handle:
        messages = [json.loads(line) for line in handle if line.strip()]
    if args.limit:
        messages = messages[:args.limit]

    ask = asker(args.host, args.model, args.temperature)
    multi, cells, bleeding, examples = 0, 0, 0, []

    for message in messages:
        beats = pipeline.split_beats(message["text"])
        if len(beats) < 2:
            continue
        multi += 1
        try:
            result = pipeline.build_grid(message["text"], ask)
        except (urllib.error.URLError, OSError) as error:
            print(f"unreachable: {error}")
            return 1
        for index, slot in enumerate(result["slots"]):
            if index >= len(beats):
                break
            others = [b for i, b in enumerate(beats) if i != index]
            for tone, alternative in zip(result["tones"], slot["alternatives"]):
                cells += 1
                if alternative.strip() == beats[index].strip():
                    continue          # reverted or unrewritten, not a bleed
                taken = bled(beats[index], alternative, others)
                if taken:
                    bleeding += 1
                    if len(examples) < 12:
                        examples.append((message["id"], tone, beats[index], alternative, taken))

    for id_, tone, fragment, rewrite, taken in examples:
        print(f"\n{id_} [{tone}] took {taken}")
        print(f"    beat:    {fragment}")
        print(f"    rewrite: {rewrite}")

    share = 100 * bleeding / cells if cells else 0
    print(f"\n{args.model}: {bleeding}/{cells} rewritten cells pulled in another "
          f"beat ({share:.0f}%), over {multi} multi-beat messages")
    return 0


if __name__ == "__main__":
    sys.exit(main())
