#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import re
import stat
import sys
import tomllib
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EXPECTED_MODULES = [
    "app", "core", "feature/dashboard", "feature/cleaner", "feature/battery",
    "feature/memory", "feature/appmanager", "feature/network", "feature/tools",
    "feature/settings", "feature/billing",
]
FORBIDDEN_MANIFEST_PERMISSIONS = {
    "android.permission.MANAGE_EXTERNAL_STORAGE",
    "android.permission.QUERY_ALL_PACKAGES",
    "android.permission.WRITE_SECURE_SETTINGS",
    "android.permission.KILL_BACKGROUND_PROCESSES",
    "android.permission.DEVICE_POWER",
}
LEGACY_BRANDS = ("OptiMax", "optimax")
IMPLEMENTATION_MARKERS = ("TODO", "FIXME", "NotImplementedError")
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"

errors: list[str] = []
checks = 0

def check(condition: bool, message: str) -> None:
    global checks
    checks += 1
    if not condition:
        errors.append(message)

def text(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")

def balanced_call_bodies(source: str, function_name: str) -> list[str]:
    bodies: list[str] = []
    start = 0
    marker = function_name + "("
    while True:
        index = source.find(marker, start)
        if index < 0:
            return bodies
        open_index = index + len(function_name)
        depth = 0
        in_string = False
        escaped = False
        for cursor in range(open_index, len(source)):
            char = source[cursor]
            if in_string:
                if escaped:
                    escaped = False
                elif char == "\\":
                    escaped = True
                elif char == '"':
                    in_string = False
                continue
            if char == '"':
                in_string = True
            elif char == "(":
                depth += 1
            elif char == ")":
                depth -= 1
                if depth == 0:
                    bodies.append(source[open_index + 1:cursor])
                    start = cursor + 1
                    break
        else:
            return bodies

# Version catalog / module topology.
with (ROOT / "gradle/libs.versions.toml").open("rb") as fh:
    catalog = tomllib.load(fh)
versions = catalog.get("versions", {})
check(versions.get("agp") == "9.3.2", "AGP must be current stable 9.3.2 for the modern built-in-Kotlin baseline.")
check(versions.get("activityCompose") == "1.13.0", "Activity Compose must stay on API-36-compatible 1.13.0.")
check(versions.get("navigation") == "2.9.8", "Navigation Compose must stay on API-36-compatible 2.9.8.")
check(versions.get("datastore") == "1.2.1", "DataStore must stay on stable 1.2.1.")
check(versions.get("work") == "2.11.2", "WorkManager must stay on stable 2.11.2.")
check(versions.get("lifecycle") == "2.8.7", "Lifecycle must remain on the pre-API-37 toolchain line until AGP 9.2 migration is tested.")
check(versions.get("shizuku") == "13.1.5", "Shizuku must remain on maintained 13.1.5 for the current privileged-access integration.")
check(versions.get("kotlin") == "2.3.21", "Kotlin/Compose compiler must stay on the Hilt-compatible 2.3.21 line.")
check(versions.get("ksp") == "2.3.9", "KSP must stay on stable 2.3.9 for Kotlin 2.3 / AGP 9 built-in Kotlin.")
check(versions.get("hilt") == "2.60.1", "Dagger/Hilt must stay on the AGP-9-compatible 2.60.1 release.")
ci_workflow = text(".github/workflows/android-ci.yml")
check("gradle-version: '9.5.0'" in ci_workflow, "CI must use Gradle 9.5.0")
wrapper_properties = text("gradle/wrapper/gradle-wrapper.properties")
wrapper_readme = text("gradle/wrapper/README.md")
check("gradle-9.5.0-bin.zip" in wrapper_properties, "Gradle wrapper properties must pin Gradle 9.5.0")
check("Gradle **9.5.0**" in wrapper_readme and "gradle-9.5.0-bin.zip" in wrapper_readme, "Gradle wrapper documentation must match the pinned 9.5.0 bootstrap")
check("8.13" not in wrapper_readme, "Stale Gradle 8.13 wrapper documentation must not return")
check(bool((ROOT / "gradlew").stat().st_mode & stat.S_IXUSR), "gradlew must remain executable for Unix/macOS source packages")
check("java-version: '17'" in ci_workflow, "CI must use JDK 17")
check('"platforms;android-36"' in ci_workflow and '"build-tools;36.0.0"' in ci_workflow, "CI SDK installation must match API-36 / AGP-9.3 documented baseline")
root_build = text("build.gradle.kts")
catalog_text = text("gradle/libs.versions.toml")
check("org.jetbrains.kotlin.android" not in catalog_text and "kotlin.android" not in root_build, "AGP 9 built-in Kotlin must not be paired with the legacy Kotlin Android plugin")
check("org.jetbrains.kotlin.kapt" not in catalog_text and "kotlin.kapt" not in root_build, "kapt must not return; all supported processors use KSP")
gradle_properties = text("gradle.properties")
check("android.dependency.excludeLibraryComponentsFromConstraints" not in gradle_properties, "Deprecated AGP library-constraint compatibility option must not return")
check("android.dependency.useConstraints=false" in gradle_properties, "AGP 9 dependency-constraint behavior must use the supported android.dependency.useConstraints=false option")
for build_file in [ROOT / "app/build.gradle.kts", ROOT / "core/build.gradle.kts", *sorted((ROOT / "feature").glob("*/build.gradle.kts"))]:
    build_text = build_file.read_text(encoding="utf-8")
    check("kapt(" not in build_text and "kapt {" not in build_text, f"{build_file.relative_to(ROOT)}: kapt usage must remain eliminated")
    check("kotlinOptions" not in build_text, f"{build_file.relative_to(ROOT)}: legacy android.kotlinOptions DSL must remain eliminated")
    check("-Xjvm-default" not in build_text, f"{build_file.relative_to(ROOT)}: deprecated -Xjvm-default compiler flag must not return")
settings = text("settings.gradle.kts")
check('rootProject.name = "ApexTuner"' in settings, "Root project name must be ApexTuner.")
for module in EXPECTED_MODULES:
    check((ROOT / module).is_dir(), f"Missing module directory: {module}")
    gradle_path = module.replace('/', ':')
    check(f'include(":{gradle_path}")' in settings, f"Module not included in settings.gradle.kts: {module}")

# Gradle Android baseline across modules.
for module in EXPECTED_MODULES:
    build = text(f"{module}/build.gradle.kts")
    check(re.search(r"compileSdk\s*=\s*36\b", build) is not None, f"{module}: compileSdk must be 36")
    check(re.search(r"minSdk\s*=\s*26\b", build) is not None, f"{module}: minSdk must be 26")
app_build = text("app/build.gradle.kts")
check('applicationId = "com.apextuner.app"' in app_build, "Application ID must be com.apextuner.app")
check(re.search(r"targetSdk\s*=\s*36\b", app_build) is not None, "App targetSdk must be 36")
check(re.search(r"versionCode\s*=\s*41\b", app_build) is not None, "Current audited app versionCode must be 41")
check('versionName = "1.1.4"' in app_build, "Current audited app versionName must be 1.1.4")
readme = text("README.md")
check("Release candidate source version: **1.1.4** (`versionCode 41`)" in readme, "README product identity must match delivered app version 1.1.4 / versionCode 41")
release_identity = text("docs/RELEASE_IDENTITY_1.1.4.md")
check("`versionCode`: `41`" in release_identity and "`versionName`: `1.1.4`" in release_identity and "`com.apextuner.app`" in release_identity, "Current release identity document must match app build metadata")
check("isMinifyEnabled = true" in app_build and "isShrinkResources = true" in app_build, "Release minify/resource shrinking must stay enabled")

# Checkpoint-3 lifecycle/background resilience invariants.
main_manifest = text("app/src/main/AndroidManifest.xml")
recovery_source = text("app/src/main/java/com/apextuner/app/AppLifecycleRecovery.kt")
automation_scheduler_source = text("feature/settings/src/main/java/com/apextuner/feature/settings/automation/AutomationScheduler.kt")
smart_worker_source = text("feature/settings/src/main/java/com/apextuner/feature/settings/automation/SmartAutomationWorker.kt")
charging_tracker_source = text("feature/battery/src/main/java/com/apextuner/feature/battery/ChargingSessionTracker.kt")
battery_scheduler_source = text("feature/battery/src/main/java/com/apextuner/feature/battery/BatteryHealthTrendScheduler.kt")
network_quality_dao_source = text("core/src/main/java/com/apextuner/core/database/NetworkQualityRunDao.kt")
network_diagnostics_source = text("feature/network/src/main/java/com/apextuner/feature/network/diagnostics/NetworkDiagnosticsRepository.kt")
check('android.permission.RECEIVE_BOOT_COMPLETED' in main_manifest, "Lifecycle recovery must retain RECEIVE_BOOT_COMPLETED")
check('.AppLifecycleRecoveryReceiver' in main_manifest and 'android:exported="false"' in main_manifest, "Lifecycle recovery receiver must remain private to app/system delivery")
check(
    'android:name=".MainActivity"' in main_manifest and 'android:launchMode="singleTop"' in main_manifest,
    "MainActivity must stay singleTop so Quick Settings/widget launches reuse the existing task and reach onNewIntent()",
)
for action in ("BOOT_COMPLETED", "MY_PACKAGE_REPLACED", "TIMEZONE_CHANGED"):
    check(action in main_manifest and action in recovery_source, f"Lifecycle recovery must handle {action}")
check("ExistingWorkPolicy.REPLACE" in recovery_source and "OneTimeWorkRequestBuilder<AppLifecycleRecoveryWorker>" in recovery_source, "Lifecycle recovery broadcasts must coalesce into one repair worker")
check("entitlementRepository.refresh" not in recovery_source, "Lifecycle recovery must never open Google Play Billing from a system broadcast path")
check(
    "runBestEffortCancellable" in recovery_source
    and "catch (cancelled: CancellationException)" in recovery_source
    and "throw cancelled" in recovery_source
    and "runCatching { tuningController.reconcileLegacyState() }" not in recovery_source,
    "Lifecycle recovery best-effort suspend work must preserve WorkManager cancellation",
)
check("hasEnabledSmartRules" in automation_scheduler_source and "SmartAutomationSchedulePolicy.shouldSchedule" in automation_scheduler_source, "Smart Automation periodic work must exist only when premium access and at least one enabled rule are present")
check(
    "if (rules.isEmpty() && !hasOwnedProfile) return Result.success()" in smart_worker_source
    and "executor.hasOwnedProfile()" in smart_worker_source,
    "Stale Smart Automation work must exit before expensive telemetry unless an owned profile needs restoration",
)
check(
    "repository.allRules()" in smart_worker_source
    and "restoreIfOwnedAndConditionCleared" in smart_worker_source,
    "Smart Automation stale-work recovery must reconcile owned profile state even after the last rule is disabled",
)
check("observationMutex = Mutex()" in charging_tracker_source and "observationMutex.withLock" in charging_tracker_source, "Charging-session writes must remain serialized across UI and worker callers")
check("PRUNE_INTERVAL_MILLIS" in charging_tracker_source, "Charging-session retention cleanup must remain throttled")
check("trimToNewest" in network_quality_dao_source and "QUALITY_HISTORY_MAX_ROWS = 200" in network_diagnostics_source, "Network Quality history must remain bounded by row count as well as age")
check("setRequiresBatteryNotLow(true)" in battery_scheduler_source and "setBackoffCriteria(BackoffPolicy.EXPONENTIAL" in battery_scheduler_source, "Daily battery-health snapshots must avoid low-battery execution and use retry backoff")
# Checkpoint-4 diagnostic/App Inspector invariants.
network_models_source = text("feature/network/src/main/java/com/apextuner/feature/network/diagnostics/NetworkDiagnosticsModels.kt")
network_route_source = text("feature/network/src/main/java/com/apextuner/feature/network/diagnostics/NetworkDiagnosticsRoute.kt")
app_manager_models_source = text("feature/appmanager/src/main/java/com/apextuner/feature/appmanager/AppManagerModels.kt")
check("NetworkQualityProbePlanner" in network_models_source and "resolverPreferred" in network_models_source, "Network Quality must keep a bounded dual-stack probe plan that respects resolver preference")
check("ipv4Quality" in network_diagnostics_source and "ipv6Quality" in network_diagnostics_source and "qualityHandshakeMillis" in network_diagnostics_source, "Network Quality must probe IPv4 and IPv6 independently instead of trusting one DNS address")
check("preferredAddressFamily" in network_route_source and "network_quality_family" in network_route_source, "Network Quality UI must expose per-family results and the best responding route")
check("summarizeAppInventory" in app_manager_models_source and "AppInventoryInsights" in app_manager_models_source, "App Manager must retain its cheap metadata-only inventory insight summary")
check("AppInsightFilter.InstallerUnknown -> !app.isSystem" in app_manager_models_source, "Unknown-installer review must not flag system apps merely because Android exposes no installer")
# Checkpoint-5 usability and ownership-safety invariants.
smart_automation_source = text("feature/settings/src/main/java/com/apextuner/feature/settings/automation/SmartAutomation.kt")
settings_vm_source = text("feature/settings/src/main/java/com/apextuner/feature/settings/SettingsViewModel.kt")
tools_route_source = text("feature/tools/src/main/java/com/apextuner/feature/tools/ToolsRoute.kt")
diagnostic_models_source = text("feature/tools/src/main/java/com/apextuner/feature/tools/diagnostics/DiagnosticReportModels.kt")
diagnostic_vm_source = text("feature/tools/src/main/java/com/apextuner/feature/tools/diagnostics/DiagnosticReportViewModel.kt")
diagnostic_repository_source = text("feature/tools/src/main/java/com/apextuner/feature/tools/diagnostics/DiagnosticReportRepository.kt")
check("bestEffort(0) { healthSamples.recent(1_000).size }" in diagnostic_repository_source and "catch (cancelled: CancellationException)" in diagnostic_repository_source and "throw cancelled" in diagnostic_repository_source, "Diagnostic best-effort history reads must remain cancellation-cooperative")
check("smartAutomationRecovery.reconcileOwnedProfile(forceRestore = !premium)" in recovery_source, "Lifecycle recovery must reconcile Smart Automation-owned profiles and force restoration when Premium is inactive")
check("if (enabled) ruleDao.clearLastTriggered(id)" in smart_automation_source, "Re-enabling a Smart Automation rule must clear stale cooldown state")
check("clearLastTriggeredForAction" in smart_automation_source and "throttleAttempt" in smart_worker_source, "Notification-rule failures must be cooldown-bounded and permission grants must be able to reset notification cooldowns")
check("smartAutomationRecovery.reconcileOwnedProfile()" in settings_vm_source, "Disabling a Smart Automation rule must immediately reconcile any profile it owns")
check("forceRestore = !premium" in smart_worker_source, "Premium loss must force restoration of any Smart Automation-owned reversible profile")
check("smartAutomationRecovery.reconcileOwnedProfile(forceRestore = !premium)" in text("app/src/main/java/com/apextuner/app/AppViewModel.kt"), "Foreground entitlement changes must reconcile Smart Automation-owned profiles")
check("trimToNewest(EVENT_HISTORY_MAX_ROWS)" in smart_automation_source and "EVENT_HISTORY_MAX_ROWS = 1_000" in smart_automation_source, "Smart Automation history must be bounded by row count as well as age")
check("internal class SmartAutomationExecutor" not in smart_automation_source and "internal class SmartAutomationOwnershipStore" not in smart_automation_source, "Public Hilt Worker dependencies must not use internal visibility that leaks through its injected constructor")
check("trimToNewest" in text("core/src/main/java/com/apextuner/core/database/AutomationDao.kt"), "Automation event DAO must support hard history bounds")
check('value.dnsLatencyMillis?.let { "$it ms" } ?: "—"' in network_route_source, "Network Quality must not present unavailable DNS latency as 0 ms")
check(all(name in tools_route_source for name in ("tools_group_device", "tools_group_connectivity", "tools_group_data", "tools_group_sessions")), "Tools hub must remain grouped by user purpose")
check("current = null" in diagnostic_vm_source and "totalNetworkDeltaBytes" in diagnostic_models_source, "Diagnostic baseline UX must require an explicit after-capture and never report unavailable network counters as zero traffic")
core_module = text("core/src/main/java/com/apextuner/core/di/CoreModule.kt")
game_receiver = text("feature/tools/src/main/java/com/apextuner/feature/tools/game/GameSessionReceiver.kt")
check("@param:ApplicationContext context" not in core_module and "@param:IoDispatcher fun" not in core_module, "Provider methods must not use constructor-only @param annotation targets")
check("@field:IoDispatcher lateinit var io" in game_receiver and "@param:IoDispatcher lateinit var io" not in game_receiver, "Field-injected dispatcher qualifier must use the backing-field annotation target")
intelligence_vm_source = text("feature/dashboard/src/main/java/com/apextuner/feature/dashboard/intelligence/IntelligenceViewModel.kt")
check("@param:IoDispatcher private val ioDispatcher: CoroutineDispatcher" in intelligence_vm_source, "Constructor-injected Intelligence dispatcher qualifier must use an explicit parameter target for Kotlin 2.3 compatibility")
all_kotlin_source = "\n".join(path.read_text(encoding="utf-8") for path in ROOT.rglob("*.kt") if "build" not in path.parts)
check("@param:Assisted" not in all_kotlin_source, "@Assisted targets VALUE_PARAMETER only; redundant @param use-site targets must not be reintroduced")
check("@param:ApplicationContext context: Context" not in all_kotlin_source, "Plain constructor parameters must not use redundant @param:ApplicationContext targets")
system_info = text("feature/tools/src/main/java/com/apextuner/feature/tools/systeminfo/SystemInfoRepository.kt")
check("context.display?.refreshRate" not in system_info and "context.display.refreshRate" in system_info, "API 30+ Context.display is non-null here; do not reintroduce the redundant safe call")
check("session!!" not in system_info and "activeSession" in system_info, "DRM cleanup must avoid redundant non-null assertions")
cleaner_route = text("feature/cleaner/src/main/java/com/apextuner/feature/cleaner/OptimizeRoute.kt")
adaptive_ui = text("core/src/main/java/com/apextuner/core/ui/AdaptiveUi.kt")
app_shell = text("app/src/main/java/com/apextuner/app/ui/shell/ApexTunerApp.kt")
tools_route = text("feature/tools/src/main/java/com/apextuner/feature/tools/ToolsRoute.kt")
dashboard_route = text("feature/dashboard/src/main/java/com/apextuner/feature/dashboard/DashboardRoute.kt")
check("PHOTO_PICKER_MAX_ITEMS" not in cleaner_route, "Photo Picker must not hard-code a count that can exceed the device MediaStore picker maximum")
check("ActivityResultContracts.PickMultipleVisualMedia()" in cleaner_route and "maxItems =" not in cleaner_route, "Photo Picker must use AndroidX's device-aware multi-select maximum")
check('filesLauncher.launch(arrayOf("image/*", "video/*"))' in cleaner_route and "reportAccessError" in cleaner_route, "Photo Picker launch must have a non-crashing SAF fallback and visible failure state")
check("ApexLayout.shouldUseCompactNavigationRail(" in app_shell and "heightDp = maxHeight.value.toInt()" in app_shell, "Root navigation must account for short phone-landscape height, not width alone")
check("label = if (compactNavigationRail) null" in app_shell and "header = if (compactNavigationRail) null" in app_shell, "Compact landscape rail must remove height-heavy labels and brand header")
check("fun navigationPresentationFor(widthDp: Int, heightDp: Int)" in adaptive_ui and "height < 600" in adaptive_ui, "Adaptive navigation strategy must classify compact-height windows")
check("GridCells.Fixed(1)" in tools_route and "ApexLayout.isCompactLandscape()" in tools_route, "Tools cards must stay single-column on short phone landscape windows")
check("!compactLandscape" in dashboard_route and "val twoColumns = maxWidth >= 620.dp" in dashboard_route, "Dashboard metric cards must not force cramped two-column phone-landscape layout")
cpu_model = text("core/src/main/java/com/apextuner/core/model/DeviceSnapshot.kt")
cpu_data_source = text("core/src/main/java/com/apextuner/core/system/AndroidDeviceTelemetryDataSource.kt")
cpu_tracker = text("core/src/main/java/com/apextuner/core/system/CpuUsageTracker.kt")
cpu_tracker_test = text("core/src/test/java/com/apextuner/core/system/CpuUsageTrackerTest.kt")
dashboard_strings = text("feature/dashboard/src/main/res/values/strings.xml")
check("enum class CpuUsageAvailability" in cpu_model and "RestrictedByPlatform" in cpu_model, "CPU telemetry must preserve an explicit Android-restricted availability state")
check("cpuUsageTracker.update(readCpuCounters())" in cpu_data_source, "CPU sampling must route aggregate counters through the shared availability-aware tracker")
check("@Synchronized" in cpu_tracker and "lastAvailable" in cpu_tracker and "sampleGapMillis < minSampleGapMillis" in cpu_tracker, "CPU tracker must remain concurrency-safe and reuse only fresh overlap samples")
check('const val PROC_STAT_PATH = "/proc/stat"' in cpu_data_source and "if (!procStat.canRead()) return CpuCounterRead.RestrictedByPlatform" in cpu_data_source, "Denied /proc/stat access must be classified as a platform restriction instead of a generic missing value")
check("catch (_: SecurityException)" in cpu_data_source and "CpuCounterRead.RestrictedByPlatform" in cpu_data_source, "CPU telemetry must safely handle kernel/framework access denial")
check("rapidSecondConsumerReusesFreshUsageInsteadOfFlickeringUnavailable" in cpu_tracker_test, "CPU tracker tests must cover overlapping telemetry consumers without unavailable-state flicker")
check("restrictedPlatformIsReportedWithoutFabricatingUsage" in cpu_tracker_test, "CPU tracker tests must prove restricted devices never receive a fabricated CPU percentage")
check("dashboard_cpu_restricted" in dashboard_route and "dashboard_cpu_status_with_frequency" in dashboard_route, "Dashboard CPU card must explain Android-restricted system usage while retaining available hardware facts")
check("dashboard_chart_cpu_restricted" in dashboard_route and "No percentage is estimated." in dashboard_strings, "CPU history must explain restricted aggregate counters and explicitly avoid estimation")
check("val displayedLatest = latest.takeIf { currentValueAvailable }" in dashboard_route and "val availabilityNote = when" in dashboard_route, "CPU history must not present a stale prior percentage as the current value after telemetry becomes restricted")
check("data.cpuUsagePercent?.let(::formatPercent) ?: stringResource(R.string.dashboard_unavailable)" not in dashboard_route, "CPU card must not collapse every unavailable state into the generic Unavailable label")

settings_route = text("feature/settings/src/main/java/com/apextuner/feature/settings/SettingsRoute.kt")
network_route_ui = text("feature/network/src/main/java/com/apextuner/feature/network/NetworkRoute.kt")
advanced_route = text("feature/tools/src/main/java/com/apextuner/feature/tools/advanced/AdvancedToolsRoute.kt")
smoke_test = text("app/src/androidTest/java/com/apextuner/app/ApexTunerSmokeTest.kt")
check("private fun DashboardLoading()" in dashboard_route and "LazyColumn(" in dashboard_route, "Dashboard loading placeholders must scroll instead of clipping on compact-height windows")
check(("Review removal" in cleaner_route or "ui_review_removal" in cleaner_route) and "verticalScroll(rememberScrollState())" in cleaner_route, "Cleaner dialogs must remain scrollable in landscape and large-font windows")
check("infoDialog" in settings_route and "verticalScroll(rememberScrollState())" in settings_route, "Settings legal/restore dialogs must remain scrollable in compact-height windows")
check("showFirewallDisclosure" in network_route_ui and "verticalScroll(rememberScrollState())" in network_route_ui, "VPN disclosure must remain fully reachable in compact-height windows")
check("phoneLandscapeKeepsTopLevelNavigationReachable" in smoke_test and "defaultMultiPhotoPickerContractCreatesAnIntent" in smoke_test, "Instrumentation suite must guard landscape navigation and Photo Picker launch regressions")

# Readability/accessibility styling invariants.
theme_source = text("app/src/main/java/com/apextuner/app/ui/theme/Theme.kt")
type_source = text("app/src/main/java/com/apextuner/app/ui/theme/Type.kt")
accessibility_source = text("core/src/main/java/com/apextuner/core/ui/ApexAccessibility.kt")
main_activity_source = text("app/src/main/java/com/apextuner/app/MainActivity.kt")
dashboard_route = text("feature/dashboard/src/main/java/com/apextuner/feature/dashboard/DashboardRoute.kt")
tools_route = text("feature/tools/src/main/java/com/apextuner/feature/tools/ToolsRoute.kt")

check("onSecondary = Color(0xFF18002A)" in theme_source, "Dark secondary foreground must retain readable contrast against ApexViolet")
check(re.search(r"bodySmall\s*=\s*TextStyle\([^)]*fontSize\s*=\s*13\.sp", type_source, re.S) is not None, "Small explanatory typography must remain at least 13sp")
check(re.search(r"labelMedium\s*=\s*TextStyle\([^)]*fontSize\s*=\s*13\.sp", type_source, re.S) is not None, "Medium labels must remain at least 13sp")
check(re.search(r"labelSmall\s*=\s*TextStyle\([^)]*fontSize\s*=\s*13\.sp", type_source, re.S) is not None, "Small labels must remain at least 13sp")
check(re.search(r"headlineMedium\s*=\s*TextStyle\([^)]*fontSize\s*=\s*24\.sp", type_source, re.S) is not None, "Recurring screen headings must remain balanced at 24sp")
check("APEX_MIN_TEXT_CONTRAST = 4.5f" in accessibility_source, "Shared visual accents must enforce the 4.5:1 text contrast floor")
check("ensureReadableColor(" in accessibility_source and "accessibleAccentPalette(" in accessibility_source, "Adaptive accent contrast helpers must remain present")
check("apexAccentPalette()" in dashboard_route and "Color(0x" not in dashboard_route, "Dashboard must use adaptive readable accents instead of fixed foreground colors")
check("apexAccentPalette()" in tools_route and "Color(0x" not in tools_route, "Tools must use adaptive readable accents instead of fixed foreground colors")
check("onSurfaceVariant.copy(alpha = 0.78f)" not in app_shell, "Navigation labels must not reduce semantic foreground contrast with extra alpha")
check(
    re.search(
        r"bottomNavigationLabelStyle\s*=\s*MaterialTheme\.typography\.labelSmall\.copy\([^)]*fontSize\s*=\s*13\.sp[^)]*lineHeight\s*=\s*16\.sp",
        app_shell,
        re.S,
    ) is not None,
    "Bottom navigation labels must preserve the app's 13sp readability floor with a 16sp line height",
)
check(
    "contentColor = MaterialTheme.colorScheme.onBackground" in main_activity_source,
    "App root must establish semantic onBackground content color for dark/light mode",
)
check(
    "containerColor = Color.Transparent,\n                contentColor = MaterialTheme.colorScheme.onBackground," in app_shell,
    "Transparent app Scaffold must preserve semantic onBackground content color",
)

transparent_surface_pattern = re.compile(
    r"MaterialTheme\.colorScheme\.background\.copy\(alpha\s*=\s*0f\)"
)
for kotlin_file in ROOT.rglob("*.kt"):
    source = kotlin_file.read_text(encoding="utf-8")
    for match in transparent_surface_pattern.finditer(source):
        call_window = source[max(0, match.start() - 240):min(len(source), match.end() + 240)]
        check(
            "Surface(" in call_window and
            "contentColor = MaterialTheme.colorScheme.onBackground" in call_window,
            f"Transparent root Surface must explicitly preserve semantic content color: {kotlin_file.relative_to(ROOT)}",
        )
check("ApexLayout.showAllBottomNavigationLabels(" in app_shell and "widthDp >= 400 && fontScale <= 1.15f" in adaptive_ui, "Bottom navigation labels must collapse before cramped widths or large font scaling")
check("label = if (showAllBottomLabels)" in app_shell and "else null" in app_shell, "Compact bottom navigation must be truly icon-only so selected labels cannot clip")
check("ApexLayout.shouldUseCompactNavigationRail(" in app_shell and "fontScale > 1.30f" in adaptive_ui, "Expanded navigation rails must collapse labels at large font scaling")
check("ApexLayout.shouldStackMetricRow(" in adaptive_ui and "widthDp < 340 || fontScale > 1.30f" in adaptive_ui, "Shared metric rows must stack at narrow widths or large font scaling")
widget_layout = text("feature/settings/src/main/res/layout/apex_status_widget.xml")
check('android:id="@+id/widget_storage"' in widget_layout and 'android:textSize="13sp"' in widget_layout, "Home-screen widget secondary text must retain the 13sp readability floor")

def _linear_channel(value: float) -> float:
    return value / 12.92 if value <= 0.04045 else ((value + 0.055) / 1.055) ** 2.4

def _relative_luminance(hex_rgb: str) -> float:
    rgb = [int(hex_rgb[index:index + 2], 16) / 255.0 for index in (0, 2, 4)]
    red, green, blue = (_linear_channel(channel) for channel in rgb)
    return 0.2126 * red + 0.7152 * green + 0.0722 * blue

def _contrast_ratio(first: str, second: str) -> float:
    first_luminance = _relative_luminance(first)
    second_luminance = _relative_luminance(second)
    lighter = max(first_luminance, second_luminance)
    darker = min(first_luminance, second_luminance)
    return (lighter + 0.05) / (darker + 0.05)

contrast_pairs = {
    "dark primary": ("13E7F4", "001F23"),
    "dark primary container": ("D8FBFF", "063248"),
    "dark secondary": ("A747FF", "18002A"),
    "dark secondary container": ("F3E3FF", "281341"),
    "dark tertiary": ("3EEE58", "002106"),
    "dark tertiary container": ("D8FFDA", "0B3515"),
    "dark background": ("F4F8FC", "010711"),
    "dark surface": ("F4F8FC", "040D18"),
    "dark surface container lowest": ("F4F8FC", "020A14"),
    "dark surface container low": ("F4F8FC", "06121F"),
    "dark surface container": ("F4F8FC", "081726"),
    "dark surface container high": ("F4F8FC", "0B1C2D"),
    "dark surface container highest": ("F4F8FC", "102437"),
    "dark surface variant": ("AFBBC8", "091827"),
    "dark error": ("FF737D", "490008"),
    "dark error container": ("FFD9DC", "3D121A"),
    "light primary": ("FFFFFF", "006A73"),
    "light primary container": ("001F23", "C9F7FC"),
    "light secondary": ("FFFFFF", "7640A0"),
    "light secondary container": ("2C0049", "F2DAFF"),
    "light tertiary": ("FFFFFF", "247A31"),
    "light tertiary container": ("002106", "BFF4C1"),
    "light background": ("0A1722", "F4F8FC"),
    "light surface": ("0A1722", "FBFDFF"),
    "light surface container lowest": ("0A1722", "FFFFFF"),
    "light surface container low": ("0A1722", "F4F8FC"),
    "light surface container": ("0A1722", "EEF3F7"),
    "light surface container high": ("0A1722", "E8EDF2"),
    "light surface container highest": ("0A1722", "E2E8EE"),
    "light surface variant": ("465A6A", "E5EEF5"),
    "light error": ("FFFFFF", "BA1A1A"),
    "light error container": ("410002", "FFDAD6"),
    "widget title": ("FFFFFF", "142B3A"),
    "widget status": ("E3F4FA", "142B3A"),
    "widget storage": ("B8CBD5", "142B3A"),
    "widget open button": ("E5FAFF", "17495D"),
    "widget refresh button": ("F1ECFF", "3B2F6F"),
}
for pair_name, (foreground, background) in contrast_pairs.items():
    check(
        _contrast_ratio(foreground, background) >= 4.5,
        f"{pair_name} foreground/background contrast must remain at least 4.5:1",
    )

# XML well-formedness.
xml_files = sorted(ROOT.rglob("*.xml"))
for xml in xml_files:
    try:
        ET.parse(xml)
    except ET.ParseError as exc:
        errors.append(f"Malformed XML {xml.relative_to(ROOT)}: {exc}")
    checks += 1

# Aggregate permissions from every main manifest, because library manifests merge into the app.
main_manifests = sorted(ROOT.glob("**/src/main/AndroidManifest.xml"))
all_permissions: set[str] = set()
for manifest_path in main_manifests:
    root = ET.parse(manifest_path).getroot()
    all_permissions.update(
        node.get(ANDROID_NS + "name") for node in root.findall("uses-permission") if node.get(ANDROID_NS + "name")
    )
for permission in FORBIDDEN_MANIFEST_PERMISSIONS:
    check(permission not in all_permissions, f"Forbidden permission declared in merged source manifests: {permission}")

# App manifest security invariants.
app_manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
app_permissions = {node.get(ANDROID_NS + "name") for node in app_manifest.findall("uses-permission")}
application = app_manifest.find("application")
check(application is not None, "Application manifest node missing")
if application is not None:
    check(application.get(ANDROID_NS + "allowBackup") == "false", "Android automatic backup must remain disabled")
    check(application.get(ANDROID_NS + "usesCleartextTraffic") == "false", "Cleartext traffic must remain disabled")
legacy_read = next((n for n in app_manifest.findall("uses-permission") if n.get(ANDROID_NS + "name") == "android.permission.READ_EXTERNAL_STORAGE"), None)
legacy_write = next((n for n in app_manifest.findall("uses-permission") if n.get(ANDROID_NS + "name") == "android.permission.WRITE_EXTERNAL_STORAGE"), None)
check(legacy_read is not None and legacy_read.get(ANDROID_NS + "maxSdkVersion") == "32", "READ_EXTERNAL_STORAGE must be capped at API 32")
check(legacy_write is not None and legacy_write.get(ANDROID_NS + "maxSdkVersion") == "29", "WRITE_EXTERNAL_STORAGE must be capped at API 29")

# App widget providers are launcher-facing receivers and must remain exported.
settings_manifest = ET.parse(ROOT / "feature/settings/src/main/AndroidManifest.xml").getroot()
settings_app = settings_manifest.find("application")
settings_receivers = settings_app.findall("receiver") if settings_app is not None else []
for widget_provider in (
    ".widget.ApexStatusWidgetProvider",
    ".widget.StorageDonutWidgetProvider",
    ".widget.BatteryHealthWidgetProvider",
):
    receiver = next((node for node in settings_receivers if node.get(ANDROID_NS + "name") == widget_provider), None)
    check(receiver is not None, f"Widget provider declaration missing: {widget_provider}")
    if receiver is not None:
        check(receiver.get(ANDROID_NS + "exported") == "true", f"Widget provider must be exported for launcher/widget-host delivery: {widget_provider}")
        actions = {action.get(ANDROID_NS + "name") for intent_filter in receiver.findall("intent-filter") for action in intent_filter.findall("action")}
        check("android.appwidget.action.APPWIDGET_UPDATE" in actions, f"Widget provider must handle APPWIDGET_UPDATE: {widget_provider}")

# Part 6 VPN manifest/security invariants.
network_manifest_path = ROOT / "feature/network/src/main/AndroidManifest.xml"
network_manifest = ET.parse(network_manifest_path).getroot()
network_permissions = {n.get(ANDROID_NS + "name") for n in network_manifest.findall("uses-permission")}
check("android.permission.FOREGROUND_SERVICE" in network_permissions, "VPN firewall must declare FOREGROUND_SERVICE")
check("android.permission.FOREGROUND_SERVICE_SPECIAL_USE" in network_permissions, "VPN firewall must declare FOREGROUND_SERVICE_SPECIAL_USE")
network_app = network_manifest.find("application")
services = network_app.findall("service") if network_app is not None else []
vpn = next((s for s in services if s.get(ANDROID_NS + "name") == ".firewall.ApexFirewallVpnService"), None)
check(vpn is not None, "ApexFirewallVpnService declaration missing")
if vpn is not None:
    check(vpn.get(ANDROID_NS + "exported") == "true", "VpnService must be exported so Android can discover/bind it")
    check(vpn.get(ANDROID_NS + "permission") == "android.permission.BIND_VPN_SERVICE", "VpnService must be protected by BIND_VPN_SERVICE")
    check(vpn.get(ANDROID_NS + "foregroundServiceType") == "specialUse", "VpnService foreground service type must be specialUse")
    actions = {a.get(ANDROID_NS + "name") for f in vpn.findall("intent-filter") for a in f.findall("action")}
    check("android.net.VpnService" in actions, "VpnService intent action missing")
    special = next((p for p in vpn.findall("property") if p.get(ANDROID_NS + "name") == "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"), None)
    check(special is not None and bool((special.get(ANDROID_NS + "value") or "").strip()), "specialUse FGS subtype explanation missing")
    always_on = next((m for m in vpn.findall("meta-data") if m.get(ANDROID_NS + "name") == "android.net.VpnService.SUPPORTS_ALWAYS_ON"), None)
    check(always_on is not None and always_on.get(ANDROID_NS + "value") == "false", "ApexTuner firewall must opt out of Always-on VPN")

# Part 6 Shizuku provider/AIDL security invariants.
tools_manifest = ET.parse(ROOT / "feature/tools/src/main/AndroidManifest.xml").getroot()
tools_permissions = {n.get(ANDROID_NS + "name") for n in tools_manifest.findall("uses-permission")}
check("android.permission.FOREGROUND_SERVICE" in tools_permissions, "Tools module must explicitly declare FOREGROUND_SERVICE")
check("android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" in tools_permissions, "Screen recorder must declare FOREGROUND_SERVICE_MEDIA_PROJECTION")
tools_app = tools_manifest.find("application")
providers = tools_app.findall("provider") if tools_app is not None else []
shizuku_provider = next((p for p in providers if p.get(ANDROID_NS + "name") == "rikka.shizuku.ShizukuProvider"), None)
check(shizuku_provider is not None, "ShizukuProvider declaration missing")
if shizuku_provider is not None:
    check(shizuku_provider.get(ANDROID_NS + "authorities") == "${applicationId}.shizuku", "ShizukuProvider authority must remain application-scoped")
    check(shizuku_provider.get(ANDROID_NS + "exported") == "true", "ShizukuProvider must be exported per Shizuku integration contract")
    check(shizuku_provider.get(ANDROID_NS + "multiprocess") == "false", "ShizukuProvider must keep multiprocess=false")
    check(shizuku_provider.get(ANDROID_NS + "permission") == "android.permission.INTERACT_ACROSS_USERS_FULL", "ShizukuProvider must retain its protective permission")
aidl = text("feature/tools/src/main/aidl/com/apextuner/feature/tools/advanced/IPrivilegedUserService.aidl")
check("void destroy() = 16777114;" in aidl, "Shizuku UserService reserved destroy transaction is missing")

# Part 6 implementation invariants.
shizuku_gateway = text("feature/tools/src/main/java/com/apextuner/feature/tools/advanced/ShizukuGateway.kt")
root_gateway = text("feature/tools/src/main/java/com/apextuner/feature/tools/advanced/RootGateway.kt")
user_service = text("feature/tools/src/main/java/com/apextuner/feature/tools/advanced/ApexPrivilegedUserService.kt")
process_runner = text("feature/tools/src/main/java/com/apextuner/feature/tools/advanced/BoundedProcessRunner.kt")
firewall_service = text("feature/network/src/main/java/com/apextuner/feature/network/firewall/ApexFirewallVpnService.kt")
network_vm = text("feature/network/src/main/java/com/apextuner/feature/network/NetworkViewModel.kt")
network_route = text("feature/network/src/main/java/com/apextuner/feature/network/NetworkRoute.kt")
network_repository = text("feature/network/src/main/java/com/apextuner/feature/network/NetworkRepository.kt")
app_manager_repository = text("feature/appmanager/src/main/java/com/apextuner/feature/appmanager/AppManagerRepository.kt")

for token in ['.tag(', '.version(', '.daemon(false)']:
    check(token in shizuku_gateway, f"Shizuku UserService configuration missing {token}")
check("newProcess" not in shizuku_gateway, "Deprecated Shizuku.newProcess must not be used")
check("BoundedProcessRunner" in root_gateway and "runInterruptible" in root_gateway, "Root operations must use interruptible bounded process execution")
check("BoundedProcessRunner" in user_service, "Shizuku UserService must share the bounded process runner")
check("Runtime.getRuntime().exec" not in "\n".join([root_gateway, user_service, process_runner]), "Privileged layer must not use unbounded Runtime.exec")
check("CloseableResourceSlot<ParcelFileDescriptor>" in firewall_service, "VPN tunnel must use guarded descriptor handoff")
check("const val TUN_MTU = 1_500" in firewall_service, "VPN TUN MTU must remain conservative 1500")
check("ACTION_START, null" not in firewall_service, "Null service intents must never implicitly start the firewall")
check("ACTION_START -> {" in firewall_service and "promoteToForeground(0)" in firewall_service, "Firewall must promote synchronously before DataStore/I/O work")
check("sanitizeFirewallPackages" in firewall_service and "setBlockedPackages(accepted)" in firewall_service, "Firewall must sanitize and persistently prune stale package selections")
check("Channel<FirewallSelectionRequest>" in network_vm, "Firewall selection writes must use an ordered queue")
check("FirewallRuntimeState.Active" in network_vm and "FirewallRuntimeState.Starting" in network_vm, "Firewall app list must be locked during active/starting runtime")
check("VpnService.prepare" in network_route, "Firewall UI must obtain Android VPN consent")
network_disclosure_surface = network_route + text("feature/network/src/main/res/values/strings.xml")
for disclosure_term in ["does not inspect", "Unselected apps bypass", "selected apps will not have network access"]:
    check(disclosure_term in network_disclosure_surface, f"Firewall disclosure missing required explanation: {disclosure_term}")
check("@InstallIn(SingletonComponent::class)" in network_repository and "FirewallPreferencesModule" in network_repository, "Firewall preferences must be Singleton-scoped for UI/service consistency")
check("LauncherApps" in app_manager_repository, "App Manager must use LauncherApps for scoped launchable inventory")
check("launcherApps.getActivityList" in app_manager_repository, "App Manager inventory must be sourced through LauncherApps.getActivityList")

# Final audit lifecycle/resource/parser regression invariants.
recording_service = text("feature/tools/src/main/java/com/apextuner/feature/tools/recording/ScreenRecordingService.kt")
projection_pos = recording_service.find("projectionManager.getMediaProjection(resultCode, resultData)")
handler_pos = recording_service.find('HandlerThread("ApexProjectionCallback")')
check("import android.util.DisplayMetrics" in recording_service, "Screen recorder must import DisplayMetrics used by the legacy display fallback")
check(recording_service.count("addTrack(encoder.outputFormat)") == 1, "Screen recorder must register exactly one encoded video track before starting MediaMuxer")
check(projection_pos >= 0 and handler_pos > projection_pos, "MediaProjection token must be validated before the callback HandlerThread starts")

game_controller = text("feature/tools/src/main/java/com/apextuner/feature/tools/game/GameSessionController.kt")
game_models = text("feature/tools/src/main/java/com/apextuner/feature/tools/game/GameSessionModels.kt")
game_store = text("feature/tools/src/main/java/com/apextuner/feature/tools/game/GameSessionStore.kt")
check("withContext(NonCancellable)" in game_controller, "Game Session rollback must survive coroutine cancellation")
check("val startSnapshot = optionalSnapshotCancellable()" in game_controller and "val snapshot = optionalSnapshotCancellable() ?: return" in game_controller, "Game Session start and monitoring snapshots must remain cancellation-cooperative")
check("catch (cancelled: CancellationException)" in game_controller and "throw cancelled" in game_controller, "Game Session optional telemetry must rethrow coroutine cancellation")
check("profileChangedByApexTuner" in game_controller and "profileChangedByApexTuner" in game_models, "Game Session must track whether ApexTuner actually changed the profile")
check('booleanPreferencesKey("profile_changed")' in game_store, "Game Session profile ownership must survive process death")
check("profileChangedByApexTuner = !profileReleased" in game_controller, "Partial Game Session recovery progress must be persisted")
check("stopAfterError()" in firewall_service and "terminate(preserveError = true)" in firewall_service, "Firewall startup/runtime errors must remain visible after service teardown")

resource_slot = text("feature/network/src/main/java/com/apextuner/feature/network/firewall/CloseableResourceSlot.kt")
resource_slot_test = text("feature/network/src/test/java/com/apextuner/feature/network/firewall/CloseableResourceSlotTest.kt")
check("else if (current === expected)" in resource_slot and "Do not double-close a stale descriptor" in resource_slot, "VPN descriptor slot must ignore stale close requests")
check("assertEquals(0, second.closes.get())" in resource_slot_test, "VPN descriptor regression test must prove a stale close cannot touch the replacement")

telemetry_parsers = text("core/src/main/java/com/apextuner/core/system/TelemetryParsers.kt")
check('fileName == "gpubusy"' in telemetry_parsers and "values.size < 2 || values[1] <= 0.0" in telemetry_parsers, "GPU busy parser must reject malformed/zero-denominator samples")
telemetry_source = text("core/src/main/java/com/apextuner/core/system/AndroidDeviceTelemetryDataSource.kt")
check("STORAGE_CACHE_TTL_MILLIS = 10_000L" in telemetry_source and "Serialize cache misses too" in telemetry_source, "Storage telemetry cache must throttle and serialize duplicate system I/O")
billing_repository = text("feature/billing/src/main/java/com/apextuner/feature/billing/data/GooglePlayEntitlementRepository.kt")
billing_catalog = text("feature/billing/src/main/java/com/apextuner/feature/billing/data/BillingCatalog.kt")
billing_policy = text("feature/billing/src/main/java/com/apextuner/feature/billing/data/BillingCatalogPolicy.kt")
billing_purchase_policy = text("feature/billing/src/main/java/com/apextuner/feature/billing/data/BillingPurchasePolicy.kt")
billing_mappers = text("feature/billing/src/main/java/com/apextuner/feature/billing/data/BillingMappers.kt")
billing_evaluator = text("feature/billing/src/main/java/com/apextuner/feature/billing/data/BillingEntitlementEvaluator.kt")
billing_route = text("feature/billing/src/main/java/com/apextuner/feature/billing/BillingRoute.kt")
billing_view_model = text("feature/billing/src/main/java/com/apextuner/feature/billing/BillingViewModel.kt")
activity_resolver = text("feature/billing/src/main/java/com/apextuner/feature/billing/ActivityResolver.kt")
billing_test = text("feature/billing/src/test/java/com/apextuner/feature/billing/data/BillingEntitlementEvaluatorTest.kt")
billing_catalog_test = text("feature/billing/src/test/java/com/apextuner/feature/billing/data/BillingCatalogPolicyTest.kt")
billing_purchase_test = text("feature/billing/src/test/java/com/apextuner/feature/billing/data/BillingPurchasePolicyTest.kt")
main_activity = text("app/src/main/java/com/apextuner/app/MainActivity.kt")
billing_build = text("feature/billing/build.gradle.kts")
check(("@param:IoDispatcher private val ioDispatcher: CoroutineDispatcher" in billing_repository or "@IoDispatcher private val ioDispatcher: CoroutineDispatcher" in billing_repository) and "withContext(ioDispatcher)" in billing_repository, "Billing entitlement cache I/O must remain off latency-sensitive dispatcher paths")
check(versions.get("billing") == "9.1.0", "Google Play Billing Library must remain on audited 9.1.0")
check("implementation(libs.play.billing)" in billing_build and "billing.ktx" not in billing_build, "Billing module must use the single required Play Billing dependency without redundant KTX")
check('const val PREMIUM_LIFETIME_PRODUCT_ID = "apextuner_premium_lifetime"' in billing_catalog, "Lifetime product ID must remain centralized and exact")
billing_main_text = "\n".join(
    path.read_text(encoding="utf-8")
    for path in sorted((ROOT / "feature/billing/src/main").rglob("*"))
    if path.is_file() and path.suffix in {".kt", ".xml"}
)
entitlement_models = text("core/src/main/java/com/apextuner/core/model/EntitlementModels.kt")
entitlement_cache = text("core/src/main/java/com/apextuner/core/billing/EncryptedEntitlementCache.kt")
advanced_tools_repository = text("feature/tools/src/main/java/com/apextuner/feature/tools/advanced/AdvancedToolsRepository.kt")
check("BillingClient.ProductType.INAPP" in billing_catalog and "BillingClient.ProductType.SUBS" not in billing_catalog, "Billing catalog must define only the one-time INAPP product")
check("apextuner_pro" not in billing_main_text and "ProductType.SUBS" not in billing_main_text and "subscription" not in billing_main_text.lower(), "Billing runtime/UI must not contain a subscription product or subscription flow")
check("ProSubscription" not in entitlement_models and "hasSuspendedSubscription" not in entitlement_models and "isPro" not in entitlement_models, "Entitlement model must expose only Free and lifetime Premium states")
check("SUBSCRIPTION_OFFLINE_GRACE" not in entitlement_cache, "Encrypted entitlement cache must not retain subscription-specific grace logic")
billing_main_sources = {
    str(path.relative_to(ROOT)): path.read_text(encoding="utf-8")
    for path in sorted((ROOT / "feature/billing/src/main").rglob("*.kt"))
}
set_product_list_calls = sum(source.count(".setProductList(") for source in billing_main_sources.values())
check(set_product_list_calls == 1, "All Billing ProductDetails requests must go through the single catalog query helper")
check("require(productTypes.size == 1)" in billing_repository and ".setProductType(spec.productType)" in billing_repository, "Catalog query helper must enforce one product type per ProductDetails request")
check("val lifetimeSpec = BillingCatalog.lifetimeProduct" in billing_repository and "queryCatalogProducts(listOf(lifetimeSpec))" in billing_repository, "Catalog refresh must query only the lifetime product")
check("queryResult.unfetchedProductList.map" in billing_repository and "statusCode = it.statusCode" in billing_repository and "productType = it.productType" in billing_repository, "Catalog query outcome must preserve every unfetched product ID, type and status")
check("PLAY_CALLBACK_TIMEOUT_MILLIS" in billing_repository and "withTimeoutOrNull(PLAY_CALLBACK_TIMEOUT_MILLIS)" in billing_repository, "Play asynchronous callbacks must be bounded by timeouts")
check("queryCatalogProducts(listOf(selectedSpec))" in billing_repository and "refreshCatalog()" not in billing_repository[billing_repository.find("override suspend fun launchPurchase"):billing_repository.find("private suspend fun queryCatalogProducts")], "Checkout must refresh only the selected lifetime product, never the whole catalog")
check("MAX_PRODUCT_DETAILS_AGE_MILLIS" not in billing_repository, "Checkout must not rely on time-cached ProductDetails")
check("BillingCatalog.recognizes(it.public.productId)" in billing_repository, "Returned catalog offers must be accepted only for the lifetime product")
check("withContext(Dispatchers.Main.immediate)" in billing_repository and "launchBillingFlow" in billing_repository, "Play purchase UI must be launched on the main thread")
check(".setOfferToken(currentOfferToken)" in billing_repository and "selectFreshCheckoutOffer" in billing_repository, "Checkout must use the freshly queried eligible Google Play offer token")
check("oneTimePurchaseOfferDetailsList.orEmpty()" in billing_mappers and "listOfNotNull(oneTimePurchaseOfferDetails)" in billing_mappers, "One-time product mapping must support current purchase-option lists with a backwards-compatible fallback")
check("purchaseOptionId = offer.purchaseOptionId" in billing_mappers and "offerId = offer.offerId" in billing_mappers and "offerToken = offer.offerToken" in billing_mappers, "One-time offer identity and token data must be preserved")
check(".enablePendingPurchases(" in billing_repository and ".enableOneTimeProducts()" in billing_repository and ".enablePrepaidPlans()" not in billing_repository, "Pending purchase support must be enabled only for one-time products")
check(".setProductType(BillingClient.ProductType.INAPP)" in billing_repository and "Purchase.PurchaseState.PENDING" in billing_repository, "Purchase restoration must query only one-time purchases and keep pending purchases locked")
check("shouldAcknowledgePurchase(" in billing_repository and "acknowledgePurchase" in billing_repository and "Purchase.PurchaseState.PURCHASED" in billing_purchase_policy, "Only recognized completed unacknowledged purchases may be acknowledged")
check("ACK_MAX_ATTEMPTS = 3" in billing_repository and "isTransientAcknowledgementFailure" in billing_repository and "delay(ACK_RETRY_DELAYS_MILLIS[attempt])" in billing_repository, "Purchase acknowledgement must use bounded retries only for transient Play failures")
check("BillingCatalog::recognizes" in billing_evaluator and "Subscription" not in billing_evaluator, "Entitlement evaluation must recognize only the lifetime product")
check("wrongPackageAndUnknownProductsNeverUnlock" in billing_test, "Billing regression suite must reject wrong package and unknown IDs")
check("canceledOrDeclinedWithoutPurchasedStateNeverUnlocks" in billing_test and "pendingOneTimePurchaseStaysLocked" in billing_test and "recognizedPurchasedLifetimeUnlocksPremium" in billing_test, "Billing regression suite must cover canceled, declined, pending and completed lifetime purchases")
check("formattedPrice = offer.formattedPrice" in billing_mappers, "Visible price must come unchanged from localized Google Play ProductDetails")
check('"See Google Play"' not in billing_mappers, "Billing UI must not invent a fallback price when Play returns no price")
check("offering.formattedPrice" in billing_route and "billing_buy_lifetime" in billing_route, "Premium UI must render the Play-provided lifetime price and one-time purchase action")
check(re.search(r"[€£$]\s*\d", billing_route + billing_repository + billing_mappers + billing_policy) is None, "Billing implementation must not hardcode user-facing currency prices")
check("viewModelScope.launch { repository.refreshCatalog() }" in billing_view_model and "viewModelScope.launch { repository.refresh(\"billing_screen_open\") }" in billing_view_model, "Premium screen must start catalog loading independently from entitlement restoration")
check("checkoutOfferingKey" in billing_view_model and "purchaseErrors" in billing_view_model and "billing_starting_google_play" in billing_route, "Premium UI must expose checkout progress and offer-specific launch errors")
check("productErrors" in billing_route and "UnavailableOfferingCard" in billing_route and "ui_retry" in billing_route, "Premium UI must show inline unavailable-product errors with Retry")
check("is ContextWrapper ->" in activity_resolver and "current.baseContext" in activity_resolver and "context.findActivity()" in billing_route, "Billing Activity resolution must unwrap ContextWrapper instances")
check("activityResolutionUnwrapsContextWrappers" in smoke_test, "Instrumentation suite must verify wrapped-context Activity resolution")
check("doubleTapCannotEnterConcurrentPurchaseLaunches" in billing_purchase_test and "PurchaseLaunchGate" in billing_repository, "Billing tests must prevent concurrent purchase launches")
check("lifetimeSuccessKeepsLocalizedOfferAvailable" in billing_catalog_test and "missingLifetimePreservesUnfetchedStatus" in billing_catalog_test, "Billing tests must cover lifetime catalog success and unavailable-product state")
check("checkoutSelectionUsesCurrentTokenForLifetimeBuyOption" in billing_catalog_test and "staleOrUnavailableOfferCannotBeSelectedForCheckout" in billing_catalog_test, "Billing tests must cover fresh offer tokens and stale offer rejection")
check("purchaseUpdateResponsesRemainSafe" in billing_purchase_test and "onlyRecognizedCompletedUnacknowledgedPurchasesAreAcknowledged" in billing_purchase_test, "Billing tests must cover safe response handling and acknowledgement gating")
check("entitlementRepository.entitlement.value.isPremium" in advanced_tools_repository and "Pro subscription" not in advanced_tools_repository, "Advanced tools must be unlocked by the lifetime Premium entitlement")
check("override fun onResume()" in main_activity and "appViewModel.refreshEntitlement()" in main_activity, "App resume must re-query purchase entitlement for completed pending/out-of-app changes")
app_view_model = text("app/src/main/java/com/apextuner/app/AppViewModel.kt")
check('refreshEntitlement("app_start")' not in app_view_model, "Cold start must not duplicate the immediate onResume Billing refresh")
check("Purchase complete. ApexTuner Premium is now active." in billing_repository, "Successful entitlement grant must be communicated to the user")
check('private const val PREMIUM_ROUTE = "premium"' in app_shell and "composable(PREMIUM_ROUTE) { BillingRoute" in app_shell, "Premium navigation route must remain wired to BillingRoute")
check("fun restorePurchases()" in billing_view_model and 'repository.refresh("restore")' in billing_view_model and "repository.refreshCatalog()" in billing_view_model, "Restore action must continue reconciling purchases and refreshing offers")
check("cache.saveVerified(state)" in billing_repository and "cache.loadOfflineGrace" in billing_repository, "Verified entitlement persistence and bounded offline grace must remain intact")
check(
    "premiumEnabled = entitlement.isPremium" in app_shell
    and 'if (entitlement.isPremium) navController.navigate("tools/advanced")' in app_shell
    and 'composable("tools/advanced")' in app_shell
    and "if (entitlement.isPremium) {" in app_shell
    and "showAdvancedTools = showAdvancedTools" in app_shell
    and "else openPremium()" in app_shell,
    "Premium feature access must remain entitlement-gated while Advanced Tools visibility remains a discoverability preference",
)
check("consumeAsync" not in billing_repository and "ConsumeParams" not in billing_repository, "Lifetime purchase must remain non-consumable")

# UI/UX, adaptive-layout, accessibility and visual-identity regression invariants.
app_shell = text("app/src/main/java/com/apextuner/app/ui/shell/ApexTunerApp.kt")
adaptive_ui = text("core/src/main/java/com/apextuner/core/ui/AdaptiveUi.kt")
tools_route = text("feature/tools/src/main/java/com/apextuner/feature/tools/ToolsRoute.kt")
cleaner_route = text("feature/cleaner/src/main/java/com/apextuner/feature/cleaner/OptimizeRoute.kt")
settings_route = text("feature/settings/src/main/java/com/apextuner/feature/settings/SettingsRoute.kt")
app_manager_route = text("feature/appmanager/src/main/java/com/apextuner/feature/appmanager/AppManagerRoute.kt")
monitor_service = text("feature/settings/src/main/java/com/apextuner/feature/settings/monitor/ApexMonitorService.kt")
widget_layout = text("feature/settings/src/main/res/layout/apex_status_widget.xml")
widget_info = text("feature/settings/src/main/res/xml/apex_status_widget_info.xml")
launcher_foreground = text("app/src/main/res/drawable/ic_launcher_foreground.xml")
typography = text("app/src/main/java/com/apextuner/app/ui/theme/Type.kt")
app_manifest_text = text("app/src/main/AndroidManifest.xml")
check("NavigationRail" in app_shell and "ApexLayout.navigationPresentationFor" in app_shell, "Top-level navigation must adapt from bottom bar to height-aware rail presentations")
check("widthIn(max = ApexLayout.MaxContentWidth)" in app_shell and "alwaysShowLabel = showAllBottomLabels" in app_shell, "App shell must constrain wide content and preserve compact navigation legibility")
file_manager_route = text("feature/files/src/main/java/com/apextuner/feature/files/FileManagerRoute.kt")
check(
    file_manager_route.count("FlowRow(") >= 2
    and "@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)" in file_manager_route,
    "File Manager action groups must wrap on narrow or large-font layouts",
)
file_manager_vm = text("feature/files/src/main/java/com/apextuner/feature/files/FileManagerViewModel.kt")
check(
    "private var navigationJob: Job? = null" in file_manager_vm
    and "operationJob?.isActive == true || navigationJob?.isActive == true" in file_manager_vm
    and "navigationJob?.cancel()" in file_manager_vm,
    "File Manager navigation must serialize folder loads and never race active file mutations",
)
cleaner_models_final = text("feature/cleaner/src/main/java/com/apextuner/feature/cleaner/model/CleanerModels.kt")
privileged_models = text("feature/tools/src/main/java/com/apextuner/feature/tools/advanced/PrivilegedModels.kt")
check("data class DeleteSelection" not in cleaner_models_final, "Unused Cleaner DeleteSelection model must stay removed")
check("PrivilegedOperationRisk" not in privileged_models, "Unused privileged-operation risk enum must stay removed")
check(not (ROOT / "core/src/main/java/com/apextuner/core/ui/FeatureLanding.kt").exists(), "Unused legacy FeatureLanding component must stay removed")
check(not (ROOT / "feature/billing/src/main/java/com/apextuner/feature/billing/data/BillingPeriodFormatter.kt").exists(), "Unused subscription-era billing period formatter must stay removed")
check("horizontalPaddingForSize" in adaptive_ui and "shouldStackMetricRow" in adaptive_ui and "Modifier.weight" in adaptive_ui, "Shared adaptive metric/padding primitives must remain responsive")
check("LazyVerticalGrid" in tools_route and "GridCells.Adaptive(minSize = 280.dp)" in tools_route, "Tools landing page must use an adaptive grid")
check("FlowRow" in settings_route and "FlowRow" in app_manager_route, "Chip controls must wrap instead of clipping on narrow/large-font layouts")
check("toggleable(" in cleaner_route and "Role.Checkbox" in cleaner_route and "selectable(" in cleaner_route and "Role.RadioButton" in cleaner_route, "Cleaner selectable rows must expose single coherent accessibility actions")
check(widget_layout.count('android:layout_height="48dp"') >= 2 and widget_layout.count('android:minHeight="48dp"') >= 2, "Home widget action targets must remain at least 48dp high")
check('android:minResizeWidth="250dp"' in widget_info and 'android:minResizeHeight="150dp"' in widget_info, "Widget resize bounds must protect readable/tappable layout")
check("FLAG_LAYOUT_NO_LIMITS" not in monitor_service and "clampOverlayToDisplay" in monitor_service and "override fun onConfigurationChanged" in monitor_service and "lp.x.coerceIn(0, maxX)" in monitor_service and "lp.y.coerceIn(0, maxY)" in monitor_service, "Floating monitor must stay within the visible display while dragging and after configuration/window-size changes")
check(".retryWhen" in monitor_service and "MonitorRetryPolicy.delayMillis(attempt)" in monitor_service and "baseline = null" in monitor_service, "Floating monitor telemetry must recover from transient sampling failures with bounded retry/backoff")
check("#54E2FF" in launcher_foreground and "#A98BFF" in launcher_foreground and "#6EF1B2" in launcher_foreground, "3D Apex launcher identity must retain its branded layered accents")
font_sizes = [int(value) for value in re.findall(r"fontSize\s*=\s*(\d+)\.sp", typography)]
check(bool(font_sizes) and min(font_sizes) >= 13, "Compose typography must not define text smaller than 13sp")
check("android:screenOrientation" not in app_manifest_text, "App must remain resizable/orientation-flexible rather than forcing a phone-only orientation")
check("android.permission.READ_SYNC_SETTINGS" not in app_manifest_text and "android.permission.WRITE_SYNC_SETTINGS" in app_manifest_text and "Migration-only" in app_manifest_text, "Current release must keep only the migration-only sync write permission needed to repair 1.0.10 state")

# Branding / implementation markers / namespace-path sanity.
source_files = [p for p in ROOT.rglob("*") if p.is_file() and p.suffix in {".kt", ".kts", ".xml", ".md", ".toml", ".pro", ".aidl"}]
for path in source_files:
    content = path.read_text(encoding="utf-8", errors="replace")
    rel = path.relative_to(ROOT)
    for brand in LEGACY_BRANDS:
        check(brand not in content, f"Legacy brand {brand!r} remains in {rel}")
    normalized = str(path).replace('\\', '/')
    if path.suffix == ".kt" and "/src/main/" in normalized:
        for marker in IMPLEMENTATION_MARKERS:
            check(marker not in content, f"Implementation marker {marker} remains in {rel}")
        pkg = re.search(r"^package\s+([\w.]+)", content, re.MULTILINE)
        if pkg and "/java/" in normalized:
            package_path = pkg.group(1).replace('.', '/')
            check(package_path in str(path.parent).replace('\\', '/'), f"Package/path mismatch in {rel}: {pkg.group(1)}")

# Module-local R.string references (AGP non-transitive R).
for module in EXPECTED_MODULES:
    module_root = ROOT / module
    string_names: set[str] = set()
    for values_file in module_root.glob("src/main/res/values*/strings.xml"):
        root = ET.parse(values_file).getroot()
        string_names.update(node.get("name") for node in root.findall("string") if node.get("name"))
    references: set[str] = set()
    for source in module_root.glob("src/main/java/**/*.kt"):
        references.update(re.findall(r"\bR\.string\.([A-Za-z0-9_]+)", source.read_text(encoding="utf-8")))
    missing = sorted(references - string_names)
    check(not missing, f"{module}: unresolved module-local R.string references: {missing}")

# Part-5 lifecycle/transaction regression invariants.
for rel in [
    "feature/battery/src/main/java/com/apextuner/feature/battery/BatteryViewModel.kt",
    "feature/memory/src/main/java/com/apextuner/feature/memory/MemoryViewModel.kt",
    "feature/tools/src/main/java/com/apextuner/feature/tools/performance/PerformanceViewModel.kt",
]:
    content = text(rel)
    check("SharingStarted.WhileSubscribed" in content, f"{rel}: telemetry must stop when unsubscribed")
    check("init {" not in content, f"{rel}: must not start unconditional polling from init")
controller = text("core/src/main/java/com/apextuner/core/tuning/SafeSystemTuningController.kt")
prefs = text("core/src/main/java/com/apextuner/core/datastore/PreferencesRepository.kt")
check("mutationPending = true" in controller and "recoverInterruptedMutationLocked" in controller, "Profile transaction journal/recovery missing")
check("AtomicLong" in controller and "latestMutationRequest" in controller and "ProfileApplyResult.Superseded" in controller, "Profile mutations must have deterministic latest-request ordering")
check("system_profile_mutation_pending" in prefs, "Persisted profile transaction marker missing")
check("targets.masterSyncEnabled" not in controller and "getMasterSyncAutomatically" not in controller, "New system profiles must never read/mutate Android global master sync")
check("legacyOriginalMasterSyncEnabled" in controller and "reconcileLegacyState" in controller, "Legacy 1.0.10 master-sync recovery path missing")
check(".coerceIn(MIN_TIMEOUT_MILLIS, MAX_TIMEOUT_MILLIS)" not in controller, "System-profile rollback must preserve the exact Android screen-timeout baseline instead of clamping it")
check("suspend fun profileStatus()" in controller and "SystemProfilePlanner.matches(" in controller, "Battery/profile UI must reconcile persisted profile state against live managed Android settings")
planner = text("core/src/main/java/com/apextuner/core/tuning/SystemProfilePlanner.kt")
check("fun matches(" in planner and "safePositiveTimeout" in planner, "Profile planner must expose pure live-state matching and keep applied targets safe without altering rollback snapshots")
battery_repository = text("feature/battery/src/main/java/com/apextuner/feature/battery/BatteryRepository.kt")
battery_route = text("feature/battery/src/main/java/com/apextuner/feature/battery/BatteryRoute.kt")
battery_vm = text("feature/battery/src/main/java/com/apextuner/feature/battery/BatteryViewModel.kt")
battery_strings = text("feature/battery/src/main/res/values/strings.xml")
check("profileStatus = tuningController.profileStatus()" in battery_repository and "profileMatchesSystem" in battery_repository, "Battery insights must use reconciled live profile status")
check("battery_profile_status_changed_externally" in battery_route and "Active: ${data.activeProfile.name}" not in battery_route, "Battery UI must not claim a stale profile is active after external system-setting changes")
check("privilegedChangesUnavailable.joinToString" in battery_vm, "Battery profile results must disclose stock-Android actions ApexTuner could not perform")
check("does not enable Android Battery Saver" in battery_strings and "exact original managed values" in battery_strings, "Battery profile copy must accurately describe scope and exact rollback behavior")
automation_workers = text("feature/settings/src/main/java/com/apextuner/feature/settings/automation/AutomationWorkers.kt")
check('refresh("scheduled_maintenance")' in automation_workers and 'refresh("night_battery_profile")' in automation_workers, "Premium scheduled workers must refresh Play entitlement before gating")
automation_scheduler = text("feature/settings/src/main/java/com/apextuner/feature/settings/automation/AutomationScheduler.kt")
automation_timing = text("feature/settings/src/main/java/com/apextuner/feature/settings/automation/AutomationTiming.kt")
check("setNextScheduleTimeOverride" in automation_scheduler and "updateWork" in automation_scheduler and "setInitialDelay" not in automation_scheduler, "Periodic automation must use wall-clock schedule overrides instead of drift-prone fixed initial delays")
check("builder::setId" in automation_scheduler and "realignNightBattery" in automation_scheduler and "realignMorningRestore" in automation_scheduler and "realignMaintenance" in automation_scheduler, "Successful periodic workers must preserve their WorkManager id while re-anchoring the next local-time run")
check("nextLocalEpochMillisAfterDays" in automation_timing and ".plusDays(daysAfter.toLong())" in automation_timing, "Automation timing must use local calendar-day realignment across DST changes")
check("if (!prefs.scheduledMaintenanceEnabled) return Result.success()" in automation_workers, "Disabled scheduled maintenance must short-circuit before Play Billing and telemetry work")
maintenance_worker = automation_workers[automation_workers.find("class MaintenanceWorker"):automation_workers.find("class NightBatteryProfileWorker")]
check("runCatching { deviceRepository.snapshot() }" not in maintenance_worker and "catch (cancelled: CancellationException)" in maintenance_worker and "throw cancelled" in maintenance_worker, "Scheduled maintenance device sampling must propagate WorkManager cancellation instead of converting it to retry")
night_pref_pos = automation_workers.find("if (!prefs.nightBatteryProfileEnabled) return Result.success()")
night_applied_pos = automation_workers.find("if (prefs.nightBatteryProfileAppliedByAutomation) return successAligned()")
night_refresh_pos = automation_workers.find('refresh("night_battery_profile")')
check(night_pref_pos >= 0 and night_applied_pos > night_pref_pos and night_refresh_pos > night_applied_pos, "Disabled/already-applied night automation must short-circuit before opening Play Billing")
morning_start = automation_workers.find("class MorningProfileRestoreWorker")
morning_end = automation_workers.find("private object AutomationNotifications")
morning_worker = automation_workers[morning_start:morning_end]
check("EntitlementRepository" not in morning_worker and "entitlementRepository" not in morning_worker, "Morning restore must remain entitlement-independent so rollback is never stranded behind Premium")
all_kotlin = "\n".join(p.read_text(encoding="utf-8", errors="ignore") for p in ROOT.rglob("*.kt"))
check("killBackgroundProcesses" not in all_kotlin, "ApexTuner must not implement fake RAM boosting via killBackgroundProcesses")

# Cross-device service-start hardening.
fgs_launcher = text("core/src/main/java/com/apextuner/core/system/ForegroundServiceLauncher.kt")
check("catch (_: SecurityException)" in fgs_launcher, "Foreground-service launcher must recover from Android security-policy rejection")
check("catch (_: IllegalStateException)" in fgs_launcher, "Foreground-service launcher must recover from Android background-start restrictions")
check("catch (_: RuntimeException)" in fgs_launcher and "catch (_: Throwable)" not in fgs_launcher, "Foreground-service launcher must recover runtime launch failures without swallowing serious VM errors")
fgs_call_sites = []
for kotlin_source in ROOT.rglob("*.kt"):
    if "/build/" in kotlin_source.as_posix():
        continue
    source_text = kotlin_source.read_text(encoding="utf-8", errors="ignore")
    if "ContextCompat.startForegroundService(" in source_text:
        fgs_call_sites.append(kotlin_source.relative_to(ROOT).as_posix())
check(
    fgs_call_sites == ["core/src/main/java/com/apextuner/core/system/ForegroundServiceLauncher.kt"],
    f"All foreground-service starts must pass through the recoverable launcher; direct calls remain: {fgs_call_sites}",
)
settings_vm = text("feature/settings/src/main/java/com/apextuner/feature/settings/SettingsViewModel.kt")
settings_route = text("feature/settings/src/main/java/com/apextuner/feature/settings/SettingsRoute.kt")
monitor_tile = text("feature/settings/src/main/java/com/apextuner/feature/settings/tile/ApexMonitorTileService.kt")
firewall_tile = text("feature/network/src/main/java/com/apextuner/feature/network/tile/ApexFirewallTileService.kt")
network_route = text("feature/network/src/main/java/com/apextuner/feature/network/NetworkRoute.kt")
game_route = text("feature/tools/src/main/java/com/apextuner/feature/tools/game/GameBoosterRoute.kt")
check("MonitorStartOutcome.BlockedByAndroid" in settings_vm and "MonitorRuntimeState.Error" in settings_vm, "Monitor start rejection must become a visible recoverable runtime state")
check("MonitorStartOutcome.OverlayPermissionRequired" in settings_route and "MonitorStartOutcome.BlockedByAndroid -> Unit" in settings_route, "Monitor launch failure must not be misreported as missing overlay permission")
check("ForegroundServiceLauncher.start" in monitor_tile and "MonitorRuntimeState.Error" in monitor_tile, "Monitor Quick Settings start must fail recoverably")
check("ForegroundServiceLauncher.start" in firewall_tile and "FirewallRuntimeState.Error" in firewall_tile, "Firewall Quick Settings start must fail recoverably")
check("ForegroundServiceLauncher.start" in network_route and "FirewallRuntimeState.Error" in network_route, "Firewall in-app start must fail recoverably")
check("ForegroundServiceLauncher.start" in game_route and "ScreenRecordingState.Failed" in game_route, "Screen recording service-start rejection must be visible in the game-tool UI")

# Coroutine cancellation must remain cooperative.
backup_manager = text("core/src/main/java/com/apextuner/core/backup/BackupRestoreManager.kt")
game_worker = text("feature/tools/src/main/java/com/apextuner/feature/tools/game/GameSessionWorker.kt")
backup_catch = backup_manager[backup_manager.find("suspend fun read(uri: Uri)"):backup_manager.find("suspend fun apply(uri: Uri)")]
check("catch (cancelled: CancellationException)" in backup_catch and "throw cancelled" in backup_catch, "Backup parsing must propagate coroutine cancellation")
check("catch (cancelled: CancellationException)" in game_worker and "throw cancelled" in game_worker, "Game-session timeout work must propagate WorkManager cancellation before generic retry handling")

# SAF root selection must distinguish multiple grants from the same provider.
file_manager = text("feature/files/src/main/java/com/apextuner/feature/files/FileManagerViewModel.kt")
saf_identity_policy = text("feature/files/src/main/java/com/apextuner/feature/files/SafTreeIdentityPolicy.kt")
check("DocumentsContract.getTreeDocumentId(uri)" in file_manager and "treeIdentity(grantedUri)" in file_manager, "File manager must identify SAF roots by exact tree-document id")
check("val authority = uri.authority" in file_manager and "first.authority == second.authority" in saf_identity_policy, "File manager SAF root identity must include provider authority")
check("first.documentId == second.documentId" in saf_identity_policy, "File manager SAF root identity must compare the exact document id")
check("roots.lastOrNull()" not in file_manager, "File manager must not fall back to an unrelated persisted SAF root")
check("locationUri.contains(Uri.parse(grantedUri)" not in file_manager, "Authority-only SAF root matching must not return")

# Phase-5 localization guard: visible Compose/tile literals belong in resources.
for ui_source in list(ROOT.rglob("*Route.kt")) + list(ROOT.rglob("*TileService.kt")):
    source_text = ui_source.read_text(encoding="utf-8", errors="ignore")
    check(re.search(r'\bText\(\s*"', source_text) is None, f"Hardcoded Text literal remains in {ui_source.relative_to(ROOT)}")
    if ui_source.name.endswith("TileService.kt"):
        check(re.search(r'\blabel\s*=\s*"', source_text) is None, f"Hardcoded tile label remains in {ui_source.relative_to(ROOT)}")
        check(re.search(r'\bcontentDescription\s*=\s*"', source_text) is None, f"Hardcoded tile content description remains in {ui_source.relative_to(ROOT)}")

for ui_source in ROOT.rglob("*.kt"):
    source_text = ui_source.read_text(encoding="utf-8", errors="ignore")
    visible_literal_patterns = (
        r'setTextViewText\([^,\n]+,\s*"',
        r'\.setContentTitle\(\s*"',
        r'\.setContentText\(\s*"',
        r'\.addAction\([^,\n]+,\s*"',
        r'NotificationChannel\([^,\n]+,\s*"',
    )
    check(
        not any(re.search(pattern, source_text) for pattern in visible_literal_patterns),
        f"Hardcoded widget/notification literal remains in {ui_source.relative_to(ROOT)}",
    )

# Source-package hygiene.
expected_wrapper_jar_sha = "497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7"
for binary in ROOT.rglob("*.jar"):
    check(binary.name == "gradle-wrapper.jar", f"Unexpected binary JAR in source project: {binary.relative_to(ROOT)}")
    if binary.name == "gradle-wrapper.jar":
        wrapper_jar_sha = hashlib.sha256(binary.read_bytes()).hexdigest()
        check(
            wrapper_jar_sha == expected_wrapper_jar_sha,
            f"Gradle wrapper JAR checksum mismatch: {binary.relative_to(ROOT)}",
        )
for generated in ["build", ".gradle", ".idea", ".git", "__pycache__"]:
    check(not any(p.name == generated for p in ROOT.rglob(generated)), f"Generated/developer directory should not be shipped: {generated}")
compiled_artifacts = [
    p.relative_to(ROOT).as_posix()
    for pattern in ("*.pyc", "*.pyo", "*.class", "*.apk", "*.aab")
    for p in ROOT.rglob(pattern)
]
check(not compiled_artifacts, f"Compiled/generated artifacts should not be shipped in source package: {compiled_artifacts[:8]}")
wrapper = text("gradle/wrapper/gradle-wrapper.properties")
expected_gradle_sha = "553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746"
check("gradle-9.5.0-bin.zip" in wrapper, "Gradle wrapper metadata must target Gradle 9.5.0")
check(f"distributionSha256Sum={expected_gradle_sha}" in wrapper, "Gradle 9.5.0 distribution checksum must be pinned")
check((ROOT / "gradlew").is_file(), "Verified POSIX Gradle bootstrap is missing")
check((ROOT / "gradlew.bat").is_file(), "Verified Windows Gradle bootstrap is missing")
if (ROOT / "gradlew").is_file():
    bootstrap = text("gradlew")
    check("GRADLE_VERSION=\"9.5.0\"" in bootstrap and expected_gradle_sha in bootstrap, "POSIX Gradle bootstrap must pin version 9.5.0 and its official checksum")
    check("verify_sha256" in bootstrap and "exec \"$GRADLE_BIN\" \"$@\"" in bootstrap, "POSIX Gradle bootstrap must verify before delegating arguments")
if (ROOT / "gradlew.bat").is_file():
    bootstrap_bat = text("gradlew.bat")
    check("GRADLE_VERSION=9.5.0" in bootstrap_bat and expected_gradle_sha in bootstrap_bat, "Windows Gradle bootstrap must pin version 9.5.0 and its official checksum")
    check("Get-FileHash -Algorithm SHA256" in bootstrap_bat, "Windows Gradle bootstrap must SHA-256 verify the distribution")

# Final holistic UX/profile-coordination invariants (versionCode 41).
cleaner_route_final = text("feature/cleaner/src/main/java/com/apextuner/feature/cleaner/OptimizeRoute.kt")
check(
    "LaunchedEffect(autoStartScanToken, cleanerReady)" in cleaner_route_final
    and "autoStartScanToken != null && cleanerReady" in cleaner_route_final,
    "Cold-start Quick Scan must wait for CleanerUiState.Ready before starting and consuming the one-shot request",
)
launch_contract = text("core/src/main/java/com/apextuner/core/navigation/AppLaunchContract.kt")
launch_contract_test = text("core/src/test/java/com/apextuner/core/navigation/AppLaunchContractTest.kt")
main_activity_final = text("app/src/main/java/com/apextuner/app/MainActivity.kt")
app_shell_final = text("app/src/main/java/com/apextuner/app/ui/shell/ApexTunerApp.kt")
cleaner_tile_final = text("feature/cleaner/src/main/java/com/apextuner/feature/cleaner/tile/CleanerQuickScanTileService.kt")
firewall_tile_final = text("feature/network/src/main/java/com/apextuner/feature/network/tile/ApexFirewallTileService.kt")
monitor_tile_final = text("feature/settings/src/main/java/com/apextuner/feature/settings/tile/ApexMonitorTileService.kt")
for destination in ("DESTINATION_OPTIMIZE", "DESTINATION_NETWORK", "DESTINATION_SETTINGS"):
    check(destination in launch_contract, f"Shared launch contract must retain {destination}")
check(
    "sanitizeDestination" in launch_contract
    and "supportedDestinations::contains" in launch_contract
    and "AppLaunchContract.sanitizeDestination" in main_activity_final,
    "External one-shot launch destinations must remain centrally allow-listed before navigation",
)
check(
    "val launchIntent = intent ?: return null" in main_activity_final
    and "launchIntent.getStringExtra(AppLaunchContract.EXTRA_DESTINATION)" in main_activity_final
    and "launchIntent.getBooleanExtra(AppLaunchContract.EXTRA_QUICK_SCAN, false)" in main_activity_final
    and "launchIntent.getLongExtra(AppLaunchContract.EXTRA_REQUEST_TOKEN, 0L)" in main_activity_final,
    "Nullable activity launch intents must be bound once before extras are read",
)
check(
    "destination == AppLaunchContract.DESTINATION_OPTIMIZE" in main_activity_final,
    "Quick Scan must remain restricted to the Optimize destination",
)
check(
    "navController.navigate(request.destination)" in app_shell_final
    and "onLaunchRequestConsumed()" in app_shell_final,
    "Allow-listed tile launch requests must navigate once and then be consumed",
)
check(
    "AppLaunchContract.DESTINATION_OPTIMIZE" in cleaner_tile_final
    and "AppLaunchContract.DESTINATION_NETWORK" in firewall_tile_final
    and "AppLaunchContract.DESTINATION_SETTINGS" in monitor_tile_final,
    "Every Quick Settings tile must open the screen that resolves its action or prerequisite",
)
check(
    "sanitizeDestination_acceptsEverySupportedDestination" in launch_contract_test
    and "sanitizeDestination_rejectsMissingOrUntrustedRoutes" in launch_contract_test,
    "Shared launch destination allow-list must retain positive and negative unit coverage",
)
launch_protocol_literals = sum(
    source.read_text(encoding="utf-8").count("com.apextuner.extra.DESTINATION")
    for source in ROOT.rglob("*.kt")
)
check(launch_protocol_literals == 1, "Launch-protocol destination key must have one shared Kotlin declaration")
profile_coordinator = text("core/src/main/java/com/apextuner/core/tuning/TemporaryProfileOverrideCoordinator.kt")
game_controller_final = text("feature/tools/src/main/java/com/apextuner/feature/tools/game/GameSessionController.kt")
game_route_final = text("feature/tools/src/main/java/com/apextuner/feature/tools/game/GameBoosterRoute.kt")
network_route_final = text("feature/network/src/main/java/com/apextuner/feature/network/NetworkRoute.kt")
security_route_final = text("feature/tools/src/main/java/com/apextuner/feature/tools/security/SecurityRoute.kt")
dashboard_vm_final = text("feature/dashboard/src/main/java/com/apextuner/feature/dashboard/DashboardViewModel.kt")
monitor_service_final = text("feature/settings/src/main/java/com/apextuner/feature/settings/monitor/ApexMonitorService.kt")
battery_vm_final = text("feature/battery/src/main/java/com/apextuner/feature/battery/BatteryViewModel.kt")
memory_vm_final = text("feature/memory/src/main/java/com/apextuner/feature/memory/MemoryViewModel.kt")
performance_vm_final = text("feature/tools/src/main/java/com/apextuner/feature/tools/performance/PerformanceViewModel.kt")
check("OWNER_GAME_SESSION" in profile_coordinator and "KEY_EXPIRES_AT" in profile_coordinator and "activeOwnerFlow" in profile_coordinator, "Temporary profile overrides must stay bounded, persist an expiry, and expose release state")
app_vm_final = text("app/src/main/java/com/apextuner/app/AppViewModel.kt")
check("temporaryProfileOverride.activeOwnerFlow" in app_vm_final and "temporaryOwner == null" in app_vm_final, "Foreground automation must re-evaluate immediately when a temporary profile owner releases control")
check(game_controller_final.count("temporaryProfileOverride.end(TemporaryProfileOverrideCoordinator.OWNER_GAME_SESSION)") >= 6, "Game Booster must release its temporary profile lease on every terminal/failure path")
for outcome in ("ProfileApplyResult.PermissionRequired", "ProfileApplyResult.Failed", "ProfileApplyResult.Superseded"):
    outcome_pos = game_controller_final.find(outcome, game_controller_final.find("tuning.apply(SystemProfile.Gaming)"))
    return_pos = game_controller_final.find("return GameSessionResult.Failed", outcome_pos)
    release_pos = game_controller_final.find("temporaryProfileOverride.end(TemporaryProfileOverrideCoordinator.OWNER_GAME_SESSION)", outcome_pos)
    check(outcome_pos >= 0 and release_pos >= 0 and return_pos >= 0 and release_pos < return_pos, f"Game Booster must release temporary ownership before early {outcome} failure")
check("Lifecycle.Event.ON_RESUME" in game_route_final and "specialAccessRefreshToken" in game_route_final, "Game Booster must refresh Modify Settings/DND access after Android Settings")
check("Lifecycle.Event.ON_RESUME" in network_route_final and "viewModel.refresh()" in network_route_final, "Network tools must refresh Usage Access state after Android Settings")
check("Lifecycle.Event.ON_RESUME" in security_route_final and "viewModel.refresh()" in security_route_final, "Security posture must refresh after Android Settings")
check("MIN_DASHBOARD_REFRESH_MILLIS = 3_000L" in dashboard_vm_final and "telemetryRefreshMillis" in dashboard_vm_final, "Dashboard must honor the telemetry preference without exceeding its audited 3-second polling floor")
check("telemetryRefreshMillis" in monitor_service_final, "Foreground refresh preference must drive the real-time overlay")
check("REFRESH_MILLIS = 5_000L" in battery_vm_final, "Battery diagnostics must retain the conservative 5-second cadence")
check("REFRESH_MILLIS = 10_000L" in memory_vm_final, "Memory diagnostics must retain the conservative 10-second cadence")
check("REFRESH_MILLIS = 5_000L" in performance_vm_final, "CPU/Performance diagnostics must retain the conservative 5-second cadence")

if errors:
    print(f"ApexTuner validation: FAIL ({len(errors)} failures across {checks} checks)")
    for error in errors:
        print(f" - {error}")
    sys.exit(1)
print(f"ApexTuner validation: PASS ({checks} checks, {len(xml_files)} XML files, {len(main_manifests)} main manifests)")
