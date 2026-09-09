# Remix

An Android prototype of the mix-and-match rewriting idea: select a message you
already wrote, in any app, and get several phrasings you can combine beat by beat
before sending.

Entry point is `ACTION_PROCESS_TEXT`, so the app appears in the floating
selection toolbar of every app on the phone. No accessibility service, no
overlay permission, no default-SMS role, nothing Play review objects to.

## Running it

1. Open the folder in Android Studio. It will fetch the Gradle wrapper and write
   `sdk.dir` into `local.properties`.
2. Optional, for real rewrites: copy `local.properties.example`, and add your key.

   ```
   ANTHROPIC_API_KEY=sk-ant-...
   ```

   Without a key the app runs `StubVariantGenerator`, which does mechanical
   string edits offline. The grid fills, the interaction works, the writing is
   bad on purpose.
3. Run on a device, then select text in any messaging app and look for **Remix**
   in the selection toolbar, possibly behind the overflow arrow.

`MainActivity` has a playground so you can exercise the screen without leaving
the app. Unit tests cover the assembly and parsing logic:

```powershell
.\gradlew.bat testDebugUnitTest
```

## How the remix works

The obvious design is to generate three rewrites and then align them into
swappable parts. Alignment is where that design dies: one version merges two
sentences, another splits one, and the parts drift out of sync.

So the model is asked for the aligned structure directly. It returns a grid:

| slot | Concise | Warm | Formal |
|---|---|---|---|
| greeting | Hey. | Hey there! | Good afternoon. |
| answer | Friday works. | Friday works great for me. | Friday would suit me well. |
| sign-off | Cheers. | See you then! | Kind regards. |

Reading a column top to bottom gives a coherent whole message, which is the
"three versions" part. Picking a different column per row is the mix-and-match
part. There is no alignment step to get wrong.

The constraint that makes mixing safe lives in `Prompt.SYSTEM`: alternatives may
not depend on the wording of a neighbouring slot or repeat what a neighbour
says. That is the sentence to tune first when a mixed draft reads badly.

`Slot.optional` marks beats the message survives without, and only those get a
Skip control, so the core content cannot be dropped by a stray tap.

## The screen

The message is the interface. Rather than a list of cards plus a separate
preview underneath, which means reading the same message twice, the draft is
shown as flowing prose with each beat highlighted. Tap a beat and its
alternatives open in the panel below; tap a tone chip and the whole message
swaps. What sits in the card is exactly what Replace hands back.

Restoring a beat you left out adopts whatever tone the rest of the message has
settled on, so an old phrasing never reappears inside a draft you have since
retoned. `RemixTest` covers that case.

Design mockups of all four screens, including the two that are not built yet,
are in `design/`. Open `design/remix-concept.html` in a browser: the remix
screen there is clickable. `design/*.dc.html` are the same screens as canvas
artboards.

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
and exposes one endpoint. `ClaudeVariantGenerator` is the only file that talks
to the SDK, and the client builder has a commented-out `.baseUrl(...)` for
exactly this.

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
  `/v1/messages` with OkHttp. `ClaudeVariantGenerator` is the only file to
  rewrite, and moving to a proxy would delete most of it regardless.
- **No structured outputs.** The model is asked for JSON in the prompt and
  `DraftParser` reads it tolerantly. The API can enforce the schema instead, via
  `OutputConfig.builder().format(JsonOutputFormat.builder().schema(...))`. That
  was left out because the exact Java builder shape could not be verified
  without a compiler. Worth doing once the project builds.
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
