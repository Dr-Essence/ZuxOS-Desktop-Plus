package com.zuxos.desktopplus.hook.keyboard;

/**
 * Every name of HeliBoard's that {@link HeliPad}, {@link HeliWindow}, {@link HeliLanguage} and
 * {@link HeliKeys} reach, in one place: when HeliBoard changes, these are the ones to check
 * against its source. Read from HeliBoard 4.3-beta1, built without obfuscation, so its names are
 * its source's - but its release build is shrunk by R8, which drops or reshapes small members, so
 * the language's and the keys' are checked against a release build's own classes (4.2 as F-Droid
 * builds it). Each constant is its name in capitals.
 */
final class HeliNames {

    // The input method service.
    static final String LATIN_IME = "helium314.keyboard.latin.LatinIME";
    static final String ON_CREATE = "onCreate";
    static final String ON_DESTROY = "onDestroy";
    static final String ON_KEY_DOWN = "onKeyDown";
    static final String ON_KEY_UP = "onKeyUp";
    static final String ON_FINISH_INPUT_VIEW = "onFinishInputView";
    static final String ON_WINDOW_HIDDEN = "onWindowHidden";
    static final String M_INPUT_VIEW = "mInputView";
    static final String M_KEYBOARD_ACTION_LISTENER = "mKeyboardActionListener";
    static final String M_KEYBOARD_SWITCHER = "mKeyboardSwitcher";
    static final String M_SUGGESTION_STRIP_VIEW = "mSuggestionStripView";

    // Its languages: every switch of one ends in the subtype change, its own or the system's.
    static final String ON_CURRENT_INPUT_METHOD_SUBTYPE_CHANGED =
            "onCurrentInputMethodSubtypeChanged";
    static final String ON_START_INPUT_VIEW = "onStartInputView";
    /** The languages it has on, in its own order: a Kotlin object's, static in a release build. */
    static final String SUBTYPE_SETTINGS = "helium314.keyboard.latin.utils.SubtypeSettings";
    static final String GET_ENABLED_SUBTYPES = "getEnabledSubtypes";
    static final String INSTANCE = "INSTANCE";
    /** The one on: the manager is a static field, its getter gone in a release build. */
    static final String RICH_INPUT_METHOD_MANAGER =
            "helium314.keyboard.latin.RichInputMethodManager";
    static final String MANAGER_INSTANCE = "instance";
    static final String GET_CURRENT_SUBTYPE = "getCurrentSubtype";
    static final String RICH_INPUT_METHOD_SUBTYPE =
            "helium314.keyboard.latin.RichInputMethodSubtype";
    static final String RAW_SUBTYPE = "rawSubtype";
    /**
     * A language's name as its own language list shows it: an extension in a Kotlin object,
     * left only as its static default-locale form in a release build.
     */
    static final String SUBTYPE_LOCALE_UTILS = "helium314.keyboard.latin.utils.SubtypeLocaleUtils";
    static final String DISPLAY_NAME = "displayName";
    static final String DISPLAY_NAME_DEFAULT = "displayName$default";

    /**
     * A language's layouts, named in its subtype's extra value ({@code MAIN:greek|...}), and the
     * main layout's file among its assets, JSON or plain text.
     */
    static final String KEYBOARD_LAYOUT_SET = "KeyboardLayoutSet";
    static final String LAYOUT_ENTRY = "|";
    static final String LAYOUT_KV = ":";
    static final String LAYOUT_MAIN = "MAIN:";
    static final String LAYOUTS_MAIN = "layouts/main/";
    /** A JSON layout's key: its label, its code, its type, and a selector's choices. */
    static final String LABEL = "label";
    static final String CODE = "code";
    static final String KEY_TYPE = "type";
    static final String TYPE_SPACER = "spacer";
    static final String TYPE_PLACEHOLDER = "placeholder";
    static final String SELECTOR = "$";
    static final String SHIFT_STATE_SELECTOR = "shift_state_selector";
    static final String SHIFTED_MANUAL = "shiftedManual";
    static final String SHIFTED = "shifted";
    static final String CASE_SELECTOR = "case_selector";
    static final String LOWER = "lower";
    static final String UPPER = "upper";
    static final String DEFAULT = "default";

    // Its listener for the keys, and the haptic event a press takes.
    static final String ON_PRESS_KEY = "onPressKey";
    static final String ON_CODE_INPUT = "onCodeInput";
    static final String ON_RELEASE_KEY = "onReleaseKey";
    static final String KEY_PRESS = "KEY_PRESS";

    // The window the keyboard is in.
    static final String INPUT_VIEW = "helium314.keyboard.latin.InputView";
    static final String ON_INTERCEPT_TOUCH_EVENT = "onInterceptTouchEvent";

    // The keyboard switcher and the keyboard's view.
    static final String GET_MAIN_KEYBOARD_VIEW = "getMainKeyboardView";
    static final String M_KEYBOARD_VIEW = "mKeyboardView";
    static final String MAIN_KEYBOARD_VIEW = "helium314.keyboard.keyboard.MainKeyboardView";
    static final String SET_KEYBOARD = "setKeyboard";
    static final String PROCESS_MOTION_EVENT = "processMotionEvent";
    static final String GET_VERTICAL_CORRECTION = "getVerticalCorrection";
    static final String GET_KEYBOARD = "getKeyboard";
    static final String M_KEYBOARD = "mKeyboard";

    // A popup of more keys, held open from a key.
    static final String POINTER_TRACKER = "helium314.keyboard.keyboard.PointerTracker";
    static final String GET_POINTER_TRACKER = "getPointerTracker";
    static final String M_POPUP_KEYS_PANEL = "mPopupKeysPanel";
    static final String M_ORIGIN_X = "mOriginX";
    static final String M_ORIGIN_Y = "mOriginY";

    // A keyboard and its keys.
    static final String GET_SORTED_KEYS = "getSortedKeys";
    static final String M_SORTED_KEYS = "mSortedKeys";
    static final String GET_X = "getX";
    static final String M_X = "mX";
    static final String GET_Y = "getY";
    static final String M_Y = "mY";
    static final String GET_WIDTH = "getWidth";
    static final String M_WIDTH = "mWidth";
    static final String GET_HEIGHT = "getHeight";
    static final String M_HEIGHT = "mHeight";
    static final String GET_CODE = "getCode";
    static final String M_CODE = "mCode";
    static final String GET_BACKGROUND_TYPE = "getBackgroundType";
    static final String M_BACKGROUND_TYPE = "mBackgroundType";
    /** The end of the name of a gap between keys ({@code Key$Spacer}), which is no key. */
    static final String SPACER = "$Spacer";

    // The settings, for the keyboard's own accent colour.
    static final String SETTINGS = "helium314.keyboard.latin.settings.Settings";
    static final String GET_VALUES = "getValues";
    static final String S_INSTANCE = "sInstance";
    static final String M_SETTINGS_VALUES = "mSettingsValues";
    static final String M_COLORS = "mColors";
    static final String GET = "get";
    static final String COLOR_TYPE = "helium314.keyboard.latin.common.ColorType";
    static final String ACTION_KEY_BACKGROUND = "ACTION_KEY_BACKGROUND";

    private HeliNames() {
    }
}
