#!/usr/bin/env python3
"""ApexTuner deterministic pre-Gradle release gate.

This intentionally complements, rather than pretends to replace, an Android Gradle build.
It catches project-graph, toolchain, manifest/resource, Room, branding and source hygiene
regressions before Android Studio/CI is invoked.
"""
from __future__ import annotations

import hashlib
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FAILURES: list[str] = []
CHECKS = 0


def check(condition: bool, message: str) -> None:
    global CHECKS
    CHECKS += 1
    if not condition:
        FAILURES.append(message)


def text(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


# Run the deep project-specific validator first.
validator = subprocess.run(
    [sys.executable, str(ROOT / "tools" / "validate_project.py")],
    cwd=ROOT,
    text=True,
    capture_output=True,
)
print(validator.stdout, end="")
if validator.stderr:
    print(validator.stderr, end="", file=sys.stderr)
check(validator.returncode == 0, "tools/validate_project.py must pass")

settings = text("settings.gradle.kts")
app_build = text("app/build.gradle.kts")
catalog = text("gradle/libs.versions.toml")
wrapper = text("gradle/wrapper/gradle-wrapper.properties")

# Toolchain compatibility pins. AGP 9.3 requires Gradle 9.5.0; source bytecode remains JDK 17.
check('agp = "9.3.2"' in catalog, "AGP must remain pinned to audited 9.3.2")
check('kotlin = "2.3.21"' in catalog, "Kotlin must remain pinned to audited 2.3.21")
check('composeBom = "2026.06.00"' in catalog, "Compose BOM must remain on the audited 2026.06.00 stable line compatible with compileSdk 36")
check('composeBom = "2024.12.01"' not in catalog, "Obsolete 2024 Compose BOM must not be reintroduced")
check("gradle-9.5.0-bin.zip" in wrapper, "Gradle wrapper metadata must remain pinned to 9.5.0")
check('distributionSha256Sum=553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746' in wrapper,
      "Gradle 9.5.0 distribution SHA-256 must remain pinned")
check("compileSdk = 36" in app_build and "targetSdk = 36" in app_build and "minSdk = 26" in app_build,
      "App SDK levels must remain compile/target 36 and min 26")
check("JavaVersion.VERSION_17" in app_build, "App Java bytecode target must remain 17")
check(re.search(r"versionCode\s*=\s*41\b", app_build) is not None, "Final candidate versionCode must be 41")
check(re.search(r'versionName\s*=\s*"1\.1\.4"', app_build) is not None, "Version name must remain 1.1.4")
release_identity = text("docs/RELEASE_IDENTITY_1.1.4.md")
check("`versionCode`: `41`" in release_identity and "`versionName`: `1.1.4`" in release_identity, "Release identity note must match the final 1.1.4 / versionCode 41 candidate")

# CI must exercise the exact audited wrapper and the hardened release gate, not a separately
# installed Gradle binary or the lighter structural validator alone.
ci = text(".github/workflows/android-ci.yml")
check("python3 tools/release_gate.py" in ci, "Android CI must run the hardened release gate")
check("./gradlew --no-daemon --stacktrace" in ci, "Android CI must use the project-pinned Gradle wrapper")
check("testDebugUnitTest" in ci and "testReleaseUnitTest" in ci, "Android CI must execute debug and release JVM unit tests")
check("lintDebug" in ci and "lintRelease" in ci, "Android CI must lint both debug and release variants")
check("assembleDebug" in ci and "assembleRelease" in ci and "bundleRelease" in ci, "Android CI must compile debug APK, release APK, and release AAB paths")
check(":app:connectedDebugAndroidTest" in ci, "Android CI must retain instrumentation smoke coverage")

# Every included Android module must exist and keep the audited SDK/JDK baseline.
modules = re.findall(r'include\("(:[^\"]+)"\)', settings)
check(bool(modules), "settings.gradle.kts must include Android modules")
module_paths: dict[str, Path] = {":app": ROOT / "app", ":core": ROOT / "core"}
for module in modules:
    parts = module.strip(":").split(":")
    module_paths[module] = ROOT.joinpath(*parts)
for module, path in sorted(module_paths.items()):
    build = path / "build.gradle.kts"
    check(build.is_file(), f"{module} must contain build.gradle.kts")
    if not build.is_file():
        continue
    build_text = build.read_text(encoding="utf-8")
    check("compileSdk = 36" in build_text, f"{module} must compile against SDK 36")
    check("minSdk = 26" in build_text, f"{module} must retain minSdk 26")
    check("JavaVersion.VERSION_17" in build_text, f"{module} must retain Java 17 source/target compatibility")

# Project dependency boundaries: a feature cannot import another feature unless declared.
def declared_project_deps(build_text: str) -> set[str]:
    return set(re.findall(r'(?:api|implementation)\(project\("(:[^\"]+)"\)\)', build_text))

for module, path in sorted(module_paths.items()):
    if not path.exists():
        continue
    build_text = (path / "build.gradle.kts").read_text(encoding="utf-8")
    deps = declared_project_deps(build_text)
    for source in path.glob("src/main/**/*.kt"):
        for line in source.read_text(encoding="utf-8").splitlines():
            if not line.startswith("import com.apextuner.feature."):
                continue
            feature = line.split(".")[3]
            target = f":feature:{feature}"
            if target == module:
                continue
            check(target in deps, f"{module} imports {target} in {source.relative_to(ROOT)} without a project dependency")
        # Feature modules must never depend upward on the app module.
        if module != ":app":
            check("import com.apextuner.app." not in source.read_text(encoding="utf-8"),
                  f"{module} must not import app-layer code ({source.relative_to(ROOT)})")

# Parse all production manifests/resources now, before aapt2 gets involved.
xml_files = [p for p in ROOT.glob("**/src/main/**/*.xml") if "build" not in p.parts]
for xml in xml_files:
    try:
        ET.parse(xml)
        check(True, f"XML parses: {xml.relative_to(ROOT)}")
    except ET.ParseError as exc:
        check(False, f"Malformed XML {xml.relative_to(ROOT)}: {exc}")

# AAPT string parsing is stricter than generic XML parsing: raw apostrophes in Android string
# text can fail resource compilation even when ElementTree accepts the document. Keep the
# project's established escaped-apostrophe convention deterministic across new UI strings.
for strings_file in ROOT.glob("**/src/main/res/values*/strings.xml"):
    body = strings_file.read_text(encoding="utf-8")
    raw_apostrophes = []
    for match in re.finditer(r"<string\b[^>]*>(.*?)</string>", body, re.S):
        value = match.group(1)
        if re.search(r"(?<!\\)'", value):
            raw_apostrophes.append(value.strip()[:80])
    check(
        not raw_apostrophes,
        f"Android string resources must escape apostrophes in {strings_file.relative_to(ROOT)}: {raw_apostrophes[:3]}",
    )

# Room v4 schema/migration gate.
db = text("core/src/main/java/com/apextuner/core/database/ApexTunerDatabase.kt")
migrations = text("core/src/main/java/com/apextuner/core/database/DatabaseMigrations.kt")
check(re.search(r"version\s*=\s*4\b", db) is not None, "Room database version must remain 4")
check("Migration3To4" in migrations and "Migration(3, 4)" in migrations, "Room migration 3 -> 4 must remain present")
check('arg("room.schemaLocation", "$projectDir/schemas")' in text("core/build.gradle.kts"), "Room schema export location must remain configured for Android Studio/CI builds")

# Production source hygiene and supplied branding integrity.
production_markers: list[str] = []
for source in ROOT.glob("**/src/main/**/*.kt"):
    body = source.read_text(encoding="utf-8")
    if re.search(r"\b(?:TODO|FIXME|NotImplementedError)\b", body):
        production_markers.append(str(source.relative_to(ROOT)))
check(not production_markers, "Production TODO/FIXME/NotImplemented markers: " + ", ".join(production_markers))
logo = ROOT / "core" / "src" / "main" / "res" / "drawable-nodpi" / "apextuner_logo.png"
EXPECTED_LOGO_SHA256 = "aa8115edcb3c7b7a37cde18f3a6dbed2547cfdf6b6ec6c23628d978000f5bfc3"
check(logo.is_file(), "Canonical in-app ApexTuner logo must exist")
if logo.is_file():
    check(sha256(logo) == EXPECTED_LOGO_SHA256, "Canonical ApexTuner logo no longer matches the supplied artwork")

# Integrated intelligence feature invariants. These guard the exact cross-module wiring added
# for Apex Intelligence without requiring network access or an Android SDK to catch regressions.
intelligence_engine = text("feature/dashboard/src/main/java/com/apextuner/feature/dashboard/intelligence/ApexIntelligenceEngine.kt")
intelligence_route = text("feature/dashboard/src/main/java/com/apextuner/feature/dashboard/intelligence/IntelligenceRoute.kt")
cleaner_route = text("feature/cleaner/src/main/java/com/apextuner/feature/cleaner/OptimizeRoute.kt")
cleaner_vm = text("feature/cleaner/src/main/java/com/apextuner/feature/cleaner/CleanerViewModel.kt")
charging_tracker = text("feature/battery/src/main/java/com/apextuner/feature/battery/ChargingSessionTracker.kt")
charging_boundary = text("feature/battery/src/main/java/com/apextuner/feature/battery/ChargingSessionBoundary.kt")
app_models = text("feature/appmanager/src/main/java/com/apextuner/feature/appmanager/AppManagerModels.kt")
network_models = text("feature/network/src/main/java/com/apextuner/feature/network/diagnostics/NetworkDiagnosticsModels.kt")
network_repo = text("feature/network/src/main/java/com/apextuner/feature/network/diagnostics/NetworkDiagnosticsRepository.kt")
automation = text("feature/settings/src/main/java/com/apextuner/feature/settings/automation/SmartAutomation.kt")
automation_worker = text("feature/settings/src/main/java/com/apextuner/feature/settings/automation/SmartAutomationWorker.kt")
cleaner_models = text("feature/cleaner/src/main/java/com/apextuner/feature/cleaner/model/CleanerModels.kt")
app_shell = text("app/src/main/java/com/apextuner/app/ui/shell/ApexTunerApp.kt")
lifecycle_recovery = text("app/src/main/java/com/apextuner/app/AppLifecycleRecovery.kt")
file_manager_route = text("feature/files/src/main/java/com/apextuner/feature/files/FileManagerRoute.kt")
check(
    "runBestEffortCancellable" in lifecycle_recovery
    and "catch (cancelled: CancellationException)" in lifecycle_recovery
    and "throw cancelled" in lifecycle_recovery
    and "runCatching { tuningController.reconcileLegacyState() }" not in lifecycle_recovery,
    "Lifecycle recovery must not swallow WorkManager cancellation",
)
check("label = if (showAllBottomLabels)" in app_shell and "else null" in app_shell, "Compact bottom navigation must remain icon-only")
check(file_manager_route.count("FlowRow(") >= 2, "File Manager action groups must wrap instead of overflowing")
file_manager_vm = text("feature/files/src/main/java/com/apextuner/feature/files/FileManagerViewModel.kt")
check("private var navigationJob: Job? = null" in file_manager_vm and "operationJob?.isActive == true || navigationJob?.isActive == true" in file_manager_vm and "navigationJob?.cancel()" in file_manager_vm, "File Manager navigation must not race active mutations or concurrent folder loads")
check(not (ROOT / "core/src/main/java/com/apextuner/core/ui/FeatureLanding.kt").exists(), "Unused legacy FeatureLanding component must stay removed")
check(not (ROOT / "feature/billing/src/main/java/com/apextuner/feature/billing/data/BillingPeriodFormatter.kt").exists(), "Unused subscription-era billing period formatter must stay removed")
check('composable("dashboard/intelligence")' in app_shell and "IntelligenceRoute(" in app_shell, "Apex Intelligence route must remain reachable from the root navigation graph")
check("Observed around this action:" in intelligence_engine and "not proof of a cause" in intelligence_engine, "Action impact must remain explicitly observational rather than causal")
check("if (latest.internalStorageTotalBytes <= 0L) return null" in intelligence_engine, "Apex Intelligence must not fabricate a critical storage score from unavailable telemetry")
check("@OptIn(ExperimentalLayoutApi::class)\n@Composable\nprivate fun SmartReviewSummaryCard" in cleaner_route, "Cleaner Smart Review FlowRow must keep its required Compose experimental opt-in")
storage_treemap = cleaner_route.split("private fun StorageTreemap(", 1)[1].split("@Composable\nprivate fun InsightCard", 1)[0]
check(".height(190.dp)" not in storage_treemap and "Column(" not in storage_treemap, "Storage Analyzer must not reintroduce the fixed-height vertical weighted treemap that allowed child overflow")
check("Text(" not in storage_treemap and ".height(32.dp)" in storage_treemap and ".filter { it.bytes > 0L }" in storage_treemap, "Storage Analyzer distribution strip must remain text-free and bounded; readable values belong to adaptive metric rows below")
check("AppInsightFilter.Unused30Days -> usageAccessGranted" in app_models, "App Intelligence must not infer inactivity when Usage Access is unavailable")
check("MAX_TRANSFER_BYTES = DOWNLOAD_BYTES + UPLOAD_BYTES" in network_models and "DOWNLOAD_BYTES = 4L * 1024L * 1024L" in network_models and "UPLOAD_BYTES = 1L * 1024L * 1024L" in network_models, "Throughput test payload must remain bounded to 4 MiB down + 1 MiB up")
check("https://speed.cloudflare.com/__down?bytes=$safeTarget" in network_repo and "https://speed.cloudflare.com/__up" in network_repo, "Bounded throughput endpoints must remain explicit HTTPS destinations")
check("CaptureDiagnosticSnapshot" in automation and "deviceHealthSampleDao.insert(snapshot.toHealthSampleEntity())" in automation, "Smart Automation diagnostic capture must remain local and persist the matched snapshot")
check("ChargingBatteryLevelAtOrAbove" in automation and "listOf(80.0, 85.0, 90.0, 95.0)" in automation and "does not stop charging" in automation, "Charge-level automation must remain opt-in reminder-only behavior with bounded thresholds")
check("diagnosticSnapshotCaptured" in automation_worker and "reused the diagnostic snapshot already captured during this evaluation" in automation_worker, "One Smart Automation evaluation must not persist duplicate diagnostic snapshots")
check("filter(Double::isFinite)" in intelligence_engine and "takeIf(Double::isFinite)" in intelligence_engine, "Apex Intelligence must reject non-finite telemetry before scoring/correlation")
check("saturatingByteSum" in cleaner_models and "Long.MAX_VALUE - total < value" in cleaner_models, "Cleaner Smart Review byte totals must saturate instead of overflowing")
# Holistic UX and lifecycle invariants. These are deliberately source-level so future UI
# refactors cannot silently reintroduce stale special-access state, nested-navigation ambiguity,
# or cancellation-as-error regressions that are difficult to observe in a static screenshot.
intelligence_vm = text("feature/dashboard/src/main/java/com/apextuner/feature/dashboard/intelligence/IntelligenceViewModel.kt")
settings_route = text("feature/settings/src/main/java/com/apextuner/feature/settings/SettingsRoute.kt")
settings_vm = text("feature/settings/src/main/java/com/apextuner/feature/settings/SettingsViewModel.kt")
performance_route = text("feature/tools/src/main/java/com/apextuner/feature/tools/performance/PerformanceRoute.kt")
game_vm = text("feature/tools/src/main/java/com/apextuner/feature/tools/game/GameBoosterViewModel.kt")
check("analysisJob?.cancel()" in intelligence_vm and "catch (cancelled: CancellationException)" in intelligence_vm and "throw cancelled" in intelligence_vm, "Intelligence refresh must cancel superseded analysis without converting cancellation into an error")
check('private const val SETTINGS_AUTOMATION_ROUTE = "settings/automation"' in app_shell and "initialSection = SettingsSection.SmartAutomation" in app_shell, "Intelligence automation actions must deep-link to Smart Automation rather than generic Settings")
check('TopLevelDestination.Dashboard -> currentRoute.startsWith("dashboard/")' in app_shell and 'TopLevelDestination.Settings -> currentRoute.startsWith("settings/")' in app_shell, "Nested Dashboard/Settings routes must preserve top-level navigation selection")
check("specialAccessRefreshToken" in settings_route and "Lifecycle.Event.ON_RESUME" in settings_route and "Settings.System.canWrite(context)" in settings_route, "Settings must refresh special-access state after returning from Android Settings")
check("usageAccessLauncher" in cleaner_route and "ActivityResultContracts.StartActivityForResult()" in cleaner_route and 'viewModel.refreshAccess("Usage access updated.")' in cleaner_route, "Cleaner must refresh Usage Access immediately after returning from Android Settings")
check("settingsActionMessage" in settings_vm and "catch (cancelled: CancellationException)" in settings_vm, "Settings mutations must surface persistence failures while preserving coroutine cancellation")
check("writeSettingsRefreshToken" in performance_route and "Settings.System.canWrite(context)" in performance_route and "ui_modify_system_settings_granted" in performance_route, "Performance must refresh and clearly reflect Modify system settings access")
check("catch (cancelled: CancellationException)" in game_vm and "throw cancelled" in game_vm, "Game Booster history refresh must preserve coroutine cancellation semantics")
check("runCatchingCancellable" in cleaner_vm and "catch (cancellation: CancellationException)" in cleaner_vm and "throw cancellation" in cleaner_vm, "Cleaner best-effort suspend operations must never swallow coroutine cancellation")
check("withTerminalBatterySample(battery)" in charging_tracker and "active.withSample(battery).copy(endedAtEpochMillis = now)" not in charging_tracker, "Charging-session termination must not count the first unplugged observation as charging telemetry")
check("endLevelPercent = battery.levelPercent" in charging_boundary and "temperature" not in charging_boundary.lower() and "current" in charging_boundary.lower(), "Charging-session terminal boundary may update end counters but must not mutate charging thermal/current statistics")

# Lovable follow-up reliability invariants: transactional file rollback, resilient contacts undo,
# durable Play acknowledgement retry, permission-loss visibility, and explicit unsupported battery telemetry.
files_repo = text("feature/files/src/main/java/com/apextuner/feature/files/SafFileRepository.kt")
contacts_vm = text("feature/contacts/src/main/java/com/apextuner/feature/contacts/ContactMergeViewModel.kt")
contacts_history = text("feature/contacts/src/main/java/com/apextuner/feature/contacts/ContactUndoHistory.kt")
contacts_repo = text("feature/contacts/src/main/java/com/apextuner/feature/contacts/ContactRepository.kt")
billing_repo = text("feature/billing/src/main/java/com/apextuner/feature/billing/data/GooglePlayEntitlementRepository.kt")
billing_retry = text("feature/billing/src/main/java/com/apextuner/feature/billing/data/PurchaseAcknowledgementRetryWorker.kt")
billing_build = text("feature/billing/build.gradle.kts")
data_cap_prefs = text("feature/network/src/main/java/com/apextuner/feature/network/DataUsageCapPreferences.kt")
data_cap_worker = text("feature/network/src/main/java/com/apextuner/feature/network/DataUsageAlertWorker.kt")
data_cap_scheduler = text("feature/network/src/main/java/com/apextuner/feature/network/DataUsageAlertScheduler.kt")
network_route = text("feature/network/src/main/java/com/apextuner/feature/network/NetworkRoute.kt")
battery_worker = text("feature/battery/src/main/java/com/apextuner/feature/battery/BatteryHealthSnapshotWorker.kt")
battery_trend = text("feature/battery/src/main/java/com/apextuner/feature/battery/BatteryHealthTrend.kt")
battery_route = text("feature/battery/src/main/java/com/apextuner/feature/battery/BatteryRoute.kt")
check("createdDocuments" in files_repo and "rollbackCreatedDocuments(createdDocuments)" in files_repo,
      "ZIP extraction must transactionally roll back all documents created by a failed extraction")
check("val rolledBack = deleteDocumentForRollback(copied)" in files_repo and "Both items may remain" in files_repo,
      "Move rollback must not claim destination removal unless the provider confirms deletion")
check("ContactUndoHistory" in contacts_vm and "discardFailedUndo" in contacts_vm and "undoBlockedByFailure" in contacts_vm and
      "discardFailedTop" in contacts_history and "topFailed" in contacts_history,
      "A failed top contact undo must remain retryable without permanently blocking older undo records")
check("batch.forEach { rule ->" in contacts_repo and "could not be restored" in contacts_repo,
      "Contact rule restoration must fall back to individual rules when a provider batch fails")
check("PurchaseAcknowledgementRetryScheduler" in billing_repo and "acknowledgementRetryScheduler.schedule()" in billing_repo,
      "Unacknowledged purchases must schedule durable background reconciliation before client acknowledgement")
check("Result.retry()" in billing_retry and "NetworkType.CONNECTED" in billing_retry and "ExistingWorkPolicy.KEEP" in billing_retry,
      "Billing acknowledgement retry must use persistent, network-constrained unique WorkManager work")
check("implementation(libs.androidx.work.runtime.ktx)" in billing_build and "implementation(libs.androidx.hilt.work)" in billing_build,
      "Billing module must declare WorkManager/Hilt Worker dependencies required by acknowledgement retry")
check("usageAccessWarningSent" in data_cap_prefs and "notifyUsageAccessRequired" in data_cap_worker,
      "Revoked Usage Access with active caps must generate bounded user-visible alert state")
check("data_usage_alerts_paused_usage_access" in network_route,
      "Network UI must explicitly state that monthly alerts are paused when Usage Access is revoked")
check("NetworkType.CONNECTED" not in data_cap_scheduler and "setRequiresBatteryNotLow(true)" in data_cap_scheduler,
      "Local data-cap/Usage-Access checks must not be delayed by an unnecessary network-connectivity constraint")
check("battery.cycleCount == null && capacity == null) return Result.success()" not in battery_worker,
      "Battery health worker must record collection attempts even when OEM health telemetry is unavailable")
check("TelemetryUnavailable" in battery_trend and "battery_health_telemetry_unavailable" in battery_route,
      "Battery health UI must distinguish unsupported OEM telemetry from missing collection history")
check("val capacitySnapshots = snapshots.filter" in intelligence_engine and "capacitySnapshots.size >= 7" in intelligence_engine,
      "Apex Intelligence must count only real capacity estimates when evaluating battery capacity history")

for required_test in [
    "feature/dashboard/src/test/java/com/apextuner/feature/dashboard/intelligence/ApexIntelligenceEngineTest.kt",
    "feature/battery/src/test/java/com/apextuner/feature/battery/ChargingHistoryAnalyzerTest.kt",
    "feature/battery/src/test/java/com/apextuner/feature/battery/ChargingSessionBoundaryTest.kt",
    "feature/network/src/test/java/com/apextuner/feature/network/diagnostics/NetworkThroughputPolicyTest.kt",
    "feature/notifications/src/test/java/com/apextuner/feature/notifications/NotificationIntelligenceTest.kt",
    "feature/cleaner/src/test/java/com/apextuner/feature/cleaner/model/CleanerSmartReviewSummaryTest.kt",
    "feature/appmanager/src/test/java/com/apextuner/feature/appmanager/AppManagerModelsTest.kt",
    "feature/settings/src/test/java/com/apextuner/feature/settings/automation/SmartAutomationPolicyTest.kt",
]:
    check((ROOT / required_test).is_file(), f"New intelligence regression test must remain present: {required_test}")


# Lovable-claims regression invariants. These guard concrete defects found during the
# post-certification holistic audit without pretending source checks replace AGP compilation.
app_manager_route = text("feature/appmanager/src/main/java/com/apextuner/feature/appmanager/AppManagerRoute.kt")
network_vm_full = text("feature/network/src/main/java/com/apextuner/feature/network/NetworkViewModel.kt")
security_vm = text("feature/tools/src/main/java/com/apextuner/feature/tools/security/SecurityViewModel.kt")
security_repo = text("feature/tools/src/main/java/com/apextuner/feature/tools/security/SecurityRepository.kt")
battery_route_full = text("feature/battery/src/main/java/com/apextuner/feature/battery/BatteryRoute.kt")
battery_display_logic = text("feature/battery/src/main/java/com/apextuner/feature/battery/BatteryDisplayLogic.kt")
main_activity_full = text("app/src/main/java/com/apextuner/app/MainActivity.kt")
game_timeout_worker = text("feature/tools/src/main/java/com/apextuner/feature/tools/game/GameSessionWorker.kt")
cleaner_repository_contract = text("feature/cleaner/src/main/java/com/apextuner/feature/cleaner/data/CleanerRepository.kt")
cleaner_android_repository = text("feature/cleaner/src/main/java/com/apextuner/feature/cleaner/data/AndroidCleanerRepository.kt")
network_models_full = text("feature/network/src/main/java/com/apextuner/feature/network/NetworkModels.kt")
network_route_full = text("feature/network/src/main/java/com/apextuner/feature/network/NetworkRoute.kt")
network_repository_full = text("feature/network/src/main/java/com/apextuner/feature/network/NetworkRepository.kt")

check(
    "HorizontalDivider()" not in app_manager_route or "import androidx.compose.material3.HorizontalDivider" in app_manager_route,
    "App Manager bare HorizontalDivider calls must keep the Material3 import required for Kotlin compilation",
)
check(
    "val startBatteryLevel = game.startBatteryLevelPercent" in intelligence_engine
    and "val endBatteryLevel = game.endBatteryLevelPercent" in intelligence_engine
    and "startBatteryLevel - endBatteryLevel" in intelligence_engine
    and "game.startBatteryLevelPercent - game.endBatteryLevelPercent" not in intelligence_engine,
    "Dashboard Intelligence must snapshot cross-module nullable game-session properties before arithmetic so Kotlin can smart-cast them",
)
network_diagnostics_route = text("feature/network/src/main/java/com/apextuner/feature/network/diagnostics/NetworkDiagnosticsRoute.kt")
check(
    "FontWeight.SemiBold" not in network_diagnostics_route or "import androidx.compose.ui.text.font.FontWeight" in network_diagnostics_route,
    "Network Diagnostics must import Compose FontWeight when using FontWeight.SemiBold",
)
check(
    "latest.copy(refreshing = false)" in network_vm_full
    and "cancelRefreshForMutation" in network_vm_full
    and "refreshJob = null" in network_vm_full
    and network_vm_full.count("cancelRefreshForMutation(current)") >= 3,
    "Network refresh cancellation must clear the refreshing flag before firewall/data-cap mutations continue",
)
check(
    "suspend fun snapshot(): SecuritySnapshot = withContext(ioDispatcher)" in security_repo
    and "@param:IoDispatcher" in security_repo
    and "viewModelScope.launch" in security_vm
    and "if (refreshJob?.isActive == true) return" in security_vm,
    "Security posture collection must run off the main dispatcher and coalesce resume/init refreshes",
)
check(
    "batteryCurrentDirection" in battery_display_logic
    and 'charging -> "charging"' in battery_display_logic
    and "formatCurrent(it, b.charging)" in battery_route_full
    and "formatBatteryPower(b.currentMicroamps, b.voltageMillivolts, b.charging)" in battery_route_full
    and 'String.format(Locale.US, "%.0f mA' not in battery_route_full,
    "Battery current/power direction must use Android charging state and locale-aware display formatting",
)
check(
    "consumeLaunchRequest(intent)" in main_activity_full
    and "val launchIntent = intent ?: return null" in main_activity_full
    and "launchIntent.getStringExtra(AppLaunchContract.EXTRA_DESTINATION)" in main_activity_full
    and "launchIntent.getBooleanExtra(AppLaunchContract.EXTRA_QUICK_SCAN, false)" in main_activity_full
    and "launchIntent.getLongExtra(AppLaunchContract.EXTRA_REQUEST_TOKEN, 0L)" in main_activity_full
    and "removeExtra(AppLaunchContract.EXTRA_DESTINATION)" in main_activity_full
    and "removeExtra(AppLaunchContract.EXTRA_QUICK_SCAN)" in main_activity_full
    and "removeExtra(AppLaunchContract.EXTRA_REQUEST_TOKEN)" in main_activity_full,
    "Launch requests must bind a non-null Intent and strip one-shot extras so configuration changes cannot replay them",
)
for route in ["battery", "memory", "performance", "network", "network-diagnostics", "files", "contacts", "notifications", "security", "game", "system-info", "diagnostic-report"]:
    check(
        f'navController.navigate("tools/{route}") {{ launchSingleTop = true }}' in app_shell,
        f"Tools navigation to {route} must be launchSingleTop to resist fast double-tap duplication",
    )
check(
    'if (entitlement.isPremium) navController.navigate("tools/advanced") { launchSingleTop = true } else openPremium()' in app_shell
    and 'composable("tools/advanced")' in app_shell
    and "if (entitlement.isPremium) {" in app_shell
    and "if (entitlement.isPremium && showAdvancedTools)" not in app_shell,
    "Premium users must never be routed to the paywall solely because Advanced Tools discoverability is hidden",
)
check(
    'controller.recoverStaleSession()' in game_timeout_worker and 'controller.stop("stale_timeout")' not in game_timeout_worker,
    "Game session timeout worker must age-check persisted state before restoring/stopping a session",
)
check(
    "suspend fun accessState(): CleanerAccessState" in cleaner_repository_contract
    and "override suspend fun accessState(): CleanerAccessState = withContext(ioDispatcher)" in cleaner_android_repository,
    "Cleaner access-state binder/permission probes must run on the IO dispatcher",
)
check(
    "monthlyDataCapUsage" in network_models_full
    and "dataCapUsagePercent" in network_models_full
    and "usageBytes = snapshot.monthlyDataCapUsage[app.packageName]" in network_route_full
    and "This month:" in network_route_full
    and "loadMonthlyUsageForPackages(caps.keys)" in network_repository_full,
    "Monthly data caps must expose current-month usage-vs-cap feedback in the Network UI",
)
for required_test in [
    "feature/battery/src/test/java/com/apextuner/feature/battery/BatteryDisplayLogicTest.kt",
    "feature/network/src/test/java/com/apextuner/feature/network/DataCapUsageProgressTest.kt",
]:
    check((ROOT / required_test).is_file(), f"Lovable-claims regression test must remain present: {required_test}")

if FAILURES:
    print(f"ApexTuner release gate: FAIL ({len(FAILURES)} failures across {CHECKS} release checks)")
    for failure in FAILURES:
        print(f" - {failure}")
    raise SystemExit(1)
print(f"ApexTuner release gate: PASS ({CHECKS} release checks + project validator)")
