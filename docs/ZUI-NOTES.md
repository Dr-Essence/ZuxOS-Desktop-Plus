# ZUI notes

What ZUI's own apps do, read from the owner's firmware (decompiled 2026-10-09). Read this before
hooking anything of ZUI's. It records facts - class roles, settings keys, behaviour - never ZUI's
code; the decompiled sources stay outside the repo.

**Where the firmware is:** the owner's private repo `Dr-Essence/Test` (`Tiimoo-debug/Test` before
the owner's GitHub rename on 2026-10-20). Its releases (tags `Test` and `Test1`) hold the APKs and
jars as pulled from the tablet - ZUX Home is `default.apk` - and its
`decompiled/` folder holds each one decompiled with jadx 1.5.1 (sources and text resources,
2026-10-18). A new session clones that repo (or downloads a release asset through the GitHub API
with the session's token) instead of asking the owner. It stays private: nothing of it is ever
copied into this public repository.

## The apps

| APK | Package | Version | What it is |
|-----|---------|---------|------------|
| ZUX Home | `com.zui.launcher` | 18.2.0.0375 | `NormalLauncher`, `DrawerLauncher` (tablet), `CustomModeLauncher` (tablet desktop mode), `SecondaryDisplayLauncher` + the `...Dp` classes (monitor), `tianjiao.LearningLauncher`, quickstep recents. Every probe so far was taken here. |
| Work Launcher | `com.zui.desktoplauncher` | 18.0.0.0178 | A second, complete Launcher3 fork in `/system/priv-app/ZuiLauncherPC`: `workmode.WorkLauncher`, its own `DrawerLauncher` / `NormalLauncher` / `SecondaryDisplayLauncher`, `workmode.pcallapps.PcAllAppsActivity`, `WorkSearchService`, its own quickstep (`com.zui.quickstep.*`). Always running. **The module does not load into it.** |
| ZUX Performance Service | `com.zui.pp` | 3.1.4.4 | Runs as the system uid. ZUI's heat, refresh-rate and app-killing policy (below). |
| Game Assistant | `com.zui.game.service` | 2.3.0.4834 | Game profiles, its thermal and game-mode switches (below). |
| SystemUI | `com.android.systemui` | 16 | Window decorations and menus, the WM shell, and work mode's own bar (below). Decompiled in full. |
| HomeSettings | `com.zui.homesettings` | 18.1.0.0054 | Fonts and app badges only - nothing about the desktop. |
| "Android System" | `android` (framework-res) | 16 | Resources only, no code. |
| System server | `services.jar` | 16 | The system's own code, with ZUI's additions: app killing, window placement, refresh-rate boosts (below). |
| Framework | `framework.jar` | 16 | The API side: `IActivityManager` with ZUI's whitelist calls, `com.lgsi.config.LgsiFeatures` (ZUI's feature switches), `OvFreeformManager`, `OVDesktopManager`. |

## Which mode is on: three settings, exact

All three are `Settings.System` integers, 1 for on. ZUI reads them itself; they are the exact
answer to "which mode is this", where the module has been inferring it from views.

| Key | On means | Read by |
|-----|----------|---------|
| `zui_ov_desktop_mode` | The tablet's desktop mode: ZUX Home's `CustomModeLauncher`, the bar with the search box | `com.zui.launcher.utils.ZuiDesktopModeManager` (observes it), `utils.TaskbarUtilities` |
| `zui_dp_display_pc_mode` | The desktop on the external display ("DP mode"): ZUX Home's `SecondaryDisplayLauncher` and `...Dp` classes | `com.zui.launcher.dpmode.DpModeManager` (`ZUI_DP_MODE_NAME`), `Utilities.isInDpMode` |
| `zui_pc_mode` | **Work mode** (ZUI calls it PC mode): Work Launcher's `com.zui.desktoplauncher/.workmode.WorkLauncher` becomes the home | Work Launcher's `Utilities`, `PcAllAppsManager`; ZUX Home's `Utilities.SYSTEM_WORK_MODE` and `OverviewComponentObserver` |

- ZUX Home's `OverviewComponentObserver` knows Work Launcher's `WorkLauncher` as a possible
  default home and stops treating itself as the recents target when it is.
- Work Launcher returns to its normal home when both `zui_pc_mode` and `zui_dp_display_pc_mode`
  are 0.
- Also seen in the code: `force_desktop_mode_on_external_displays`,
  `freeform_active_in_desktop_mode`, `desktop_mode_visible_tasks`, `close_button_from_pcmode`.

**For the module:** `TaskbarScope` tells the tablet's desktop mode from its bar by the search box;
`zui_ov_desktop_mode` says it exactly. Work mode is a fourth mode the module does not cover.

## Work mode is ZUI's PC mode

Read from the strings in SystemUI's code and in framework-res; SystemUI's code itself is not read
yet.

- **What it is:** a full-screen mode on the tablet's own screen. framework-res names its home
  (`config_pcmode_launcher_package` / `config_pcmode_launcher_class`): Work Launcher's
  `WorkLauncher`. SystemUI replaces the bar with its own (`pcmode.nav.WorkNavLayout`,
  `NavigationBarViewPcMode`): pinned apps plus Wi-Fi, volume, notification and search icons.
  Window decorations are SystemUI's own too (`OvPcMode`, the `decor_*_pcmode` buttons). None of
  it is ZUX Home, so none of the module's hooks apply.
- **Ways in:** a PC-mode quick-settings tile (`qPCModeTileProvider`), a Settings page
  (`com.zui.settings.PC_MODE_SETTINGS`), attaching a keyboard (`enter_work_mode_from_keyboard`,
  `startPcModeByInputKeyguard`), and the broadcast `com.zui.intent.action.SWITCH_PC_MODE`.
- **What turns it off:** `zui_pcmode_not_support`, `persist.sys.csdk.disallowSetPcMode`, and
  ZUI's "OV" desktop features all disabled ("pcMode can not use"). Other keys:
  `zui_pc_mode_switch`, `zui_pc_mode_trigger`, `zui_pcmode_cannot_switch`, `zui_test_pcmode`,
  `zux_pcmode_ui_style`, `current_pc_mode_state`, `persist.sys.zui.pcmode`.
- **Old or current:** "PC mode" is ZUI's name from before ZUX, but Work Launcher is version 18.0
  next to ZUX Home's 18.2, so it is maintained. Whether this tablet offers it is open: the probe
  reads the keys above.
- **Not PC mode:** SystemUI's `WorkModeTile` is AOSP's work-profile tile.

## Heat and app killing: ZUX Performance Service (`com.zui.pp`)

- **Overheat cleaning** (`com.zui.power.overheat.OverHeatCleanService`): when the temperature
  passes the config's start temperature, apps on its clean list are **force-stopped**
  (`ActivityManager.forceStopPackage`), some at once and some after a notification. Spared: the
  one "top" package (`SystemInterface.TOP_PACKAGE`, ZUX Home by default, then the foreground
  app), apps with a foreground service, apps used within the last `bgTime`, games under game mode,
  and the overheat whitelist.
- **Stubborn cleaning** (`StubbornCleanService`): the same force-stop for apps it marks as
  stubborn (high background power), with the same exemptions.
- **Config**: `system/etc/zuipp_powercfg.xml` (`SystemInterface.SYS_CONFIG_POWER_CFG`) holds
  `startCleanTemp`, `cancelCleanTemp`, `timeInterval`, `bgTime`, `cycle`, `bigPower` and the
  overheat whitelist. The probe reads it.
- **For #12 (apps on the monitor are never closed):** an app on the monitor is not the "top"
  package, so overheat and stubborn cleaning can force-stop it. The disable-don't-fight route is
  to put the monitor's apps into what these cleaners already spare - decided once the config is
  read.
- **Refresh rate** (`com.zui.performance.refreshratecenter.RefreshRateManager`): rates are
  votes with priorities - settings, app requests, games, game power-save, battery, and a
  full-screen state (`Settings.Global key_top_full_screen_state`) that votes 60 Hz - kept in
  `pp.refreshrate.db`, alongside `peak_refresh_rate`.
- **Thermal** (`com.zui.performance.thermalcenter`): policies as code, through the thermal HAL,
  through perf locks and through the vendor thermal engine.

## App killing inside the system (`services.jar`)

Each of these runs only when ZUI's switch for it (`com.lgsi.config.LgsiFeatures`) is on for this
firmware; the probe lists them.

- **Memory cleaner** (`com.android.server.am.ZuiMemoryCleaner`, switch `ZuiMemoryAcceleration`):
  on memory pressure reported by lmkd, it force-stops whole packages or kills processes, lowest
  importance first. A package is skipped if **any** of its processes is more important than the
  config's minimum adj (`/system/etc/ZuiMemCleanerConfig.xml`, picked by RAM size), so holding a
  monitor app's adj low - what the module's system keep-alive does - keeps it out. Also skipped:
  the permanent list (`/data/system/zui/zui_zmc_whitelist` plus the config's), Lenovo's
  performance center list (`content://com.lenovo.performancecenter.provider.querywhitelist/...`),
  and the most-used apps (`ZuiAppPersistenceRanking`, `/system/etc/zui_app_ranking_config.xml`).
  Its kills carry the reason `ZuiMemoryCleaner[...]`.
- **Kill whitelists, ZUI's own API:** `IActivityManager.addZmcLmkWhiteList(packages, type)`,
  `removeZmcLmkWhiteList`, `getZmcLmkWhiteList` - type `"1"` is lmkd's list
  (`/data/system/zui/zui_lmkd_whitelist`, switch `ZuiLmkWhiteList`), `"2"` the memory cleaner's
  (switch `ZuiMemoryAcceleration`). No permission check in the stub or the service. This is the
  disable-don't-fight route for #12: ask ZUI itself to spare the monitor's apps, and take them off
  again when their window closes. Read in full on 2026-10-09:
  - lmkd's add appends to its file and then tells lmkd (`ProcessList.updateLmkWhitelist`, lmkd
    command 101); remove rewrites the file without them and tells lmkd again. Add **deletes from
    the list it is given** the names already on file, so a caller passes a copy. Its get returns
    null, not an empty list, until the file exists.
  - The memory cleaner's add and remove change its permanent list in memory, which also holds the
    config's `PermanentPackageName` entries; its get shows only the file. So a caller must read
    the config too, or it may take one of ZUI's own off.
  - The module uses both from 1.0.165 (`hook/KeepAlive`): it adds only names on neither list,
    records what it added in the launcher's own `zux_keep_alive` preferences, and removes only
    those.
- **Fixed importance per app** (`ZuiAdjCustomize`, `/system/etc/adj_customize_config.xml`): the
  oom adjuster gives listed packages a computed adj.
- `ZuiDesktopKeepLiveUtil` is misnamed: it places new desktop windows so they do not cover each
  other, nothing to do with keeping apps alive.
- SystemUI's `ZuiDesktopModeKeepAlived` decides, on entering ZUI's desktop mode, which windows
  turn into floating ones and which tasks are killed (`ZuiMemoryCleaner_PcMode`).

## Refresh rate inside the system

With `ZuiAutoRefreshRate` on, `DisplayPolicy` asks the power HAL for a boost (ids 106, 107, 201)
on every touch-down and fling, and on touches of `DrawerLauncher` and a few listed apps.
`ZuiAutoRefreshRateForVideo` picks other boosts while video plays. What each boost does is the
vendor power HAL's, not in these jars.

## Maximize: what ZUI's own window button does (SystemUI's WM shell)

- **Tablet desktop mode on** (the system sets it from `zui_ov_desktop_mode`, also
  `persist.sys.zui.ovdesktop`; SystemUI holds it in one flag for every display): the button turns
  a floating window into a **full-screen task** (`DesktopTasksController.moveToFullscreen`), or
  shows a toast when the app does not allow it; on a full-screen task it goes back to floating.
  The module's Maximize on the tablet does the same.
