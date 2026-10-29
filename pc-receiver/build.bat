@echo off
setlocal
cd /d "%~dp0"

set "CSC=%WINDIR%\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
if not exist "%CSC%" (
    echo [ERROR] .NET Framework 4.x compiler not found:
    echo         %CSC%
    exit /b 1
)

"%CSC%" /nologo /codepage:65001 /target:winexe /optimize+ ^
    /out:PcReceiver.exe ^
    /r:System.dll ^
    /r:System.Windows.Forms.dll ^
    /r:System.Drawing.dll ^
    /r:System.Web.Extensions.dll ^
    PcReceiver.cs

if errorlevel 1 (
    echo [ERROR] Build failed.
    exit /b 1
)

echo [OK] PcReceiver.exe generated.
exit /b 0
