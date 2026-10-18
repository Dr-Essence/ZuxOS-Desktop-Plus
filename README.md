# ZuxOS Desktop Plus

**An LSPosed module that turns ZuxOS desktop mode into a real desktop.** Plug the tablet into a
monitor and you get a home screen you can arrange, widgets, folders, a liquid-glass app drawer and
a taskbar of the apps that are actually open. It also fixes a handful of bugs in ZUI's own desktop
mode along the way.

> **Early alpha - experimental.** This module is under heavy development and changes several
> times a day. Things break, behaviour changes without notice, and some features are half done.
> There is no public release yet: one will come once it is fully stable on the developer's own
> tablet. Until then, use it only if you are happy to test, and keep a way to disable LSPosed
> modules from recovery.

> **Tested on a real device.** Every build has run on a Lenovo Legion Tab Gen 5 with ZuxOS
> 2.0.10.026 (Android 16), driving an external 2560×1440 display. Each fix here came from that
> device's LSPosed logs and the module's own probe dumps, not from guesswork.

| | |
|---|---|
| Device | Lenovo Legion Tab Gen 5 (Snapdragon 8 Elite Gen 5) |
| OS | ZuxOS 2.0.10.026, Android 16 |
| Launcher | `com.zui.launcher`, desktop activity `SecondaryDisplayLauncher` |
| Needs | Root, LSPosed, and the module scoped to the home app |

**Status (2026-10-18, build 0.0.278)** - builds are numbered 0.0.x while in early alpha (the earlier 1.0.1-1.0.213 count on from the same number, so updating keeps your settings and folders).

The work now is making every feature run through ZUI's own code - its folders, menus, drag, animations and grids - so it looks and moves like ZUI itself.

**Working on the device** (confirmed by the owner, or seen working in the owner's videos):

- **Drag and drop on the tablet, through ZUI's own drag:**
  - taskbar drawer -> taskbar (pinned) and -> home screen (placed by ZUI);
  - an app out of a taskbar folder -> taskbar (moved out of the folder);
  - rearranging ZUX Home's drawer: apps and folders move, fold into folders and come out of them;
  - a drawer folder -> home screen, as a ZUI folder with its apps;
  - drops land through ZUI's own drop animation;
  - a folder pinned to the taskbar -> home screen.
- **Drawers:** ZUI's own folders in both of the tablet's drawers, with ZUI's look and motion, Add apps, Remove from folder and Uninstall.
- **Menus:** ZUI's own popups, shortcut icons in ZUI's icon shape.
- **Select on the home screen:** ZUI's edit mode, several apps moved together.
- **Tablet bar:** open apps stop at the navigation keys, which sit flush with the right edge; pinned folders are ZUI's own.
- **Monitor:** after a replug, its touchscreen and pen stay on the monitor.
- **Home screen uses the dock's space:** the strip ZUX Home keeps for its dock above the taskbar is home screen.

**New in 0.0.278, awaiting the owner's test:**

- **An app dragged out of a drawer folder goes back in:** rest it on the folder and the folder opens to take it; let go on the folder's icon and it shows there at once, in both drawers.
- **A folder held in portrait** is picked up sharp, at its own size with ZUI's small lift.
- **A folder dragged from the taskbar to the home screen** looks like the folder, not an empty box.
- **Several chosen apps dropped into a folder** all show in it at once.

**New in 0.0.277** (confirmed by the owner, apart from the icon):

- **Rearrange apps inside drawer folders** in both drawers: hold an app in an open folder and move it, as on ZUI's home screen. Dragged out, it goes to the drawer, the taskbar or (ZUX Home's drawer) the home screen.
- **Drag apps and folders from the home screen onto the taskbar**: they are pinned where you let go and leave the home screen.
- **A drawer folder left with one app breaks** into that app.
- **Game controller:** Triangle opens the menu of the app or folder you are on; the D-pad moves through the menu, A picks, B or Triangle closes it.
- **A real task manager icon** in the taskbar's menu.

