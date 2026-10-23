# When ZuxOS, ZUX Home or HeliBoard updates

The module reaches into ZUI's own code (ZUX Home, SystemUI and Android's system process) and into
HeliBoard's. An update can rename or change what it reaches, and the features built on that stop
working until they are fixed. A feature whose hook finds nothing falls back to ZUI's own behaviour
and writes why to the log; it does not take ZUX Home down with it. This page says what to do.

## For you, and for testers

**Before updating ZuxOS**
- Note the version you are on (Settings → About).
- Have a backup.
- Know how to turn LSPosed modules off from recovery or safe mode, in case the home app or the
  tablet does not start afterwards.

**Right after updating**
- Open the module's app → **Diagnostics** → **Feature health** (with **Report feature health**
  on, as it is by default, and ZUX Home opened once).
  - Red: a feature that found nothing on the new firmware. These are listed first.
  - Grey: switched off.
  - Green: working.
  - Only ZUX Home's features are listed. Those inside SystemUI and the system show only in the
    LSPosed log.
- A feature that misbehaves can be turned off alone, with its own switch, until it is fixed.

**Then send**
- the LSPosed log zip (Manager → Logs → save);
- a probe (see the README's [Reporting a problem](../README.md#reporting-a-problem));
- a screenshot of Feature health;
- the new ZuxOS version.

Pull the new firmware's APKs and jars the way the current ones were pulled, and add them as a new
release of the private `Dr-Essence/Test` repository (ZUX Home is `default.apk`). That is what the
fixes are read from.

**If the home app or the tablet does not start:** turn the module off from recovery or safe mode
first, then send the logs.

**HeliBoard updates** work the same way, with HeliBoard's version. Only typing with a game
controller depends on it.

## For whoever fixes it

1. Decompile the new firmware into `Dr-Essence/Test`'s `decompiled/` with the same jadx version
   and options as before (`docs/ZUI-NOTES.md`, at the top). Never copy any of it into this
   repository.
2. Read the whole module log, the `ZuxDesktopPlus` lines: every warning (`[W]`), above all those
   that say something was not found, and the red rows of Feature health.
3. Check the names against the new code:
   - `hook/system/SystemNames` against `services.jar` and `framework.jar`, and `hook/keyboard/HeliNames` against
     HeliBoard's source at its new tag;
   - for the other areas, the class, method and field names in that area's files.
4. Update the versions in `docs/ZUI-NOTES.md`.
5. Open one row in `docs/ROADMAP.md` per broken feature, and fix them one at a time under
   `CLAUDE.md`'s rules: evidence first, through ZUI's own path, falling back to stock behaviour.
