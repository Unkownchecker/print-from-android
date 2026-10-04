# LAN Print for Android

Turns an Android phone with a USB-OTG cable into a print server for an
HP LaserJet P1005 / P1006 / P1007 / P1008 / P1505 / P1505n, **or** an
HP LaserJet 1020 / 1020 Plus — the same role the Windows desktop app plays, but driving the
printer directly over USB instead of through a PC. Talks to the same relay
server and pairing-ID system as the desktop app and phone web app, so
nothing else in your setup needs to change.

**These are two genuinely different printer-language protocols under the
hood** (XQX for the P1000/P1500 series, ZJ-stream/"ZJS" for the 1020
family) — the app picks the right one automatically once it identifies
your exact model, but it's worth knowing they're not the same thing
wearing a different hat, in case something works for one family and not
the other.

**Protocol selection:** P1007 is sent through the XQX converter and uses
the P1005 firmware. The 1020 and 1020 Plus are sent through the
Zenographics ZJ-stream converter (`foo2zjs`) and use the 1020 firmware.
The 1020 profile matches USB ID `03F0:2B17` or a reported device name
containing `1020`.

## Before you start: get the printer's firmware file

**This is not optional — printing will not work at all without it.** These
printers have no persistent firmware storage; a firmware blob has to be
uploaded to them fresh every time they're power-cycled or reconnected. This
app has a "Load printer firmware file" button for exactly this, but you
need to supply the file yourself — it's HP's own proprietary firmware, not
something this app (or its GPL-licensed printing code) can legally bundle.

**Important: which file to use depends on your model, and it's not always
the obvious one:**

| Your printer | Firmware file you need |
|---|---|
| P1005 | `sihpP1005.dl` |
| P1006 | `sihpP1006.dl` |
| **P1007** | **`sihpP1005.dl`** (same hardware as the P1005, different USB ID) |
| **P1008** | **`sihpP1006.dl`** (same hardware as the P1006, different USB ID) |
| P1505 | `sihpP1505.dl` |
| P1505n | `sihpP1505n.dl` |
| 1020 / 1020 Plus | `sihp1020.dl` |

The original `foo2zjs.rkkda.com/firmware/` mirror is currently down. Use
this GitHub mirror instead, which bundles the same files directly in the
repo: `https://github.com/koenkooi/foo2zjs` — look for `sihpP1005.img`,
`sihpP1006.img`, `sihpP1505.img`, and `sihp1020.img` at the repo root
(there's no separate P1505n file in that mirror — see the note below if
that's your model). Download the raw `.img` file for your model from the
table above — I've verified each of these four actually convert correctly
through this app's firmware loader, byte-for-byte against the original
`arm2hpdl` tool.

Then, in the app, plug in your printer, wait for it to connect, and tap
**"Load printer firmware file"** to pick either the raw `.img` file or an
already converted `.dl` file. For an HP LaserJet 1020 / 1020 Plus, the
NokoPrint data package contains `sihp1020.dl`; select that file directly
(copy it to the phone's Downloads folder first if it is on a computer).
This `.dl` file is already in HP's PJL/ACL download format and is sent as-is.
Raw images are converted by the app using foo2zjs's `arm2hpdl` tool.

**P1505n note:** that mirror only has a plain `sihpP1505.img`, not a
separate `sihpP1505n.img` that the driver's own scripts expect for that
specific model. I haven't been able to confirm whether the P1505n
genuinely needs different firmware or can use the plain P1505 file — if
you have this exact model, try the P1505 file first and let me know
whether it works.

You'll need to reload it any time the printer loses power (unplugged,
turned off, etc.) — the app tracks this per-connection and will tell you if
firmware hasn't been sent yet.

If the app says it received no `FWVER` response, that does **not** mean the
firmware file was rejected: it means the printer did not provide its optional
firmware-version string over USB. The app still allows printing after a full
USB transfer; send a small test page to check whether the printer is ready.

## Building the APK (via GitHub Actions — no Android Studio needed)

1. Create a new GitHub repository and push this entire folder to it.
2. GitHub Actions will automatically build a debug APK on every push (see
   `.github/workflows/build.yml`) — check the **Actions** tab on your repo,
   open the latest run, and download the `lan-print-debug-apk` artifact
   once it finishes (a few minutes).
3. Unzip that artifact to get `app-debug.apk`.
4. Transfer it to your phone and install it — you'll need to enable
   "Install unknown apps" for whichever app you use to open it (Settings
   will prompt you the first time).

If you'd rather build locally with Android Studio instead, open this folder
as a project — it's a normal Gradle/Android project — and run/build as
usual. `./gradlew assembleDebug` also works directly if you have Android's
SDK + NDK already installed.

## Using it

1. Plug the printer into your phone via a USB-OTG adapter/cable, then open
   the app (or let it prompt you when the printer is plugged in).
2. Grant USB permission when asked.
3. Load the printer's firmware file (see above) — the app will tell you if
   this hasn't been done yet.
4. For same-network printing: nothing else needed — the phone web app's
   local-network mode should be able to reach... actually, this app is
   itself the print server, not a phone browsing to one — see "How the
   phone web app connects to this" below.
5. For printing from anywhere: paste your relay's URL into "Print from
   anywhere" and note the pairing ID, exactly like the desktop app.

### How the phone web app connects to this

This app doesn't run a local HTTP server the way the Windows app does (a
phone can't easily browse to "another phone's" address the same way it
browses to a PC on the same Wi-Fi). Realistically, this app is most useful
in **"Anywhere" (relay) mode**: any device with the phone web app — a
different phone, a laptop, whatever — enters your relay URL and this app's
pairing ID, exactly as if it were pairing with the Windows desktop app. If
you want the phone running this print-server app to also be reachable by
name on the local network the way the PC is, that would need a local HTTP
server added to this app too — not included here, since the relay path
already covers the "print from any device" need.

