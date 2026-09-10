# Can a small model do this well enough?

Nobody can answer that from benchmark scores, because the task is narrow and
benchmarks are not. This measures it directly, on fifty messages chosen to break
things: a flat no, a genuine maybe, a conditional yes, two times in one
sentence, a door code, two weekdays that must not swap, a bereavement, a
complaint that must not turn into an apology, and a two-word message.

Each is annotated with the facts that must survive and the position the writer
took.

## Read this before the numbers

There are two scores here and they disagree. `grade.py` is mechanical: it
checks the shape, whether every date, time, number and name survived, whether
the alternatives differ, whether neighbouring beats repeat. It is fast, free
and deterministic, and it cannot see meaning.

That is not a small gap. Both of these scored perfectly clean:

```
was: sorry i cant make saturday, ive got my sisters wedding. have a great time though
got: i hope you have a fantastic time at your sister's wedding! i'll be thinking of you

was: yes if we can finish by 4, otherwise ill have to skip it
got: i'd love to join but unfortunately can't make it this time due to other commitments
```

The first hands the writer's own excuse to the reader. The second turns a
conditional yes into a flat no with an invented reason. Every hard token
survived both.

`judge.py` reads each rewrite against the original and asks only whether the
commitment, the facts and the roles are intact. It disagrees with the
mechanical score often enough to change every conclusion, so read them
together or not at all.

## Where it got to

Fifty messages, scored on the pipeline the app actually uses, both ways.

| model | size | mechanical | faithful | kept as-is |
|---|---|---|---|---|
| 1.5B one-shot grid | 986 MB | 10% | 51% | 4% |
| 0.5B stock | 397 MB | 14% | 63% | 30% |
| 0.5B fine-tuned | 397 MB | **66%** | 59% | 9% |
| 1.5B stock | 986 MB | 58% | 70% | 6% |
| 1.5B fine-tuned | 986 MB | 60% | 66% | 7% |
| 7B stock | 4.7 GB | 72% | 75% | 12% |
| 14B stock | 9.0 GB | 72% | **81%** | 12% |

Four things worth reading off that.

**Reshaping the task was the biggest single win, and both measures agree.**
The one-shot grid asked a model to segment, paraphrase, keep every cell
independent and preserve every fact in one pass. Splitting that up took the
1.5B from 10% to 58% mechanically and 51% to 70% on faithfulness, with no
change of model.

**Faithfulness is strictly ordered by size; the mechanical score is not.**
Mechanically the 1.5B, 7B and 14B cluster around 60 to 72 and the ordering is
noisy. On faithfulness they separate cleanly, 70, 75, 81. The "ceiling that
parameters do not move", which an earlier version of this file reported, was
the mechanical score running out of resolution rather than the models running
out of headroom.

**Fine-tuning optimised the metric and hurt the product.** At both sizes it
raised the mechanical score and lowered faithfulness. The fine-tuned 0.5B is
the best small model on one measure and nearly the worst on the other. It
learned the form of a good answer: well-formed, distinct, structurally clean,
and wrong about meaning more often. Untrained, the same model refused more
work, handing back the writer's own words 30% of the time.

**"Kept as-is" is the price of safety.** It is the share of alternatives the
guarantee replaced with the original, because the rewrite lost a fact,
collapsed into a label, or ran away past its beat. Two models tying on the
mechanical score are not equivalent if one needed three times the rescuing.

Single runs at temperature 0.7 move by up to sixteen points between passes on
fifty messages. `repeat.py` runs a configuration several times and reports the
spread; anything closer than that is not a ranking.

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

`grade.py`, all mechanical and all cheap:

| Column | Question |
|---|---|
| valid | Did it come back as a grid, one alternative per tone in every slot? |
| hard | Did days, times, numbers and names survive into every tone column? |
| soft | Did ordinary nouns survive? Nothing protects these, so it is a quality signal. |
| distinct | Are the alternatives inside a slot actually different from each other? |
| indep | Do neighbouring beats repeat each other, which makes a mixed draft read badly? |
| kept as-is | What share of alternatives did the guarantee replace with the original? |
| CLEAN | None of the above went wrong. |

`hard` and `soft` split using the same rule the app's guarantee uses, so `hard`
is a correctness check and `soft` is a quality one.

An earlier version of this file claimed the failure that hurts a user is
checkable without a judge. That was wrong, and the two examples at the top are
why. Meaning needs `judge.py`:

```bash
python eval/judge.py --judge qwen2.5:14b-instruct
```

It reads each assembled column against the original and asks one question: are
the commitment, the facts and the roles intact. Style, tone and length are
explicitly not its business. It runs locally, so it costs nothing.

Three caveats it repeats in its own output. It is a model and can be wrong.
Judging one Qwen with another flatters the family, so the 14B's top position
may be self-preference. And it is a measuring instrument, not a guarantee:
nothing about it belongs in the app.

`grade.py` also prints the position-taking messages with all three columns, for
the two minutes of reading that neither script replaces.

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

LoRA over every linear layer, loss on the completion only, then merged.

It works, in the narrow sense that it does what it was asked. The 0.5B's
distinctness went from 22% to 92% and its mechanical score from 14% to 66%.
Its faithfulness went from 63% to 59%.

That is worth sitting with before running it again. Training on a teacher's
output, filtered by the same mechanical rules the eval scores, taught the
student to satisfy those rules. What the rules do not measure got worse. If
you want a fine-tune that is actually better, the filter needs to include the
judge, and the teacher needs to be better than the 7B used here.

## Keeping it honest

`pipeline.py` and the app's `BeatSplitter` plus `GridBuilder` are the same
algorithm in two languages, and the prompt has to be identical down to the line
breaks, because a student trained on one wording and prompted with another
loses part of what it learned.

```bash
python eval/check_parity.py
```

That compares the prompt pinned inside the Kotlin test against the one this
directory scores, runs the Python splitter over the cases the Kotlin tests
assert, and checks the messages file is well formed. It exits non-zero on a
mismatch, so it belongs in a hook. `PromptParityTest` guards the same text from
the Kotlin side; neither check sees both languages alone.

Add your own messages as you hit failures in real use. A test set that grows out
of actual mistakes is worth more than one written in advance, this one included.
