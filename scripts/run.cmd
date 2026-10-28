@echo off
rem Builds and launches Datagrok on Windows. Arguments are passed to Gradle, e.g.
rem   scripts\run.cmd -Pdatagrok.home=%TEMP%\dg
cd /d "%~dp0.."
call gradlew.bat :app:run %*