## What this does and doesn't support yet

- **PDF and common image files.** Android-supported JPEG, PNG, GIF, BMP, and
  WebP images are converted to a one-page PDF for printing. Other document
  formats (such as Word files) must first be exported to PDF.
- **Print options:** A4 is the default paper size; the relay print options
  also accept `orientation: "landscape"`, `scale` or `scalePercent` from 10
  to 200 (100 fits the page), and `pageRange` such as `"1-3,5"` or `"all"`.
  `side: "manual"` (or a two-sided/duplex `side` value) starts manual
  two-sided printing; these printers do not have a hardware duplexer.
- **One printer at a time** — whichever one is currently plugged in and
  connected.
- **No hardware duplex.** These printers don't have one over USB. Manual
  duplex (print odd pages, flip the stack, print even pages) works exactly
  like the desktop app's version, including over the relay.
- Runs as a foreground Service with a persistent notification so Android
  doesn't kill it in the background — you'll also be prompted to exempt it
  from battery optimization, which matters if you want it reliably
  reachable from "anywhere" while you're not actively looking at the phone.

## Licensing — read this before distributing the APK to anyone else

The actual printer-language conversion (`foo2xqx.c`, `foo2zjs.c`, the JBIG
compression code, and `arm2hpdl.c` for firmware conversion) is taken from
the **foo2zjs** project and is licensed **GPL-2.0-or-later** (see `LICENSE`
in this repo — extracted from foo2zjs's own `COPYING` file). Because that code is compiled directly into this app's
native library rather than run as a separate process, the resulting APK is
a combined/derivative work, and **the GPL's terms most likely extend to the
whole app**, not just the C portion.

Practically, this means:
- **Using it yourself** (sideloading, personal use): no issue at all.
- **Giving the APK to someone else, or publishing it anywhere** (a repo
  release, an app store, a forum): you should make this project's complete
  source available to whoever receives it (which this repository already
  does, if you keep it public) and keep the GPL notice intact. Don't strip
  the license or distribute a closed/obfuscated build.

None of HP's proprietary firmware is included in this repository — only
the open-source driver code that talks to the printer once it's already
running that firmware.

## What's actually been verified, and what hasn't

To be precise about confidence levels, since this project involves porting
driver code I can't run on real hardware myself:

- **Verified, byte-for-byte, against the real reference tools**: the
  foo2xqx (XQX) conversion, the foo2zjs (ZJ-stream) conversion, and the
  arm2hpdl firmware conversion — all tested here against the actual
  foo2zjs source and real firmware files, across multiple page counts,
  paper sizes, and error conditions, run repeatedly in one process to
  confirm no state leaks between jobs.
- **Not yet verified**: that the Kotlin/Android layer (JNI bindings, USB
  handling, the Gradle/NDK build itself) actually compiles and runs on a
  real device — I don't have the Android SDK or real hardware available to
  test that part. The GitHub Actions build is the first real compile test;
  if it fails, the error output will say why, and I can fix it from that.
  Actual USB communication with a real printer is also unverified until
  you try it — the conversion logic producing correct bytes is necessary
  but not sufficient for that last step to work.
