[English](README.en.md) | [中文](README.md)

# DSH Watch

Read and drive [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) sessions running on your computer, from a smartwatch.

With DSH running on your PC and the watch connected to it, you can glance at your wrist any time to see how far the AI has got:

- Browse every **session** by **project**, tap in to read the conversation
- Expand **tool calls** to see which files each step read, which commands it ran, and what came back
- **Send messages**, stop generation, scroll back through history
- Answer the AI's questions (single choice, multiple choice, or typed)
- Switch **model** and reasoning effort, check **token** usage and context occupancy

No server on the internet to set up, no changes to any DSH configuration, no DSH plugins.

> Developed on an OPPO Watch (Android 11, 466×466 round screen). The app needs Android 8.0 or later; other Wear OS / Android watches should work in theory, but the UI is designed for a round screen.

## How it works

```
┌─────────────┐        HTTP/JSON         ┌──────────────┐       DSH protocol     ┌─────────┐
│  Watch app  │ ───────────────────────▶ │ Relay server │ ───────────────────────▶ │   DSH   │
│  (Android)  │ ◀─────────────────────── │ (on your PC) │ ◀─────────────────────── │ Desktop │
└─────────────┘   only 10 simple APIs    └──────────────┘   signed cookie + trim  └─────────┘
                          ▲                      │
                          └─ UDP auto-discovery ─┘
```

**Why not talk to DSH directly?** Two hard obstacles:

1. DSH's `/api` only accepts signed cookies it issued itself and bound to its own address, so the watch cannot authenticate on its own;
2. One session's raw response runs to hundreds of KB (streaming fragments and tool diffs), which the watch can neither hold nor needs.

The relay absorbs both: it derives a valid ticket from the local DSH credentials to authenticate, then trims the data down to what the watch actually uses, exposing only a small set of REST endpoints. **It only reads credentials — it changes no configuration and installs no plugins.**

## Download and install

### 1. On the computer (pick one)

**Option A: desktop app (recommended)**

