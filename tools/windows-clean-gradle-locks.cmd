@echo off
setlocal EnableExtensions
cd /d "%~dp0\.."

echo [ApexTuner] Stopping Gradle daemons...
call gradlew.bat --stop

echo [ApexTuner] Removing project-local Gradle/build outputs...
if exist ".gradle" rmdir /s /q ".gradle"
if exist "app\build" rmdir /s /q "app\build"
if exist "core\build" rmdir /s /q "core\build"
for /d %%D in ("feature\*") do (
    if exist "%%~fD\build" rmdir /s /q "%%~fD\build"
)

echo [ApexTuner] Cleanup complete. Re-open Android Studio and rebuild the release bundle.
endlocal
