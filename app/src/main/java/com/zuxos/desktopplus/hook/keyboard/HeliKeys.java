package com.zuxos.desktopplus.hook.keyboard;

import android.content.res.AssetManager;
import android.inputmethodservice.InputMethodService;
import android.text.InputType;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodSubtype;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.logic.PhysicalKeys;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.regex.Pattern;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * A physical keyboard typing HeliBoard's language: with Greek on, the 8BitDo's A types α. Android
 * gives a physical keyboard its own layout, which HeliBoard's language never reaches, and ZUI's
 * input system takes no layout for this keyboard at all; HeliBoard reads physical keys with that
 * layout too ({@code HardwareKeyboardEventDecoder}, "TODO: get the layout for this hardware
 * keyboard"). So each letter place of a QWERTY keyboard types the key at the same place of
 * HeliBoard's own layout for the language on ({@link PhysicalKeys}), as the language's PC layout
 * does - Greek's is the same. A Latin layout is left to the keyboard's own layout, which matches
 * its printed keys; so are digits, Enter, Backspace, arrows, Ctrl, Alt and Meta chords, and
 * fields that take no text.
 *
 * <p>The letter goes in by HeliBoard's own key path, a screen key's ({@code onCodeInput}): its
 * suggestions, capitals and accent keys work as when its keys are touched. With its keyboard
 * hidden for the physical one, HeliBoard has not started its input, so the letter is committed
 * straight to the app, an accent joined to the letter after it. Main thread only.
 */
public final class HeliKeys {

    /** Each physical letter row, left to right, as Android names its keys. */
    private static final int[][] ROWS = {
            {KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_R,
                    KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_Y, KeyEvent.KEYCODE_U,
                    KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_O, KeyEvent.KEYCODE_P,
                    KeyEvent.KEYCODE_LEFT_BRACKET, KeyEvent.KEYCODE_RIGHT_BRACKET},
            {KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_F,
                    KeyEvent.KEYCODE_G, KeyEvent.KEYCODE_H, KeyEvent.KEYCODE_J,
                    KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_L, KeyEvent.KEYCODE_SEMICOLON,
                    KeyEvent.KEYCODE_APOSTROPHE},
            {KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_V,
                    KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_M,
                    KeyEvent.KEYCODE_COMMA, KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_SLASH}};
    /** Not a place on the screen's keyboard: HeliBoard's own value for that. */
    private static final int NO_COORDINATE = -1;

    /** The keys whose press was typed here, so their release is kept from the app too. */
    private static final BitSet TAKEN = new BitSet();
    /** HeliBoard's layout last looked at, and its keys; null where it does not apply. */
    private static String sLayout;
    private static PhysicalKeys sKeys;
    /** Its keyboard hidden: an accent typed, waiting for its letter; 0 for none. */
    private static int sAccent;

    private HeliKeys() {
    }

    public static void install(ClassLoader loader) {
        try {
            Class<?> ime = Reflect.findClass(HeliNames.LATIN_IME, loader);
            if (ime == null || !HeliLanguage.findCurrent(loader)) {
                L.w("heli keys: HeliBoard's keyboard or its language not found - a physical "
                        + "keyboard types Android's layout");
                return;
            }
            int hooked = XposedBridge.hookAllMethods(ime, HeliNames.ON_KEY_DOWN, DOWN).size()
                    + XposedBridge.hookAllMethods(ime, HeliNames.ON_KEY_UP, UP).size();
            L.i("heli keys: a physical keyboard types HeliBoard's language x" + hooked);
        } catch (Throwable t) {
            L.w("heli keys: not installed, a physical keyboard types Android's layout ("
                    + t + ")");
        }
    }

    private static final XC_MethodHook DOWN = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (param.thisObject instanceof InputMethodService && param.args.length > 1
                        && param.args[1] instanceof KeyEvent
                        && type((InputMethodService) param.thisObject, (KeyEvent) param.args[1])) {
                    param.setResult(true);
                }
            } catch (Throwable t) {
                if (L.once("HeliKeys.down")) {
                    L.w("heli keys: a key left to Android's layout (" + t + ")");
                }
            }
        }
    };

    private static final XC_MethodHook UP = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (param.args.length > 0 && param.args[0] instanceof Integer
                        && (Integer) param.args[0] >= 0 && TAKEN.get((Integer) param.args[0])) {
                    TAKEN.clear((Integer) param.args[0]);
                    param.setResult(true);
                }
            } catch (Throwable t) {
                // The release goes on to HeliBoard and the app.
            }
        }
    };

    /** A physical key typed in HeliBoard's language; false when it is left as it was. */
    private static boolean type(InputMethodService ime, KeyEvent event) throws Exception {
        int row = -1;
        int column = -1;
        for (int r = 0; r < ROWS.length && row < 0; r++) {
            for (int c = 0; c < ROWS[r].length; c++) {
                if (ROWS[r][c] == event.getKeyCode()) {
                    row = r;
                    column = c;
                    break;
                }
            }
        }
        if (row < 0 || event.isCtrlPressed() || event.isAltPressed() || event.isMetaPressed()
                || event.isFunctionPressed()) {
            sAccent = 0;
            return false;
        }
        if (event.getDeviceId() == KeyCharacterMap.VIRTUAL_KEYBOARD || !Cfg.keyboardFollows()) {
            return false;
        }
        EditorInfo editor = ime.getCurrentInputEditorInfo();
        if (editor == null
                || (editor.inputType & InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) {
            return false;
        }
        PhysicalKeys keys = keys(ime);
        int code = keys == null ? PhysicalKeys.NONE
                : keys.code(row, column, event.isShiftPressed(), event.isCapsLockOn());
        if (code == PhysicalKeys.NONE) {
            return false;
        }
        boolean typed = ime.isInputViewShown()
                ? codeInput(ime, code, event.getRepeatCount() > 0)
                : commit(ime, code);
        if (typed) {
            TAKEN.set(event.getKeyCode());
        }
        return typed;
    }

    /** HeliBoard's own path for a key, as a touch on its screen's key takes. */
    private static boolean codeInput(Object ime, int code, boolean repeat) throws Exception {
        sAccent = 0;
        Object listener = Reflect.field(ime, HeliNames.M_KEYBOARD_ACTION_LISTENER);
        if (listener != null) {
            for (Method m : listener.getClass().getMethods()) {
                if (m.getName().equals(HeliNames.ON_CODE_INPUT) && m.getParameterCount() == 4) {
                    m.invoke(listener, code, NO_COORDINATE, NO_COORDINATE, repeat);
                    return true;
                }
            }
        }
        if (L.once("HeliKeys.listener")) {
            L.w("heli keys: HeliBoard's key path not found - physical keys type Android's layout");
        }
        return false;
    }

    /** Its keyboard hidden: the letter straight to the app, an accent kept for the next one. */
    private static boolean commit(InputMethodService ime, int code) {
        InputConnection connection = ime.getCurrentInputConnection();
        if (connection == null) {
            return false;
        }
        if (PhysicalKeys.isAccent(code)) {
            sAccent = code;
            return true;
        }
        String text = sAccent != 0 && Character.isLetter(code) ? PhysicalKeys.join(sAccent, code)
                : new String(Character.toChars(code));
        sAccent = 0;
        connection.commitText(text, 1);
        return true;
    }

    /** The keys of HeliBoard's layout for the language on: read again only when it changes. */
    private static PhysicalKeys keys(InputMethodService ime) throws Exception {
        Object subtype = HeliLanguage.current();
        String layout = subtype instanceof InputMethodSubtype
                ? mainLayout((InputMethodSubtype) subtype) : null;
        if (layout == null) {
            return null;
        }
        if (!layout.equals(sLayout)) {
            sLayout = layout;
            sKeys = read(ime.getAssets(), layout);
        }
        return sKeys;
    }

    /** The main layout a language of HeliBoard's has: {@code MAIN:greek} in its extra value. */
    private static String mainLayout(InputMethodSubtype subtype) {
        String set = subtype.getExtraValueOf(HeliNames.KEYBOARD_LAYOUT_SET);
        if (set == null) {
            return null;
        }
        for (String entry : set.split(Pattern.quote(HeliNames.LAYOUT_ENTRY))) {
            if (entry.startsWith(HeliNames.LAYOUT_MAIN)) {
                return entry.substring(HeliNames.LAYOUT_MAIN.length());
            }
        }
        return set.contains(HeliNames.LAYOUT_KV) ? null : set;
    }

    /** A layout of HeliBoard's own, from its files; null where it is not one or does not fit. */
    private static PhysicalKeys read(AssetManager assets, String layout) throws Exception {
        String path = HeliNames.LAYOUTS_MAIN + layout;
        List<List<int[]>> rows;
        try {
            rows = json(text(assets, path + ".json"));
        } catch (FileNotFoundException notJson) {
            try {
                rows = PhysicalKeys.parseText(text(assets, path + ".txt"));
            } catch (FileNotFoundException notText) {
                L.i("heli keys: " + layout + " is not one of HeliBoard's own layouts - physical "
                        + "keys type Android's layout");
                return null;
            }
        }
        PhysicalKeys keys = PhysicalKeys.of(rows);
        L.i("heli keys: " + layout + (keys != null ? " - physical keys type it"
                : " is Latin or not a keyboard's three rows - physical keys type Android's "
                + "layout"));
        return keys;
    }

    private static String text(AssetManager assets, String path) throws Exception {
        try (InputStream in = assets.open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** HeliBoard's JSON layout: rows of keys, a key its code and its code with Shift. */
    private static List<List<int[]>> json(String text) throws Exception {
        JSONArray rows = new JSONArray(text);
        List<List<int[]>> out = new ArrayList<>();
        for (int r = 0; r < rows.length(); r++) {
            JSONArray row = rows.getJSONArray(r);
            List<int[]> keys = new ArrayList<>();
            for (int k = 0; k < row.length(); k++) {
                JSONObject key = row.optJSONObject(k);
                String type = key != null ? key.optString(HeliNames.KEY_TYPE) : "";
                if (key != null && !HeliNames.TYPE_SPACER.equals(type)
                        && !HeliNames.TYPE_PLACEHOLDER.equals(type)) {
                    keys.add(codes(key));
                }
            }
            out.add(keys);
        }
        return out;
    }

    /** A key's code and its code with Shift: a selector's own two, or the key's alone. */
    private static int[] codes(JSONObject key) {
        String selector = key.optString(HeliNames.SELECTOR);
        if (HeliNames.SHIFT_STATE_SELECTOR.equals(selector)) {
            JSONObject shifted = key.optJSONObject(HeliNames.SHIFTED_MANUAL);
            if (shifted == null) {
                shifted = key.optJSONObject(HeliNames.SHIFTED);
            }
            return new int[] {code(key.optJSONObject(HeliNames.DEFAULT)), code(shifted)};
        }
        if (HeliNames.CASE_SELECTOR.equals(selector)) {
            return new int[] {code(key.optJSONObject(HeliNames.LOWER)),
                    code(key.optJSONObject(HeliNames.UPPER))};
        }
        if (!selector.isEmpty()) {
            return new int[] {code(key.optJSONObject(HeliNames.DEFAULT)), PhysicalKeys.NONE};
        }
        return new int[] {code(key), PhysicalKeys.NONE};
    }

    /** What a key types: its code where it has one, else its label's one character. */
    private static int code(JSONObject key) {
        if (key == null) {
            return PhysicalKeys.NONE;
        }
        int code = key.optInt(HeliNames.CODE, 0);
        return code > 0 ? code : PhysicalKeys.single(key.optString(HeliNames.LABEL, null));
    }
}
