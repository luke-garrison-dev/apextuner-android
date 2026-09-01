# ApexTuner Android — source/Studio repair 1.1.5 / versionCode 42

## Confirmed issues

| Issue | Status |
| --- | --- |
| Android Studio Sync failed without canonical `gradle-wrapper.jar` (`Could not find or load main class org.gradle.wrapper.GradleWrapperMain`) | Fixed — official Gradle 9.5.0 wrapper JAR shipped (`497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`) |
| Kotlin JVM target not declared in several feature modules while Java bytecode was 17, which Android Studio reports as inconsistent JVM-target compatibility | Fixed — every Android module now sets `jvmTarget` JVM 17 and `-jvm-default=no-compatibility` |
| Conflicting merged string `ui_retry` (`" Retry"` vs `"Retry"`) | Fixed |
| Conflicting merged string `ui_notification_history` (`Notification History` vs `Notification history`) | Fixed |
| First-run rail header used hardcoded English instead of resources | Fixed |
| Predictive-back callback not enabled on the application | Fixed (`android:enableOnBackInvokedCallback`) |
| `gradlew` executable bit required by the source validator | Fixed |
| Build number needed incrementing after the repair | Fixed — `1.1.5` / `versionCode 42` |

## Unchanged, still working

Lifetime Premium billing (`apextuner_premium_lifetime` INAPP), Quick Scan one-shot launch gate, screen-recording lifecycle gate, Game Session rollback, SAF/file guards, and capability-aware privileged tools were reviewed and left intact.

## Release identity

- Version name: `1.1.5`
- Version code: `42`
- Application ID: `com.apextuner.app`
