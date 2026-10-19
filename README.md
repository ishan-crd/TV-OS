# TV OS

**A clean home screen for Android TV and Google TV.** No ads, no sponsored rows, no telemetry.

TV OS is a small native launcher (plain Java, about 370 KB) that replaces the stock Google TV home
screen. It is installed over Wi-Fi with ADB; no root, nothing uninstalled, and the stock launcher comes
back with one command.

It grew out of [tv-vibehack](https://github.com/Gathin23/tv-vibehack) by Gathin (MIT), whose launcher,
install tools and debloat approach it builds on. The home screen design is inspired by
[Rivulet](https://github.com/l984-451/Rivulet)'s Apple TV app, and a few features by
[Arc Launcher](https://github.com/meddouribadis/arclauncher) (ideas only; no code from either).

## What it looks like

**Hero home** (default):

- **Featured carousel** of your four dock apps over full-screen ambient artwork in each app's own colour,
  with the app's name, a line about it, an "App · Video · TODAY" line, a white **Open** button, info and
  next buttons, and page dots. It advances every 9 seconds.
- **This week's artwork**: Netflix, Prime Video and JioHotstar show a still from what's popular on that
  service in India this week ("Popular this week: …"), from TMDB's catalogue of what each service
  offers; refreshed weekly and kept on the TV, so the home never waits on the network. Needs a free TMDB
  key (themoviedb.org → Settings → API) as `tmdb.key=<key>` in `launcher/local.properties` (git-ignored);
  apps and region are in `weekly_art` in config.xml. Without a key, apps keep their colour glow.
- **Rows** that slide up when you press Down:
  - **Your Apps**: the apps you pick, in your order.
  - **Continue Watching**: the films and episodes you're part-way through, with posters and a progress
    bar, from Android TV's Watch Next list; OK resumes it in its app. Focusing one puts its poster full
    screen behind the rows. Which apps report there is up to them: on Google TV, JioHotstar and Prime
    Video do, Netflix and YouTube don't. Without anything reported, the row lists the video apps you used
    in the last week.
  - **All Apps**.
- **Long-press any card** to edit from the sofa: add to or remove from Your Apps, move left or right, add
  to or remove from the featured carousel, resume a show, or open the app's info.
- **Quick settings panel** on the remote's Settings button, laid out like Google TV's: date and time,
  **All settings**, and tiles for Wi-Fi (with the network name), Bluetooth (Remotes & Accessories),
  Picture, Sound, Screensaver, Inputs, Display mode, Accessibility, AirPlay and Running apps. It slides in from the right over
  whatever is playing, and nothing of it runs while it's closed. Bluetooth opens Remotes &
  Accessories directly, as Google TV's panel does: Settings only shows that page to holders of
  `WRITE_SECURE_SETTINGS`, which the install script grants over adb (without it, the remote-key service
  selects the entry inside Settings). Pages open in a fresh task, so one Back returns home.
  **Wi-Fi** opens the Wi-Fi page in Settings. (A network list inside the panel is built in but off:
  `WIFI_LIST_IN_PANEL` in QuickPanelActivity.)
- **AirPlay** tile: switches an AirPlay receiver on and off ("On · Bedroom" / "Off") so an iPhone, iPad or
  Mac can mirror to the TV, without ever opening the receiver app; long press opens its settings. The
  receiver is a separate app, [iMirror](https://github.com/prat3ik/iMirror) (GPL-3.0, kept out of this MIT
  codebase), in a small fork that adds a control broadcast guarded by a signature permission (so it must
  be signed with the same key as the launcher) and opens a bare full-screen video view by itself when a
  session starts; Back ends the session. Install it with `tools/b64push.py` + `pm install`, then
  `appops set dev.imirror.receiver SYSTEM_ALERT_WINDOW allow` and
  `dumpsys deviceidle whitelist +dev.imirror.receiver` so it may start in the background. Without the
  fork, the tile just opens the app. It's hidden from the app rows (`hidden_apps`). Without any receiver
  installed, the tile says "Get the app" and shows where to download it, with a QR code.
- **Running apps** (last tile of the quick panel): the apps you opened since the TV started, memory in
  use, ✕ per app and **Clear all**. Closing is a real force stop, which Android only allows the system,
  so the remote-key service presses Force stop → OK on each app's Settings page (about a second per app)
  and returns you to the panel with the memory freed. The TV's own system apps aren't listed or stopped.
- **Night mode**: from 22:00 to 06:00 the artwork is dimmed (`night_*` in config.xml).
- **Light on memory**: artwork is decoded off the main thread at 2/3 screen size into small caches, which
  are let go while an app is in front, so the home screen leaves the memory to whatever you're watching.
- **Clock screensaver** that drifts every 30 seconds so nothing burns in, or a **photo screensaver** with
  your own pictures (see Configure).
- **Top bar**: a Home pill, then Wi-Fi and Bluetooth icons (each opens its Settings page) and the clock.
  Inputs and Settings are left to the remote's own buttons (see Remote buttons).
- The backdrop follows the focused card; rows that scroll under the top bar fade out; Back returns
  to the carousel.

**Classic home** (`hero_home` false): pitch black, a dock of four apps, a 4-column grid and one glass
highlight that springs between tiles.

Built for TVs with 1-2 GB of RAM: no blur, no video. The only network use is fetching show posters, which
are cached on disk. Backdrops and posters are built off the main thread and cached; on a MediaTek Google
TV 14 set at 60 Hz it renders at a median 24 ms per frame (the classic home: 23 ms).

## Install

You need a computer on the same network as the TV, Python 3, JDK 17 and the Android SDK (platform 34).

1. **On the TV**: Settings → System → About → press OK on *Android TV OS build* seven times, then
   Developer options → **USB debugging** on. Google TV 12+ may also need **Wireless debugging**: pair once
   with `adb pair <ip>:<port> <code>`, then `adb tcpip 5555`.
2. **Tools**:
   ```bash
   python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt
   export TV_HOST=192.168.1.50                      # your TV's IP
   tools/venv/bin/python -I tools/tv.py --probe     # model and Android version
   ```
3. **Optional debloat** (disables, never uninstalls): `tools/disable.sh devices/<your tv>/debloat.txt`
4. **Build and install**:
   ```bash
   cd launcher
   TV_HOST=$TV_HOST JAVA_HOME=/path/to/jdk17 INPUT_KEY_SERVICE=1 ./build-and-install.sh
   ```
   The script installs the launcher, sets it as Home, disables the stock launcher it recognises, grants
   usage access and `READ_TV_LISTINGS` (for Continue Watching), `WRITE_SECURE_SETTINGS` (for the quick
   panel's Bluetooth page) and, with `INPUT_KEY_SERVICE=1`, turns on
   the remote-key service.
5. **Optional screensaver**:
   ```bash
   tools/venv/bin/python -I tools/tv.py 'settings put secure screensaver_components com.gathin.tvhome/.ClockDream; settings put secure screensaver_enabled 1'
   ```

Undo everything: `tools/restore.sh devices/<your tv>/debloat.txt com.google.android.apps.tv.launcherx`.

## Configure

Everything per TV lives in [`launcher/app/src/main/res/values/config.xml`](launcher/app/src/main/res/values/config.xml):

| Setting | What it does |
|---|---|
| `hero_home` | Hero home (true) or classic home (false) |
| `dock_apps` | The featured carousel (and the classic dock), in order. Edits made on the TV win |
| `your_apps` | The "Your Apps" row; empty = a "Recently Used" row. Edits made on the TV win |
| `night_start_hour`, `night_end_hour`, `night_dim_percent` | Night dimming of the artwork |
| `hidden_apps` | Apps never shown |
| `live_tv_package` | The TV's Live TV app, offered in the Inputs menu |
| `remote_app_keys` | Remote buttons the key service takes over (see below) |

**Custom artwork**: push an image to the TV and it replaces the generated backdrop or the app's name:

```bash
tools/venv/bin/python -I tools/b64push.py my.jpg /sdcard/Android/data/com.gathin.tvhome/files/backdrops/com.netflix.ninja.jpg
# logos: .../files/logos/<package>.png
```

**Photo screensaver**: put pictures (JPEG, PNG or WebP) in the launcher's screensaver folder and the
screensaver plays them, shuffled, with a crossfade, a slow zoom and a small clock in a bottom corner. Wide
photos fill the screen; tall and square ones are shown whole over a blurred copy. They stay on the TV:
nothing is uploaded, and an empty folder brings the clock back. Each one shows for
`screensaver_photo_seconds` (12).

```bash
tools/venv/bin/python -I tools/tv.py 'mkdir -p /sdcard/Android/data/com.gathin.tvhome/files/screensaver'
tools/venv/bin/python -I tools/b64push.py photo.jpg /sdcard/Android/data/com.gathin.tvhome/files/screensaver/photo.jpg
```

Phone photos can be 12 MP or more; scaling them to about 1920 px first (`sips -Z 1920 *.jpg` on a Mac)
makes them quicker to push and to load. Photos are turned upright from their EXIF tag.

**Font**: the repo ships [Inter](https://rsms.me/inter/). To use another font in your own builds, put it at
`launcher/app/src/release/res/font/ui.ttf` (git-ignored); release builds pick it up over Inter.

## Remote buttons

With the stock launcher disabled, some remote buttons lose their handler, and on some TVs the vendor's
handler crashes. `InputKeyService` (an accessibility service, enabled by `INPUT_KEY_SERVICE=1`) takes those
keys first. In `remote_app_keys`, each item is `KEYCODE_NAME=target` or `KEYCODE_NAME:scancode=target`
(when one key code serves several buttons); the target is a package to open, `@inputs`, `@settings`,
`@panel` (quick settings panel), `@running` (running apps), `@home`, or empty to swallow the key.

To see what a button sends: `tools/tv.py 'setprop log.tag.InputKeyService DEBUG'`, press it, then
`tools/tv.py 'logcat -d | grep InputKeyService'`.

**Philips Smart 4K TV (Skyworth, Google TV 14)**, which the defaults are set up for: Input sends
`ENDCALL` scan 383, the programmable button `ENDCALL` scan 176, the antenna button `ENDCALL` scan 439
(opens Running apps), Settings sends `NOTIFICATION` (opens the quick panel).
Skyworth's global-key service crashes on `ENDCALL`/`MACRO_2-4` while the stock launcher is disabled, and
five crashes in a row make Android's RescueParty reset settings, so those keys must stay mapped. See
[`devices/philips-smart-4k-google-tv/debloat.txt`](devices/philips-smart-4k-google-tv/debloat.txt).

## AirPlay (iMirror)

AirPlay comes from a separate, free app, **iMirror** by prat3ik:

- Source: https://github.com/prat3ik/iMirror (GPL-3.0)
- Ready-made APKs: https://github.com/prat3ik/iMirror/tree/main/apk (`armeabi-v7a` for most TVs,
  `arm64-v8a` for newer 64-bit ones, `universal` when unsure; `getprop ro.product.cpu.abilist` tells)

Install it with `tools/venv/bin/python -I tools/b64push.py iMirror.apk /data/local/tmp/im.apk` and
`tools/tv.py 'pm install -r /data/local/tmp/im.apk'`. The quick panel's AirPlay tile then opens it. For the
on/off toggle without the app's screens, the receiver needs the small control addition described above
(signed with the same key as the launcher); the stock app works too, it just opens its own screen.

## Licence

MIT, see [LICENSE](LICENSE). Fonts: Inter and Outfit, SIL Open Font License 1.1.
