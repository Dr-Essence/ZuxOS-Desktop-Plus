# ZUI notes

What ZUI's own apps do, read from the owner's firmware (decompiled 2026-10-09). Read this before
hooking anything of ZUI's. It records facts - class roles, settings keys, behaviour - never ZUI's
code; the decompiled sources stay outside the repo.

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
  is lost. Every drop the owner made over our icons was lost this way.
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
