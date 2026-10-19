package com.zuxos.desktopplus.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import com.zuxos.desktopplus.core.Const;
import com.zuxos.desktopplus.core.ModuleStatus;
import com.zuxos.desktopplus.core.Prefs;
import com.zuxos.desktopplus.core.Tone;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.core.theme.Theme;
import com.zuxos.desktopplus.notify.TestNotifications;

/** Settings for the module, plus a short explanation of what to expect on the device. */
public class MainActivity extends Activity {

    private static final int REQUEST_NOTIFY = 1;

    private SharedPreferences mPrefs;
    private LinearLayout mRoot;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPrefs = Prefs.get(this);

        ScrollView scroll = new ScrollView(this);
        mRoot = new LinearLayout(this);
        mRoot.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        mRoot.setPadding(pad, pad, pad, pad);
        scroll.addView(mRoot, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        addStatus();

        addHeader("General");
        addSwitch("Module enabled", "Turn the whole desktop surface off without uninstalling",
                Const.KEY_ENABLED, true);
        addSpinner("Which desktop mode", new String[]{
                        "External display only (recommended)",
                        "Tablet screen only",
                        "Both"},
                Const.KEY_DISPLAY_MODE, Const.DISPLAY_EXTERNAL);
        addSummary("ZUI's own home on the tablet is never covered - it keeps its icons, folders "
                + "and Recents, and gets the taskbar only.");
        addSpinner("Stock home content", new String[]{
                        "Leave it alone (overlay on top)",
                        "Hide the stock icon grid (recommended)",
                        "Hide everything the stock home draws"},
                Const.KEY_TAKEOVER, Const.TAKEOVER_GRID);
        addSwitch("Attach to any activity",
                "Only if the desktop home is not detected automatically",
                Const.KEY_ATTACH_ANY, false);
        addText("Extra launcher packages",
                "Comma separated, if your ZuxOS build uses a package the module does not know",
                Const.KEY_EXTRA_TARGETS);

        addHeader("Desktop");
        addSwitch("Widgets", "Add widgets through the desktop right-click menu",
                Const.KEY_WIDGETS_ENABLED, true);
        addSwitch("Folders", "Drop one icon on another to make a folder",
                Const.KEY_FOLDERS_ENABLED, true);
        addSwitch("Show icon labels", null, Const.KEY_SHOW_LABELS, true);
        addSwitch("Shadow behind labels", "Keeps labels readable on light wallpapers",
                Const.KEY_LABEL_SHADOW, true);
        addSwitch("Show the Apps button",
                "Off by default - the stock taskbar covers it. The drawer still opens from the "
                        + "desktop menu or the All-apps key",
                Const.KEY_DRAWER_BUTTON, false);
        addSwitch("Desktop pages", "More than one page of icons, with arrows to move between them",
                Const.KEY_PAGES, true);
        addSwitch("Page dots",
                "Dots under the desktop for its pages. The arrows at the sides already show "
                        + "there is another page",
                Const.KEY_PAGE_DOTS, false);
        addSwitch("A desktop for each monitor",
                "Each external screen keeps its own icons and folders. A monitor plugged in for "
                        + "the first time starts from the desktop you have now. Off: every "
                        + "monitor shows the same desktop. The launcher restarts to apply it",
                Const.KEY_MONITOR_LAYOUTS, false);
        addSwitch("Start animation",
                "Desktop Mode+ greets you on the monitor as its desktop comes up, once each "
                        + "time it is plugged in",
                Const.KEY_START_ANIMATION, true);
        addSwitch("Catch pinned shortcuts",
                "A web page or file pinned from any app also lands on the desktop",
                Const.KEY_CATCH_PINS, true);
        addSwitch("Glass style", "Translucent panels, with real blur where Android allows it",
                Const.KEY_GLASS, true);
        addSwitch("Animations", "Sliding, fading and page transitions",
                Const.KEY_ANIMATIONS, true);
        addSlider("Grid cell size", "dp", Const.KEY_CELL_SIZE, 104, 72, 180);
        addSlider("Icon size", "dp", Const.KEY_ICON_SIZE, 52, 32, 96);

        addHeader("Tablet home screen");
        addSummary("Home screen grid: in ZUX Home's own settings (hold the home screen, Home "
                + "settings), where 6 × 5, 8 × 5, 8 × 6, 10 × 7 and 12 × 8 join ZUI's own grids. "
                + "10 × 7 and 12 × 8 let an icon land close to wherever you let it go. Going back "
                + "to a smaller grid, ZUI moves what no longer fits onto more pages (9 at most).");
        addSwitch("Fit icons to fine grids",
                "On the finer grids, icons, names and dots shrink to fit their cells, widgets "
                        + "keep their size on screen and icons keep their places. The launcher "
                        + "restarts to apply it",
                Const.KEY_GRID_FIT, true);
        addSwitch("Home screen uses the dock's space",
                "The band ZUX Home keeps for its dock above the taskbar becomes home screen, and "
                        + "the dock is hidden. The launcher restarts to apply it",
                Const.KEY_HOME_BAND, true);
        addSwitch("Home screen uses its top and bottom bands",
                "The empty band at the top of each page and the page dots' band above the "
                        + "taskbar become home screen; the dots show over the last row while you "
                        + "swipe. The launcher restarts to apply it",
                Const.KEY_HOME_EDGES, true);
        addSwitch("Remove folder in the home folder menu",
                "Hold a folder on the home screen: Remove folder puts its apps back on the home "
                        + "screen, in the first free places, and the folder goes",
                Const.KEY_HOME_FOLDER_REMOVE, true);

        addHeader("Customize");
        addSpinner("Theme", new String[]{
                        "Glass",
                        "Retro (monitor)"},
                Const.KEY_THEME, Theme.GLASS_ID);
        addSummary("Retro is Windows 98 on the external screen: grey bevelled boxes, a pixel font, "
                + "no blur and no live glass, which also saves the GPU there. The tablet keeps "
                + "glass. The launcher restarts to apply it.");
        addSpinner("Taskbar position (monitor)", new String[]{
                        "Bottom",
                        "Top"},
                Const.KEY_TASKBAR_EDGE, Const.EDGE_BOTTOM);
        addSummary("The launcher restarts to apply it. The tablet's taskbar never moves.");
        addSwitch("Hide the monitor's status bar",
                "ZUI's bar at the top of the external screen only shows icons - it cannot be "
                        + "pulled down. The tray and its panels do its job, and apps get its "
                        + "room. Needs System UI ticked in LSPosed; takes effect the next time "
                        + "the monitor's desktop starts.",
                Const.KEY_HIDE_MONITOR_STATUS_BAR, true);

        addHeader("App drawer");
        addSwitch("Use the stock taskbar drawer too",
                "Applies your folders, order and hidden apps to the launcher's own app drawer",
                Const.KEY_NATIVE_DRAWER, true);
        addSpinner("Default order", new String[]{
                        "Alphabetical",
                        "My own order",
                        "Alphabetical (recently used first is not available yet)"},
                Const.KEY_DRAWER_SORT, Const.SORT_CUSTOM);
        addSwitch("Load the app list without waiting",
                "After a boot or a restart ZUX Home fills its drawer within a second, instead of "
                        + "only once an app has been opened",
                Const.KEY_LOADER_NO_WAIT, true);

        addHeader("Taskbar");
        addSwitch("Status tray in the taskbar",
                "Network, battery and a clock next to the navigation buttons, with Wi-Fi and "
                        + "Bluetooth switches behind them",
                Const.KEY_TASKBAR_TRAY, true);
        addSwitch("Show temperatures",
                "CPU, GPU and battery temperature in the tray, where the kernel lets the "
                        + "launcher read them",
                Const.KEY_TASKBAR_TEMPS, true);
        addSwitch("Edit the quick panel's tiles",
                "Hold a tile, or press Edit at the top of the panel, to take tiles off, put "
                        + "them back and drag them into your own order",
                Const.KEY_TILE_EDIT, true);
        addSwitch("Taskbar menu",
                "Hold or right-click empty taskbar space for Task manager and settings",
                Const.KEY_TASKBAR_MENU, true);
        addSpinner("Taskbar text and icons", new String[]{
                        "Follow the background",
                        "Always black",
                        "Always white"},
                Const.KEY_TASKBAR_TEXT_MODE, Tone.MODE_AUTO);
        addSwitch("Glass taskbar",
                "Experimental. Replaces the taskbar's own bar with a translucent one. Whether it "
                        + "works depends on how your firmware paints that bar, so it starts off",
                Const.KEY_TASKBAR_GLASS, false);
        addSwitch("Only open apps in the taskbar",
                "The taskbar shows what is open on its screen, plus your pins - not the "
                        + "launcher's hotseat and recommendations",
                Const.KEY_TASKBAR_RUNNING_ONLY, false);
        addSwitch("Menu on a taskbar icon",
                "Hold an app in the taskbar for open, close, app info and the app's own "
                        + "shortcuts",
                Const.KEY_TASKBAR_APP_MENU, true);
        addSwitch("App shortcut icons in ZUI's shape",
                "An app's own shortcuts in the app menus - New chat, Start voice - get the same "
                        + "icon shape ZUI gives every app icon, instead of the app's square "
                        + "backing",
                Const.KEY_SHORTCUT_SHAPES, true);
        addSwitch("Drag apps onto the taskbar to pin them",
                "An app dragged out of ZUI's taskbar drawer and let go on the taskbar is pinned "
                        + "there; anywhere else it still opens in a floating window or split "
                        + "screen. Needs System UI ticked in LSPosed",
                Const.KEY_BAR_DROP, true);
        addSwitch("Drag from the taskbar to the home screen",
                "With the home screen behind it, an app dragged out of the taskbar's drawer is "
                        + "placed on the home screen as from ZUX Home's own drawer; a pinned app "
                        + "dragged off the tablet's taskbar moves there. The drawer needs System "
                        + "UI ticked in LSPosed",
                Const.KEY_HOME_DROP, true);
        addSwitch("Drag from the home screen to the taskbar",
                "An app or a folder of apps dragged from ZUI's home screen and let go on the "
                        + "taskbar is pinned there and leaves the home screen, as ZUI's own dock "
                        + "takes it",
                Const.KEY_HOME_TO_BAR, true);
        addSwitch("Drag a drawer folder to the home screen",
                "In ZUX Home's drawer, hold one of our folders and move your finger: it lands on "
                        + "the home screen as ZUI's own folder with its apps; the drawer keeps it",
                Const.KEY_DRAWER_FOLDER_HOME, true);
        addSwitch("Rearrange ZUX Home's drawer by dragging",
                "Drag an app in ZUX Home's drawer and the others make room, as on ZUI's home "
                        + "screen; rest it on an app to make a folder, on a folder to put it in, "
                        + "and drag past the top of the apps to place it on the home screen",
                Const.KEY_DRAWER_REARRANGE, true);
        addSwitch("Rearrange in the taskbar's drawer",
                "On the tablet, an app dragged inside the taskbar's drawer only moves there; the "
                        + "floating window starts once it leaves the drawer. Cancel at the top "
                        + "stops it, and resting on the start button opens the drawer again",
                Const.KEY_TASKBAR_DRAWER_REARRANGE, true);
        addSwitch("Drawer folders open under a dragged app",
                "Rest a dragged app on a drawer folder and it opens, as ZUI's home folders do, "
                        + "so the app can be placed anywhere inside",
                Const.KEY_DRAWER_FOLDER_SPRING, true);
        addSwitch("Select apps on the home screen",
                "Select in a home-screen app's menu opens ZUI's edit mode with that app selected: "
                        + "tap others to add them, then drag them together. Selected apps shake",
                Const.KEY_HOME_SELECT, true);
        addSwitch("Select in the drawers",
                "Select in a drawer app's menu marks it: tap others to add them, then drag them "
                        + "together into a new place, a folder or the taskbar",
                Const.KEY_DRAWER_SELECT, true);
        addSwitch("Taps on drawer apps always count",
                "A tap anywhere on a drawer app's icon or name opens it. ZUI measures where an "
                        + "icon can be touched by the home screen's icon size, so taps near the "
                        + "edge of a larger drawer icon went nowhere. Applies after ZUX Home "
                        + "restarts",
                Const.KEY_DRAWER_TAPS, true);
        addSwitch("Game controller's Home button opens the drawer",
                "Press it to open or close the app drawer, then move with the stick or D-pad, "
                        + "A to open, B to go back. Folders are reached too. Hold it to go to "
                        + "the home screen. Off, the button is Android's again from its next "
                        + "press",
                Const.KEY_GAMEPAD_DRAWER, true);
        addSwitch("Game controller's Triangle opens menus",
                "In the drawers and on the home screen, Triangle opens the menu of the app or "
                        + "folder the controller is on, as a hold does, without floating window "
                        + "and split screen; in any menu the D-pad moves between its rows and "
                        + "stays in it, A picks a row and B closes it",
                Const.KEY_GAMEPAD_MENUS, true);
        addSwitch("Game controller on the taskbar",
                "From the bottom row of the home screen or a drawer, D-pad down goes onto the "
                        + "taskbar: left and right move along it, A opens, Triangle gives the "
                        + "menu - in an open folder, the menu of the app it is on - B or up "
                        + "goes back. A touch hands the bar back to touch",
                Const.KEY_GAMEPAD_BAR, true);
        addSwitch("Game controller's L1 and R1 on the taskbar",
                "From anywhere, L1 and R1 take the controller onto the taskbar and move it "
                        + "along. A full-screen app - a game, a video - keeps them for itself",
                Const.KEY_GAMEPAD_SHOULDERS, true);
        addSwitch("Game controller's right stick scrolls the drawers",
                "Tilt it to scroll either app drawer, faster the further it goes; the icon the "
                        + "controller is on stays in view",
                Const.KEY_GAMEPAD_STICK, true);
        addSwitch("Navigation keys at the bar's right edge",
                "On the tablet the back, home and recents keys sit flush with the bar's right "
                        + "edge; ZUI's room there comes back while it has a button to show in it",
                Const.KEY_NAV_KEYS_EDGE, true);
        addSwitch("Start button robot falls asleep",
                "After 30 seconds with nothing pressed the robot shuts its eyes and dozes off, "
                        + "z's drifting up; any press wakes it",
                Const.KEY_ROBOT_SLEEP, true);
        addSwitch("Start button robot breathes",
                "The robot's head swells gently with each breath - slower and deeper while it "
                        + "sleeps",
                Const.KEY_ROBOT_BREATH, true);
        addSwitch("Keep the launcher's recents off the taskbar",
                "With \"Only open apps\" on, the launcher's own recent and recommended apps are "
                        + "never added to the taskbar - not even for a moment when an app opens "
                        + "or closes",
                Const.KEY_HIDE_RECENTS_FLASH, true);
        addSwitch("Navigation keys act on their own screen",
                "Back, home and recents on the monitor's taskbar act on the monitor - not on "
                        + "the tablet, which is where the launcher sends them on its own",
                Const.KEY_NAV_OWN_SCREEN, true);
        addSwitch("Recents on the screen you pressed",
                "The recents button opens recents on its own screen - tablet or monitor - full "
                        + "screen and in front of every app, on the first press",
                Const.KEY_RECENTS_ROUTE, true);
        addSwitch("Never kill apps on the monitor",
                "Apps open on the external screen are only closed when you close them. They go "
                        + "on ZUI's own never-kill lists, and root keeps battery limits off them; "
                        + "for the rest, also tick System Framework for this module in LSPosed "
                        + "and reboot once",
                Const.KEY_KEEP_ALIVE, true);
        addSwitch("Monitor's touchscreen on the monitor",
                "A touchscreen monitor's touches always land on the monitor - after a replug "
                        + "Android could send them to the tablet. Needs System Framework ticked "
                        + "for this module in LSPosed; takes effect at the next plug",
                Const.KEY_MONITOR_TOUCH, true);
        addSwitch("Window buttons follow the window",
                "When a window moves between the tablet and the monitor, its title bar and its "
                        + "\u2026 menu move with it. Needs System UI ticked for this module in "
                        + "LSPosed",
                Const.KEY_CAPTION_FOLLOW, true);
        addSwitch("Tablet drawer ends at the taskbar",
                "The taskbar drawer's apps scroll up to the bar and stop there, instead of "
                        + "passing under the bar's icons",
                Const.KEY_DRAWER_ABOVE_BAR, true);
        addSwitch("Drawer button on the left",
                "Moves the launcher's own all-apps button to the left of the taskbar, beside the "
                        + "navigation keys, instead of leaving it in the middle of the icons",
                Const.KEY_START_LEFT, true);
        addSwitch("Android logo on the drawer button",
                "Shows the green Android head on the launcher's all-apps button, like a start "
                        + "button, instead of its own icon",
                Const.KEY_START_ROBOT, true);
        addSwitch("Mark the apps that are open",
                "A line under every taskbar icon whose app is running - including shortcuts and "
                        + "folders, which otherwise look the same open or closed",
                Const.KEY_RUNNING_MARKS, true);
        addSwitch("Drag apps out of the stock drawer",
                "Hold an app in the launcher's own drawer to drag it onto the desktop, or onto "
                        + "the taskbar to pin it there. Pins are this module's own and go away "
                        + "with it; the launcher's own hotseat is never written to",
                Const.KEY_DRAWER_DRAG, true);
        addSwitch("Open apps on the screen you tapped",
                "The launcher starts its own icons without naming a screen, so an app tapped on "
                        + "the external desktop can open on the tablet. This gives those launches "
                        + "the display the tap was on. Off by default - it changes where every app "
                        + "opens",
                Const.KEY_LAUNCH_DISPLAY, false);
        addSwitch("Glass app drawer",
                "Puts the same translucent pane behind the launcher's own app drawer, in whatever "
                        + "tone the drawer already uses so its labels stay readable",
                Const.KEY_DRAWER_GLASS, true);
        addSwitch("Use root",
                "Lets the quick settings switch Bluetooth, aeroplane mode, eye protection and "
                        + "take screenshots, which an ordinary launcher may not. Turn off and "
                        + "those buttons open the matching settings screen instead",
                Const.KEY_USE_ROOT, true);
        addSwitch("ZUI's sidebar never waits for a missing AI service",
                "With ZUI's AI service removed, the sidebar froze on every drag of an app and "
                        + "Android said it was not responding. It no longer asks a service "
                        + "that is not there. Needs ZUI's sidebar ticked in LSPosed",
                Const.KEY_SIDEBAR_AI_WAIT, true);

        addHeader("Stock launcher unlocking (optional)");
        addSwitch("Try to unlock the stock launcher",
                "Flips the launcher's own \"editing disabled\" flags where we can name them",
                Const.KEY_UNLOCK_STOCK, true);
        addSwitch("Aggressive unlocking",
                "Also guesses by method name. Turn off if the launcher misbehaves",
                Const.KEY_UNLOCK_AGGRESSIVE, false);

        addHeader("Notifications");
        addSwitch("Notifications in the quick panel",
                "Shows what is in the shade, with a tap to open and a cross to dismiss",
                Const.KEY_NOTIFICATIONS, true);
        addSwitch("Pop-ups on the monitor",
                "A new notification that would pop up on the tablet shows above the tray on the "
                        + "external screen for a few seconds, with its buttons and a reply box. "
                        + "The launcher restarts to apply it",
                Const.KEY_NOTIFY_POPUPS, true);
        addSlider("Pop-ups stay for", "s", Const.KEY_POPUP_SECONDS, 6, 3, 15);
        addSlider("Pop-up width", "dp", Const.KEY_POPUP_WIDTH, 360, 300, 480);
        addSpinner("Pop-ups come in at", new String[]{
                "The right-hand corner",
                "The left-hand corner"}, Const.KEY_POPUP_SIDE, Const.SIDE_RIGHT);
        addSummary("Above the taskbar, or under it when the bar is at the top. Time and width "
                + "apply to the next pop-up; the corner once those on screen have gone.");
        addButton("Apps that never pop up", this::pickMutedApps);
        addSummary("Their notifications still come in, in the shade and in the quick panel; "
                + "they just never pop up on the monitor.");
        addButton("Grant notification access", () -> {
            try {
                // The launcher may not read notifications and cannot be granted permission to.
                // This module's own listener can, once it is switched on here.
                startActivity(new android.content.Intent(
                        "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable t) {
                android.widget.Toast.makeText(this,
                        "Could not open notification access settings",
                        android.widget.Toast.LENGTH_LONG).show();
            }
        });
        addSummary("Find \"ZuxOS Desktop Plus\" in that list and switch it on. Without it the "
                + "panel simply shows no notifications; nothing else is affected.");
        addButton("Send test notifications", this::sendTestNotifications);
        addSummary("Three, a moment apart: a message with a picture, a button and a reply box; "
                + "a long one with two buttons; a plain one. With the monitor's desktop on, they "
                + "pop up above its tray. Replying updates the first without popping it up again.");

        addHeader("Diagnostics");
        addSwitch("Verbose log", "Writes details to the LSPosed log", Const.KEY_DEBUG, false);
        addSwitch("Dump launcher info on attach",
                "Writes the activity and view tree to the launcher's files dir",
                Const.KEY_PROBE, false);
        addSwitch("Report feature health",
                "ZUX Home tells this app which of its features found what they need on this "
                        + "firmware, once each time it starts",
                Const.KEY_HEALTH, true);
        addButton("Feature health", this::showHealth);

        addButton("How to use this", this::showHelp);
    }

    // --- building blocks -------------------------------------------------

    private void addStatus() {
        boolean active = ModuleStatus.isActive();
        TextView tv = new TextView(this);
        tv.setText(active
                ? "Module is active"
                : "Module is NOT active - enable it in LSPosed and reboot, then scope it to your "
                        + "home app (com.zui.home / com.zui.launcher).");
        tv.setTextColor(Color.WHITE);
        tv.setBackground(Ui.roundRect(active ? 0xFF2E7D32 : 0xFFB3261E, Ui.dp(this, 14)));
        int p = Ui.dp(this, 16);
        tv.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = rowParams();
        lp.bottomMargin = Ui.dp(this, 12);
        mRoot.addView(tv, lp);
    }

    private void addHeader(String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(13);
        tv.setAllCaps(true);
        tv.setAlpha(0.7f);
        LinearLayout.LayoutParams lp = rowParams();
        lp.topMargin = Ui.dp(this, 20);
        lp.bottomMargin = Ui.dp(this, 4);
        mRoot.addView(tv, lp);
    }

    @SuppressWarnings("deprecation")
    private void addSwitch(String title, String summary, String key, boolean def) {
        Switch sw = new Switch(this);
        sw.setText(title);
        sw.setTextSize(16);
        sw.setChecked(mPrefs.getBoolean(key, def));
        sw.setPadding(0, Ui.dp(this, 10), 0, summary == null ? Ui.dp(this, 10) : 0);
        sw.setOnCheckedChangeListener((v, checked) ->
                save(key, mPrefs.edit().putBoolean(key, checked)));
        mRoot.addView(sw, rowParams());
        if (summary != null) {
            addSummary(summary);
        }
    }

    /**
     * Saves a change and tells the launcher, which takes it at once - or, for one it reads only
     * as it starts, restarts with it. Nobody has to force-stop it.
     */
    private void save(String key, SharedPreferences.Editor edit) {
        // Written before the launcher is told, so it reads the new value.
        edit.commit();
        // To every launcher the module is in: each listens for itself.
        sendBroadcast(new android.content.Intent(Const.ACTION_SETTINGS_CHANGED));
    }

    private void addSummary(String summary) {
        TextView tv = new TextView(this);
        tv.setText(summary);
        tv.setTextSize(13);
        tv.setAlpha(0.7f);
        LinearLayout.LayoutParams lp = rowParams();
        lp.bottomMargin = Ui.dp(this, 8);
        mRoot.addView(tv, lp);
    }

    private void addSpinner(String title, String[] options, String key, int def) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(16);
        tv.setPadding(0, Ui.dp(this, 10), 0, 0);
        mRoot.addView(tv, rowParams());

        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        int value = mPrefs.getInt(key, def);
        spinner.setSelection(Math.max(0, Math.min(value, options.length - 1)));
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // Also called once as the spinner is set up, with what is already saved.
                if (position != mPrefs.getInt(key, def)) {
                    save(key, mPrefs.edit().putInt(key, position));
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        mRoot.addView(spinner, rowParams());
    }

    private void addSlider(String title, String unit, String key, int def, int min, int max) {
        int value = mPrefs.getInt(key, def);
        TextView tv = new TextView(this);
        tv.setText(title + ": " + value + " " + unit);
        tv.setTextSize(16);
        tv.setPadding(0, Ui.dp(this, 10), 0, 0);
        mRoot.addView(tv, rowParams());

        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(value - min);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int v = min + progress;
                tv.setText(title + ": " + v + " " + unit);
                mPrefs.edit().putInt(key, v).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            /**
             * Let go: the launcher is told, as for a switch. Saved without telling it, a pop-up's
             * time and width waited for ZUX Home to come to the front again.
             */
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                save(key, mPrefs.edit().putInt(key, min + seekBar.getProgress()));
            }
        });
        mRoot.addView(bar, rowParams());
    }

    private void addText(String title, String summary, String key) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(16);
        tv.setPadding(0, Ui.dp(this, 10), 0, 0);
        mRoot.addView(tv, rowParams());

        EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setHint("com.example.home");
        et.setText(mPrefs.getString(key, ""));
        et.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                mPrefs.edit().putString(key, s.toString()).apply();
            }
        });
        mRoot.addView(et, rowParams());
        if (summary != null) {
            addSummary(summary);
        }
    }

    /**
     * The apps whose notifications never pop up, ticked in a list of every app with a launcher
     * icon. Read off the main thread: the labels of a few hundred apps take a moment.
     */
    private void pickMutedApps() {
        new Thread(() -> {
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.Intent launchable = new android.content.Intent(
                    android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_LAUNCHER);
            java.util.TreeMap<String, String> apps = new java.util.TreeMap<>(
                    String.CASE_INSENSITIVE_ORDER);
            for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(launchable, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (Const.MODULE_PKG.equals(pkg)) {
                    continue;
                }
                // Label first so the list reads in order; the package keeps two apps of the same
                // name apart.
                apps.put(ri.loadLabel(pm) + "\u0000" + pkg, pkg);
            }
            runOnUiThread(() -> showMutedApps(apps));
        }, "zux-desktop-plus-apps").start();
    }

    private void showMutedApps(java.util.TreeMap<String, String> apps) {
        if (isFinishing() || apps.isEmpty()) {
            return;
        }
        java.util.Set<String> muted = new java.util.LinkedHashSet<>();
        for (String pkg : mPrefs.getString(Const.KEY_POPUP_MUTED, "").split(",")) {
            if (!pkg.isEmpty()) {
                muted.add(pkg);
            }
        }
        String[] labels = new String[apps.size()];
        String[] packages = new String[apps.size()];
        boolean[] checked = new boolean[apps.size()];
        int i = 0;
        for (java.util.Map.Entry<String, String> e : apps.entrySet()) {
            labels[i] = e.getKey().substring(0, e.getKey().indexOf('\u0000'));
            packages[i] = e.getValue();
            checked[i] = muted.contains(e.getValue());
            i++;
        }
        new AlertDialog.Builder(this)
                .setTitle("Apps that never pop up")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> {
                    if (isChecked) {
                        muted.add(packages[which]);
                    } else {
                        muted.remove(packages[which]);
                    }
                })
                .setPositiveButton("Done", (d, w) -> mPrefs.edit()
                        .putString(Const.KEY_POPUP_MUTED, String.join(",", muted)).commit())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void sendTestNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFY);
            return;
        }
        if (!TestNotifications.send(this)) {
            android.widget.Toast.makeText(this,
                    "Notifications are off for Desktop Plus - allow them in its app settings",
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] results) {
        if (requestCode == REQUEST_NOTIFY && results.length > 0
                && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            sendTestNotifications();
        }
    }

    private void addButton(String title, Runnable action) {
        Button b = new Button(this);
        b.setText(title);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = rowParams();
        lp.topMargin = Ui.dp(this, 20);
        lp.gravity = Gravity.START;
        mRoot.addView(b, lp);
    }

    private LinearLayout.LayoutParams rowParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private void showHelp() {
        String text = "1. Enable the module in LSPosed and add your home app to its scope "
                + "(com.zui.home or com.zui.launcher - check LSPosed's app list), then reboot.\n\n"
                + "2. Connect the external display and switch to desktop mode.\n\n"
                + "3. On the desktop: right-click (or long-press) empty space for the menu - "
                + "add apps, widgets, shortcuts and folders from there.\n\n"
                + "4. Drag an icon to move it. Drop one icon on another to make a folder. "
                + "Drag to the Remove bar at the top to take it off the desktop.\n\n"
                + "5. The Apps button at the bottom-left opens the drawer: search, drag apps out "
                + "onto the desktop, drag them onto each other to make drawer folders, and use "
                + "the three-dot menu to keep your own order.\n\n"
                + "If the desktop surface does not appear, turn on 'Dump launcher info on attach' "
                + "and check the LSPosed log - it prints the activity and view tree it found.";
        new AlertDialog.Builder(this)
                .setTitle("How to use this")
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /**
     * Every feature the launcher reported (#13): green working, grey switched off, red not found
     * on this firmware - those first, as they are the ones to look at.
     */
    private void showHealth() {
        java.io.File file = new java.io.File(getFilesDir(),
                com.zuxos.desktopplus.notify.NotifyProvider.HEALTH_FILE);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        list.setPadding(pad, Ui.dp(this, 8), pad, 0);
        try {
            byte[] raw = java.nio.file.Files.readAllBytes(file.toPath());
            org.json.JSONObject root = new org.json.JSONObject(
                    new String(raw, java.nio.charset.StandardCharsets.UTF_8));
            org.json.JSONArray rows = root.getJSONArray("rows");
            String when = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,
                    java.text.DateFormat.SHORT).format(new java.util.Date(root.optLong("when")));
            addHealthNote(list, "Reported by " + root.optString("from") + ", " + when + ".");
            java.util.List<String[]> sorted = new java.util.ArrayList<>();
            for (int i = 0; i < rows.length(); i++) {
                String[] row = rows.getString(i).split("\t");
                if (row.length == 3) {
                    sorted.add(row);
                }
            }
            sorted.sort((a, b) -> Integer.compare(rank(a), rank(b)));
            for (String[] row : sorted) {
                addHealthRow(list, row);
            }
        } catch (Throwable t) {
            addHealthNote(list, "Nothing reported yet. Open ZUX Home once with the module on, "
                    + "and \"Report feature health\" switched on.");
        }
        addHealthNote(list, "Only ZUX Home's features are listed; those inside SystemUI and the "
                + "system do not report here yet.");
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        new AlertDialog.Builder(this)
                .setTitle("Feature health")
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** Not found first, then switched off, then working. */
    private static int rank(String[] row) {
        return row[1].equals("not found") ? 0 : row[2].equals("off") ? 1 : 2;
    }

    private void addHealthRow(LinearLayout list, String[] row) {
        int rank = rank(row);
        TextView tv = new TextView(this);
        android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder();
        text.append("●  ");
        text.setSpan(new android.text.style.ForegroundColorSpan(
                        rank == 0 ? 0xFFE53935 : rank == 1 ? 0xFF9E9E9E : 0xFF43A047),
                0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.append(row[0]);
        text.append(rank == 0 ? " - not found on this firmware"
                : rank == 1 ? " - switched off" : "");
        tv.setText(text);
        tv.setTextSize(14);
        tv.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 5));
        list.addView(tv);
    }

    private void addHealthNote(LinearLayout list, String note) {
        TextView tv = new TextView(this);
        tv.setText(note);
        tv.setTextSize(13);
        tv.setAlpha(0.7f);
        tv.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        list.addView(tv);
    }
}
