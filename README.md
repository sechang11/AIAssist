# Remix

An Android prototype of the mix-and-match rewriting idea: select a message you
already wrote, in any app, and get several phrasings you can combine beat by beat
before sending.

Two ways in: the selection toolbar of every app, via `ACTION_PROCESS_TEXT`,
and a floating chat head. No accessibility service and no default-SMS role,
so nothing Play review objects to.

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

## Known gaps

- **Nothing here is compiled.** It was written on a machine with no JDK, no
  Android SDK and no Gradle, so the first build will surface real errors. The
  pure-Kotlin core is covered by tests; the Android and SDK wiring is not.
- **`anthropic-java` is unproven on Android.** It is a JVM SDK that pulls in
  Jackson and OkHttp. `minSdk` is 26 with core library desugaring on, and
  `proguard-rules.pro` has keep rules, which should be enough. If it fights the
  build anyway, delete the dependency and hand-roll the one POST to
  `/v1/messages` with OkHttp. `ClaudeBeatRewriter` is the only file to rewrite,
  and moving to a proxy or an open model would replace it regardless.
- **No structured outputs on the Claude path.** The model is asked for JSON in
  the prompt and `BeatParser` reads it tolerantly. The API can enforce a schema
  instead, via `OutputConfig.builder().format(JsonOutputFormat.builder()...)`,
  which was left out because the exact Java builder shape could not be verified
  without a compiler. The eval's Ollama path already uses schema-constrained
  decoding, and it was worth roughly sixty points of validity there, so this is
  the highest-value thing to add once the project builds.
- **No refusal handling.** An empty response is reported as "the model returned
  no text" rather than inspecting `stop_reason`. Server-side fallbacks are also
  not wired up.
- **The preview is not editable.** People will want one last manual tweak before
  sending.
- **Latency is untested.** `effort` is set to `LOW` for this reason, and it is
  the first knob to turn. If a round trip feels slow next to a keyboard, that is
  the argument for an on-device model.

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