- **On the monitor, as the device does it** (1.0.166's system trace, 2026-10-10; the monitor's
  desktop on, `zui_dp_display_pc_mode=1`, tablet desktop mode off): the button also makes the
  window **full screen**. SystemUI starts a transition of type **1106** (Android's "leave desktop
  mode by the window's button") that sets windowing mode full screen, empty bounds, not always on
  top, and moves it in front; the window then covers the whole display (0,0-2560,1440). Restore
  starts type **1101** ("enter desktop mode by the window's button"): windowing mode undefined,
  which a desktop display makes floating again, at the bounds it had, in front.
- **What the code also has, but the device did not run:** a toggle that keeps the window
  floating at the display's **stable bounds** (`DesktopModeUtils.calculateMaximizeBounds`), with
  the old bounds kept for restore. Stable bounds are the display minus the cutout, minus the
  status bar at the top, and minus max(navigation-bar inset, a fixed bar height) at the bottom.
  An app that cannot resize keeps its aspect ratio, as large as fits, centred, its title bar not
  counted in the ratio (`maximizeSizeGivenAspectRatio`; jadx's plain output of it is wrong, the
  raw instructions match Android's source). A window counts as maximised when its bounds are the
  stable bounds, or for an app that cannot resize, when it has their full width or height
  (`isTaskMaximized`); restore puts back the size it remembered, or, for a window it never saw
  before, 3/4 of the display each way, centred (`calculateDefaultDesktopTaskBounds`). 1.0.165
  shipped this (`logic/WindowMath`); from 1.0.167 it is only the fallback when the system refuses
  the transition.
- The launcher's desktop interface (`IDesktopMode`) has no maximize or full-screen call (it has
  `moveTaskToDesktop` and launch and show calls). So from 1.0.167 the module's Maximize on the
  monitor starts the same two transitions itself (`WindowOrganizer.startNewTransition`); its
  menus call the way back "Floating" (1.0.169). SystemUI
  follows them like its own: its request handler (`DesktopTasksController.handleRequest`, read
  from the raw instructions) only steps in for "open" and "to front" transitions and lets others
  pass as they are. 1.0.133 instead applied the change directly (`applyTransaction`), which
  SystemUI did not follow, and its window menu then opened on the tablet.
- **The window menu ("…")** is `OvHandleMenu`. It opens on the display of the window
  decoration's own copy of the task (`mTaskInfo.getDisplayId()`), not where the task is now. A
  menu on the wrong screen means SystemUI's copy had the wrong display.
- SystemUI logs each decoration relayout with that display - tag `OVC`, "WindowDecoration
  pre_relayout taskId:… displayId:…" (`android.util.OvcLog.i`) - **only while its debug switch
  is on**: `OvcLog.isDebuggable()`, which is `Log.isLoggable("OVC", DEBUG)`, so the property
  `log.tag.OVC`. It is off on this device, which is why the probe's section came back empty on
  1.0.166. `setprop log.tag.OVC D` (root, until reboot) turns it on.

## Folders and menus in ZUI's drawers (read 2026-10-10)

- `OptionsPopupView.show(ActivityContext, RectF, List<OptionItem>, boolean)` is ZUI's popup with any
  items: each `OptionItem(CharSequence, Drawable, EventEnum, OnLongClickListener)` becomes a
  `system_shortcut` row - the same rows as ZUI's folder menu (`SystemShortcut.SwitchFolder`
  "Enlarge folder", the rename shortcut "Rename"). `ActivityContext.lookupContext(context)` gives
  the drawer's own activity context, Launcher or the taskbar overlay. `hook/ZuiMenu.show` uses it
  for our menus on the tablet. Over the taskbar, the bar's window only grows for a popup through
  `BaseTaskbarContext.onPopupVisibilityChanged(true)`; it is given back with `false` on close.
- ZUI's folder panel is the drawable `round_rect_folder`: `?attr/folder_fill_color` (`#fae5e5e5`),
  a `folder_border_color` stroke, corner `bg_round_rect_radius_folder`. Tablet folders use it.
- `FolderIcon.inflateFolderAndIcon(layout, ActivityContext, parent, FolderInfo)` builds ZUI's real
  folder for any activity context; ZUI's taskbar already hosts real folders (`TaskbarView`
  implements `FolderIcon.FolderIconParent`).
- A pressed icon's picture shrinks to 0.8 inside its drawable (`FastBitmapDrawable.onStateChange`).
  On touch down `BubbleTextView` holds itself pressed (`setStayPressed(true)`), and only ZUI's own
  handlers let go: `ItemClickHandler` (`setStayPressed(false)`, `resetIconScale()`), a hold that
  opens a popup without a drag (`clearPressedBackground()`, in `LauncherCustom.skipHotseatDrag`
  and `ZuiNavOverlayContext.onIconLongClick`), a drag (`prepareDrawDragView`). A rebuilt icon
  starts at its view's pressed state (`initState`), so an icon never let go is small for good.
  Every tap, hold or drag we take from ZUI calls `hook/IconPress.release`, the same calls.
- ZUI's app popup (`PopupContainerWithArrow`) puts deep shortcuts on top and its system shortcuts
  below, as an icon strip or as `deep_shortcut` rows (`addSystemShortcutsIconsOnly`).
  Every app popup - drawers, home, taskbar - goes through
  `ZuiPopupContainerWithArrow.populateAndShowRowsZui(view, deepCount, notifications, shortcuts)`:
  one shortcut is a row, more are an icon strip (`system_shortcut_icon_only_zui`, label as
  tooltip), more than five a 2x5 grid. `RemoteActionShortcut(RemoteAction, target, info, view)`
  is Launcher3's shortcut for outside actions (Wellbeing's); our actions join the list as those
  (`hook/taskbar/ZuiAppRows`). ZUI's shortcut icons are filled with `ic_system_shortcut_solid`
  (#666666, night #99fafafa); its own icons for ours: `ic_pin_zui`, `ic_unpin`,
  `desktop_mode_ic_taskbar_menu_new_window`, `ic_close_option`.
- ZUI's `Folder` runs outside `Launcher` too: `LauncherDelegate.d(context)` gives the taskbar a
  delegate (`LauncherDelegate.b`) with no drag (`h()` false) and the model's own writer.
  `FolderIcon.inflateFolderAndIcon(layout, ctx, parent, FolderInfo)` builds icon and folder; ZUI's
  taskbar opens one with `folder.animateOpen()` after making its window full screen
  (`TaskbarActivityContext.expandFolder` / `x0`, which also closes the drawer), `Launcher` with
  `ItemClickHandler.onClickFolderIcon`. A folder writes through `ModelWriter` only: positions on
  `bind` (`updateItemLocationsInDatabaseBatch` -> `moveItemsInDatabase`), its name
  (`FolderInfo.setTitle` -> `updateItemInDatabase`), moves and removals. `Folder.onLongClick` drags
  only when `LauncherDelegate.h()` allows. `FolderIcon` only needs `ShortcutAndWidgetContainer` /
  `CellLayout` parents for drag-over and resize, not to open or close.
- A folder's motion (`FolderAnimationManager.getAnimator`) is chosen by where it is: the bar
  (`TaskbarActivityContext` -> `getZuiTbFolderAnimator`) or else the home screen
  (`getZuiFolderAnimator`, which needs `Launcher` - null in the taskbar's drawer, a crash on
  close). `getAnimatorZui` checks `isFromLauncher()` and `instanceof TaskbarActivityContext` at
  each step; it is what our folders in the taskbar drawer use.
- In the taskbar's drawer (`TaskbarOverlayContext`) `showPopupMenuForIcon` does nothing (the
  `BaseTaskbarContext` default); ZUI shows a drawer icon's menu with
  `onClickIconWithRightMouse(view)`, which goes to the bar's `showPopupMenuForIcon`.
- The bar's `showPopupMenuForIcon(view)` makes the window full screen and posts
  `TaskbarPopupController.showForIcon(view)`, which reads the view's tag as an `ItemInfo`; the
  bar's own apps are an `AppInfo[]` on `TaskbarPopupController` (`setApps`).
- Outside `Launcher` ZUI's folder delegate (`LauncherDelegate$b`) answers no `Launcher` (`e()`),
  so a folder in the taskbar's drawer gets ZUI's context-checking motion; given ZUI's tablet
  Launcher (`Launcher.ACTIVITY_TRACKER.getCreatedContext()`, as `expandFolder` reads it) it is
  drawn and moves as in ZUX Home's drawer, its Launcher-only effects still behind
  `isFromLauncher()` (the folder's own context).
- The home motion (`getZuiFolderAnimator`) scales the stand-in apps (`w()` -> `B()`) by
  `Workspace.getNewScaleFolder()` (the workspace's state scale, about 0.89 while the taskbar
  drawer is up) from the icon to their place, and at its end (`a.onAnimationEnd`) scales the
  folder by the same only when `isFromLauncher()`; any other folder ends at 1.0. A folder of
  ours in the drawer's window therefore had its apps snap from 0.89 to 1.0 as the motion ended;
  the module reads that scale as 1.0 while ZUI builds the motion for one of ours (0.0.215).
- **Drag in the tablet drawers (read 2026-10-12, for "through ZUI's path" #2).** Both drawers
  share `BaseAllAppsAdapter`; its long press comes from the context
  (`getAllAppsItemLongClickListener()`).
  - ZUX Home's drawer (`Launcher`): the stock `ItemLongClickListener.INSTANCE_ALL_APPS` ->
    `Workspace.beginDragShared(view, appsView, new DragOptions())`, popup first and the drag
    once the finger moves, on `LauncherDragController`. On drag start the Workspace sends the
    Launcher to `SPRING_LOADED` (drawer closes, home shows); a drop lands through
    `Workspace.onDropExternal` (`o1`): new folder `y0`, into a folder `o0` ->
    `FolderIcon.acceptDrop` / `onDrop` -> `Folder.addFolderContent` ->
    `ModelWriter.addOrMoveItemInDatabase`. The drop-target bar (`DropTargetBar2`) holds Cancel
    and Uninstall. `FolderIcon` is no drop target of its own; an open `Folder` is.
  - The taskbar's drawer (`TaskbarOverlayContext`): `TaskbarDragController.startDragOnLongClick`
    drags only with ZUI's one-vision features on (`CommercialManager.isOVFeatureEnable`,
    `ENABLE_ONE_VISION_ALL_FEATURE`, 1 by default) outside desktop mode, else the popup only; on
    the monitor, the popup only. Its drag becomes a system drag (`startDragAndDrop` with an
    activity or shortcut clip) for split and floating windows; no drop target in the launcher
    (`getDefaultDropTarget()` null, folders refused), and the overlay closes at drag end.
  - ZUI's drawer never reorders: `AlphabeticalAppsList` always sorts by pinyin and label;
    `isZuiRow()` is only the region. The drawer is no drop target and has no folders of its own
    (no folder view type in the adapter) - drawer folders are only ours.
  - So "ZUI's path" for drag in the drawers exists only for drags out of ZUX Home's drawer onto
    the home screen. Reordering and moving apps in and out of our drawer folders has no ZUI path
    to follow: it would be ours, on the drawer's own `DragController` (ZUX Home) or the overlay's
    (taskbar), with a drop target of our own. The probe's "drawer drag" section reads both.
  - Where the taskbar drawer's system drag lands (read 2026-10-12): SystemUI's
    `DragAndDropController` window ("ShellDropTarget", type 2200 with ZUI's split, full screen,
    `PRIVATE_FLAG_INTERCEPT_GLOBAL_DRAG_AND_DROP`, so it gets the clip at the start) takes it
    for floating or split (`SplitDragPolicy`). ZUI's clip carries `DISALLOW_HIT_REGION` (its
    transient bar) which `getTargetAtLocation` skips - but the window still covers it, so
    nothing under it gets the drop. ZUX Home itself takes drags from other windows only for its
    own pin MIME (`BaseItemDragListener`: a `DragLayer` listener starting a `DragController`
    drag with `simulatedDndStartPoint`, every later event to `onDragEvent`). ZUI's
    `PinToTaskbarShortcut` exists but its flag (`enablePinningAppWithContextMenu`) is compiled
    off.
- **Who an app's drag reaches (read 2026-10-13, `services.jar` `DragState`).** A drag whose clip
  is an app, a shortcut or a task (`containsApplicationExtras`) is sent only to the window it
  started in and to windows that intercept global drags (SystemUI's "ShellDropTarget"):
  `isValidDropTarget` refuses every other window. So the taskbar drawer's drag never reaches the
  bar's window or ZUX Home's home screen - only the drawer's own window ("Taskbar Overlay") and
  the shell. And while any system drag is under way the bar's touch region can stand in the way
  of the window below it (Launcher3 empties it only for the bar's own drags,
  `TaskbarInsetsController.updateInsetsTouchability`). A drag of our own MIME is no app clip and
  reaches every window. ZUX Home takes a drag from elsewhere into its own `DragController` with
  `DragOptions.simulatedDndStartPoint` and `onDragEvent` (`DragDriver.b`, as
  `BaseItemDragListener` does), which works from any window of the same process once each event
  is moved to the home screen's coordinates (the drawer's window and the home screen's do not
  share their origin, log 15:46).
- **The drawer's window during a drag (read 2026-10-13).** While a system drag is under way ZUI
  makes "Taskbar Overlay" untouchable (`TaskbarOverlayDragLayer.onComputeInternalInsets` empties
  its touch region when `TaskbarOverlayContext.s()`, any system drag, is true), so the shell under
  it takes the drag; the window is then told only that a drag started. Its layer keeps the bar's
  height clear at its bottom. ZUI's own listener on that layer (`TaskbarDragController.i0` ->
  `W`) answers false to a drop. ZUX Home's drawer hold (`ItemLongClickListener`, the all-apps
  instance) returns false even after it started its drag with `beginDragShared`; whether
  `DragController.isDragging()` is true says whether it took the hold.
