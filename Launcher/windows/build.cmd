@echo off
setlocal EnableExtensions DisableDelayedExpansion
if not defined VCToolsInstallDir (
    for /f "usebackq delims=" %%I in (`"%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "CLOTH_VS=%%I"
)
if defined CLOTH_VS call "%CLOTH_VS%\VC\Auxiliary\Build\vcvars64.bat" >nul
if errorlevel 1 exit /b 1
if not defined VCToolsInstallDir (
    >&2 echo Building the Cloth Windows launcher requires Visual Studio C++ x64 tools and a Windows SDK.
    exit /b 1
)

cl.exe /nologo /EHsc /std:c++17 /W4 /WX /O2 /MT /utf-8 /Brepro /I. /I"%CLOTH_LAUNCHER_JDK%\include" /I"%CLOTH_LAUNCHER_JDK%\include\win32" "%~dp0main.cpp" /Fe"%CLOTH_LAUNCHER_OUTPUT%" /link /Brepro /MACHINE:X64 /MANIFEST:EMBED /MANIFESTINPUT:"%~dp0utf8.manifest" bcrypt.lib shell32.lib
exit /b %errorlevel%
