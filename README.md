# Guardian

An all-in-one Android phone guard (Kotlin, framework Views, no third-party libraries).

| Guard | How it works |
|---|---|
| **Pocket / charger / movement alarm** | Arm it, put the phone away. Taken out of the pocket (proximity sensor), unplugged, or moved (accelerometer) without the PIN → full-screen PIN prompt, then a loud alarm on the alarm stream at max volume. |
| **Find my phone by SMS** | Text your secret word to your number from any phone (or only trusted numbers). It rings at full volume, even on silent with Do Not Disturb access, and texts back a map link and battery level. |
| **Ad blocker, per app** | A local DNS-only VPN applied only to the apps you pick. Ad/tracker lookups get "no such domain"; everything else goes straight to your normal DNS. Built-in list plus an optional full list download (StevenBlack hosts). |
| **Data usage warnings** | Reads Android's own mobile-data counters (Usage access). Daily limit, bundle size with renewal day, and a data-hog alert; checked about every 30 minutes. |
| **Battery guard** | Charge alarm at 80/85/90/100%, low-battery and overheat warnings, and health details the phone reports (health, cycle count on Android 14+, voltage, time at 100%). |
| **Quick Settings tiles** | Pocket alarm, Ad blocker, Data today, Charge alarm. "Add tiles to my panel" on Android 13+. |

## Install

GitHub Actions builds a signed debug APK on every push: open **Releases → Guardian latest debug build → Guardian-debug.apk** on your phone.
Updates install over the previous version (shared key in `app/debug.keystore`).

## Honest limits

- Android can't stop someone powering the phone off; the alarm can't survive that.
- On Samsung, allow **Unrestricted battery**, or One UI may stop the guards.
- Apps that use their own private DNS (or Private DNS set to a hostname) bypass the ad blocker; some apps serve ads from the same servers as content.
- SMS replies cost normal airtime. Location in the reply needs "Allow all the time" location.

## Code

```
core/     pure logic + unit tests (DNS packets, block list, detectors, SMS commands, data math)
data/     preferences and event history
system/   guard service, VPN, SMS receiver, jobs, tiles, notifications
screens/  tabs (Home, Security, Network, Battery), onboarding, settings, app picker, alarm PIN pad
ui/       small Material 3-style UI kit
```