- **A drag out of ZUX Home's drawer (read 2026-10-13).** `ItemLongClickListener` (all apps) ->
  `Workspace.beginDragShared(view, appsView, options)`: ZUI's popup is the pre-drag; once the
  finger moves past `deep_shortcuts_start_drag_threshold`, `Workspace.onDragStart` -> `L0` closes
  the popup and goes to `SPRING_LOADED` for an all-apps source - that one step takes the drawer
  away. Nothing in the drawer is a `DropTarget`; `DragController` asks the newest target first.
  ZUI's list has no item animator (`setItemAnimator(null)`) and no order of its own. A drop target
  that animates keeps `deferDragViewCleanupPostAnimation` and ends the drag itself
  (`DragView.remove`, `callOnDragEnd`; ZUI's own is the obfuscated `DragController.g`).
- **Who owns the drag card after a drop (read 2026-10-13, `DragState`, `TaskbarDragController`).**
  ZUI's drawer drag uses flags 2816, among them `DRAG_FLAG_REQUEST_SURFACE_FOR_RETURN_ANIMATION`:
  Android skips its own return animation and hands the drag surface to the source in
  `ACTION_DRAG_ENDED` only when the drop was not taken (ZUI then animates it back,
  `TaskbarDragController.I`). A drop taken by a window that does not intercept global drags gets
  no surface either, so the card stays where it was let go until Android's timeout. The shell, an
  interceptor, normally takes the drop and the surface with it. ZUI redraws its card for 300 ms
  from the drag's start (`k0`, `updateDragShadow` each frame).
- **Drop targets on ZUI's drag controller (read 2026-10-13, for 0.0.252).**
  - `isInState` is a default method of the interface `StatefulContainer`: a search of the
    launcher's classes never finds it (`Reflect.call` returned null and the drawer drags of
    0.0.250/0.0.251 never began). ZUI's own `Launcher.isInAllAppsState()` is declared.
  - `DragController.drop` sets `dragComplete`, then calls `onDragExit` on the target, and only
    then `acceptDrop` and `onDrop`. A target that undoes its preview on exit has to tell a drop
    by `dragComplete`.
  - `DropTarget.getDropView()` is a default method, `return (View) this`; `DragController.e()`
    maps every move into it (`mapCoordInSelfToDescendant`), so a target that is not a view must
    answer one - null throws on the first move. Answered with ZUI's `DragLayer`, the move's
    `DragObject.x/y` stay in the layer's coordinates.
  - ZUI's drawer list takes a new order through `DiffUtil` without moves (`isSameAs` is the view
    type, contents never the same) and no item animator: every cell stays where it is and is
    bound again with its new entry.
