# Can a small model do this well enough?

Nobody can answer that from benchmark scores, because the task is narrow and
benchmarks are not. This measures it directly, on fifty messages chosen to break
things: a flat no, a genuine maybe, a conditional yes, two times in one
sentence, a door code, two weekdays that must not swap, a bereavement, a
complaint that must not turn into an apology, and a two-word message.

Each is annotated with the facts that must survive and the position the writer
took.

## Where it got to

Qwen, off the shelf, no training, fifty messages, scored on the pipeline the app
actually uses.

| model | mode | valid | hard | soft | distinct | indep | CLEAN |
|---|---|---|---|---|---|---|---|
| 1.5B | one-shot grid | 98% | 84% | 86% | 70% | 38% | 14% |
| 0.5B | split | 100% | 100% | 88% | 36% | 92% | 30% |
| 7B | split | 100% | 100% | 90% | 88% | 96% | 68% |
| 1.5B | split | 100% | 100% | 92% | 88% | 98% | **74%** |
| 14B | split | 100% | 100% | 90% | 82% | 98% | **74%** |

Four things worth reading off that.

**There is a ceiling near 74% and parameters do not move it.** The 14B is
twenty-eight times the size of the 0.5B and nine times the 1.5B, and it scores
what the 1.5B scores, at thirty times the latency. Whatever the last 26% is, it
is not a capability problem, so no amount of model shopping fixes it.

**Hard facts sit at 100% for every split run.** That is the guarantee in
`pipeline.build_grid`, not the models being careful: a rewrite that loses a day,
time, number or name is discarded and the beat falls back to the writer's own
words. It is the one number that should never drop, and if it does, something is
broken rather than merely weak.

**The 1.5B matches the 7B.** Five times the parameters buys nothing once the
task is the right shape. That is the finding the whole on-device plan rests on.

**The 0.5B is safe but not yet useful.** It keeps every hard fact, because the
guarantee makes it, and then offers three alternatives that are barely different
from one another: distinctness 36% against 88% for the 1.5B. Safety and
usefulness are separate problems, and it has only solved the first.

## Running it

Ollama, no API key, nothing to `pip install`:

```bash
ollama pull qwen2.5:1.5b-instruct
```

```bash
python eval/run.py --backend ollama --model qwen2.5:1.5b-instruct --mode split
```

```bash
python eval/grade.py
```

`--mode grid` runs the older one-shot architecture for comparison. The Anthropic
backend needs `pip install anthropic` and a key, and exists only to give the
table a ceiling; there is still no Opus row.

## What it measures

All mechanical, because the failure that actually hurts someone is the model
quietly changing what they committed to, and that is checkable without a judge.

| Column | Question |
|---|---|
| valid | Did it come back as a grid, one alternative per tone in every slot? |
| hard | Did days, times, numbers and names survive into every tone column? |
| soft | Did ordinary nouns survive? Nothing protects these, so it is a quality signal. |
| distinct | Are the alternatives inside a slot actually different from each other? |
| indep | Do neighbouring beats repeat each other, which makes a mixed draft read badly? |
| CLEAN | No problem of any kind. The number to watch. |

`hard` and `soft` split using the same rule the app's guarantee uses, so `hard`
is a correctness check and `soft` is a quality one.

Whether a no is still a no, no script can tell you. `grade.py` prints the
position-taking messages with all three columns for you to read.

## Training a student

`gen_data.py` builds a training set with a large open model as teacher and the
pipeline's own rules as the filter.

```bash
python eval/gen_data.py messages --model qwen2.5:14b-instruct --per-cell 4
```

```bash
python eval/gen_data.py beats --model qwen2.5:14b-instruct
```

```bash
python eval/gen_data.py pack
```

Source messages are invented by crossing a taxonomy, intent by relationship by
register by length by how many facts are buried in the message, rather than by
asking for five hundred text messages and getting five hundred versions of
"sorry I'm late". Then every teacher answer that loses a load-bearing token is
thrown away, because a bad pair poisons training worse than a missing one.

The fifty eval messages are excluded from generation by exact match, or the
score would be meaningless.

```bash
python eval/train.py --base Qwen/Qwen2.5-0.5B-Instruct --epochs 3
```

LoRA over every linear layer, loss on the completion only, then merged. The
0.5B is the target because 397MB is the only size that makes the download
comfortable, and because its weakness is distinctness, which is a learned
behaviour and therefore the kind of thing training moves.

## Keeping it honest

`pipeline.py` and the app's `BeatSplitter` plus `GridBuilder` are the same
algorithm in two languages. If you change one, change the other.

Add your own messages as you hit failures in real use. A test set that grows out
of actual mistakes is worth more than one written in advance, this one included.
