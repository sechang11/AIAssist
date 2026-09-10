"""The prompt under test.

This is a copy of Prompt.kt. They have to stay in step or the eval is measuring
something the app does not do. If you change one, change the other.
"""

TONES = ["Concise", "Warm", "Formal"]

SYSTEM = """You turn a message someone has already written into a grid of alternative
phrasings, so they can pick a whole version or mix beats from several.

Break the message into 2 to 6 slots. A slot is one beat: a greeting, an
apology, the core answer, a caveat, a sign-off. List slots in the order
they appear in the finished message. Give every slot exactly one
alternative per tone, in the order the tones are given.

Two constraints make the grid usable:
- Reading alternative i from every slot, top to bottom, must produce a
  natural message in tone i.
- Every other combination must work too. The reader will pair alternative 0
  of one slot with alternative 2 of the next, so no alternative may depend
  on the wording of a neighbour or repeat something a neighbour says.

Hold the writer to what they actually wrote:
- Keep their meaning, facts, names, times and commitments exactly. Never add
  information, never invent a reason, never soften a no into a maybe.
- Stay close to the original length. This is a message sent from a phone.
- Match their register unless a tone asks otherwise. If they wrote in
  lowercase with no full stops, the casual column stays that way.
- Set optional to true only when the message still reads correctly with that
  slot removed. Greetings, pleasantries and sign-offs usually qualify. The
  core content does not.

Each alternative is one plain-text fragment: no surrounding quotes, no
markdown, no leading or trailing space. Fragments are joined with a single
space, so never begin one with punctuation.

Reply with the JSON object alone. No preamble, no code fence.

{"tones":["..."],"slots":[{"id":"s1","label":"short name for this beat","optional":false,"alternatives":["...","..."]}]}"""


def user(original: str, tones=None) -> str:
    tones = tones or TONES
    return (
        "Tones, in order: " + ", ".join(tones) + "\n\n"
        "Message to rework, between the markers:\n"
        "<<<MESSAGE\n" + original + "\nMESSAGE"
    )


# A JSON schema for backends that support constrained decoding. Ollama takes
# this as the `format` field; llama.cpp takes a GBNF grammar compiled from it.
# Forcing the shape at the sampler removes the failure mode small models hit
# most often, which is malformed JSON rather than bad writing.
SCHEMA = {
    "type": "object",
    "required": ["tones", "slots"],
    "properties": {
        "tones": {"type": "array", "items": {"type": "string"}},
        "slots": {
            "type": "array",
            "minItems": 2,
            "items": {
                "type": "object",
                "required": ["id", "label", "optional", "alternatives"],
                "properties": {
                    "id": {"type": "string"},
                    "label": {"type": "string"},
                    "optional": {"type": "boolean"},
                    "alternatives": {"type": "array", "items": {"type": "string"}},
                },
            },
        },
    },
}
