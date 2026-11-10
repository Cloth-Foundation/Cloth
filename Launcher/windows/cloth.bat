@echo off
setlocal EnableExtensions DisableDelayedExpansion
set "CLOTH_BIN=%~dp0"
if not exist "%~f0" for %%I in ("%~nx0") do set "CLOTH_BIN=%%~dp$PATH:I"
if not defined CLOTH_BIN goto missingLauncher
if not exist "%CLOTH_BIN%cloth.exe" goto missingLauncher
"%CLOTH_BIN%cloth.exe" %*
exit /b %errorlevel%

:missingLauncher
>&2 echo Cloth launcher: cloth.exe is missing from the installation.
exit /b 2
