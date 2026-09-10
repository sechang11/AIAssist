"""Run every test message through one model and save the grids.

    python run.py --backend ollama --model qwen2.5:7b-instruct
    python run.py --backend ollama --model qwen2.5:1.5b-instruct --no-schema
    python run.py --backend anthropic --model claude-opus-5

Ollama needs nothing installed beyond Ollama itself. The Anthropic backend needs
`pip install anthropic` and a key in ANTHROPIC_API_KEY, and is only there so you
have a ceiling to compare against.

Output lands in out/<name>.jsonl, one line per message. grade.py reads it.
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from prompt import SCHEMA, SYSTEM, TONES, user  # noqa: E402

HERE = Path(__file__).parent


def load_messages():
    with open(HERE / "messages.jsonl", encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def call_ollama(model: str, text: str, use_schema: bool, host: str) -> str:
    body = {
        "model": model,
        "stream": False,
        "options": {"temperature": 0.7, "num_predict": 900},
        "messages": [
            {"role": "system", "content": SYSTEM},
            {"role": "user", "content": user(text)},
        ],
    }
    # Constrained decoding. Newer Ollama takes a full schema; older builds only
    # understand "json". Either beats nothing, so fall back rather than fail.
    if use_schema:
        body["format"] = SCHEMA

    def post(payload):
        request = urllib.request.Request(
            host.rstrip("/") + "/api/chat",
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(request, timeout=300) as response:
            return json.loads(response.read())

    try:
        result = post(body)
    except urllib.error.HTTPError as e:
        if use_schema and e.code in (400, 500):
            body["format"] = "json"
            result = post(body)
        else:
            raise
    return result["message"]["content"]


def call_anthropic(model: str, text: str) -> str:
    import anthropic  # imported here so the Ollama path needs no dependency

    client = anthropic.Anthropic()
    response = client.messages.create(
        model=model,
        max_tokens=8000,
        system=SYSTEM,
        output_config={"effort": "low"},
        messages=[{"role": "user", "content": user(text)}],
    )
    return "\n".join(b.text for b in response.content if b.type == "text")


def extract_grid(raw: str):
    """The app's parser is deliberately forgiving, so this one is too."""
    start, end = raw.find("{"), raw.rfind("}")
    if start < 0 or end <= start:
        return None, "no JSON object in the reply"
    try:
        parsed = json.loads(raw[start : end + 1])
    except json.JSONDecodeError as e:
        return None, f"invalid JSON: {e}"
    if not isinstance(parsed, dict):
        return None, "JSON was not an object"
    return parsed, None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--backend", choices=["ollama", "anthropic"], required=True)
    ap.add_argument("--model", required=True)
    ap.add_argument("--host", default="http://localhost:11434")
    ap.add_argument("--no-schema", action="store_true", help="ollama: skip constrained decoding")
    ap.add_argument("--name", help="output file stem (default: the model name)")
    args = ap.parse_args()

    name = args.name or args.model.replace(":", "-").replace("/", "-")
    out_path = HERE / "out" / f"{name}.jsonl"
    out_path.parent.mkdir(exist_ok=True)

    messages = load_messages()
    print(f"{len(messages)} messages -> {args.backend}:{args.model}\n")

    with open(out_path, "w", encoding="utf-8") as out:
        for i, message in enumerate(messages, 1):
            started = time.time()
            raw, grid, error = "", None, None
            try:
                if args.backend == "ollama":
                    raw = call_ollama(args.model, message["text"], not args.no_schema, args.host)
                else:
                    raw = call_anthropic(args.model, message["text"])
                grid, error = extract_grid(raw)
            except Exception as e:  # a dead backend should not lose the run so far
                error = f"{type(e).__name__}: {e}"

            elapsed = time.time() - started
            mark = "ok " if grid else "FAIL"
            print(f"  {i:>2}/{len(messages)}  {mark}  {elapsed:5.1f}s  {message['id']}"
                  + (f"   {error}" if error else ""))

            out.write(json.dumps({
                "id": message["id"],
                "model": f"{args.backend}:{args.model}",
                "seconds": round(elapsed, 2),
                "raw": raw,
                "grid": grid,
                "error": error,
            }) + "\n")

    print(f"\nwrote {out_path}")
    print(f"now run:  python grade.py {out_path.name}")


if __name__ == "__main__":
    main()
