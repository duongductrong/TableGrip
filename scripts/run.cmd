@echo off
rem Builds and launches TableGrip on Windows. Arguments are passed to Gradle, e.g.
rem   scripts\run.cmd -Ptablegrip.home=%TEMP%\dg
cd /d "%~dp0.."
call gradlew.bat :app:run %*
