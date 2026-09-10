"""Split, then paraphrase each beat on its own.

The one-shot grid asks a model to fill a spreadsheet: rows are beats, columns
are tones, every cell has to keep the writer's facts, and no cell may lean on
its neighbours. The paraphrasing inside that is easy. The bookkeeping around it
is what a small model cannot hold, which is what the first three eval rounds
actually measured.

So the bookkeeping moves into code:

  1. Split the message into beats mechanically. No model, so it cannot
     hallucinate, cannot drop a clause, and costs nothing.
  2. Ask for three phrasings of ONE short fragment at a time. That is the job
     small models are good at, and it is roughly what Apple and Google already
     ship on-device.

Two failure modes disappear by construction rather than by instruction. Beats
cannot repeat each other, because each is rewritten without ever seeing its
neighbours' output. And a fragment that loses a name is cheap to spot and retry
on its own, instead of discarding the whole grid.
"""

import re

TONES = ["Concise", "Warm", "Formal"]

_SENTENCE = re.compile(r"(?<=[.!?])\s+")
_COMMA = re.compile(r",\s+")

# A comma inside a long run is usually a real beat boundary in a text message,
# where people rarely use full stops. Below this length it is usually not.
_SPLIT_OVER = 45
_MERGE_UNDER = 14
_MAX_BEATS = 6


def split_beats(text: str):
    """Cut a message into beats using only punctuation and length."""
    text = re.sub(r"\s+", " ", text).strip()
    if not text:
        return []

    pieces = [p.strip() for p in _SENTENCE.split(text) if p.strip()]

    split_further = []
    for piece in pieces:
        if len(piece) > _SPLIT_OVER and "," in piece:
            split_further += [p.strip() for p in _COMMA.split(piece) if p.strip()]
        else:
            split_further.append(piece)

    # A three-word tail is not a beat, it belongs to the one before it.
    merged = []
    for piece in split_further:
        if merged and len(piece) < _MERGE_UNDER:
            merged[-1] = (merged[-1].rstrip(",") + " " + piece).strip()
        else:
            merged.append(piece)

    if len(merged) > _MAX_BEATS:
        merged = merged[: _MAX_BEATS - 1] + [" ".join(merged[_MAX_BEATS - 1:])]

    return [m.rstrip(",").strip() for m in merged if m.strip()]


def system(tones=None) -> str:
    tones = tones or TONES
    numbered = "; ".join(f"{i + 1} {t.lower()}" for i, t in enumerate(tones))
    return f"""You rewrite one short fragment of a personal message {len(tones)} ways: {numbered}.

Rewrite the fragment only. The rest of the message is given for context and must
not appear in your answer.

Keep every name, date, time, number and place from the fragment, exactly as
written, in all {len(tones)} rewrites. Add nothing that is not in the fragment. Never
change what it commits to: a no stays a no, a maybe stays a maybe, and every
condition survives.

Stay within about the length of the fragment. Match the writer's register in the
casual rewrites: lowercase with no full stops stays lowercase with no full stops.

Give the fragment a two-word label saying what beat it is, and mark it optional
only if the whole message still reads correctly without it."""


def user(whole: str, fragment: str, tones=None) -> str:
    tones = tones or TONES
    return (
        "Whole message, for context only:\n<<<\n" + whole + "\n>>>\n\n"
        "Rewrite just this fragment:\n<<<\n" + fragment + "\n>>>\n\n"
        "Tones in order: " + ", ".join(tones) + "."
    )


def schema(tones=None):
    n = len(tones or TONES)
    return {
        "type": "object",
        "required": ["label", "optional", "alternatives"],
        "properties": {
            "label": {"type": "string"},
            "optional": {"type": "boolean"},
            "alternatives": {
                "type": "array",
                "minItems": n,
                "maxItems": n,
                "items": {"type": "string"},
            },
        },
    }


_TOKEN = re.compile(r"[A-Za-z0-9:.]*[A-Za-z0-9][A-Za-z0-9:.]*")

# Lowercase in a text message and still load-bearing. Capitalisation alone was
# missing every "friday" and "saturday" in the test set, which is most of them.
_ALWAYS_HARD = {
    "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
    "today", "tomorrow", "tonight", "weekend", "january", "february", "march",
    "april", "may", "june", "july", "august", "september", "october", "november",
    "december",
}


def lost_tokens(fragment: str, rewrite: str):
    """Which of the fragment's load-bearing tokens went missing. Days, times,
    numbers and names; ordinary words are meant to change."""
    hard = []
    for token in _TOKEN.findall(fragment):
        stripped = token.strip(".")
        if not stripped:
            continue
        if (
            any(c.isdigit() for c in stripped)
            or stripped.lower() in _ALWAYS_HARD
            or (stripped[:1].isupper() and len(stripped) > 2 and stripped.lower() != "the")
        ):
            hard.append(stripped)
    lower = rewrite.lower()
    return [t for t in hard if t.lower() not in lower]


def build_grid(whole: str, ask, tones=None, retry=True):
    """`ask(fragment) -> dict` does one model call. Returns an app-shaped grid.

    A beat whose rewrite drops a name or a number gets one more attempt on its
    own, which is only affordable because the unit of failure is now a fragment
    rather than the entire message.
    """
    tones = tones or TONES
    beats = split_beats(whole)
    slots, retried, reverted = [], 0, 0

    for index, fragment in enumerate(beats):
        answer = ask(whole, fragment)
        alternatives = (answer or {}).get("alternatives") or []

        if retry and alternatives and any(lost_tokens(fragment, a) for a in alternatives):
            retried += 1
            second = ask(whole, fragment)
            if second and second.get("alternatives"):
                before = sum(len(lost_tokens(fragment, a)) for a in alternatives)
                after = sum(len(lost_tokens(fragment, a)) for a in second["alternatives"])
                if after < before:
                    answer, alternatives = second, second["alternatives"]

        if len(alternatives) != len(tones):
            # Better a beat the reader cannot retone than a beat that vanishes.
            alternatives = [fragment] * len(tones)

        # The guarantee. A rewrite that dropped a date or a name is not a worse
        # rewrite, it is a different message, so it does not get shown. Losing
        # the retoning on one beat is a far smaller harm than losing the time
        # the writer agreed to meet, and the reader can see it is unchanged.
        kept = []
        for alternative in alternatives:
            if lost_tokens(fragment, alternative):
                kept.append(fragment)
                reverted += 1
            else:
                kept.append(alternative)

        slots.append({
            "id": f"s{index + 1}",
            "label": (answer or {}).get("label") or f"part {index + 1}",
            "optional": bool((answer or {}).get("optional", False)),
            "alternatives": kept,
        })

    return {
        "tones": list(tones),
        "slots": slots,
        "_beats": len(beats),
        "_retried": retried,
        "_reverted": reverted,
    }
