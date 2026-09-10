# Does a small model do this well enough?

Nobody can answer that from benchmark scores, because the task is narrow and
benchmarks are not. This measures it directly.

Twenty messages chosen to break things: a flat no, a genuine maybe, a
conditional yes, two times in one sentence, four facts in one line, a message
of four words, and a rambling one that is hard to split into beats. Each is
annotated with the facts that must survive and the position the writer took.

## Run it

Install Ollama, then pull a model. Your card has 16GB, which comfortably fits
anything up to about fourteen billion parameters at four-bit.

```powershell
ollama pull qwen2.5:7b-instruct
```

```powershell
python eval/run.py --backend ollama --model qwen2.5:7b-instruct
```

```powershell
python eval/grade.py
```

Nothing to `pip install` for the Ollama path. The Anthropic backend needs
`pip install anthropic` and a key, and exists only so you have a ceiling to
compare against.

Run several and `grade.py` puts them in one table:

```powershell
python eval/run.py --backend ollama --model qwen2.5:1.5b-instruct
```

The small one is the honest test, because a 1.5B is roughly what a phone can
carry. The 7B tells you what your home server could do.

## What it measures

Five checks, all mechanical, because the failure that actually hurts someone is
the model quietly changing what they committed to, and that is checkable
without a judge.

| Column | Question |
|---|---|
| valid | Did it come back as a grid at all, with every slot holding one alternative per tone? |
| facts | Did the writer's facts survive into every tone column? |
| no-add | Did it invent a day, a time or a number that was never in the original? |
| distinct | Are the alternatives inside a slot actually different from each other? |
| indep | Do neighbouring beats repeat each other, which would make a mixed draft read badly? |
| CLEAN | No problem of any kind. This is the number to watch. |

Median and slowest response times are reported too, since latency decides
whether this can live on a phone.

## What it cannot measure

Whether a no is still a no. No script can tell you that, so `grade.py` prints
the position-taking messages with all three columns side by side for you to
read. There are six of them. It takes two minutes and it is the most important
two minutes in this directory.

## Interpreting it

`valid` below about 90% usually means malformed JSON rather than bad writing.
Before concluding the model is too small, check that constrained decoding is on;
`run.py` sends a JSON schema to Ollama by default and falls back to plain JSON
mode on older builds. Compare with `--no-schema` to see how much it is doing.

`facts` and `no-add` are the ones that decide whether you can ship. A model that
writes beautifully and loses the time you agreed to meet is worse than useless.

If a small model lands close to a large one on facts but behind on the reading
test, that is the case for fine-tuning: the gap is style and format, which
training on a few thousand examples fixes, rather than comprehension, which it
does not.

## Keeping it honest

`prompt.py` is a copy of `Prompt.kt`. If you change one, change the other, or
this stops measuring what the app does.

Add your own messages to `messages.jsonl` as you find failures in real use. A
test set that grows out of actual mistakes is worth more than one written in
advance, this one included.
