# eyeFold

The iPhone "Duo" frosted-glass fold, system-wide on Android foldables. As you
open or close the phone, each half of the screen acts as a pane of frosted
glass hinged at the crease: blurred and darkened while folded, sharpening
into focus as you reach flat. It plays over everything (home screen, lock
screen, any app) and follows the real hinge angle. No root.

Tuned on the Galaxy Z Fold 8; should work on other Android 13+ foldables.
A fork of [marcoazeem/duo-open](https://github.com/marcoazeem/duo-open) with
smoother frost, live (not frozen) content under the glass, and fixes for the
wallpaper and half-black frames after an unfold.

## Setup

You need a computer with [Android Studio](https://developer.android.com/studio)
(for the Android SDK and JDK 21+) and a USB cable.

1. **Turn on USB debugging** on the phone: *Settings → About phone → Software
   information*, tap *Build number* 7 times, then *Settings → Developer
   options → USB debugging*. Plug the phone in and accept the prompt.

2. **Build and install:**
   ```
   git clone https://github.com/Ermag/eyeFold-android.git
   cd eyeFold-android
   ./gradlew installFullDebug
   ```
   If Gradle can't find a JDK, point `JAVA_HOME` at Android Studio's bundled
   one, e.g. `/Applications/Android Studio.app/Contents/jbr/Contents/Home`.

3. **Turn it on:** *Settings → Accessibility → Installed apps → eyeFold
   full-screen fold → On*. Apps installed over USB aren't blocked by
   Android's restricted-settings check, so the toggle just works.

4. **Samsung: add Shizuku (strongly recommended).** Galaxy Folds only give
   apps three hinge positions (0°/90°/180°), so without it the fold plays as
   a timed ease instead of following your hand.
   - Install [Shizuku](https://shizuku.rikka.app/) and start it (wireless
     debugging; once per reboot).
   - In eyeFold: **Tune → Shizuku mode → Authorise**.

Done. Fold the phone partway and open it, or tap **Tune → Test**.

## Tips

- **Tune** has strength, frost, darkening, *Crease frost*, which half moves,
  and *Clear frost on pause* (fade the frost out when you stop mid-fold).
- On Samsung, setting the built-in **Fold interactive** wallpaper gives 1°
  hinge readings on the home screen, smoother than Shizuku's ~5° alone.
- Reinstalling turns the accessibility service off; redo step 3.
- Debug log: `adb logcat -s DuoOverlay`. Replay the effect:
  `adb shell am broadcast -a com.duoopen.DEMO`.

## Privacy

No network permission. The screen is captured only into memory, only while
the fold plays, and secure screens (banking, DRM video) are never captured.
With Shizuku, a helper reads just the hinge-sensor and fold-wallpaper lines
from the system log, nothing else.

## Credits

- [marcoazeem/duo-open](https://github.com/marcoazeem/duo-open) (MIT): the app this fork is built on.
- [Atomicx7/Duo-animation](https://github.com/Atomicx7/Duo-animation): the original frosted-glass shader.
- [joeconsorti/duo-fold-live](https://github.com/joeconsorti/duo-fold-live) (MIT): the Samsung fold-wallpaper angle and shell-side capture techniques.
- [OrangeHubX/duo-open-for-galaxy](https://github.com/OrangeHubX/duo-open-for-galaxy) (MIT): the Samsung sensor-log angle, hinge tracker and fold-posture feed.
- [Shizuku](https://github.com/RikkaApps/Shizuku) and [AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) (Apache-2.0).

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). MIT licensed, see [LICENSE](LICENSE).
