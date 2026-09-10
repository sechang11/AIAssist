"""Run every test message through one model and save the grids.

Two architectures, same harness, so they can be compared directly:

    --mode grid    one call builds the whole grid          (prompt.py)
    --mode split   split into beats, one call per beat     (pipeline.py)

    python run.py --backend ollama --model qwen2.5:1.5b-instruct --mode split
    python run.py --backend ollama --model qwen2.5:7b-instruct  --mode grid
    python run.py --backend anthropic --model claude-opus-5 --mode grid

Ollama needs nothing installed beyond Ollama itself. The Anthropic backend needs
`pip install anthropic` and a key in ANTHROPIC_API_KEY, and is only there so you
have a ceiling to compare against.

Output lands in out/<name>.jsonl, one line per message. grade.py reads it.
"""

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import pipeline  # noqa: E402
import prompt  # noqa: E402

HERE = Path(__file__).parent


def load_messages():
    with open(HERE / "messages.jsonl", encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def parse_json_object(raw: str):
    start, end = raw.find("{"), raw.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        parsed = json.loads(raw[start : end + 1])
    except json.JSONDecodeError:
        return None
    return parsed if isinstance(parsed, dict) else None


class Ollama:
    def __init__(self, model, host, use_schema):
        self.model, self.host, self.use_schema = model, host.rstrip("/"), use_schema

    def __call__(self, system_text, user_text, schema_obj, max_tokens=900):
        body = {
            "model": self.model,
            "stream": False,
            "options": {"temperature": 0.7, "num_predict": max_tokens},
            "messages": [
                {"role": "system", "content": system_text},
                {"role": "user", "content": user_text},
            ],
        }
        if self.use_schema:
            body["format"] = schema_obj

        def post(payload):
            request = urllib.request.Request(
                self.host + "/api/chat",
                data=json.dumps(payload).encode("utf-8"),
                headers={"Content-Type": "application/json"},
            )
            with urllib.request.urlopen(request, timeout=300) as response:
                return json.loads(response.read())

        try:
            result = post(body)
        except urllib.error.HTTPError as e:
            # Older builds understand "json" but not a full schema.
            if self.use_schema and e.code in (400, 500):
                body["format"] = "json"
                result = post(body)
            else:
                raise
        return result["message"]["content"]


class Anthropic:
    def __init__(self, model):
        import anthropic  # imported here so the Ollama path needs no dependency

        self.model = model
        self.client = anthropic.Anthropic()

    def __call__(self, system_text, user_text, schema_obj, max_tokens=8000):
        response = self.client.messages.create(
            model=self.model,
            max_tokens=max_tokens,
            system=system_text,
            output_config={"effort": "low"},
            messages=[{"role": "user", "content": user_text}],
        )
        return "\n".join(b.text for b in response.content if b.type == "text")


def one_shot_grid(call, text):
    raw = call(prompt.system(), prompt.user(text), prompt.schema())
    grid = parse_json_object(raw)
    if grid is None:
        return None, raw, "no usable JSON object in the reply"
    # The prompt no longer asks for the tone names back, because small models
    # echoed the placeholder from the example. The caller knows them.
    grid.setdefault("tones", list(prompt.TONES))
    return grid, raw, None


def split_then_paraphrase(call, text):
    """One short call per beat. The bookkeeping lives in pipeline.py."""
    transcript = []

    def ask(whole, fragment):
        raw = call(
            pipeline.system(),
            pipeline.user(whole, fragment),
            pipeline.schema(),
            max_tokens=300,
        )
        transcript.append(raw)
        return parse_json_object(raw)

    grid = pipeline.build_grid(text, ask)
    if not grid["slots"]:
        return None, "\n---\n".join(transcript), "splitter produced no beats"
    return grid, "\n---\n".join(transcript), None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--backend", choices=["ollama", "anthropic"], required=True)
    ap.add_argument("--model", required=True)
    ap.add_argument("--mode", choices=["grid", "split"], default="grid")
    ap.add_argument("--host", default="http://localhost:11434")
    ap.add_argument("--no-schema", action="store_true", help="ollama: skip constrained decoding")
    ap.add_argument("--name", help="output file stem")
    args = ap.parse_args()

    stem = args.name or f"{args.model.replace(':', '-').replace('/', '-')}-{args.mode}"
    out_path = HERE / "out" / f"{stem}.jsonl"
    out_path.parent.mkdir(exist_ok=True)

    if args.backend == "ollama":
        call = Ollama(args.model, args.host, not args.no_schema)
    else:
        call = Anthropic(args.model)

    build = one_shot_grid if args.mode == "grid" else split_then_paraphrase
    messages = load_messages()
    print(f"{len(messages)} messages -> {args.backend}:{args.model} [{args.mode}]\n")

    with open(out_path, "w", encoding="utf-8") as out:
        for i, message in enumerate(messages, 1):
            started = time.time()
            raw, grid, error = "", None, None
            try:
                grid, raw, error = build(call, message["text"])
            except Exception as e:  # a dead backend should not lose the run so far
                error = f"{type(e).__name__}: {e}"

            elapsed = time.time() - started
            extra = ""
            if grid:
                extra = f"  {grid.get('_beats', len(grid['slots']))} beats"
                if grid.get("_retried"):
                    extra += f", {grid['_retried']} retried"
            print(f"  {i:>2}/{len(messages)}  {'ok ' if grid else 'FAIL'}  {elapsed:5.1f}s  "
                  f"{message['id']:<15}{extra}" + (f"   {error}" if error else ""))

            out.write(json.dumps({
                "id": message["id"],
                "model": f"{args.backend}:{args.model} [{args.mode}]",
                "seconds": round(elapsed, 2),
                "raw": raw,
                "grid": grid,
                "error": error,
            }) + "\n")

    print(f"\nwrote {out_path}")
    print(f"now run:  python grade.py {out_path.name}")


if __name__ == "__main__":
    main()
