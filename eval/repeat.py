"""Run the same configuration several times and report the spread.

    python repeat.py --times 3 --models qwen2.5:0.5b-instruct,remix-0.5b

Sampling runs at temperature 0.7, so one pass over fifty messages is a noisy
estimate. Two runs of the stock 1.5B, identical in every other way, came back
at 74% and 58% clean. Anything read off a single run below about fifteen points
of separation is not a difference, it is the dice.

This repeats each configuration, grades every run, and prints mean and range
per metric so the size of that noise is visible rather than assumed.
"""

import argparse
import json
import statistics
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
import grade  # noqa: E402

METRICS = [
    ("valid", "valid"), ("hard_kept", "hard"), ("soft_kept", "soft"),
    ("nothing_invented", "no-add"), ("alts_distinct", "distinct"),
    ("slots_independent", "indep"), ("reverted", "kept-as-is"), ("clean", "CLEAN"),
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--times", type=int, default=3)
    ap.add_argument("--models", required=True, help="comma separated ollama model names")
    ap.add_argument("--mode", default="split")
    args = ap.parse_args()

    messages = grade.load_messages()
    models = [m.strip() for m in args.models.split(",") if m.strip()]
    out_dir = HERE / "out"
    results = {}

    for model in models:
        runs = []
        for i in range(1, args.times + 1):
            stem = f"rep-{model.replace(':', '-').replace('/', '-')}-{i}"
            path = out_dir / f"{stem}.jsonl"
            if not path.exists():
                print(f"  running {model} pass {i}", flush=True)
                subprocess.run([
                    sys.executable, str(HERE / "run.py"),
                    "--backend", "ollama", "--model", model,
                    "--mode", args.mode, "--name", stem,
                ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
            if path.exists():
                runs.append(grade.score(path, messages))
        if runs:
            results[model] = runs

    header = f"{'model':<28}" + "".join(f"{label:>13}" for _, label in METRICS)
    print("\n" + header)
    print("-" * len(header))
    for model, runs in results.items():
        cells = []
        for key, _ in METRICS:
            values = [r[key] for r in runs]
            mean = statistics.fmean(values)
            spread = max(values) - min(values)
            cells.append(f"{mean:>7.0f}{'+-' + str(round(spread / 2)):>6}")
        print(f"{model[:27]:<28}" + "".join(cells))

    print(f"\nmean +- half the range, over {args.times} passes of fifty messages each.")
    print("Overlapping ranges are not a ranking.")

    (HERE / "results" / "repeats.json").parent.mkdir(exist_ok=True)
    (HERE / "results" / "repeats.json").write_text(json.dumps({
        model: {key: [r[key] for r in runs] for key, _ in METRICS}
        for model, runs in results.items()
    }, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
