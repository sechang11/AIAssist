# Remix

An Android prototype of the mix-and-match rewriting idea: select a message you
already wrote, in any app, and get several phrasings you can combine beat by beat
before sending.

Two ways in: the selection toolbar of every app, via `ACTION_PROCESS_TEXT`,
and a floating chat head. No accessibility service and no default-SMS role,
so nothing Play review objects to.

## What measurement actually showed

Two things, and the second one reverses the first.

**Restructuring the task beat every other change.** The original design asked
one call to segment a message, paraphrase every beat, keep each cell
independent and preserve every fact. That is a bookkeeping problem, and it is
what small models cannot hold: their output stayed fluent and well-formed while
the content went wrong. Moving the bookkeeping into code took a 1.5B from 10%
to 58% on the mechanical score, a fivefold improvement with no change of model.

**Then the mechanical score turned out to be measuring the wrong thing.** It
sees structure and hard facts. It cannot see meaning. So it scored as perfect a
rewrite that turned "sorry i cant make saturday, ive got my sisters wedding"
into "i hope you have a fantastic time at your sister's wedding", handing the
writer's own excuse to the reader.

Adding a semantic judge, which reads each rewrite against the original and asks
only whether the commitment, the facts and the roles survive, gives a different
and strictly size-ordered answer:

| model | size | mechanical | faithful |
|---|---|---|---|
| Qwen 0.5B | 397 MB | 14% | 63% |
| Qwen 0.5B fine-tuned | 397 MB | **66%** | **59%** |
| Qwen 1.5B | 986 MB | 58% | 70% |
| Qwen 1.5B fine-tuned | 986 MB | 60% | 66% |
| Qwen 7B | 4.7 GB | 72% | 75% |
| Qwen 14B | 9.0 GB | 72% | **81%** |

Fine-tuning raised the mechanical score and lowered faithfulness, at both
sizes. It taught the form of a good answer at the cost of the substance, which
is Goodhart's law arriving on schedule: the metric rewarded exactly what
training optimised and was blind to what that cost.

So the earlier conclusion here, that a ceiling near 74% exists and model size
does not move it, was an artefact of the instrument. Size does buy fidelity,
monotonically. Worth one caveat: the judge is a 14B scoring output that
includes its own, so its top position may be self-preference. The trend across
the three models it did not produce is consistent regardless.

The practical read is that nothing here is ready for messages that take a
position, and the honest next step is a better judge rather than a better
score. `eval/` has the harness and the reasoning.

## Running it

1. Open the folder in Android Studio. It will fetch the Gradle wrapper and write
   `sdk.dir` into `local.properties`.
2. Optional, for real rewrites: copy `local.properties.example`, and add your key.

   ```
   ANTHROPIC_API_KEY=sk-ant-...
   ```

   Without a key the app runs `StubBeatRewriter`, which does mechanical string
   edits offline. The grid fills, the interaction works, the writing is bad on
   purpose.
3. Run on a device, then select text in any messaging app and look for **Remix**
   in the selection toolbar, possibly behind the overflow arrow.

`MainActivity` has a playground so you can exercise the screen without leaving
the app. Unit tests cover the splitter, the fact guarantee, assembly, parsing
and the rebasing that keeps your picks while later beats stream in:

```powershell
.\gradlew.bat testDebugUnitTest
```

## How the remix works

The obvious design is one call that returns the whole grid: rows are beats,
columns are tones. That is what this started as, and measurement killed it.

`eval/` runs twenty deliberately awkward messages through a model and scores
what comes back. Against Qwen 1.5B, the size a phone can carry, the one-shot
grid scored 5% clean. The output was never bad English; validity was 100% while
the content was wrong. Asking one pass to segment, paraphrase, keep every cell
independent and preserve every fact is a bookkeeping problem, and the
bookkeeping is what a small model cannot hold.

So the bookkeeping moved into code, and the same model scored 65%.