**New in 0.0.276, awaiting the owner's test:**

- **Game controller:** the Home button now opens and closes the drawer; the focus mark follows you over folders; B closes only what is on top (a folder, then the drawer).

**New in 0.0.275, awaiting the owner's test:**

- **Dropping apps into a folder no longer stops ZUX Home** in the taskbar's drawer.
- **Every chosen app goes into the folder**, after the dragged one and in order, and their pictures fly in.
- **Taskbar drawer:** an app dragged out of a folder is placed in the drawer where you let go, and the drawer stays open.

**New in 0.0.274, awaiting the owner's test:**

- **Drawer folders are held exactly like ZUI's home-screen folders:** no shrink under the finger, the same small lift when held, and in the taskbar's drawer ZUI's own Rename in the folder's menu.

**New in 0.0.272 (built as 0.0.273), awaiting the owner's test:**

- **ZUX Home no longer stops** when an app is dropped into a folder in the taskbar's drawer.
- **A folder held in the taskbar's drawer** is picked up at its own size and sharp, not three times too big.
- **Taps on drawer apps always count**, anywhere on the icon or its name.
- **Drawer folders match the apps beside them** in landscape and portrait, all the same size.
- **Game controller:** its Home button opens and closes the app drawer; the stick or D-pad moves, A opens, B goes back, folders included.

**New in 0.0.270, awaiting the owner's test:**