- **The drag's end and the bar's touch (read 2026-10-13, owner's videos 20:16-20:20).**
  `ACTION_DRAG_ENDED` carries no clip description, so a listener that recognises a drag by its
  clip has to recognise its end by its own state. While the taskbar's drawer is open the bar
  still takes touches over its row (ZUI's region, and ours added by `TaskbarFollow`); a drop of
  ZUI's drawer drag there lands in the bar's window, which Android never told of the drag, and
  is lost. Proven: the bar's window (type 2024, layer 25) lies over the drawer's (2038, layer 11,
  `WindowManagerPolicy.getWindowLayerFromTypeLw`); our touch region runs after ZUI's
  (`TaskbarFollow` re-registers last) and adds our row, start button and tray, our row as high
  as the bar (2902-3040 in portrait, probe 20:21); every missed drop in the videos 20:16-20:20
  was let go over the bar; neither the drawer's window nor the shell logged one of them. Not
  yet seen: the window that got each drop - traced from 0.0.254 (`SystemDrag`, "let go over
  ... told of the drag").
- **Selecting on the home screen (read 2026-10-12).** ZUI's edit mode (`LauncherState.EDIT_MODE`,
  `PMRLauncher.getEditModePanel()` = `ZuiEditModePanel`) selects: a tap there goes to
  `ZuiEditModeCustom.onItemClick` -> `switchSelectedState(view)` (home-screen apps and shortcuts,
  `WorkspaceItemInfo` and app pairs only; check mark by `drawSelectedFlag`; 24 at most). A drag of
  a selected icon carries every selected one (`MultiDragSupport.initSelectedViewAndDragView`,
  from `LauncherDragController.startDrag`; drop slots by `MultiDragExt.getDropLocations`). It
  can also make a folder of them (`combineFolderBySelectedIcons`) or remove them. ZUI has no
  icon wiggle; Launcher3's nearest is the repeating reorder shake (`ReorderPreviewAnimation`,
  mode 1, kept per icon in a map it is handed). The drawers have no selection.
- **ZUI's bar overflow (read 2026-10-12).** Only in ZUI's desktop mode
  (`zui_ov_desktop_mode` = 1, `TaskbarView.B`): `updateRecents` counts hotseat plus recent apps
  plus one against `getMaxNumIconViews()` (field `v`, from the width left after the search box,
  nav keys and margins, per icon and gap) and puts the excess recent apps behind
  `TaskbarOverflowView` (`taskbar_overflow_view.xml`). Tapping it toggles `#overflow_rv`
  (`TaskbarViewController.toggleOverlayList`), a horizontal list fed by `ZuiOverflowAdapter`
  (`GroupTask`s, ZUI's own icon views, click and drag as on the bar). The regular tablet bar
  has no overflow. Our row is not ZUI's items, so it cannot be fed into it as it is; an
  overflow for our row would be ours, in ZUI's look. The probe's "bar overflow" section reads
  ZUI's count, whether its button is up, and how much of our row fits.
- The taskbar's drawer takes back by registering itself with its window's
  `OnBackInvokedDispatcher` (`TaskbarAllAppsSlideInView`); a `Folder` registers nothing there, so
  back skips it. `AbstractFloatingView` is an `OnBackAnimationCallback` whose `onBackInvoked`
  closes it.
- ZUI's app popup fills its app-shortcut rows (`loadAppShortcuts`) only for ZUI's own icons
  (`instanceof WorkspaceIconCompat`); on any other view they stay empty placeholders.
- ZUI's bar builds its row from item infos (`TaskbarView.updateHotseatItems`): an app as ZUI's
  icon, a `FolderInfo` as `FolderIcon` opened by `expandFolder`. Handed our pins there
  (1.0.193-1.0.198), ZUI laid them out centred over the navigation keys (which it moved to the
  left), repeated them after its divider, and our row of open apps sat on top: the bar's row
  is ZUI's whole layout, not a list to slot items into. Pins are our own row again (1.0.199).
- ZUI's own Uninstall is `Utilities.startUninstallActivity(launcher, item)` (`ic_delete_zui`,
  `uninstall_item_title`); its factory leaves items outside the home screen out.
- ZUI's folder "Add apps" (`Folder.createAddAppButton` -> `BigFolderIconSelectDialog`) moves apps
  between the folder and the home screen (`addOrMoveItemInDatabase(item, -100, ...)`,
  `launcher.removeItem`). Hidden in our folders until it is routed to our drawer.
- ZUI's drawer list: `AdapterItem.viewType` picks the cell; `isIconViewType` (mask 1538) gives
  the icon span; `onCreateViewHolder`/`onBindViewHolder` switch on exact types and throw for one
  nobody supports. Items are matched across rebuilds by type (`AdapterItem.isSameAs`), so a type
  has to be given as `AdapterItem.asApp` makes the item. Row heights are cached per type
  (`ScrollableLayoutManager.mCachedSizes`). Our drawer folders are ZUI's own this way
  (`hook/drawer/ZuiFolders`, writes kept out of ZUI's database by `ZuiFolderWrites`).
- The A-Z bar in both drawers is `com.zui.launcher.views.RecyclerViewLettersScroller`, from either
  `all_apps_fast_scroller` layout (chosen by `Utilities.isZuiRow()`); no setting turns it off.
  ZUI shows it again whenever a search starts or ends (`ActivityAllAppsContainerView.x()`); its
  letters are a fixed A-Z array drawn in `onDraw`, which also sets its back-gesture exclusion.
- ZUI's taskbar opens a popup with `BaseTaskbarContext.showPopupMenuForIcon`: the bar's window
  full screen first (`setTaskbarWindowFullscreen(true)`), the popup on a post. When the popup
  leaves the drag layer, `TaskbarDragLayerController.onDragLayerViewRemoved` shrinks the window
  back (`onDragEndOrViewRemoved`). Only `TaskbarActivityContext` has these methods.
- ZUI's popup look: panel `ic_popupcontainer_bg_new` (`popup_container_bg`, a
  `popup_container_stroke` hairline, `popup_container_radius`), row text
  `popup_container_text_color` (`hook/ZuiLook`).
- ZUI's taskbar menus (`TaskbarPopupController.showForIcon`, list from `s(isInDpMode)`): in
  desktop mode App info, Lock, Unlock, Hide and Cast (`SystemShortcut.APP_INFO` via `f7411h`,
  `ZuiSystemShortcuts.LOCK_APP` / `UNLOCK_APP` / `HIDE_APP`, `CASTING`); out of it only Float
  open, split and window entries - no App info in the tablet bar's menus or its drawer's. The
  App info factory also refuses the bar's own icons out of desktop mode ("long press for taskbar
  view not show"). `ZuiAppRows` hands ZUI's own factories' shortcuts to the popup there, and
  `SystemShortcut.AppInfo` directly where the factory refuses.
- ZUI's taskbar drawer is `TaskbarAllAppsSlideInView`, made by `TaskbarAllAppsController.d` on
  every open: attached when it opens, `handleClose` as it starts closing, detached when gone.
  The start button's eyes follow these (`TaskbarStart.install`).
- ZUI's popups, folders and menus (`AbstractFloatingView`) sit in the bar's drag layer while
  open, with the bar's window full screen; they are not part of the bar's row.
- ZUI's bar builds its row (`updateHotseatItems`) before it is handed its apps
  (`TaskbarPopupController.setApps`, from `bindAllApplications`): at start the bar's `AppInfo[]`
  is empty; items built from it at start come up short.
- A hold in the taskbar's drawer is `TaskbarOverlayContext.getAllAppsItemLongClickListener()`, the
  drawer's own `TaskbarDragController.startDragOnLongClick`: popup and drag in the drawer's
  window. `TaskbarOverlayContext.onClickIconWithRightMouse` goes through the bar
  (`TaskbarActivityContext.showPopupMenuForIcon`), whose window goes full screen over the drawer
  and takes its touches.
- Back in the drawer: ZUI's key path (`TaskbarOverlayDragLayer.dispatchKeyEvent`) closes
  `AbstractFloatingView.getTopOpenView`; only the sheet registers with `OnBackInvokedDispatcher`.
- Add apps (`BigFolderIconSelectDialog`): `Folder.createAddAppButton` needs the delegate's
  `Launcher` and an open folder; ZUX Home's tap is `ItemClickHandler` -> `showDialog(launcher,
  folder)`; in the taskbar's drawer the tap is the bar's app launch. The picker reports
  `getSelectedItemInfos()` (status 2 picked, 1 unpicked); its OK (`L`) moves apps between the
  folder and the home screen, its Cancel (`K(launcher, view, view)`) closes with its motion. It is
  a window of the home activity; the drawer is a `TYPE_APPLICATION_OVERLAY` window above it.
- In the tablet's desktop mode ZUI's all-apps button opens the home's own drawer
  (`LauncherState.ALL_APPS` on `CustomModeLauncher`), not the taskbar's; ZUI's own
  `expandFolder` checks for it and closes it with `getStateManager().goToState(NORMAL)`. The log
  of 12:44 shows the start button taking that for no drawer and opening the taskbar's as well.
- The home's dock (`ZuiHotseat`) hands its icons to the tablet's bar while the bar lines up
  with it (`TaskbarLauncherStateController`, `Hotseat.setIconsAlpha`, channel
  `ALPHA_CHANNEL_TASKBAR_ALIGNMENT`) and takes them back whenever the bar's icons step aside -
  the taskbar's drawer opening is one. With the dock's apps kept off our bar, they surfaced
  under it then. `TaskbarUtilities.isTaskbarSwitchOpen(context)` says whether ZUI's tablet bar
  is on (always in desktop mode).
- SystemUI loads a title bar's name and icons off the main thread
  (`DesktopModeWindowDecoration$$ExternalSyntheticLambda0`): the veil icon only after checking
  `isClosed`, the name and header icon without, and `WindowDecorTaskResourceLoader` throws for a
  closed one ("non-existent decoration") - a crash of SystemUI seen as the monitor was unplugged.
- `OvcLog` reads `log.tag.OVC` on every call, so `setprop log.tag.OVC D` takes effect at once.
- A touchscreen is tied to a screen by the system, not by ZUI: each display gets an input
  viewport by its `DisplayDeviceInfo.touch` (internal, external, virtual by unique id;
  `DisplayManagerService.getViewportType`), handed to the input system in
  `InputManagerService.setDisplayViewportsInternal`. An external touchscreen goes to the external
  viewport, and without a valid one to the internal screen. No ZUI app ties input to a display
  (`addPortAssociation`, `setDisplayIdForInputDevice` appear nowhere in them).
  The input system's own explicit tie is `InputManagerService.addUniqueIdAssociationByDescriptor`
  (input device descriptor -> display unique id), applied by `hook/system/TouchScreens` to each
  external touchscreen while there is one external screen (1.0.200).
- ZUI's compact popup works beside any view: `popup_container_zui` +
  `populateAndShowRowsZui(view, 0, [], shortcuts)` - no app shortcuts asked for, no placeholder
  rows (`ZuiNavPopupController` does the same for its buttons).
  With 0 app shortcuts ZUI loads no shortcut icons itself: each row's icon is exactly the
  bitmap handed in. ZUI loads an app shortcut's icon with `CacheableShortcutInfo.getIcon(ctx,
  info, dpi, true)` (Android's icon in ZUI's icon theme); when the app gives none its icon cache
  falls back to a default icon.
- Closing the taskbar drawer's window the ZUI way (`TaskbarOverlayController.hideWindow`) closes
  every view open in it (`AbstractFloatingView.closeAllOpenViews`), and the window stays while
  any view is open. Closing only the sheet (`TaskbarAllAppsSlideInView.close`) leaves a folder
  open in it over the home screen.
- ZUI's `FolderIcon` name (`folder_icon_name`, a `BubbleTextView`) is laid out for the home
  screen and does not shorten to its width; ZUI's own drawer names do (`all_app_item_text_color`,
  their own size, lines and ellipsis).
- The taskbar drawer's list with 3-button navigation: ZUI pads its bottom by the bar's height
  (`AdapterHolder.c`, `rect.bottom` outside `TaskbarActivityContext`) but sets its bottom margin
  to 0 and draws its nav-bar cover only in `Launcher`'s drawer, so rows scroll under the bar.
- Folder blur: in `Launcher` ZUI cuts the folder's shape out of the home's views
  (`makeFolderBlurClipOutRect`) and blurs the wallpaper behind it with a blur region on the
  home's window (`setWallpaperBlur` -> `LauncherBlurAreaData` -> `Launcher.updateAreaBlur` ->
  `setBlurRegions`); on the bar it is a region on the bar's window
  (`TaskbarActivityContext.setFolderBlurParams`). A folder in the drawer's overlay gets neither,
  and a window region could not blur the drawer anyway: the sheet is in the same window.
- ZUI's bar opens a folder with `TaskbarActivityContext.expandFolder(FolderIcon)`: bar window
  full screen, then `animateOpen` posted, then ZUI's own click and hold on every item inside
  (`x0`, `mapOverItems`). Nothing needs the icon to be in `TaskbarView` (parents are checked
  with `instanceof`; placement is `getDescendantRectRelativeToSelf`), and full screen ends by
  itself when the folder leaves the drag layer (`TaskbarDragLayer.onViewRemoved` -> `r1`).
  `createAddAppButton` needs a `Launcher` from the folder's delegate, so bar folders have no Add
  apps button.
- ZUI's folder blur animation (`FolderBlurBgAnim`, render blur 0 -> 70, wallpaper 0 -> 400, which
  drives `onFolderOpen/CloseBlurParamsChanged` -> `setWallpaperBlur`) is added in
  `FolderAnimationManager` only when the folder's own context is `Launcher` (`isFromLauncher`);
  bar folders get `FolderBlurBgTbAnim` instead. A folder in the taskbar drawer's overlay gets
  neither.
- The icon shape is a system overlay: `ShapeIconService` ("shape_icon") turns on one
  `com.lgsi.iconmask.overlay.*` overlay on `android` (`config_icon_mask`) and writes
  `persist.sys.iconmask`. The launcher hears it through `graphics.ThemeManager` (an
  OVERLAY_CHANGED receiver on `android`), which tells its `ThemeChangeListener`s
  (`addChangeListener`); ZUI's own clears its icon pool and `forceReload`s the model. The taskbar
  is not made again (the config change's assets flag goes to `onAssetsPathChange`). A ZUI icon
  theme comes through `LauncherModel.onZuiThemeChanged(Intent)`.
- Night mode reaches the bar as `TaskbarActivityContext.onUiModeChange()`;
  `NavbarButtonsViewController` colours the keys `taskbar_nav_icon_dark_color_on_home`
  (#ff333333) by day and `..._light_color_on_home` (#ccfafafa) at night
  (`Utilities.isDarkTheme`: uiMode night). The bar is `taskbar_background` (#f7f7f7 / #000000);
  ZUI's own running dot is `taskbar_running_app_dot_indicator_color` (#191919 / #fafafa).
- SystemUI never makes a window's title bar again when the window changes screen
  (`DesktopModeWindowDecorViewModel.onTaskInfoChanged` only relays it out); its copy
  (`mTaskInfo`) follows shell transitions. The "…" menu (`OvHandleMenu`) of a window its copy
  thinks full screen is added through the WindowManager of the screen the title bar was made on.
  ZUI moves a window to the monitor with `SystemUiProxy.moveToExternalDisplay(taskId)` ->
  `IDesktopMode` (transaction 13) -> `DesktopTasksController.moveToNextDisplay`, a CHANGE
  transition SystemUI follows.
- At boot ZUI's taskbar is built a moment before its drawer's list (and so its app store as we
  read it): our bar folders, made from that list, wait for it.
- A folder's popup is built from `Launcher.getSupportedShortcuts()`; with big folders supported
  it holds `SystemShortcut.SWITCH_FOLDER` ("Enlarge folder", factory `getShortcut(context,
  info, view)`) and `RENAME_FOLDER`. The factory makes `SwitchFolder` for any `FolderInfo` of
  item type 2 outside desktop mode, and its click casts the icon's layout params to the home
  grid's `CellLayoutLayoutParams` - a folder anywhere else stops ZUX Home. Rename only calls
  `folder.animateOpen`. Our folders reach this popup because `Workspace.beginDragShared` sets
  `FolderIcon.startLongPressAction()` (`PopupContainerWithArrow.showForIcon`) as the drag's
  `preDragCondition` for any `FolderIcon` when `BigFolderConfig.isSupportFeature()` (read
  2026-10-13).
- The drawer's list (`AlphabeticalAppsList`) is refilled from the app store by
  `onAppsUpdated()`, which then calls `updateAdapterItems()`; `updateAdapterItems` alone lays
  out the list it already has.
- ZUI's loader (`LoaderTask.run`) binds the home screen, then waits (`waitForIdle`: "step 1
  loading workspace complete") until the main thread is idle before `LoadAllApps`. The wait is
  `BaseLauncherBinder.newIdleLock(this)`, a `LooperIdleLock` - an idle handler on the main
  looper, waited on a second at a time with no limit (`while (!stopped &&
  lock.awaitLocked(1000))`). Its `queueIdle()` takes the loader's monitor, clears its waiting
  flag and notifies; the loader holds that monitor while it runs, so `queueIdle` must not be
  called from the main thread. With ZUX Home in front its main thread did not go idle for 14-23
  s after a boot (logs 18:59, 21:31, 21:58), and the drawer stayed empty until an app covered
  it.
- The icon a drag starts from is hidden by ZUI for the drag: `PopupContainerWithArrow`'s
  pre-drag (`onPreDragEnd` with the drag started) calls `mOriginalIcon.setVisibility(INVISIBLE)`
  (`mNotHideOriginalIcon` is never set). ZUI shows it again through its own drop targets (its
  home screen's) and `DragController.cancelDrag` (for a `FolderIcon` only); a drop handled
  elsewhere has to show it itself (read 2026-10-13).
- ZUI's own way in for a drag from outside its views: `DraggableView.ofType(int)` (a static
  interface method; `DRAGGABLE_ICON` 0) as the drag's `originalView`, as
  `PendingItemDragHelper` does; the `DragSource` is told the outcome in
  `onDropCompleted(target, dragObject, success)`. With `DragOptions.simulatedDndStartPoint` set
  the drag is driven by `SystemDragDriver` from `DragController.onDragEvent`: LOCATION moves,
  DROP moves and ends, EXITED leaves the drop target, ENDED cancels.
- ZUI lands a drop with `DragLayer.animateViewIntoPosition(DragView, x, y, alpha, scaleX,
  scaleY, Runnable onDone, completion, duration, View anchor)` -> `animateView`, which first
  cancels the `DragView`'s own animation and then runs its own animators (`View.SCALE_X/Y`,
  `VIEW_TRANSLATE_X/Y`, alpha). With completion 0 its end is `clearAnimatedView()` ->
  `DragController` removing the picture and, with `deferDragViewCleanupPostAnimation`,
  `callOnDragEnd()`. The target of an icon is worked out in `animateViewIntoPosition(DragView,
  View, int, View)`: `getDescendantCoordRelativeToSelf(view, pos)` gives the scale, the icon's
  `getWorkspaceVisualDragBounds`, then scale = bounds width / (picture width -
  `getBlurSizeOutline()`) x that scale, and x = pos + bounds.left x scale0 - blur x scale / 2 -
  width x (1 - scale) / 2 (y the same). A `View.animate()` on the picture is not followed
  (video 14:17, read 2026-10-14).
- `UserManager.getSerialNumberForUser` is a call into the system process each time; a key made
  per drawer cell per finger move made drags 17-47 ms of input per frame (0.0.260's frame
  trace). A user's serial does not change while the process lives.

## ZUX Home's home grid and its dock's band (read 2026-10-14)

- A grid is one of the `<grid-option>`s of `res/xml-sw480dp/device_profiles.xml` (ZUI forces the
  factory density, so the sw480 bucket): "6x4" (default), "5x4", "6x5" (`canBeShown=false`),
  and the custom 7x5 of its work and desktop modes. Names are columns x rows. ZUI's home
  settings (`ZuiLauncherFragment`) list `Utilities.queryGridOptions` (`GridCustomizationsProxy`
  over `parseAllGridOptions`, the shown ones); a choice goes through
  `GridCustomizationsProxy.update("/default_grid")` -> `IDP.setCurrentGrid` ->
  `onConfigChanged`, kept in `SettingsValue` "current_layout_config_key".
- A partner app may set the grid: `InvariantDeviceProfile.v(Context, DisplayMetrics)` reads
  `Partner` "grid_num_rows"/"grid_num_columns" into the public `numRows`/`numColumns` before
  any `DeviceProfile` is made (`HomeGrids` used it until 0.0.268). `CellLayout.onMeasure` sizes
  its cells from its own size (`calculateCellWidth/Height`).
- The picker's list is `IDP.parseAllDefinedGridOptions(Context, Info)`. A name is resolved by
  the static `IDP.F(Context, String name, Info, boolean, boolean)`, which returns the display
  options (`IDP$a`, its `GridOption` in field `f2381a`); `G`/`H` write
  `SettingsValue.setCurrentLayoutConfigKey` when the name it resolved differs from the one asked.
  `GridOption`'s `name`, `numRows`, `numColumns`, `canBeShown` are final but set by reflection.
  Since 0.0.269 `HomeGrids` appends copies of "6x4" resized to 8x5, 8x6, 10x7, 12x8, shows
  "6x5", and resolves those names in `F` to "6x4" with its options resized.
- Icon size on these grids: `DeviceProfile.I(Context)` sets `iconSizePx`/`iconTextSizePx` and
  calls `updateIconSize(float, Context)`; for a grid that is neither scalable nor responsive (all
  of ZUI's here) that only works the cell height out from the icon and never shrinks it
  (:1680-1694). `getCellSize()` gives the real cell; `Utilities.calculateTextHeight(float)` the
  name's height. The dots are made right after `I`, in the constructor:
  `DotRenderer(Context, iconSize, Path, pathSize, circleHeight, textSize, boolean)`, the
  workspace's and the drawer's through a cache keyed by icon size (`DeviceProfile.g`).
- Migration: `GridSizeMigrationTask$MultiStepMigrationTask.migrate(Point from, Point to)` holds
  an `SQLiteDatabase` field; the home screen's rows are table `favorites`, container -100,
  widgets itemType 4 and 5 with `spanX`/`spanY`.
- At load `GridSizeMigrationTask.migrateGridIfNeeded` compares `numColumns x numRows` (and the
  hotseat count) with the size it saved and moves the items (`MultiStepMigrationTask`, one row
  or column a step). Items that no longer fit go to new pages up to `getMaxPageCount()` (9);
  past that they are removed. ZUI treats exactly 7x5 as its work-mode grid (`DeviceProfile`
  :853, :2244).
- The dock's band is `DeviceProfile.hotseatAreaHeight` = `hotseat_bar_height_pad` +
  `launcherTaskBarOffset` (`L()` :801). `N(Context)` (the workspace padding) sets
  `workspacePadding.bottom` from it; the page dots and scrim follow the padding. In
  `Utilities.isUnlimitedWorkMode` (tablet desktop mode) `N` takes another path. No setting gives
  the band up. A drop goes to the dock only when `ZuiHotseat.getCustomBoundsView()` is not null
  (`Workspace.D1`, its only caller); ZUI's desktop mode has the dock stand aside for touches.

## Drags in the drawers, the taskbar's drag controller and edit mode (read 2026-10-14)

- **A folder opening under a drag:** `Folder.beginExternalDrag()` opens the folder with an empty
  place and adds the folder as a drop target on the Launcher's `DragController`; it reorders under
  the finger (`realTimeReorder`) and `Folder.onDrop` saves through
  `ModelWriter.addOrMoveItemInDatabase(item, folderId, ...)`, then goes to `NORMAL`. ZUI's home
  calls it from `FolderIcon`'s open alarm (800 ms). `TaskbarDragController.addDropTarget` ignores
  any `Folder`, so in the taskbar's drawer a folder never opens under a drag.
- **The taskbar's drag controller** (`com.android.launcher3.taskbar.TaskbarDragController`):
  `callOnDragStart()` keeps the drag in the drawer, on its own controller, when
  `mDisallowGlobalDrag` (dex field `o`, `setDisallowGlobalDrag`) is set or the bar is in desktop
  mode; otherwise it sets `startSystemDrag` and calls `o0(View)`, the system drag from the
  current `dragView` (floating window and split screen; the drawer closes). With the flag set,
  `endDrag` animates the picture back to its icon (300 ms) unless the drop succeeded (field `m`,
  from the source's `onDropCompleted`); `exitDrag` leaves the picture. `startDragOnLongClick`
  reads the flag too. Its fields: `mActivity` (the `TaskbarOverlayContext` for the drawer),
  `mDragObject` (`originalView`, `dragInfo`, `dragView`, `x`, `y`, `startSystemDrag`,
  `deferDragViewCleanupPostAnimation`, `dragComplete`). The drawer's sheet is
  `getVisibleContainerView()` of its `AllAppsContainerView`. The overlay's drag layer has no
  `animateViewIntoPosition`.
- **ZUI's Cancel target** (`DropTargetBar2`, `DeleteDropTarget2`, made only with ZUX Home's
  `Launcher`): drawables `ic_remove_target` and `ic_drop_target_bg`, colours
  `delete_target_background`/`_hover`, dimens `dynamic_grid_drop_target_size_2`,
  `..._margin_top`, `..._textsize`; it fades in 175 ms (`DropTargetBar.animateToVisibility`).
- **Edit mode's marks** (`ZuiEditModePanel`, up to `MAX_SELECTED_COUNT` 24): drawables
  `edit_mode_selected`/`edit_mode_unselected`, drawn at `iconBounds.right + offset - width`,
  `iconBounds.top - offset` (dimen `edit_mode_checkbox_offset`, `drawSelectedFlag`). Its Done:
  drawable `edit_view_drag_hint_bg`, colour `edit_mode_drag_hint_text_color`, dimens
  `edit_mode_complete_text_size`, `edit_mode_drag_hint_height`, `edit_mode_drag_hint_padding_h`,
  string `gesture_tutorial_action_button_label`.
- **The pickup:** `DragView` animates from the scale it is made at (`setScaleX`) to fields `g`
  (x) and `D` (y), `(width + extra) / width`, over 150 ms. Its start is the scale the held view
  is drawn at, so a view drawn scaled up (our drawer folders) starts near its end.

## More of ZUX Home's drag, folders and menus (read 2026-10-15)

- **A folder as a drop target:** `Folder.c0` (its open) adds itself to its controller's targets
  only when it has no parent; `isDropEnabled` is false while it animates (`mState == 1`). The
  taskbar's `TaskbarDragController.addDropTarget` refuses any `Folder`. `DragController.e` asks
  its targets newest first (list field of `DragController`, the one holding the targets).
- **A drag from inside a folder:** on the home screen `Folder.startDrag` sets `p` (the rank) and
  `t` (the view) and hands the drag to its delegate (`Workspace.beginDragShared`, the folder as
  the drag's source); `Folder.onDragStart` takes the app out of its pages only when the drag's
  source is the folder. Outside ZUX Home the delegate (`LauncherDelegate$b.b`) does nothing.
  `Folder.onDropCompleted` puts the app back when the drop failed.
- **The held icon:** `PopupContainerWithArrow`'s pre-drag hides it when the drag starts
  (`onPreDragEnd` → `INVISIBLE`); the taskbar's `TaskbarDragController.d0` brings back only its
  alpha. `n0` starts an app's taskbar drag: `DragPreviewProvider`, `getScaleAndPosition`,
  `startDrag(Drawable, View, DraggableView, x, y, DragSource, ItemInfo, Rect, startScale,
  dropScale, DragOptions)` with the icon's pressed scale as the start; its source sets field `m`
  (a drop that took it), and without it `endDrag` animates the picture back (300 ms).
- **Press:** app icons press through `FastBitmapDrawable` (0.8 in 200 ms accelerating, back in
  200 ms decelerating; `BubbleTextView.O`). `FolderIcon` has no press; its `dispatchDraw` draws
  the background, apps and dot before its name.
- **Multi-drag** (`MultiDragSupport`): each chosen icon gets a `DragView`; they gather under the
  finger in 150 ms (`initSelectedViewAndDragView`) and fly onto their target on drop (`x`:
  scale to the target's visual bounds, translate, linear, 300 ms).
- **Floating window and split screen in taskbar menus:** `TaskbarPopupController.s()` adds
  `FLOAT_OPEN` (`ZuiSystemShortcuts.FloatOpen`, only where `isPackageSupportZuiFreeform`) and the
  split options of `TaskbarUIController.d(controllers)` (`TaskbarSplitShortcut`, one per side).
  `SystemShortcut` keeps its label in `mLabelResId` and its icon in `j`.
- **No "remove folder" in ZUI:** a folder goes when one app is left
  (`FolderIcon.replaceFolderWithFinalItem`). Apps leave a folder by `Folder.removeFolderContent`;
  free cells from `CellLayout.findCellForSpan(int[], 1, 1)`; `ModelWriter.moveItemInDatabase`,
  `Launcher.createShortcut`, `Workspace.addInScreen` (a default method of
  `WorkspaceLayoutManager`).
- **The home screen's bands (tablet, portrait):** the page dots' band is added to the workspace's
  bottom padding in `DeviceProfile.N` (`workspacePageIndicatorHeight - r`,
  `workspace_page_indicator_height` 60 dp); each page's top band is `cellLayoutPaddingPx.top`
  (60 dp, `Utilities.getCellLayoutPaddingVertical`). The dots are alpha 0 at rest.
- **The taskbar's drag picture:** for drags from the bar's own window, ZUI turns it into the
  floating window card when it leaves the bar and back into the icon over it
  (`TaskbarDragController.W`, `p0`/`m0`, `updateDragShadow`); for the drawer's window it starts as
  the card (`b.onDrawShadow`). SystemUI's drop window (`DragAndDropController.onDrag`) hides its
  zones on leaving unless `mIsZuiSplit`, and shows them on entering (`DragLayout.show`).

## The taskbar's drag, drawer taps, folders held and the controller (read 2026-10-17/18)

From the owner's crash reports, then checked against ZUX Home and `services.jar` decompiled again
on 2026-10-18.

- **The drop's spoken announcement:** `TaskbarDragController.startDrag` gives the drag a
  `DragViewStateAnnouncer` (field `stateAnnouncer` of `DropTarget.DragObject`) unless it is an
  accessible drag; the announcer exists only while an accessibility service is on
  (`DragViewStateAnnouncer.createFor`). `Folder.onDrop` ends with its `completeAction`, which asks
  `Launcher.getLauncher` for the view's context: in the taskbar's windows that throws ("Cannot
  find BaseActivity in parent tree") and ZUX Home stops. ZUI never meets it - the taskbar's
  controller refuses folders as drop targets.
- **The taskbar's drag size:** `TaskbarDragController.g` is the size of the icon being dragged
  (the taskbar profile's `iconSizePx` at first), set by `n0` to the icon's `getIconSize()`;
  `startDrag` gives its `TaskbarDragView` a growth of
  `g` less the drag rect's width (`DragView`: from its start scale to
  `(width + growth) / width`). For an app the two are equal: no growth. Its flight back (`k0`)
  sizes the view by its whole width unless it is a `BubbleTextView` or `ActiveIconView`.
- **Where an app icon takes a touch:** `BubbleTextView.onTouchEvent` lets a touch down go when
  `CheckLongPressHelper.isContentRect(Context, View, x, y)` says it is off the icon's content
  (unless it is from a mouse or TalkBack is on). That area is a square of the home profile's
  `iconSizePx` (`LauncherAppState...getDeviceProfile(context)`), centred at the view's top
  padding - the name is never in it - for app icons, folders and app pairs alike, in the drawers
  too, whatever size they draw their icons at.
- **A folder held on the home screen:** no press of its own (`FolderIcon.onTouchEvent` only arms
  the long press). `Workspace.beginDragShared` picks it up at its own scale (an app's at its
  pressed scale), with ZUI's folder menu as the pre-drag (`FolderIcon.startLongPressAction` →
  `PopupContainerWithArrow.showForIcon`, which asks for `Launcher.getLauncher`: ZUX Home's
  window only); while the menu shows the picture grows by `pre_drag_view_scale`, 6 dp, in 150 ms
  (`LauncherDragController.startDrag`).
- **A drop into a folder** (`Folder.onDrop`): the folder's delegate's `Launcher` (`e()`; null
  for a folder outside ZUX Home, and `onDrop` then returns) makes ZUI's item
  (`WorkspaceItemFactory.makeWorkspaceItem(Context)`, an `AppInfo` from the drawer), adds it at
  the folder's empty place, and lands the drag picture on that Launcher's `DragLayer`
  (`animateViewIntoPosition(DragView, View, View)`; with a picture not drawn yet it shows the
  icon at once instead). The landing's end calls that layer's controller
  (`clearAnimatedView` → `DragController.g`), which reads its own drag object - null with no drag
  of its own. Then `multiDragSupport.onDropFolderSuccess` files ZUI's own chosen items.
  `Folder.addFolderContent(ItemInfo, rank, animate)` adds one item through ZUI's model writer.
- **Back keys:** ZUX Home sets `enableOnBackInvokedCallback="true"`, so a back key goes first to
  the window's top `OnBackInvokedCallback` (`ViewRootImpl.doOnBackKeyEvent`) and is then
  forwarded to the views marked cancelled. `TaskbarOverlayDragLayer.dispatchKeyEvent` closes the
  top open view on a back key's release without checking `isCanceled()`; Escape closes it on
  press. The drawer's sheet registers itself as a back callback (`TaskbarAllAppsSlideInView`).
- **The drawers' focus mark:** `FocusedItemDecorator` over the list, its listener set on each app
  icon by the adapter (`BaseAllAppsAdapter.mIconFocusListener`); it draws at the focused view's
  left and top in its parent (`SimpleFocusIndicatorHelper.viewToRect`). ZUI's folder focuses its
  first app on opening (`setFocusOnFirstChild`) and its icon again on closing.
- **ZUI's folder menu:** Enlarge (`SystemShortcut.SWITCH_FOLDER`, not in desktop mode) and Rename
  (`RENAME_FOLDER`). Rename closes the menu and after `config_popupOpenCloseDuration` (150 ms)
  calls `Folder.animateOpen(true)`, the folder opening with its name ready to edit.
- **The drawer's icon size:** `DeviceProfile.allAppsIconSizePx` (kept under its own name).
- **A game controller's buttons** (Android's `Generic.kcm`, unchanged by ZUI as far as the logs
  show): A falls back to DPAD_CENTER, B to BACK, X to DEL, Y to SPACE, Start to DPAD_CENTER,
  Select to MENU, Home (`BUTTON_MODE`) to HOME; L1/R1/L2/R2 to nothing. A fallback is pressed only
  when the app in front leaves the button unhandled. ZUI's `PhoneWindowManager
  .interceptKeyBeforeDispatching(IBinder, KeyEvent, int)` returns a `long`, -1 dropping the key
  before any window sees it; ZUI adds a pass-through of its own after the key combinations
  (`continueDispatchKeyByMotoPolicy`). Nothing in it treats a controller's buttons. A broadcast's
  sender (`BroadcastReceiver.getSentFromUid`) is told only when the sender shares it
  (`BroadcastOptions.setShareIdentityEnabled`); the system's own sends do not, so a receiver
  limits its senders by a permission instead.

## Folders in a drag, dissolving, popups and the home screen to the bar (read 2026-10-18)

For 0.0.277, from the owner's log of 11:00 and ZUX Home 18.2.0.0375 decompiled.

- **The taskbar keeps a drag in its drawer by closing everything else:**
  `TaskbarDragController.callOnDragStart`, with `setDisallowGlobalDrag` on (field `o`) or in
  desktop mode, calls `AbstractFloatingView.closeAllOpenViewsExcept(ctx, 262144)`
  (`TYPE_TASKBAR_ALL_APPS`); the 2-argument form calls `(ctx, boolean animate, int except)`. Any
  open folder of the drawer closes as a drag starts, and ZUI's popup with it.
- **A drag from inside a folder** (`Folder.onLongClick` → `startDrag(view, options)`: `p` the
  rank, `t` the view, `Z` adds the folder as a drag listener, `d0` → its delegate's `b` →
  `Workspace.beginDragShared(view, folder, options)`; the taskbar delegate's `b` does nothing).
  `onLongClick` asks `getIsLauncherDraggingEnabled` and `LauncherCustom.skipHotseatDrag` (popup
  only in OVERVIEW). `Folder.onDragStart` takes the icon off its pages and, inside `r0`, out of its
  `FolderInfo` (`removeFolderContent`, its dissolving skipped while `r0` runs). `onDragExit` closes
  the folder 400 ms after the drag leaves it (`completeDragExit`); `onDragOver` speaks each new
  place through the drag's announcer. `onDropCompleted(target, drag, success)`: not taken, the app
  goes back; taken elsewhere with one app left, `W0`.
- **Dissolving:** `Folder.a1` refuses to open a folder of one ("Couldn't animate folder open").
  `Folder.bind` posts `E0`: one item or none → `X0(true)`. `W0` is `X0(false)`. `X0` sets `B` from
  `mLauncherDelegate.i(folder, z)`: the taskbar delegate's `i` does nothing; ZUX Home's
  (`LauncherDelegate.i`, if `ZuiEditModeCustom.replaceFolderWithFinalItemEnable`) inflates the last
  item into the folder's cell layout, `addOrMoveItemInDatabase`, `Launcher.removeItem` of the
  folder (`deleteCollectionAndContentsFromDatabase`) and `addInScreenFromBind`.
- **Popups:** `PopupContainerWithArrow.showForIcon(View)` (ZUX Home's, needs `Launcher`) and
  `TaskbarPopupController.showForIcon(View)` (the bar and its drawer; reached from
  `TaskbarControllers.taskbarPopupController`, which `TaskbarDragController` holds) both
  `requestFocus()` the popup as it opens; `getOpen(ctx)` is `getOpenView(ctx, 2)`. A folder's own
  hold menu is `FolderIcon.startLongPressAction` → `showForIcon(this)`. The drawers' layers see
  every key first: `dragndrop.DragLayer.dispatchKeyEvent` (ZUX Home; overview's arrows aside, to
  its drag controller then up) and `TaskbarOverlayDragLayer.dispatchKeyEvent` (back and Escape).
  `ZuiSystemShortcuts.REMOVE_ITEM` shows only for an item with an id, or in the dock.
- **The home screen's drag:** a drag listener's `Workspace.onDragStart(DragObject, DragOptions)`
  sees every drag of ZUX Home's controller; the source is the `Workspace`, the `Hotseat`, a
  `Folder` or a drawer. ZUI's drop targets are asked newest first (`DragController.e`).
  `Launcher.removeItem(View, ItemInfo, boolean deleteFromDb, String)`: an item in a folder leaves
  it (`removeFolderContent`), any other leaves its cell; then the database row goes (for a folder,
  with its contents). `Workspace.onDropCompleted` takes the cell away itself for a drop taken
  elsewhere (`ZuiEditModeCustom.canRemoveWorkspaceItem`). A group dragged in edit mode is in
  `DragObject.multiDragSupport.selectedViews`.
- **Item types:** `ItemInfo.itemType` 0 is an app; ZUI's stand-ins for our folders' apps
  (`makeWorkspaceItem`) have no id (-1), so `REMOVE_ITEM` is never offered for them.

## A folder's app back in, the drag picture and what a home page draws itself (read 2026-10-18)

For 0.0.278, from the owner's material on 0.0.277 (LSPosed zip 21:30:29, probe 21:31:01, videos
21:24-21:31) and ZUX Home 18.2.0.0375 decompiled.

- **An app back into the folder it came from:** `Folder.beginExternalDrag` adds an empty place
  at the end and opens the folder - on ZUI's home screen the folder icon's open alarm does this
  for the app's own folder too. `Folder.onDrop` with the folder itself as the drag's source puts
  its own view (`t`, the one `onDragStart` took off its pages) back at the new rank (`p`).
- **The drag picture:** `DragPreviewProvider.createDrawable` draws the view at its `scaleX`
  through `drawDragView(Canvas, float)` (jadx shows `h`) into a hardware bitmap the size of
  `getSourceVisualDragBounds` plus `blur_size_medium_outline` (and, for a folder that is not big,
  `blur_size_medium_outline_folder_diff`), and returns it as a `FastBitmapDrawable`. `DragView`
  puts it in an `ImageView` of its intrinsic size and scales itself; `getPreviewBitmap` goes
  through `Utilities.drawableToBitmap`, which takes any `Drawable`. Nothing casts a folder's drag
  picture: `Workspace` and `TaskbarDragController` look only at a `BubbleTextView`'s icon.
- **Hardware bitmaps:** ZUI's icons are hardware bitmaps (`BitmapRenderer.USE_HARDWARE_BITMAP`),
  which a software `Canvas` cannot draw. `PreviewItemManager.drawParams` catches the error, so a
  folder drawn on a software canvas comes out as its background alone. ZUI draws its pictures
  through a `Picture` (`BitmapRenderer.createHardwareBitmap`): its canvas records hardware bitmaps
  (`onHwFeatureInSwMode`) and `Bitmap.createBitmap(Picture)` renders it on the GPU.
- **What a home page draws itself**, with no view of its own:
  - `CellLayout.onDraw`: its background; each `DelegatedCellDrawing` of its list (`j`,
    `drawUnderItem`) at the drawing's `mDelegateCellX/Y`; its folder leave-behind
    (`PreviewBackground` `k`, `drawLeaveBehind` in a translucent grey, `S`) while its
    `mDelegateCellX` is 0 or more. `dispatchDraw` draws the drawings' `drawOverItem`.
  - `visualizeGrid`, while flag `w` or `x` is set: the drop outlines (cells `o`, alphas `p`).
  - A `PreviewBackground` joins the list as a folder's accept ring (`n`,
    `addDelegatedCellDrawing`) and leaves it at rest (`m`). The leave-behind is set only in the
    dock (`ShortcutAndWidgetContainer.drawFolderLeaveBehindForIcon`, container type 1).
  - The probe lists all three for each page ("drawn over a cell", "leave-behind", "outline").

## The controller's focus mark and ZUI's popups (read 2026-10-18)

For 0.0.279, from ZUX Home 18.2.0.0375 decompiled.

- **The drawer's focus mark:**
  - `ActivityAllAppsContainerView` adds a `FocusedItemDecorator` to the list. It is a
    `SimpleFocusIndicatorHelper`, whose `viewToRect` is the view's left, top, right and bottom
    in its parent.
  - It hands the helper to its adapter (`setIconFocusListener`, kept in
    `BaseAllAppsAdapter.mIconFocusListener`), which sets it on each app it binds.
  - The mark is a stroke in `Utilities.getFocusRectColor` (`focused_background`, or the
    system's accent with Monet on), `focused_background_stroke_width` 2 dp, corners
    `focused_background_radius` 20 dp. `shouldDraw` needs the view attached.
- **Popup rows and focus:**
  - Text rows (`deep_shortcut_zui`: `DeepShortcutView` on `middle_item_primary_zui`;
    `system_shortcut` on `ic_popupcontainer_bg_new`) have plain shapes behind them, with no
    focused state.
  - The icon buttons (`system_shortcut_icon_only_zui`, style `PopupItemIconOnly`) have
    `selectableItemBackgroundBorderless`, a ripple that shows focus.
  - `PopupContainerWithArrow` makes itself focusable in touch mode as it opens.
- **A folder's hold menu** is `FolderIcon.startLongPressAction` → `showForIcon(this)`. Triangle
  on one of ZUI's own folders goes there: it is neither one of ours nor an app.
- **Recents and the pages:** in ZUI's recents the pages' icons are faded but the pages
  themselves still draw (`CellLayout.onDraw`: cell drawings, leave-behind, `visualizeGrid` with
  its outlines and `MultiDragExt` cells). A mark seen only in recents is the page's own
  drawing.
- **The dock's predictions:** `HotseatPredictionController.onDragStart` adds outline drawings for
  predicted icons. `onDragEnd` calls `h(true)`, which is empty in this build, so they are
  removed only at the next drag's start (`s` → `r`). None were in the dock in the 21:31 probe.

## The drag picture's constructors and the taskbar's focusable window (read 2026-10-18)

For 0.0.280, from ZUX Home 18.2.0.0375 decompiled.

- **`DragView` is built in three steps.** `DragView(ctx, Drawable, regX, regY, initial, onDrop,
  growth)` calls `DragView(ctx, View, w, h, regX, regY, initial, onDrop, growth)`, which calls
  `DragView(ctx, View, w, h, regX, regY, initial, onDrop, SizeF, boolean)`. The last one:
  - sets the end scales `g` and `D` (`(w + growth) / w`);
  - sets the scale to `initial`;
  - adds the 150 ms growth animator (`n`: from `initial` to `g`).

  A hook on all constructors runs after each of the three. `TaskbarDragView` is a `DragView`
  whose `animateTo` scales to `mScaleOnDrop`.
- **The taskbar's window and keys:**
  - `TaskbarActivityContext.setTaskbarWindowFocusable(focusable, ime)`:
    - true clears `FLAG_NOT_FOCUSABLE` and `FLAG_NOT_TOUCH_MODAL`, and with `ime` also
      `FLAG_ALT_FOCUSABLE_IM`, so the window then takes every touch;
    - false sets both back.
  - ZUI calls it from `onPopupVisibilityChanged`: still true while a folder of its is open
    (`getOpenView(this, 1)`). It also calls it from a folder's state (open → true) and for the
    IME.
  - `TaskbarDragLayer.dispatchKeyEvent` gives back's release to the top open view that can take
    it, and passes every other key on.
  - The bar's window is a navigation-bar panel (type 2024) above every app, so while it is
    focusable it keeps the keys from the app below.
- **Back over the taskbar's drawer:** ZUX Home takes back through callbacks, and in the
  taskbar's drawer the drawer's sheet registers one (`TaskbarAllAppsSlideInView`). ZUI's
  popups (`ArrowPopup.show`, closed by `handleClose`/`closeComplete`) register none, so back
  closes the drawer with a popup open in it.
- **Blur areas behind the launcher's window:**
  - `Launcher.updateAreaBlur` sends `SurfaceControl.Transaction.setBlurRegions` for every
    `LauncherBlurAreaProvider` (`addBlurAreaProvider`) whose `LauncherBlurAreaData` has a
    radius, an alpha, a non-empty rect and `isValidate`. SurfaceFlinger draws them behind the
    window, so no view or probe of the window's tree shows one.
  - Providers:
    - big folders (`FolderIcon.addBlurProvider`, only when `isBigFolder` and folder blur is on;
      areas on the current page only, `screenId == currentScreenId`, and all of them in
      overview);
    - ZUI's search widget;
    - the dock: `ZuiHotseat.getBlurAreaData`, updated in `B0`/`onOverlayScrollChange` when
      `BlurUtils.isDockDynamicBlurEnable`. Its rect is the pill rect `z0` moved by the dock's
      scale (pivot `H0`/`I0`) and its place on the screen (`getBlurRect`). Its alpha is the
      dock's alpha × the drag layer's (`getBlurAlpha`), not its drawing.
- **The list's focus mark is a decoration:** `FocusedItemDecorator.onDrawOver` draws after the
  rows and is not clipped by `clipToPadding`, which clips only the children.
- **Popup icon buttons:** the `PopupItemIconOnly` ripple (`selectableItemBackgroundBorderless`,
  `colorControlHighlight` `popupColorTertiary`) shows no visible focus on this build.

## App info, window options, the bar's focus and the controller's buttons (read 2026-10-19)

For 0.0.283, from ZUX Home 18.2.0.0375 and `services.jar` 16 decompiled.

- **App info closes ZUX Home's drawer only.** `SystemShortcut.AppInfo.onClick`:
  - in tablet desktop mode, on the bar (`TaskbarActivityContext.isInDesktopMode`), dismisses
    its menu after 500 ms, else at once (`dismissTaskMenuView`);
  - starts Settings (`PackageManagerHelper.startDetailsActivityForInfo`);
  - for a `Launcher` target in `ALL_APPS` or `OVERVIEW`, sets the rest state to `NORMAL` and
    calls `returnToHomescreen`.

  The taskbar's drawer (`TaskbarOverlayContext` target) is left open, and its window is above
  every app. `TaskbarOverlayController`'s task-stack listener, which in AOSP hides the drawer
  when a task changes, is empty here.
- **Floating window and split screen in ZUI's menus:**
  - ZUX Home: `QuickstepLauncher.getSupportedShortcuts` puts `ZuiSystemShortcuts.FLOAT_OPEN`
    first, then one `QuickstepSystemShortcut.getSplitSelectShortcutByPosition` factory per side
    (`n5`, tablets only). That factory makes a `SplitSelectSystemShortcut` (a `SplitShortcut`),
    and none in OV desktop mode or for apps in `QSS_NO_SPLIT`.
  - The bar (`TaskbarPopupController.s`): out of desktop mode `FLOAT_OPEN` (its `E` makes a
    `ZuiSystemShortcuts.FloatOpen` for an app that can float), then
    `TaskbarUIController.d` (`TaskbarSplitShortcut`, a `SplitShortcut`).
  - Both `showForIcon`s map the factories to a list and hand it to
    `ZuiPopupContainerWithArrow.populateAndShowRowsZui(View, int, List, List)`.
- **The bar's window on a popup or folder:**
  - `onPopupVisibilityChanged(b)` calls `setTaskbarWindowFocusable(true, true)` while a folder
    is open (`getOpenView(this, 1)`), else `(b, false)`. The popup's close posts
    `onPopupVisibilityChanged(false)` to the drag layer.
  - A folder's state listener (`TaskbarActivityContext.c`) calls `(true, true)` when it is
    open (state 2), and posts `setTaskbarWindowFocusableForIme(false)` when it is closed
    (state 0). That calls `(false, true)` unless a folder is still open.
  - A view removed with the focus in it hands the focus to the window's first focusable view.
- **Folders' names take the focus.** `Folder`'s name is an `ExtendedEditText`, and its focus
  starts the rename.
- **Full screen, in the system:**
  - `DisplayPolicy.topAppHidesSystemBar(int type)` is false with no top full-screen opaque
    window, or when the type is shown by force. With ZUI's OV freeform on the default display
    it reads the top non-freeform window. Otherwise it is whether the top window asks the bar
    hidden.
  - `PhoneWindowManager` keeps `mDefaultDisplayPolicy`, `mTopFocusedDisplayId` (volatile) and
    `isKeyguardShowingAndNotOccluded()`, all public.
- **Home:**
  - The bar's Home key runs `TaskbarNavButtonController.l` (navigateHome): ZUI's
    learning-launcher overlay, `closeSystemWindowsAsync(homekey)`, the callbacks'
    `onNavigateHome` and ZUI's `onHomeClick`.
  - `BaseActivityInterface.closeOverlay` hides the taskbar's drawer (`hideOverlayWindow`).
  - The system's `launchHomeFromHotKey(display)` starts home without any of that.
- **Joystick events:** no view of ZUX Home handles one (`onGenericMotionEvent` in
  `BubbleTextView`, `PagedView` and `Folder` reads pointers only). ZUI sets
  `OnGenericMotionListener`s on items and nav buttons, never on a window's root.
  `ActivityAllAppsContainerView.onAttachedToWindow` is declared there, and
  `getActiveRecyclerView` gives the list shown.

## HeliBoard, the owner's keyboard (read 2026-10-19)

For 0.0.285, from HeliBoard's public source (`Helium314/HeliBoard`, main at 2026-10-18,
4.3-beta1, GPL; read only, nothing copied). It is the keyboard in use on the tablet
(`helium314.keyboard/.latin.LatinIME`) and ZUI's `desktop_ime_default`.

- **Release builds** shrink with R8 (`proguard-android-optimize`) but `-dontobfuscate`: class,
  method and field names are kept. A small getter can still be inlined away, so each read falls
  back to its field: `Key.mX`, `mY`, `mWidth`, `mHeight`, `mCode`, `mBackgroundType`;
  `KeyboardView.mKeyboard`; `Keyboard.mSortedKeys`; `KeyboardSwitcher.mKeyboardView`;
  `Settings.sInstance.mSettingsValues`.
- **Keys reach it first.**
  - While a keyboard is up, every key goes to it before the app (`ImeInputStage`), the left
    stick's synthesized arrows included.
  - `LatinIME.onKeyDown` and `onKeyUp` hand them to `KeyboardActionListenerImpl`, which decodes
    only hardware keyboards (`mEnableHardwareKeyboard`).
  - Back, the fallback of a controller's B, closes the keyboard (`InputMethodService`).
- **Touch:**
  - `MainKeyboardView.onTouchEvent` passes to `processMotionEvent(MotionEvent)`, which hands each
    pointer id to its `PointerTracker` (`getPointerTracker(id)`, made on demand).
  - The tracker does the press, the preview, long-press popup keys (`mPopupKeysPanel`, a
    `PopupKeysKeyboardView` laid at `mOriginX`/`mOriginY`, which the same pointer's moves pick
    in) and repeats.
  - Its `KeyDetector` takes the view's padding off, and adds back `getVerticalCorrection`.
  - A down within `mTouchNoiseThresholdTime` of the last up, close by, is dropped as noise.
- **Keys:**
  - `Keyboard.getSortedKeys` lists them top-left to bottom-right, with `Key.Spacer`s among them.
  - `Key.getX/getY/getWidth/getHeight` are in the keyboard's coordinates.
  - The action key's code is Enter only for a plain Enter (`KeyLabel.getActionKeyCode`); its
    background type is always `Key.BACKGROUND_TYPE_ACTION` (3).
  - Codes: delete -7, the cursor arrows -21 and -22, space ' ', Enter '\n'.
- **The rest of its window:**
  - `LatinIME.mInputView` (an `InputView`, whose `onInterceptTouchEvent` sees every finger) holds
    `mSuggestionStripView` (word `TextView`s and toolbar `ImageButton`s, its own click and long
    click).
  - The toolbar sends codes through `KeyboardActionListener.onCodeInput(code, x, y, repeat)`.
- **Colours:** `Settings.getValues().mColors.get(ColorType.X)`; `ACTION_KEY_BACKGROUND` is the
  theme's accent.
- **Shift and caps lock:**
  - Shift (`KeyCode.SHIFT`, -11) is a key of its layouts. `KeyboardState` makes a tap shift
    once, a double tap lock, and a held shift chord with the next keys.
  - Caps lock (`KeyCode.CAPS_LOCK`, -13) is seldom on a layout; `KeyboardState.onReleaseKey`
    toggles the lock.
  - A key reaches the listener as `onPressKey(code, repeat, pointers, HapticEvent)`, then
    `onCodeInput(code, x, y, repeat)`, then `onReleaseKey(code, sliding)`.
- **Is the keyboard up, from the system:** `DisplayPolicy.mDisplayContent` (public final),
  `DisplayContent.mInputMethodWindow` (a public `WindowState`), and `WindowState.isVisible()`.

## ZUI's sidebar and its AI service (read 2026-10-15, from the owner's ANR traces)

- `com.zui.freeform.sidebar` listens to every drag (`FloatingOnDragListener.onDrag`) and on its
  main thread asks ZUI's AI service which apps take the drag: `TransferService.updateAIList` →
  `TransferUtils.getAIIntentList` → `AIServiceProxy`/`AIServiceHelper.getInteractTypeForSE`, which
  waits on a `CountDownLatch` with no time limit. Without `com.zui.ai.aiservice` (removed on the
  owner's tablet) no answer comes: every drag freezes the sidebar for 5 s and Android reports it
  not responding. The sidebar runs as uid 1000.

## Two USB-C ports: which one charges (`services.jar` 16)

- The kernel picks the port that powers the tablet; Android does not. With both ports in use it
  raises a uevent `DOU_USB=usb2port`, and `com.android.server.usb.DualPortUsbManager`
  (`ZuiDualPortDialogEventObserver`) only shows "The long side USB-C port takes priority when both
  ports are connected".
- `DualPortChargingObserver` only watches: both ports `sink` → broadcast
  `com.lenovo.action.CHARGE_INFO`, `dual_port_charging=true`. It sets nothing.
- `persist.sys.zui.usbport.status` names the port that is host and source, as information.
- Bypass charging (Game Assistant, `ItemBypassCharging`) writes
  `/sys/class/qcom-battery/batt_charge_bypass_en` (and `charging_enabled`,
  `lenovo_supply/bq2589h-charger/user_charging_enabled`). It applies to whichever port charges.
  On this build Game Assistant writes no file (`bypassWriteSetting` is true): it calls
  `android.hardware.battery.ZuiBatteryManager.setBypassChargingStatus(boolean)`, which
  `ZuiBatteryManagerService` turns into a charging-disabled vote. The service lets uid 0 or 1000,
  or any system app, call it, so ZUX Home can, and the tray's battery menu does
  (`hook/panel/Bypass`). The service turns bypass off at every boot (`init`).
- The kernel's dual-port driver is `/devices/virtual/douusb/usbc1c2`. Its uevents carry
  `DOU_USB=usb1or0port` when it re-arbitrates the ports, and `usb2port` / `c2dpin` for ZUI's
  dialogs. The owner's probes (2026-10-10, hub on the long-side port, 68 W charger on the USB 2.0
  port) show the result. Bypass turned on with the charger at 8.5 V × 2.5 A and the battery at
  0 A. About 15 s later the USB 2.0 port's input (`wireless`) went offline, and the tablet ran on
  the hub's 5 V at 0.4-1 A, with the battery making up the rest.
- On this tablet `BATTERY_PROPERTY_CURRENT_NOW` and the `battery` supply's `current_now` are
  positive when the battery gives current; the `qcom-battery` `fg*_current` nodes are the
  opposite.
- The driver's folder has no control: its only writable file is `uevent` (1.0.179's probe,
  three times). The port choice is the kernel's.
- The owner reports that bypass does hold with both cables in (0 mA, "Not charging AC"), so the
  5 V hand-over seen in the probes is not the whole story: what turns charging back on is
  traced from 1.0.182.
- The charger is held back by Qualcomm's thermal-engine, not by ZUI:
  `thermal-engine_battery_0.conf`, `[MON-BATTERY-USER]`, sensor `quiet-therm` (the skin),
  sampled every 2 s. It sets the `battery` cooling device to level 7 at 30 °C, then 11 at 32,
  13 at 34, 15 at 36, 16 at 38 and 20 at 49. Every probe of 2026-10-10 had it at 16 of 20, with
  quiet-therm near 44 °C. During 3DMark with bypass on, the input was 2-3 W (8.9 V × 0.35 A,
  then 5 V × 0.4 A) and the battery gave the rest, about 1.6 A.
- In bypass the battery status is 4 (not charging); ZUI's battery service logs it as
  `[bypass, bp]:[true, ...]` in `LongtimeChargingProtectController`.

## The module's hooks against ZUX Home 18.2.0.0375

Every class and method the module hooks by name is present in this version:

- `com.android.launcher3.taskbar.TaskbarView` - `updateItems`, `updateHotseatItems`
- `com.android.launcher3.taskbar.TaskbarModelCallbacks` - `bindRecentUsedApps`
- `com.android.launcher3.taskbar.NavbarButtonsViewController` - `setNavButtonContainerGravity`
- `com.android.launcher3.taskbar.TaskbarBackgroundRenderer` - `draw`, `getBackgroundHeight`
- `com.android.launcher3.taskbar.TaskbarActivityContext` - `createDefaultWindowLayoutParams`
  (the monitor's bar is the window titled `Taskbar_dp`); `TaskbarActivityContextDp` -
  `getDefaultTaskbarWindowSize`; `TaskbarInsetsController` - its two `Insets` answers for a side,
  found by shape (`e(int, int)`, `f(int, int, int)` here). Only with the bar set to the top.
- `com.android.launcher3.views.ScrimView` and the drawable `drag_handle_indicator`
- ZUI's start menu on the monitor is Launcher3's taskbar drawer (`TaskbarOverlayDragLayer`, the
  `bottom_sheet_background` sheet), in a full-screen window. It assumes a bar at the bottom twice:
  `AbstractSlideInView.setTranslationShift` slides the sheet down to close it, and
  `TaskbarAllAppsContainerView.setInsets` sets the foot to the bar's height when `isInDpMode`. With
  the bar at the top, both are decided the other way (`hook/drawer/DrawerFromTop`).
- The monitor's navigation keys: `DpModeNavbarButtonsViewController.init` lays `end_nav_buttons`
  at the start with a margin, once. Our Start sits in that margin and the keys move along by its
  width (`hook/taskbar/TaskbarStart`).
- `com.zui.launcher.uiextend.ZuiHotseat` - `dispatchDraw`, `onLayout`
- `com.zui.launcher.taskbar.ZuiTaskbarSearchContainer`, `com.zui.launcher.taskbar.TaskbarActivityContextDp`
- `com.zui.launcher.dpmode.secondarydisplaydp.RecentsDragLayerDp`
- `com.zui.launcher.secondarydisplay.SecondaryDisplayLauncher`
- 0.0.277: `com.android.launcher3.folder.Folder.X0(boolean)` (renamed in each build: found by name
  and parameter, logged when missing), `AbstractFloatingView.closeAllOpenViewsExcept(ActivityContext,
  boolean, int)`, `dragndrop.DragLayer.dispatchKeyEvent`,
  `taskbar.overlay.TaskbarOverlayDragLayer.dispatchKeyEvent`, `Workspace.onDragStart` and
  `onDragEnd` (the home screen to the bar).
- 0.0.278: `graphics.DragPreviewProvider.drawDragView(Canvas, float)` called (by name, else by
  its parameters) to draw a held drawer folder's picture at its shown size; `Folder.getItemCount`
  read when ZUI asks to dissolve one of our folders.
- 0.0.279: `BaseAllAppsAdapter.mIconFocusListener` read (by name, else by type);
  `Utilities.getFocusRectColor(Context)` called; `DragController.isDragging` and `Folder.isOpen`
  / `close(boolean)` at a drawer drag's end.
- 0.0.280: `taskbar.TaskbarDragLayer.dispatchKeyEvent` and `dispatchTouchEvent`,
  `TaskbarActivityContext.setTaskbarWindowFocusable(boolean, boolean)` (hooked and called),
  `onPopupVisibilityChanged(boolean)` called, `AbstractFloatingView.getTopOpenView` read.
- 0.0.281: `popup.ArrowPopup.show`, `handleClose`, `closeComplete`.
- 0.0.282: `ZuiHotseat.getBlurAreaData` (its result replaced while the dock's drawing is held
  back), `keyboard.FocusedItemDecorator.onDrawOver` (clipped), `Launcher.getBlurAreaProvider`
  read by the probe.
- 0.0.283: `popup.SystemShortcut$AppInfo.onClick` (after: its `mTarget` read),
  `ZuiPopupContainerWithArrow.populateAndShowRowsZui` (a key-opened menu's list without
  `ZuiSystemShortcuts$FloatOpen` and `splitscreen.SplitShortcut`),
  `ActivityAllAppsContainerView.onAttachedToWindow` and `getActiveRecyclerView`, `Folder.getOpen`,
  `TaskbarPopupController.showForIcon` from the bar's `mControllers`.
- 0.0.285, in HeliBoard's process: `LatinIME.onKeyDown`, `onKeyUp`, `onFinishInputView`,
  `onWindowHidden`; `MainKeyboardView.setKeyboard` and `processMotionEvent` (called);
  `InputView.onInterceptTouchEvent`; `PointerTracker.getPointerTracker` and `mPopupKeysPanel`.
- 0.0.287: HeliBoard's `KeyboardActionListener.onPressKey`, `onCodeInput`, `onReleaseKey` (caps
  lock); in the system, `DisplayPolicy.mDisplayContent`, `DisplayContent.mInputMethodWindow`,
  `WindowState.isVisible()` (read on L1/R1).

Class names like these survive ZUI's R8 pass; fields and most private methods are renamed in
every build, which is why the module finds those by type and shape, not by name.

Checked again on 2026-10-09, against every class and method name in the module's launcher-side
hooks:
- All found, except the alternative names some lookups try as fallbacks. Examples are the
  stock-unlock list's `com.zui.launcher.Launcher` and `com.zui.launcher.Workspace`, and Launcher3's
  `taskbar.customization.TaskbarBackgroundRenderer`. Those lookups move on to the next name.
- `IActivityTaskManager.setTaskWindowingMode`, which recents used to make a windowed recents full
  screen, is not in this firmware. The owner's logs said "no setTaskWindowingMode on this build".
  That fallback is gone; the launch options keep recents full screen.

## The module's hooks inside the system, against `services.jar` 16

Checked 2026-10-09. Every hook below matches by name and parameters, unless marked missing:

| Feature | Hook | In this firmware |
|---------|------|------------------|
| Keep-alive | `ProcessStateRecord.setCurAdj(int)` | found |
| Keep-alive | `ProcessList.setOomAdj(int, int, int)` (static) | found |
| Keep-alive | `ProcessRecord.killLocked(String, ...)` | 5 overloads, all found. The owner's log says "kills x5" |
| Full screen | `LaunchParamsController.calculate(...)`, fields `LaunchParams.mWindowingMode`, `mBounds`, `ActivityRecord.intent` | found. ZUI calls it with phase 10 in its desktop mode, 3 otherwise |
| Full screen | `setWindowingMode(int)` on `Task` and on `ConfigurationContainer`; field `Task.intent` | found |
| New window | `ActivityStarter.setInitialState(...)`, fields `mIntent`, `mLaunchMode`, `mLaunchFlags` | found |
| New window | `ActivityStarter.getReusableTask` | **missing**: it is `resolveReusableTask(boolean)` here. The owner's log says "task reuse x0". The hook is gone; `setInitialState` alone does the job, confirmed on 1.0.91 |
| Drag | `DragState.isValidDropTarget(WindowState, boolean, boolean)`, fields `mFlags`, `mUid`; `WindowState.getOwningUid()` | found |
| Controller | `PhoneWindowManager.interceptKeyBeforeDispatching(IBinder, KeyEvent, int)`; fields `mTopFocusedDisplayId`, `mDefaultDisplayPolicy`; `isKeyguardShowingAndNotOccluded()`, `DisplayPolicy.topAppHidesSystemBar(int)` (0.0.283, read on L1/R1) | found |
| Trace | `WindowOrganizerController.applyTransaction`, `applySyncTransaction`, `startTransition` | found. `startLegacyTransition` is not, which only drops one traced name. From 1.0.165 the overloads taking an `ActionChain` are left out: they are the system's own step inside a call, under its own uid. The shell's `startNewTransition` goes through `startTransition(int, IBinder, …)`, so its type is in the line |

## The roadmap rows against ZUI's code

Read 2026-10-09 from the decompiled apps and jars. These are facts for planning, not tests on the
device.

- **#1 Unplug and replug.** ZUX Home learns that the monitor's desktop is on from
  `Settings.System zui_dp_display_pc_mode` (`DpModeManager`, through `SettingsCache`). On 0, or
  when that display is removed, it drops its monitor context. The system writes 0 itself
  (`OVDesktopController`). The module can follow the same switch with an observer instead of
  inferring from windows. The display timeline now records it, together with
  `zui_ov_desktop_mode` and `zui_pc_mode`.
- **#3 Other apps' quick-settings tiles.** Binding a tile needs `BIND_QUICK_SETTINGS_TILE`
  (signature|recents). ZUX Home holds the recents role (`config_recentsComponentName` is its
  `RecentsActivity`), but its manifest does not ask for that permission, so it cannot bind them.
  Possible only from SystemUI, which hosts the tiles, or by a system-side grant. The module's own
  tiles are unaffected.
- **#5, #6 Notifications on the monitor.** SystemUI has a status bar on the monitor in DP mode
  (`dpmode/ExtendStatusBarController`, its own notification icons). Whether heads-up pop-ups appear
  there is not clear from the code. The probe now lists SystemUI's windows per display. Android 16
  Live Updates arrive through the module's listener like any notification.
- **#20 The monitor's status bar** (read 2026-10-10). Built by SystemUI's
  `dpmode/ZuiDpModeManager`, in one place: its `createStatusBar(Display)` (compiled as the static
  `-$$Nest$mcreateStatusBar`), called when `zui_dp_display_pc_mode` turns on and when the external
  screen is added in that mode. It is a `DpStatusBarWindowControllerImpl` window, type 2000,
  titled `StatusBar<display id>`, keeping `statusBars` and `tappableElement` clear at the top. No
  setting of ZUI's turns it off. Everything else in the manager allows for it not being there
  (`removeStatusBar`, the colour updates in `onSystemBarAttributesChanged`); the screen width it
  stores on the way (`XSystemUtil.mDpModeScreenWidth`) is read only by the status bar's own view.
  - **The window buttons went with it.** The title bar of a full-screen window is shown only
    while `mIsStatusBarVisible` is true: `DesktopModeWindowDecoration.relayout` (the 7-argument
    one) passes the field to `updateRelayoutParams`, which makes it
    `RelayoutParams.mIsCaptionVisible` (`isFreeform() || (statusBarVisible && !keyguardOccluded)`,
    read with jadx `--show-bad-code`; plain jadx skips that method). ZUI's immersive test
    `WindowDecoration.getCurrentTaskInfoImmersived` reads the same field, and so does
    `CaptionWindowDecoration`. The field follows the `statusBars` insets on the task's display
    (`DesktopModeWindowDecorViewModel.DesktopModeOnInsetsChangedListener`), so with no status bar
    every maximised window lost its buttons. 1.0.172 hooked only the immersive test, which is not
    what hides them; 1.0.181 sets the field before each relayout instead. ZUI's own test for an
    app asking to be immersive is `(task.requestedVisibleTypes & statusBars()) == 0`
    (`TaskInfoKt.getRequestingImmersive`).
- **Where windows may go** (read 2026-10-10). The shell keeps windows in
  `DisplayLayout.getStableBounds` / `getStableBoundsForDesktopMode`, built in `recalcInsets` -
  run again on every change of the display's insets
  (`DisplayInsetsController...DisplayWindowInsetsControllerImpl`). It assumes the navigation bar
  at the bottom of a landscape screen, adds the configured status bar height at the top whenever
  the display has one (`mHasStatusBar`, not the real bar), and on the desktop ends the bottom at
  the screen less `mTaskbarFrameHeight` when that differs from `mNavBarFrameHeight` - the only
  use of `mTaskbarFrameHeight`. A bar at the top, or no status bar, is not seen.
- **ZUI's drawer app names** (ZUX Home): `BubbleTextView` with `mDisplay == 1` (all apps) take
  their colour from their layout when made and from `setTextColor(int)` /
  `setTextColor(ColorStateList)` after; `applyFromItemInfo` sets it only for workspace, hotseat
  and folder containers (`Utilities.getTextColor`).
- **#9 Boot animation.** Chosen by the native boot animation, not in these jars. The probe lists
  the files present.
- **#10, #16 Window frames.** SystemUI draws them from resources:
  - **Work mode:** `pcmode_window_decor`.
  - **Tablet desktop mode:** `zui_desktop_window_decor` and `..._freeform`.
  - **Elsewhere:** Android's `desktop_mode_app_header`.
  - **Styling:** button drawables `zui_desktopmode_*`, corner radius
    `ovc_wd_freeform_task_corner_radius_pad` and the system's `ov_*_corner_radius`, caption height
    `freeform_decor_caption_height`, and title colours.
  - **The disable-don't-fight route:** restyle frames with a resource overlay (fabricated overlays
    for sizes and colours, an overlay package for layouts and pictures), with no hook in SystemUI.
    The probe reads the current values and overlays.
- **#11 Window animations.** Fixed in SystemUI's code, with no resource or setting besides
  Android's global animation scales:
  - floating window open and minimise: 400 ms (`FreeformTaskTransitionHandler`);
  - close: 300 ms, standard-accelerate (`CloseDesktopTaskTransitionHandler`);
  - maximise and restore: 300 ms (`ToggleResizeDesktopTaskTransitionHandler`).

  Custom animations mean hooking those handlers in SystemUI: high risk, as planned.
- **#13 Adapting to updates.** The two tables above are the check; the probe's health section
  shows the same on the device. From 0.0.216 the launcher also sends that list to the settings app once
  per start, through the module's own provider (`METHOD_HEALTH`, the only way back from the
  launcher to the module); SystemUI and the system process send nothing.
- **#14 Taskbar on another edge.** Read in full for the top (2026-10-10, 1.0.170):
  - The window's parameters come from `createDefaultWindowLayoutParams(type, title)`, gravity
    BOTTOM, for the bar and each of its four rotations (`o0()` here), titled `Taskbar_dp` on the
    monitor only. The same method makes "Taskbar Nav Buttons" and a voice-interaction window, so
    the title is what tells the bar apart.
  - Its height is `getDefaultTaskbarWindowSize()`: on the monitor the bar (`taskbarHeight`, 80 px)
    plus a corner radius and tooltip room above it - the 162 px the probe shows.
    `setTaskbarWindowFullscreen` stretches it to the screen's height during drags, folders and
    menus.
  - The space kept clear for apps: `TaskbarInsetsController`'s two `Insets` answers handle a
    gravity of bottom, start and end; any other lands on the right.
  - The background renderer paints at the canvas's foot (`b(Canvas)` translates by the canvas's
    height less the bar's). The rows in `taskbar.xml` (`taskbar_view`, `navbuttons_view`) are
    `layout_gravity="bottom"`, and no code sets their gravity again (only `end_nav_buttons`'
    horizontal gravity).
    `transient_taskbar.xml` and `customizable_taskbar.xml` keep them at the bottom too, with
    `transient_taskbar_bottom_margin` under `taskbar_view`. So anything of ours on the bar sits
    there by the same gravity (`TaskbarTray.onRow`), never by a top measured once: with the
    drawer open the window is the transient bar's 305 px, and our row kept that top when it went
    full screen (0.0.215).
  - The renderer's fields are renamed in this build (`context` is `a`, the heights `f` and `n`),
    so its bar is found by type.

  Earlier note: Launcher3's bar already builds window parameters per rotation,
  with a side gravity and a width for 90° and 270° (`TaskbarActivityContext`, by shape: a void
  method taking a rotation and the window's layout params). No setting picks the edge. Moving the
  monitor's bar means changing those parameters in `TaskbarActivityContextDp` only, plus the space
  it reserves (its provided insets). Launcher side, medium risk, as planned.
- **#17, #18 Game Assistant.**
  - **Thermal policy:** it switches the device's thermal policy through Lenovo's security centre
    (`content://com.lenovo.safecenter.power.utils`, value `userThermal`: 103 for its high-performance
    mode, 0 for normal).
  - **Game mode:** it sets ZUX Performance Service's game mode (`Settings.Global
    game_helper_game_mode` on Android 16), which `com.zui.pp` treats as a game.
  - **For #18:** a cooler profile, if the security centre has one, is a switch ZUI already offers.
    That app (`com.lenovo.safecenter`) is not read yet.
- **#2, #4, #8, #15** are drawn entirely by the module: nothing of ZUI's to check.
- **#22 One layout per monitor.** A monitor is told apart by its display's unique id
  (`Display.getUniqueId()`, hidden, e.g. `local:<physical id>`; the same at every plug), read by
  reflection. ZUX Home itself keeps one layout for the monitor's desktop and never asks which
  monitor it is. The probe's display timeline prints each display's id.

## Still to read

- SystemUI's window menus (#10).
- Lenovo's security centre (`com.lenovo.safecenter`): its thermal profiles (#18).