`BeatSplitter` cuts the message into beats with punctuation and length alone. No
model, so it cannot hallucinate a beat or drop a clause, and five of the twenty
test messages are correctly a single beat. `GridBuilder` then asks for three
phrasings of one short fragment at a time, which is the job small models are
actually good at.

Two failure modes now go by construction rather than by instruction. Beats
cannot repeat each other, because a beat never sees its neighbours' output;
that measure went from 15% to 95%. And a lossy beat is cheap to retry alone
rather than discarding the whole message.

Then the guarantee: if a rewrite loses a date, time, number or name that was in
the fragment, it is not shown at all and that beat falls back to the writer's
own words. Losing the retoning on one beat is a far smaller harm than losing
the time they agreed to meet, and an unchanged beat is visibly unchanged.

Beats stream to the screen as they land, which the interface already suited,
since they are independent by design.

`eval/pipeline.py` and `BeatSplitter`/`GridBuilder` are the same algorithm in
two languages. If you change one, change the other, or the eval numbers stop
predicting what the app does.

`Slot.optional` marks beats the message survives without, and only those get a
Skip control, so the core content cannot be dropped by a stray tap.

## How it lives on the phone

Two ways in, and they answer different needs.

**The selection toolbar** (`ProcessTextActivity`) is for text you wrote. Select
it, tap Remix, and the reworked version replaces it in place. No permission, no
pasting.

**The bubble** (`bubble/`) is a chat head, in the Messenger sense: a circle
floating over every app, for text you did not write. Copy the message, tap the
bubble, pick your wording, and it goes back on the clipboard. It needs the
draw-over-other-apps permission, which the user grants in a system settings
screen, and it runs a foreground service with a permanent notification because
an overlay cannot outlive a background process.

Everything that makes a chat head feel like one is hand-written in `ChatHead`,
because the framework offers none of it. A window position is two integers in
`WindowManager.LayoutParams`, so a spring animates a float and writes those
integers every frame:

- Drag it anywhere; let go and it flies to the nearest edge, with the fling
  velocity deciding which edge wins and seeding the spring.
- Start dragging and a target appears at the bottom. Get close and it swells and
  takes the bubble magnetically; drop it there to put the bubble away.
- Leave it alone and it fades back so it stops competing with what is underneath.
  Touching it wakes it.
- Vertical travel is clamped, so it cannot be lost behind the status bar or under
  the navigation bar.

Opened, it is a bottom sheet rather than a speech balloon hanging off the
circle, because the remix panel needs most of the width. Back collapses it, and
so does tapping the app above it. Taps below it are the keyboard, which must not
close the panel it is serving.

### The bubble cannot see the app behind it

This is the constraint that shapes everything. Nothing in the public SDK lets
one app read another's screen. Four ways to feed it, cheapest first:

| Route | Cost | What it gets you |
|---|---|---|
| Clipboard | Nothing | Whatever the user copied. Costs one extra gesture. |
| Notification listener | One permission, declarable to Play | Incoming messages as they arrive. Not your own draft. |
| Screen capture and OCR | Consent per session, a cast icon | Anything on screen, lossy and slow, with no idea who said what. |
| Accessibility service | Play will pull the listing | Everything, perfectly. |

The clipboard route is what is built, behind `TextSource`, because it is the
only one that ships. The clipboard is readable since Android 10 only by the app
holding input focus, so `BubbleWindow` makes its window focusable on open and
waits for focus to actually land before reading. If a device refuses that read
the panel falls back to a paste field, so the bubble is never a dead end.

An accessibility-backed `TextSource` is roughly a hundred lines and would make
the bubble read the foreground app directly. Fine for a build you sideload onto
your own phone; do not put it on the Play Store. Google restricts the
accessibility API to apps genuinely serving users with disabilities and enforces
it, and Android 13 and later make sideloaded apps clear an extra restricted
setting before it can even be switched on.

