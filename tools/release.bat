@echo off
REM creates and pushes a vX.Y.Z tag which triggers the release-apk workflow
REM usage: release.bat [version]   e.g. release.bat v3.26111.1430
REM        with no arg a tag is generated as 3.YYDDD.HHMM matching the gradle scheme
setlocal

set "REMOTE=origin"
set "VERSION=%~1"

if "%VERSION%"=="" (
    REM build 3.YYDDD.HHMM via powershell so day-of-year and zero padding are correct
    for /f "usebackq delims=" %%v in (`powershell -NoProfile -Command "$n=Get-Date; '3.{0:00}{1:000}.{2:00}{3:00}' -f ($n.Year %% 100), $n.DayOfYear, $n.Hour, $n.Minute"`) do set "VERSION=%%v"
)

REM normalise to a leading v
set "TAG=%VERSION%"
if not "%TAG:~0,1%"=="v" set "TAG=v%VERSION%"

REM bail on uncommitted tracked changes so the released apk matches what is committed
for /f "delims=" %%s in ('git status --porcelain --untracked-files=no') do (
    echo error: working tree is dirty, commit or stash first
    exit /b 1
)

git rev-parse "%TAG%" >nul 2>&1
if %errorlevel%==0 (
    echo error: tag %TAG% already exists
    exit /b 1
)

echo tagging %TAG% and pushing to %REMOTE%
git tag -a "%TAG%" -m "%TAG%"
if errorlevel 1 exit /b 1
git push "%REMOTE%" "%TAG%"
if errorlevel 1 exit /b 1

echo done. watch the Build and Release APK workflow on github
endlocal
