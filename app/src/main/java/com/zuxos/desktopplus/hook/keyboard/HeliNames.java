package com.zuxos.desktopplus.hook.keyboard;

/**
 * Every name of HeliBoard's that {@link HeliPad}, {@link HeliWindow} and {@link HeliLanguage}
 * reach, in one place: when HeliBoard changes, these are the ones to check against its source.
 * Read from HeliBoard 4.3-beta1, built without obfuscation, so its names are its source's. Each
 * constant is its name in capitals.
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
    static final String SWITCH_TO_SUBTYPE = "switchToSubtype";
    /** The languages it has on, in its own order: a Kotlin object's. */
    static final String SUBTYPE_SETTINGS = "helium314.keyboard.latin.utils.SubtypeSettings";
    static final String GET_ENABLED_SUBTYPES = "getEnabledSubtypes";
    static final String INSTANCE = "INSTANCE";
    /** The one on, and the next after it. */
    static final String RICH_INPUT_METHOD_MANAGER =
            "helium314.keyboard.latin.RichInputMethodManager";
    static final String GET_INSTANCE = "getInstance";
    static final String GET_CURRENT_SUBTYPE = "getCurrentSubtype";
    static final String GET_NEXT_SUBTYPE_IN_THIS_IME = "getNextSubtypeInThisIme";
    static final String GET_RAW_SUBTYPE = "getRawSubtype";
    /** A language's name as its own language list shows it: an extension in a Kotlin object. */
    static final String SUBTYPE_LOCALE_UTILS = "helium314.keyboard.latin.utils.SubtypeLocaleUtils";
    static final String DISPLAY_NAME = "displayName";

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