Download `DSH-Relay.exe` from [Releases](https://github.com/DING-HAO-RAN/dsh-watch/releases/latest) and double-click it. Or use the direct link:

```
https://github.com/DING-HAO-RAN/dsh-watch/releases/latest/download/DSH-Relay.exe
```

This is the main form: one file, copy it to any Windows 10/11 machine and it runs — **the target PC needs no .NET, no Node.js, nothing else**. The app finds the DSH instance already running on the machine and shows the pairing code and address in its window.

**Option B: command-line relay (needs Node.js)**

If you don't need a UI, or want to run it on a server, run it straight from source:

```powershell
node relay/src/server.mjs            # LAN only
node relay/src/server.mjs --tunnel   # also open a public tunnel
```

Common flags: `--port 19390` (port the watch connects to), `--dsh-port 19387` (local DSH port), `--state-file <file>` (where the pairing token is stored).

You can also pack it into a single exe with `scripts/pack-relay.ps1` (bundles the Node runtime) — see [building from source](#command-line-relay).

Both options require **DSH desktop to be installed and running** on the computer (the relay reads its login credentials).

> **Public access is an optional extra** and needs `cloudflared`. It is not shipped in Releases (about 50 MB); drop `cloudflared.exe` next to the program (or the relay) to enable it. Without it you only lose public access — LAN keeps working.

### 2. On the watch

Download `DSH-Watch.apk` from [Releases](https://github.com/DING-HAO-RAN/dsh-watch/releases/latest) (direct link: `https://github.com/DING-HAO-RAN/dsh-watch/releases/latest/download/DSH-Watch.apk`), then:

1. Enable "Developer options" and "USB debugging" on the watch (usually by tapping the build number in "About watch" repeatedly);
2. Connect it to the computer with a cable and allow the debugging authorization on the watch;
3. Check that the device is ready:

```powershell
adb devices    # status should be "device", not "unauthorized"
```

4. Install:

```powershell
adb install -r "DSH-Watch.apk"
```

You can also pre-fill the address and pairing code with launch arguments, so you don't have to type on the watch:

```powershell
adb shell am start -n cn.dsh.watch/.MainActivity `
  --es host 192.168.1.100 --ei port 19390 --es code 123456 --ez autopair true
```

`autopair` starts pairing as soon as the fields are filled, so you don't tap anything on the watch.

### 3. Pairing

Two ways; the first is recommended.

**One-tap pairing (easiest)**: on the watch open "Settings" → tap "One-tap pairing" → the watch finds the computer on the same WiFi → a confirmation dialog appears on the computer, tap "Yes" → address and token are imported automatically.

**Manual entry**: the computer window shows a **6-digit pairing code** and **one or more addresses**. On the watch's "Settings":

- **Address**: enter the address shown in the window (on the same WiFi, the "LAN" one; you can also tap "Find LAN automatically" to fill it in)
- **Pairing code**: the 6 digits from the window
- Tap "Pair and connect"

Once paired, the watch remembers it and opens straight to the session list from then on. **Both the pairing code and the token are stored on the computer**, so restarting the relay or even upgrading the program won't change them and you won't have to pair again. To change the code, tap "New pairing code" in the window.

### 4. Switching between devices

The watch remembers every device it has connected to (computer name and address). In "Settings" → "Saved devices", tap an entry to switch to it without pairing again. Public tunnel when you're out, LAN at home, another computer — all in this list.

## Everyday use

**Gestures**: swipe right to go back one level, swipe left to open the action menu.

| What you want | How |
|---|---|
| See your projects | The app opens on the project list; tap a project to see its sessions |
| Read a conversation | Tap a session card; it opens at the newest content |
| See the AI's reasoning | Each reply has "Thinking ▸" above it; tap to read the reasoning behind that reply |
| See each operation | Tool calls between two replies collapse into one group (labelled "N items"); tap once for the list, then tap an entry for its arguments and result |
| Just the conversation | Swipe left → "Conversation only" |
| Send a message | On a session, tap "Send message" → type → "Send" |
| Stop generating | On a session, tap "Stop" |
| Answer a question | While a question is pending, "AI is waiting for your answer" appears; tap it and pick an option |
| Change model | Swipe left → "Model": the current model shows first, then pick provider → model → reasoning effort |
| Check usage | Swipe left → "Usage": token spend, context occupancy, timing |
| Scroll back | Swipe left → "Load older content" |
| Switch computer / device | Swipe left → "Settings": tap a saved device to switch |
| Connect a new computer | On the settings page tap "One-tap pairing", then tap "Yes" on the computer |

## Building from source

### Watch app

Needs **JDK 17+**, **Android SDK platform-35** and **build-tools**.

```powershell
$env:JAVA_HOME            = "C:\path\to\jdk"        # JDK 17 or newer
$env:ANDROID_HOME         = "C:\path\to\android-sdk"
$env:WATCH_BUILD_ROOT     = "D:\build\watch-dsh"    # must be outside the project directory
pwsh -File scripts/build.ps1 -WorkRoot .
```

Produces `dist/DSH手表.apk`. The build drives the SDK toolchain directly (aapt2 → javac → d8 → zipalign → apksigner) and does not use Gradle.

The signing key is generated on first build at `$env:WATCH_BUILD_ROOT/signing/dsh-watch-dev.jks` (default password `android-dev`). **Keep the same key** so you can install over an existing build; for real distribution, use your own key.

`WATCH_BUILD_ROOT` must be outside the project directory — the script refuses to build inside the working tree, which keeps temporary artifacts and private keys out of the repository.

### Desktop app

Only needs the **.NET Framework 4.8 compiler that ships with Windows**; no .NET SDK install:

```powershell
pwsh -File scripts/build-app.ps1 -WorkRoot .
```

Produces `dist/DSH中继/`. The first build fetches the WebView2 managed assemblies from NuGet once and caches them; after that it works offline.

### Command-line relay

Needs **Node.js 22+**:

```powershell
pwsh -File scripts/pack-relay.ps1 -WorkRoot .
```

Produces `dist/中继命令行版/`. It packs the relay and the Node runtime into one exe with Node's SEA (Single Executable Application), so it runs on a machine with no Node installed. The first pack fetches `postject` once over the network and caches it.

> These scripts are invoked with PowerShell 7 (`pwsh`); the Windows PowerShell 5.1 that ships with Windows works too.

## Tests

The relay ships several self-check scripts that hit the same endpoints the watch uses, so they stand in for most on-device tapping:

```powershell
node tests/check-protocol.mjs                 # verify each DSH endpoint's parameter shape (read-only)
node tests/check-features.mjs                 # project grouping, step details, model catalog, usage, paging (read-only)
node tests/e2e.mjs --port 19390 --code 123456 # end to end: pair, list, read messages, send a message
node tests/answer-once.mjs --pick 米饭         # answer the pending question and confirm it leaves the pending list
node tests/send.mjs --session <id> --text "内容"  # send a message from the relay side
```

`e2e.mjs` really posts a timestamped message into a session and confirms it reaches DSH (the send case creates its own blank session, so existing conversations aren't touched).

A few more for troubleshooting:

```powershell
node tests/watch-events.mjs --seconds 15      # watch the DSH event stream (use when a question never shows up)
node tests/probe-shapes.mjs                   # inspect the field shapes DSH actually returns
```

For changes that affect UI behavior, also walk through the real entry points on a device with `adb` (launch → tap a project → tap a session → send a message / answer a question).

## Implementation notes

- **Authentication**: DSH's `/api` only accepts the `dsh-auth-<hash>` cookie it issued. The relay reads the signing secret from `~/.dsh/.credentials.yaml` and signs a ticket for `127.0.0.1:<port>` with the same algorithm, so it can call DSH legitimately without modifying it. The lifetime must not exceed DSH's `cookieMaxAgeDays` (30 days by default).
- **Finding DSH automatically**: the desktop app looks for the `.dsh` config directory in this order — location of the running DSH process → user home → common install paths → other drive letters — and probes a few common ports; both have to match before it reports success. When it fails it says why (not installed / not running / credentials unreadable).
- **Questions run on two channels**: a question still waiting (`open`) is delivered only over the WebSocket `$events` stream and **carries no callId**, so it has to be answered through `$events/result` keyed by `eventId`; a question that has timed out and been suspended (`continued`) goes through the `userQuestions/answer` RPC. The relay picks the channel itself.
- **Recovering missed questions**: DSH replays all pending events when a new client attaches to the event stream, so when "a session is running but no question appears", the relay reconnects the stream once to fill the gap.
- **Trimming**: the relay keeps only human messages, assistant text and tool steps, truncates long text, and only then hands it to the watch. Session events carry a lot of system-injected `user/message` entries (time context, memory snapshots, instructions); only those whose `source.kind` is `user` / `user-question-reply` are real human messages.
- **LAN discovery**: every 2 seconds the relay broadcasts a JSON line (`{service, port, name}`) to UDP port 19391; the watch fills in the address as soon as it hears one. When broadcast is unreliable you can still type the address. UDP broadcast rather than mDNS, so the watch pulls in no dependencies.
- **Single-file distribution**: the desktop app embeds both the WebView2 managed assemblies and the native loader into the exe — the managed assemblies are **loaded from memory** at startup, the native loader is written to the program directory. Distribution is one file, and nothing piles up in the user's config directory.
- **Round screen**: padding comes from the circle's inscribed rectangle (15% left and right, 18% top and bottom); the corners sit about 0.474 from the center against a radius of 0.5, so the whole scroll area stays inside the circle and no page needs its own margins.
- **The UI always works**: the desktop app has two UI layers — a web console in an embedded browser (nicer, denser) and a pure WinForms native UI (zero dependencies). The first needs the WebView2 runtime, which a trimmed-down Windows install may lack; the app then falls back to the second, and the pairing code and address still show.
- **Sharp on HiDPI**: the app ships a DPI awareness manifest (PerMonitor V2), so it renders at true resolution on screens scaled to 125%/150%/200% — text and borders stay crisp. The initial window size is derived from the current display's working area, so it never overflows a small screen or looks empty on a large one.

## Project layout

```
.
├── app/                       watch app (Android, native Java)
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/cn/dsh/watch/
│       │   ├── MainActivity.java    all pages and interaction logic
│       │   ├── WatchUi.java         round-screen widgets and safe area
│       │   ├── Markdown.java        rich-text rendering for text and tool results
│       │   ├── Api.java             HTTP client
│       │   ├── Store.java           address and token storage
│       │   └── Discovery.java       UDP auto-discovery
│       └── res/
├── desktop/                   desktop app (C#, single-file exe)
│   ├── src/
│   │   ├── MainForm.cs         window, embedded browser, initial size
│   │   ├── NativePanel.cs      zero-dependency native UI (fallback target)
│   │   ├── panel.html          web console
│   │   ├── app.manifest        DPI awareness manifest (sharp on HiDPI)
│   │   ├── RelayServer.cs      relay service
│   │   ├── DshClient.cs        DSH authentication and RPC
│   │   ├── DshLocator.cs       locate local DSH and credentials
│   │   ├── Steps.cs            session data trimming
│   │   ├── PublicTunnel.cs     cloudflared public tunnel
│   │   ├── Bootstrap.cs        loading embedded dependencies
│   │   └── StartupLog.cs       startup log
│   └── 使用说明.txt            instructions shipped with the package
├── relay/                     command-line relay (Node, SEA packaged)
│   ├── src/
│   │   ├── server.mjs          entry point and argument parsing
│   │   ├── relay.mjs           REST API for the watch + UDP broadcast
│   │   ├── dsh.mjs             DSH protocol and authentication
│   │   ├── state.mjs           trimming and normalizing DSH data
│   │   ├── tunnel.mjs          public tunnel
│   │   └── paths.mjs           own directory (packaging compatibility)
│   ├── public/index.html       control panel
│   └── start-relay.cmd         double-click to start
├── scripts/                   build scripts
│   ├── build.ps1               build the watch APK
│   ├── build-app.ps1           build the desktop app
│   ├── pack-relay.ps1          package the command-line relay
│   └── bundle-relay.mjs        merge relay sources into one file
└── tests/                     self-check scripts
```

## Known limits

- The relay serves the LAN over **plain HTTP** (public access uses HTTPS) and relies on the 6-digit pairing code for protection. Run it only on a network you trust, and close it when you're done.
- The watch shows at most 40 messages / 60 sessions at a time; older history loads in batches through "Load older content".
- The relay needs DSH desktop running on the computer; **close the relay window and the watch is disconnected**.
- The public address is a temporary cloudflared tunnel and changes on every start; for a fixed address you need your own domain or reverse proxy.
- **Cloudflare rate-limits unregistered tunnels**, so toggling one on and off repeatedly in a short window returns a rate-limit error. Wait a few minutes and try again; LAN is unaffected.
- The watch accepts all certificates over HTTPS — there's no convenient way to install a certificate on a watch, so access control rests on the pairing token.
- The desktop app requires Windows 10 1903+ or Windows 11; older systems ship a .NET Framework below 4.8, which you have to install yourself.

## FAQ

**It says "DSH not found"?**
Start DSH desktop first, then reopen the relay. The relay needs its credentials, so it can't read them while DSH isn't running.

**The watch keeps spinning and never connects?**
Check that the watch and the computer are on the same WiFi, and use the "LAN" address (something like `http://192.168.1.x:19390`) rather than `127.0.0.1` — that points at the watch itself. Also check that the computer's firewall isn't blocking port 19390.

**Does the pairing code change?**
No. The pairing code and token are stored on the computer, so restarting the relay or upgrading the program won't change them and the watch never needs re-pairing. Only tapping "New pairing code" changes it.

**One-tap pairing can't find the computer?**
Check that the watch and the computer are on the same WiFi. Windows Firewall may be blocking UDP 19391 — allow it, or just enter the address and pairing code by hand; the result is the same.

**Does the relay modify my DSH configuration?**
No. It only reads DSH's login credentials for local communication; it changes no configuration file and installs no plugin.

**Can the watch still connect after I close the window?**
No. The relay runs inside that window; closing it stops the relay.

**The desktop app won't open or closes immediately?**
`startup.log` in the program directory records the whole startup (DSH lookup, ports, dependency extraction, exception stack) — that's the first thing to read when troubleshooting.

## License

[MIT](LICENSE). Free to use, modify and distribute, including commercially; just keep the copyright notice.
