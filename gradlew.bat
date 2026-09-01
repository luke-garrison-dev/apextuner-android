@echo off
setlocal EnableExtensions
set "GRADLE_VERSION=9.5.0"
set "GRADLE_SHA256=553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746"
set "GRADLE_URL=https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip"
if not defined GRADLE_USER_HOME set "GRADLE_USER_HOME=%USERPROFILE%\.gradle"
set "BOOTSTRAP_ROOT=%GRADLE_USER_HOME%\wrapper\apextuner-bootstrap"
set "INSTALL_DIR=%BOOTSTRAP_ROOT%\gradle-%GRADLE_VERSION%"
set "GRADLE_BIN=%INSTALL_DIR%\bin\gradle.bat"
set "ZIP_PATH=%BOOTSTRAP_ROOT%\gradle-%GRADLE_VERSION%-bin.zip"
set "TMP_ZIP=%ZIP_PATH%.part.%RANDOM%%RANDOM%"
set "TMP_DIR=%BOOTSTRAP_ROOT%\.extract-%GRADLE_VERSION%-%RANDOM%%RANDOM%"

if exist "%GRADLE_BIN%" goto run_gradle
if not exist "%BOOTSTRAP_ROOT%" mkdir "%BOOTSTRAP_ROOT%" >nul 2>&1

if exist "%ZIP_PATH%" (
  call :verify "%ZIP_PATH%"
  if not errorlevel 1 goto extract
  del /q "%ZIP_PATH%" >nul 2>&1
)

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -UseBasicParsing -Uri '%GRADLE_URL%' -OutFile '%TMP_ZIP%'"
if errorlevel 1 goto download_failed
call :verify "%TMP_ZIP%"
if errorlevel 1 goto checksum_failed
move /y "%TMP_ZIP%" "%ZIP_PATH%" >nul

:extract
if exist "%TMP_DIR%" rmdir /s /q "%TMP_DIR%"
mkdir "%TMP_DIR%" >nul 2>&1
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "Expand-Archive -LiteralPath '%ZIP_PATH%' -DestinationPath '%TMP_DIR%' -Force"
if errorlevel 1 goto extract_failed
if not exist "%TMP_DIR%\gradle-%GRADLE_VERSION%\bin\gradle.bat" goto layout_failed
if exist "%INSTALL_DIR%" rmdir /s /q "%INSTALL_DIR%"
move "%TMP_DIR%\gradle-%GRADLE_VERSION%" "%INSTALL_DIR%" >nul
rmdir /s /q "%TMP_DIR%" >nul 2>&1

goto run_gradle

:verify
for /f %%H in ('powershell -NoProfile -ExecutionPolicy Bypass -Command "(Get-FileHash -Algorithm SHA256 -LiteralPath '%~1').Hash.ToLowerInvariant()"') do set "ACTUAL_SHA256=%%H"
if /I not "%ACTUAL_SHA256%"=="%GRADLE_SHA256%" (
  echo ApexTuner Gradle bootstrap: Gradle distribution checksum mismatch. 1>&2
  echo Expected: %GRADLE_SHA256% 1>&2
  echo Actual:   %ACTUAL_SHA256% 1>&2
  exit /b 1
)
exit /b 0

:run_gradle
call "%GRADLE_BIN%" %*
exit /b %ERRORLEVEL%

:download_failed
echo ApexTuner Gradle bootstrap: failed to download Gradle %GRADLE_VERSION%. 1>&2
goto fail
:checksum_failed
del /q "%TMP_ZIP%" >nul 2>&1
echo ApexTuner Gradle bootstrap: refusing unverified Gradle distribution. 1>&2
goto fail
:extract_failed
echo ApexTuner Gradle bootstrap: failed to extract Gradle. 1>&2
goto fail
:layout_failed
echo ApexTuner Gradle bootstrap: downloaded archive has an unexpected layout. 1>&2
goto fail
:fail
if exist "%TMP_ZIP%" del /q "%TMP_ZIP%" >nul 2>&1
if exist "%TMP_DIR%" rmdir /s /q "%TMP_DIR%" >nul 2>&1
exit /b 1
