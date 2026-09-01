# ApexTuner Android — first-run UX and copy repair 1.1.6 / versionCode 43

## Confirmed issues

| Issue | Status |
| --- | --- |
| Android Studio Sync failed without canonical `gradle-wrapper.jar` | Fixed in 1.1.5 and retained |
| Kotlin JVM target not declared in several feature modules | Fixed in 1.1.5 and retained |
| Conflicting merged strings `ui_retry` and `ui_notification_history` | Fixed in 1.1.5 and retained |
| First-run rail header used hardcoded English | Fixed in 1.1.5 and retained |
| Predictive-back callback not enabled | Fixed in 1.1.5 and retained |
| Top-level navigation labels were hardcoded Kotlin strings | Fixed — `nav_*` string resources |
| Dashboard first-run hero/privacy copy was hardcoded while the rest of Dashboard uses resources | Fixed |
| Health-timeline chart ignored existing `dashboard_health_timeline_chart_desc` | Fixed |
| Battery Intelligence showed raw `BatteryHealth` enum names such as `OverVoltage` | Fixed — same readable labels as Dashboard |
| App Manager kind chips showed `All` / `User` / `System` | Fixed — All apps / User apps / System apps |
| Cleaner scan progress showed `PerceptualHashing` and categories such as `EmptyFolder` | Fixed |
| Settings premium status used `PremiumLifetime` enum-name surgery | Fixed — Premium active / Free edition |
| Theme chips labeled the system option `System` | Fixed — Match system |
| Premium screen showed `EntitlementTier` names and hardcoded verification copy | Fixed |
| Failed screen-recording stop forced `Recording` even when the session was Starting/Stopping | Fixed — current lifecycle state is preserved |
| CPU/Performance thermal status used raw enum names | Fixed |
| Files: folders could not be selected, so rename/copy/move/ZIP of folders was unreachable | Fixed — tap selects files and folders; directories also have Open |
| Files: a failed “choose another folder” replaced a working tree with a dead-end Error | Fixed — last usable location is kept |
| Data-usage alerts never requested `POST_NOTIFICATIONS` on API 33+ | Fixed — permission is declared, requested on save/start, and the alert body is a resource |
| Game session start did not request notification permission, so End & restore could vanish | Fixed — same notification gate as recording |
| Instrumentation smoke test looked for exact “Game Session Booster” while the hub appended Premium | Fixed |
| Status widget Open / Refresh were hardcoded English | Fixed |
| Tools hub titles/bodies were hardcoded and disagreed with destination screen names | Fixed |
| Cleaner chips, cards, and actions used three different names for the same categories | Fixed — chips share card resources |
| Contacts Ready-with-zero looked like a blank first-run screen | Fixed — empty-state copy |
| Game Booster flashed “no launchable apps match this filter” before the list loaded | Fixed |
| Settings → Notification history selected the Tools tab | Fixed — `settings/notifications` keeps Settings selected |

## Unchanged, still working

Lifetime Premium billing (`apextuner_premium_lifetime` INAPP), Quick Scan one-shot launch gate, screen-recording lifecycle gate, Game Session rollback, SAF/file guards, and capability-aware privileged tools were reviewed and left intact.

## Release identity

- Version name: `1.1.6`
- Version code: `43`
- Application ID: `com.apextuner.app`