Worth knowing: Android's own `Notification.BubbleMetadata` API, despite the
name, is not this. It only bubbles conversation-style notifications from a
messaging app, so a utility overlay has to use `SYSTEM_ALERT_WINDOW`.

## Before you ship any of this

**The API key is compiled into the APK.** `BuildConfig` is not a secret store;
anyone can pull the key out of the artifact. That is acceptable for a debug
build on your own phone and unacceptable the moment someone else installs it.

The fix is a server you own that holds the key, applies your own rate limits,
and exposes one endpoint. `ClaudeBeatRewriter` is the only file that talks to
the SDK, and the client builder has a commented-out `.baseUrl(...)` for exactly
this. Swapping in an open model, hosted or on-device, means writing one more
`BeatRewriter` and nothing else.

Worth deciding early, because it shapes the privacy story: this app reads
people's private messages. Either say plainly that drafts go to a server, or
move to an on-device model and say nothing leaves the phone.

## What has actually been run

Debug and release both build, and 33 unit tests pass. That happened on a Fedora
box with a hand-built toolchain, not in Android Studio, so treat the IDE as
unverified rather than the code.

**Never run on a phone.** Everything below the compiler is untested: whether the
chat head drags properly, whether an overlay window can really read the
clipboard once focused, how the streaming beats feel, whether the tap-to-offset
mapping on the message picks the right beat. All of that needs a device.

## Known gaps

- **The Claude path cannot use the SDK's schema-derived structured outputs.**
  `anthropic-java` bundles victools' schema generator, which reflects over
  `java.lang.reflect.AnnotatedType`. Android has no such class at any API level.
  R8 fails the release build without the `-dontwarn` rules now in
  `proguard-rules.pro`, and suppressing them is only safe because nothing calls
  that path. The class-based `outputConfig(SomeClass.class)` overload would
  throw on a real phone. Send a schema as JSON or keep parsing text.

  This matters, because schema-constrained decoding was worth about sixty points
  of validity on the Ollama side of the eval. The Claude path does not get it
  for free.
- **Split mode costs more on a paid API than the one-shot grid did.** One call
  per beat means the system prompt is resent every time and thinking happens
  every time, so a four-beat message is four to eight calls rather than one.
  Roughly triple the old per-message estimate. The architecture was chosen for
  a local model where per-call cost is zero, and it is the right shape there;
  on the Claude path it is a real regression. Prompt caching does not save you,
  because the system prompt sits below the minimum cacheable prefix.
- **Beats are fetched sequentially, though they are independent.** On device
  that is correct, since there is one accelerator. Against an API it means a
  four-beat message waits four round trips when it could wait one. Worth
  parallelising if the API path survives.
- **No refusal handling.** An empty response is reported as "the model returned
  no text" rather than inspecting `stop_reason`. Server-side fallbacks are also
  not wired up.
- **The assembled message is not editable.** People will want one last manual
  tweak before sending.
- **Real latency is unmeasured.** On a 5090 a beat comes back in roughly half a
  second, but that says nothing about a phone or a round trip to an API.
- **The debug APK is 17MB**, most of it the Anthropic SDK, Jackson and Compose.
  An on-device model would replace the first two and add a much larger download
  of its own.

## Where this goes next

1. **A keyboard.** `InputMethodService` beats the bubble for anything you are
   writing yourself: it reads the compose field through `InputConnection` with no
   permission at all, puts the grid above the keys with no app switch, and can
   type the result straight in rather than handing you a clipboard. The bubble's
   real territory is text you did not write.
2. **Incoming-message context.** `NotificationListenerService` would let the
   bubble already know the message you are replying to before you tap it, which
   removes the copy gesture for the case that matters most. It needs its own
   permission and a Play declaration, but unlike the accessibility route it is
   one you can actually get.
3. **A trigger better than a permanent circle.** A bubble parked over every app
   is a lot of screen furniture for something used a few times a day. The
   notification listener could summon it only when a message arrives, and it
   could hide itself in apps you never write in.
