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
- **Night mode**: from 22:00 to 06:00 the artwork is dimmed (`night_*` in config.xml).
- **Clock screensaver** that drifts every 30 seconds so nothing burns in.
- **Top bar**: a Home pill, the clock, and glass pills for **Inputs** (HDMI, AV, Live TV), **Wi-Fi**
  (shows the network name) and **Settings**.
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
   usage access and `READ_TV_LISTINGS` (for Continue Watching) and, with `INPUT_KEY_SERVICE=1`, turns on
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

**Font**: the repo ships [Inter](https://rsms.me/inter/). To use another font in your own builds, put it at
`launcher/app/src/release/res/font/ui.ttf` (git-ignored); release builds pick it up over Inter.

## Remote buttons

With the stock launcher disabled, some remote buttons lose their handler, and on some TVs the vendor's
handler crashes. `InputKeyService` (an accessibility service, enabled by `INPUT_KEY_SERVICE=1`) takes those
keys first. In `remote_app_keys`, each item is `KEYCODE_NAME=target` or `KEYCODE_NAME:scancode=target`
(when one key code serves several buttons); the target is a package to open, `@inputs`, `@settings`,
`@home`, or empty to swallow the key.

To see what a button sends: `tools/tv.py 'setprop log.tag.InputKeyService DEBUG'`, press it, then
`tools/tv.py 'logcat -d | grep InputKeyService'`.

**Philips Smart 4K TV (Skyworth, Google TV 14)**, which the defaults are set up for: Input sends
`ENDCALL` scan 383, the programmable button `ENDCALL` scan 176, Settings sends `NOTIFICATION`.
Skyworth's global-key service crashes on `ENDCALL`/`MACRO_2-4` while the stock launcher is disabled, and
five crashes in a row make Android's RescueParty reset settings, so those keys must stay mapped. See
[`devices/philips-smart-4k-google-tv/debloat.txt`](devices/philips-smart-4k-google-tv/debloat.txt).

## Licence

MIT, see [LICENSE](LICENSE). Fonts: Inter and Outfit, SIL Open Font License 1.1.