- **Folders on the taskbar no longer show their apps two or three times**; the copies already saved are cleaned up.
- **ZUI's sidebar stops "not responding" on drags** when ZUI's AI service is removed (tick ZUI's sidebar in LSPosed).
- **Drawer folders that open under a dragged app now take it**, in both drawers.
- **Taskbar drawer:** folders can be moved, apps inside its folders reordered, and a pinned app no longer leaves a blank cell.
- **Folders react to touch** like app icons, then lift when dragged.
- **Selected apps fly** into the folder or their new places, as on ZUI's home screen.
- **Floating window and Split screen** in the menus of the taskbar's pinned and open apps.
- **Remove folder** in the menu of a home-screen folder.
- **The home screen uses its top and bottom bands.**

Confirmed in 0.0.269: fine grids fit, ZUX Home's drawer to the taskbar, Select in the drawers.

Also built and awaiting the owner's test: apps on the monitor never killed, no status bar on the monitor's desktop, the Retro theme, maximise and full screen, notification pop-ups on the monitor, editing the quick panel's tiles, one layout per monitor, the start animation.

**Known bugs, still open**

- **Taskbar drawer:** a folder dragged to a new place may fly back instead of moving (waiting for a log).
- **Window "..." menu on the wrong screen:** after a window has moved between screens (traced through SystemUI's window log).
- **Bar at the top of the monitor:** the notification and quick-settings panels may not show when pressed (traced, waiting for a log).
- **Recents on the tablet:** a pale blank square at the left (waiting for a probe taken in Recents).
- **Game Assistant:** its overlay does not come up over games on the monitor.
- **Bypass charging:** it holds at rest, but under heavy load the battery still gives current.
- **Charging port:** charging from the USB 2.0 port while the fast port carries a hub without PD pass-through (the kernel picks the port).
- **Folders:** not yet ZUI's own on the monitor.

**Roadmap, in order**

1. Finish drag and drop on the tablet through ZUI's own drag, and the taskbar drawer's folders through ZUI's folder menu.
2. The monitor's folders as ZUI's own, themed Glass or Retro.
3. Seamless unplug and replug of the monitor.
4. Customise the UI: taskbar, drawer, folders, menus, panels - colour, shape, transparency. The foundation is built.
5. Animations for every control.
6. Live notifications, and customising the pop-ups further.
7. Taskbar position on the monitor: top is built; left and right are planned. Then taskbar colours of your choosing.
8. Window looks and animations: colours, corners, title bars, a full-screen button, and a window manager editor.
9. A boot animation.
10. Less CPU, GPU and heat; the tablet's own heat management (both being measured).
11. Adapting to ZUI updates on its own.
12. No root for the monitor's keys and quick-settings toggles, widgets without the "allow" dialog, and work mode.
13. **Native Linux** - Linux apps and games as Android apps, everything written for this module, nothing else to install:
    - **The Linux itself:** a lean Arch Linux ARM in a chroot of our own on the tablet's kernel - no virtual machine, no proot, full native speed. A full Linux (pacman, GTK, Qt, Mesa, PipeWire and the rest), started with the first Linux app and gone after the last.
    - **Clean and secure:** Linux apps and games never run as root and never see Android's system, other apps' data or Magisk. They do see what a Linux PC would: your Documents, Downloads, Pictures (DCIM), Videos and Music, external drives, USB CD/DVD/Blu-ray drives, and every mouse, keyboard, pen and gamepad Android knows.
    - **Every Linux window an Android window:** our own Wayland window host inside the module. Each window is its own Android window with the app's name and icon, in the taskbar, Recents and Alt-Tab, opening on the monitor, with ZUI's window frame. Its picture goes straight from the GPU to the screen (SurfaceFlinger), never copied - for games too.
    - **Graphics:** Mesa's Turnip (Vulkan) and Zink (OpenGL) straight on the Adreno GPU.
    - **A Linux tab in the drawers,** beside ZUI's work tab, with All, Games, Accessories, Internet, Office, Graphics, Multimedia, Development, System and Settings.
    - **On an external SSD or USB drive without formatting it:** Linux apps and games in a library file on the drive.
    - **Later:** x86 programs and Steam through FEX-Emu; Windows games through Wine/Proton with DXVK and VKD3D-Proton.
    - **Status:** planned in full; it starts once the current drawer and home screen work is confirmed. The first step checks the GPU and the kernel, and proves the GPU-to-screen path with a one-triangle test.
14. **Android games' data on an external drive without formatting it:** a game's big `Android/obb` and `Android/data` folders moved to an SSD, mounted back in place before the game starts; the game will not start with the drive unplugged, so nothing is ever lost.

The full list, with every step's status and what each fix was: [`docs/ROADMAP.md`](docs/ROADMAP.md).

**At a glance**

- A home screen with free icon placement, pages, folders, widgets and shortcuts.
- The launcher's own app drawer, organised: your order, your folders, hidden apps.
- A taskbar of open apps, with pins, folders, reordering and a hold menu on every icon.
- Liquid glass (a refracting, lens-like glass effect) on the drawer, folders and menus.
- Back, home and recents on the monitor that act on the monitor, not on the tablet.

---

## What it adds

### Home screen (external display)

- **Place anything anywhere.** Drag icons to any cell, and drop one icon on another to make a
  folder. Drag to *Remove* at the top to take something off.
- **Pages.** Arrows at the sides move between pages. Drag an icon onto an arrow to send it to the
  next page, and dragging past the last page starts a new one. Page dots are optional.
- **Widgets.** A real `AppWidgetHost` runs inside the launcher, which ZUI's desktop mode has no
  support for at all.
  - Hold a widget **anywhere** and move to pick it up. It lands with the spot you grabbed under
    your finger.
  - Hold and let go for the resize frame.
  - Widgets are rebuilt at their real size after every restart, so they don't spill out of their
    frame.
- **Shortcuts.** Pin an app's deep shortcuts ("New message", "New private tab") onto the desktop.
  A page or file pinned from any app (a browser's *Add to home screen*) lands here too, instead of
  only on the tablet.
- **Motion.** Dropped items glide into their cell. Folders grow out of the icon you tapped and
  shrink back into it.
- **Menus.** Right-click, or hold and let go, for a liquid-glass menu with an icon on every row.
  App menus include **Close** and the app's own shortcuts with their icons. Menus open above the
  taskbar, never under it.

### App drawer

- **The launcher's own drawer, organised.** Your order, your folders and your hidden apps are
  applied to ZUI's drawer as well as the module's. The two share one set of data. This works on
  the tablet's drawer too.
- **Hold to choose.** Hold an app and let go for its menu: Open, Close, App info, its shortcuts,
  *Pin to the taskbar* and *Add to desktop*. Hold and move to drag it out onto the desktop or the
  taskbar. On the tablet, ZUI's own drag is left alone.
- **Folders that open like ZUI's.** A compact panel sized to its contents, with the name in the
  corner, zooming out of the icon you tapped.

### Taskbar

- **Only open apps, lined up like Windows.** The drawer button sits at the left, then your pins,
  then open apps in the order you opened them, one row from left to right. The row stops before
  the tray and scrolls when full.
  - Apps you have open inside a folder get an icon of their own, and the folder gets a mark.
  - ZUI's recent and recommended apps never reach the bar: the call that adds them on every
    launch and close is skipped, so nothing flashes.
- **Pins.** Drop an app or a folder on the bar to pin it.
  - Hold a pin and move it to reorder: the other pins slide aside to open a gap.
  - Hold and let go, or right-click, for its menu.
  - Pinned folders open in place.
- **A hold menu on every icon:** Open, Close, App info, and the app's own shortcuts with their
  icons. This includes ZUI's icons and the icon of the app in front.
- **A mark under every open app.**
- **The drawer button is moved to the far left, beside the navigation keys**, and shows the
  green Android head as a start logo (optional). It is ZUI's own button, moved, so it opens ZUI's drawer as
  before.
- **Scrolling.** With more apps than room, the row scrolls and its ends fade.
- **A status tray:** network, battery, temperatures and a clock, with a quick panel behind it for
  toggles, media and notifications.
- **Notification pop-ups on the monitor** (optional): what would pop up on the tablet shows above
  the tray for a few seconds, with its buttons and a reply box. Needs notification access.
- **Glass taskbar** (optional). Under the glyphs it adds the lightest tint that keeps them at a
  3:1 contrast ratio over any app, so white icons don't vanish over a white app.
- **Retro theme** (optional, monitor only): everything on the monitor as Windows 98 drew it - the
  bar, Start and its grey start menu, the tray, menus, panels, window previews, pop-ups, folders,
  the home screen's buttons, recents and the screenshot card - grey bevels, navy selection, a pixel
  font and pixel keys. Open apps are raised buttons, the one in front pressed in. Nothing moves
  and no glass is captured, so it is also the lightest look. The tablet keeps glass.
- **Taskbar at the top** (optional, monitor only), over the monitor's status bar strip. Menus,
  panels, pop-ups and previews open below it, and maximised windows start under it.
- **No status bar on the monitor.** ZUI's bar there only shows icons; the tray does its job, and
  apps get the strip back. Maximised windows keep their buttons. Needs System UI ticked in
  LSPosed, which also keeps windows clear of the taskbar wherever it is.

### Liquid glass

The drawer, folder panels and desktop menus bend what is behind them through a signed-distance
lens. The rim compresses the backdrop, fringes it with chromatic dispersion and carries a two-lobe
highlight. The optical model is ported from
[LiquidGlass for Android](https://github.com/QWEA0/Liquid-Glass-Android) (MIT); see
[NOTICE.md](NOTICE.md).

To do that, the panel takes a snapshot of what is behind it. The snapshot is rendered **through
the GPU** (a `RenderNode` drawn by a `HardwareRenderer` of the module's own). A software capture
fails on this device as soon as a blurred pane or a widget is behind the panel. If a capture does
fail, only that one panel falls back to a real blur.

---

## ZuxOS bugs it works around

All of these were found in logs and probe dumps from the device above.

| What goes wrong in stock ZuxOS | What the module does |
|---|---|
| Back, home and recents on the monitor's taskbar act on the **tablet** (the system sends them to whichever screen it last thought was in front) | Sends each key to the monitor's own display. The launcher isn't allowed to inject keys, so this goes through root |
| The taskbar **crashes the launcher** with `IllegalStateException: The specified child already has a parent`, from ZUI's `RecentUsedModel` rebinding the row | Catches that one exception, so the bar skips a refresh instead of the launcher restarting |
| Recent and recommended apps flash into the taskbar on every launch and close | Skips ZUI's call that adds them, and hides any of its icons the instant they are added |
| With ZUI as the main launcher, open apps that live in a hotseat folder never show in the bar | Gives every open app its own icon; the folder gets a mark |
| The all-apps button floats in the middle of the icons | Moves ZUI's own button to the left edge |
| Apps tapped on the monitor sometimes open on the tablet | Optional: gives each launch the display it was tapped on |
| The stock drawer has no folders or custom order | Applies the module's folders, order and hidden apps to it |

---

## How it works

The module does not try to talk the stock launcher into features it was built without. It adds
its own surface to the desktop-mode home activity and builds the features itself, with public
Android APIs: `LauncherApps`, `AppWidgetHost` and drag and drop. Where it does reach into
ZUI's launcher:

- **Class names survive R8, field names do not.** ZuxOS ships a minified Launcher3, so the
  module looks things up by class name (`TaskbarView`, `TaskbarModelCallbacks`) and finds fields
  by their type and shape, not by name.
- **Nothing foreign goes inside `TaskbarView`.** The bar animates every child as one of its own
  icons, and an outside view there crash-looped the launcher during development. The module's row,
  tray and drop target live in the taskbar's drag layer beside it.
- **Hooks sit on methods ZUI's classes declare themselves.** Xposed only hooks methods declared on
  the class it is given, so a hook on an inherited method silently does nothing. The log reports
  how many methods each hook caught (`x2`, `x0`), so a hook that caught nothing is visible.
- **Drags cross windows.** The desktop, the taskbar and the stock drawer are separate windows.
  Drags carry their payload on the clip as well as in local state, so an icon can go from the
  drawer to the desktop to the bar.
- **Everything is reversible.** Hidden icons, moved buttons and taken-over listeners are
  remembered and put back when their setting is turned off.

There is also an optional *stock launcher unlocking* feature, which flips the launcher's own
"editing disabled" switches where they can be identified. It is a bonus, not a dependency. See
[docs/TUNING.md](docs/TUNING.md).
Where the project is going, and in what order: [docs/ROADMAP.md](docs/ROADMAP.md).

---

## Install

1. You need root with **LSPosed** (Zygisk on Magisk, KernelSU or APatch) working on Android 16.
2. Install the APK, enable the module in LSPosed and **scope it to the home app**
   (`com.zui.launcher` on this firmware). Then restart the home app or reboot. Tick **System
   Framework** too to keep apps on the monitor alive, and **System UI** to take the monitor's
   status bar away.
3. Open **ZuxOS Desktop Plus** once. The banner says whether LSPosed has really loaded it.
4. Connect the monitor and enter desktop mode.

Updates install over the top, with no uninstall and no lost settings. Every build is signed with
the same key and has a higher version code.

## Using it

| To | Do |
|---|---|
| Open an item's menu | Right-click it, or hold it and let go |
| Move something | Hold it and move |
| Make a folder | Drop one icon on another |
| Get the desktop menu (add apps, widgets, shortcuts, folders, sizes, export) | Right-click empty space |
| Pin to the taskbar | Drag onto the bar, or use *Pin to the taskbar* in a drawer menu |
| Reorder pins | Hold a pin and slide it along the bar |
| Get the taskbar menu (task manager, settings) | Right-click empty bar space |

Your layout is stored in the launcher's own data folder (`files/zux_desktop_plus/`), so it
survives restarts and reboots.

## Settings worth knowing

| Setting | Default | Why you would change it |
|---|---|---|
| **Which desktop mode** | External only | The tablet has a desktop mode of its own too |
| **Stock home content** | Hide the icon grid | Switch to "hide everything" if you still see duplicated icons |
| **Only open apps in the taskbar** | Off | The taskbar shows what is open, not ZUI's hotseat and recommendations |
| **Keep the launcher's recents off the taskbar** | On | ZUI's recent and recommended apps never reach the bar |
| **Navigation keys act on their own screen** | On | Back, home and recents on the monitor act on the monitor |
| **Drawer button on the left** | On | ZUI's all-apps button beside the navigation keys |
| **Mark the apps that are open** | On | A line under each running app, folders included |
| **Menu on a taskbar icon** | On | Hold an icon for Open, Close, App info and shortcuts |
| **Glass taskbar** | Off | Experimental: a translucent bar with a contrast floor |
| **Theme** | Glass | Retro: Windows 98 on the monitor's bar and panels |
| **Taskbar position (monitor)** | Bottom | Top; read when the launcher restarts |
| **Hide the monitor's status bar** | On | Off brings ZUI's back, the next time the monitor's desktop starts |
| **Status tray / Show temperatures** | On | The tray at the right of the bar |
| **Drag apps out of the stock drawer** | On | Hold and move in ZUI's drawer to drag to the desktop or bar |
| **Use the stock taskbar drawer too** | On | Your folders and order in ZUI's own drawer |
| **Glass app drawer** | On | A translucent pane behind ZUI's drawer |
| **Desktop pages / Page dots** | On / Off | More than one page; the dots are optional |
| **Open apps on the screen you tapped** | Off | If apps keep opening on the wrong screen. It changes every launch, so it starts off |
| **Use root** | On | Needed for the navigation-key fix, quick toggles and screenshots |
| **Dump launcher info on attach** | Off | Writes a probe dump. Turn on when reporting a bug |

## Other modules

These were seen hooking the same launcher on the test device. They are worth knowing about when
something misbehaves.

- **ZTool** (`com.qimian233.ztool`): its *launcher recent task memory view* option runs just
  before a `dispatchDetachedFromWindow` crash in the launcher. If the launcher crashes when its
  desktop closes, turn that one option off.
- **ZuiRecentsFix** (`io.github.miner7222.fixrecents`) also patches Recents. Turn it off when
  testing this module's navigation keys.
- **unfuckzui** and other ZUI modules: no conflicts seen so far.

## Reporting a problem

1. From the taskbar's menu, take a probe, or from the desktop, right-click → **Export layout +
   launcher info**. Each writes `probe-<date>_<time>.txt` to `Download/`, a new file each time.
   **Dump launcher info on attach** writes `probe.txt` on every attach, replacing the last one.
2. Export the LSPosed log (Manager → Logs → save).
3. Send both, with a screenshot if it is visual.

The log has one line per taskbar whenever its contents change, like
`taskbar running: display 2 open=[...] zui=[...] ours=[...]`. That line answers most "why isn't
this app on the bar" questions on its own.

## Known limits

Once limits, now worked around:

- **Glass refracts what is behind it, other apps included.** Android does not let one app read
  another's pixels, but the window manager can capture the screen for the device's recents app,
  and ZUI's launcher is that app. So the panels and menus capture what is behind them live -
  apps, other windows and the wallpaper - and bend it, only while they are on screen. If a device
  refuses the capture, the panes fall back to the system's blur, and the log says so.
- **ZUI's own folders are reused** in both of the tablet's drawers - the home's and the taskbar's
  - with ZUI's own icon, view and motion (ZUI's Add apps dialog in them is built, awaiting
  test).

Still limits, each planned to be bypassed (rows in [`docs/ROADMAP.md`](docs/ROADMAP.md)):

- **The monitor's folders are still the module's own**, drawn to look and move like ZUI's.
  Planned: ZUI's own folders there too, next on the roadmap.
- **Root is needed for the monitor's navigation keys** (the launcher is refused
  `INJECT_EVENTS`, and the system would otherwise act on the tablet), and for some
  quick-settings toggles. The tablet's own keys stay ZUI's. Planned: the system process, which
  the module already runs in, does these for the launcher, so root is no longer needed.
- **The layout is stored per kind of display** (external or tablet), not per monitor.
  Planned: one layout per monitor, picked by the monitor's own id.
- **Widgets need the launcher to be allowed to bind them.** System launchers normally are; if
  not, Android's own "allow this widget?" dialog appears. Planned: the system process allows
  the launcher, so the dialog never appears.
- **Work mode is not touched.** It makes a different app the home (Work Launcher), which the
  module does not load into. Planned: support for it; how far is the owner's call.

Not a limit to bypass, just how it works: **system hooks need System Framework ticked in
LSPosed** and a reboot - the monitor's touchscreen and pen on the monitor, and keeping the
monitor's apps alive.

---

## Building

There is nothing exotic here. The Xposed API is vendored as compile-only stubs in `xposed-api/`,
so the build never depends on the often-offline Xposed maven repo.

```bash
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
./gradlew :logic:test         # the unit tests - seconds, no Android SDK needed
tools/check.py                # a real compile check and the unit tests, no Android SDK
tools/deadcode.py             # what nothing in the module reaches, to delete
```

Or grab the APK from the **Build module APK** GitHub Actions run for any pushed commit.

### Tests

Everything that can be decided without a device lives in `logic/`, a plain Java module with no
Android dependency. It holds the layout format, the taskbar's ordering and spacing, pin moves,
drag payloads, and the glass and contrast maths. `./gradlew :logic:test` runs anywhere. On a
machine that cannot reach Google's maven repo, add `--configure-on-demand`. CI runs the tests
before it builds the APK.

The hook and view code needs a device, so the tests are a safety net under the logic, not a
guarantee about the UI.

`tools/check.py` is the other half of that net. It compiles everything against Robolectric's
`android-all` jar, which is fetched once from Maven Central and cached outside the repo. Plain
`javac` can't do this: it gives up inside every class that touches a framework type, so a deleted
method compiles clean and only fails in CI. It then runs the `logic` tests with JUnit, fetched
the same way. Run it before pushing; it says what CI will say.

`tools/deadcode.py` keeps the code free of what nothing uses. It compiles the module, asks ProGuard
what no entry point reaches (kept from the Xposed entry point and the manifest's components),
and adds fields that are written but never read, and constants, imports and private methods named
nowhere else. Things reached only by name are listed in its `KEEP` set.

Release and debug APKs are signed with the key committed in `keystore/`, and the version code
climbs with the commit count, so **every build installs over the previous one**. The key is
deliberately not secret. Use your own signing config if you distribute this.

## Repository layout

```
app/src/main/java/com/zuxos/desktopplus/
  core/      settings, logging, storage, reflection, root, tone, menus
    glass/     liquid glass: shaders, live backdrop, glass views
    motion/    iOS springs, animations, hover, refresh rate
    icons/     the start button robot, menu glyphs, tray icons
  hook/      LSPosed entry point and what every hook shares: windows, overlays, probe
    system/    runs in the system process - must never crash (boot loop)
    taskbar/   ZUI's three taskbars: our app row, start button, tray, menus, nav keys
    drawer/    ZUI's own app drawer: folders, order, drag out, glass, account bar
    home/      home screens: our desktop on the monitor, ZUI's dock and folders
    panel/     quick settings, sound, notifications, tray state
    shots/     screenshot button, preview, share sheet
    recents/   the monitor's recents, recents press routing
  model/     items, desktop/drawer persistence, app and icon repository
  desktop/   the desktop surface: grid, icons, folders, widgets, menus, drag and drop
  drawer/    the module's own app drawer
  ui/        the settings app
logic/       plain Java, no Android: the layout format and the decisions worth unit-testing
xposed-api/  compile-only Xposed API stubs (never packaged)
tools/       check.py (compile check + tests), deadcode.py, glass_preview.py
docs/        TUNING.md - adapting the module to another ZuxOS build
             ROADMAP.md - the agreed plan; read it before starting new work
```
