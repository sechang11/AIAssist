"""The prompt under test.

Keep this in step with Prompt.kt or the eval stops measuring what the app does.

Three rounds against Qwen 1.5B and 7B, each fixing something the eval exposed:

  v1  The schema example wrote {"tones":["..."]} and the 7B copied the literal
      "..." into its answer. And `alternatives` was unbounded, so the 1.5B
      returned one alternative per slot: a segmentation, not a grid.

  v2  Shape fixed by pinning minItems and maxItems, and by not asking for the
      tone names back. Validity went to 100% for both. But the models then
      copied the *content* of the new example: nearly every answer opened
      "hey" / "hi there" / "Good morning", verbatim from the sample, then
      padded with apology and sign-off filler while deleting the actual
      message. A request to move a meeting from 6 to 7:30 came back as "hey
      sure thing just in case thanks".

  v3  No example at all. The schema already enforces shape, so the example was
      only ever offering content to plagiarise. Prompt cut to roughly a third,
      because small models drown in rules, and pointed hard at the two observed
      failures: keep the writer's own words, and invent no beats.
"""

TONES = ["Concise", "Warm", "Formal"]


def system(tones=None) -> str:
    tones = tones or TONES
    numbered = "; ".join(f"alternative {i + 1} is {t.lower()}" for i, t in enumerate(tones))
    return f"""You rewrite one short personal message into alternative phrasings.

Split it into 2 to 6 slots. A slot is one beat of the message. Keep them in the
order the writer wrote them.

Give every slot exactly {len(tones)} alternatives: {numbered}.

Reuse the writer's own words wherever you can. Every name, date, time, number
and place in their message must appear in all {len(tones)} alternatives of the slot
that carries it.

Invent nothing. Do not add a greeting, apology, pleasantry, reassurance or
sign-off that is not already in their message. If they wrote two beats, return
two slots.

Never turn a no into a maybe, or a maybe into a yes. Keep every condition.

Match their register. Lowercase with no full stops stays lowercase with no full
stops.

Alternatives within a slot must differ from each other, and must not repeat what
a neighbouring slot says. They are joined with a single space, so start none of
them with punctuation.

Mark a slot optional only if deleting it still leaves a correct message."""


def user(original: str, tones=None) -> str:
    tones = tones or TONES
    return (
        "Message:\n<<<\n" + original + "\n>>>\n\n"
        "Split the message above into slots, using its own words. "
        "Tones in order: " + ", ".join(tones) + "."
    )


def schema(tones=None):
    """Constrained decoding. Ollama takes this as `format`; llama.cpp compiles
    an equivalent GBNF grammar. Pinning the alternatives count is what stops a
    small model returning a segmentation instead of a grid, and it means the
    prompt needs no example, which is what stopped them copying one."""
    n = len(tones or TONES)
    return {
        "type": "object",
        "required": ["slots"],
        "properties": {
            "slots": {
                "type": "array",
                "minItems": 2,
                "maxItems": 6,
                "items": {
                    "type": "object",
                    "required": ["id", "label", "optional", "alternatives"],
                    "properties": {
                        "id": {"type": "string"},
                        "label": {"type": "string"},
                        "optional": {"type": "boolean"},
                        "alternatives": {
                            "type": "array",
                            "minItems": n,
                            "maxItems": n,
                            "items": {"type": "string"},
                        },
                    },
                },
            },
        },
    }


SYSTEM = system()
SCHEMA = schema()
