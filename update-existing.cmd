@echo off
setlocal
if "%~1"=="" (
  echo Usage: update-existing.cmd "C:\path\to\existing\udp-assignment"
  exit /b 1
)
set "RFT_UPDATE_TARGET=%~f1"
if not exist "%RFT_UPDATE_TARGET%\build.cmd" (
  echo Target must be the existing folder containing build.cmd.
  exit /b 1
)
if not exist "%~dp0src\acn\ChatConsole.java" (
  echo Updated source files are missing. Extract the complete updated ZIP before running this script.
  exit /b 1
)
if /I "%~dp0"=="%RFT_UPDATE_TARGET%\" (
  echo Already in the destination folder. Compiling the files here.
  call "%RFT_UPDATE_TARGET%\build.cmd"
  exit /b
)
robocopy "%~dp0src" "%RFT_UPDATE_TARGET%\src" /E /NFL /NDL /NJH /NJS
if errorlevel 8 (
  echo Update failed while copying Java source files.
  exit /b 1
)
robocopy "%~dp0docs" "%RFT_UPDATE_TARGET%\docs" /E /NFL /NDL /NJH /NJS
if errorlevel 8 (
  echo Update failed while copying documentation.
  exit /b 1
)
robocopy "%~dp0tests" "%RFT_UPDATE_TARGET%\tests" /E /NFL /NDL /NJH /NJS
if errorlevel 8 (
  echo Update failed while copying tests.
  exit /b 1
)
robocopy "%~dp0." "%RFT_UPDATE_TARGET%" *.cmd *.txt *.md *.sh .gitignore .gitattributes /NFL /NDL /NJH /NJS
if errorlevel 8 (
  echo Update failed while copying root instructions and scripts.
  exit /b 1
)
echo Source and instructions updated. Existing inputs, received files and run logs were preserved.
call "%RFT_UPDATE_TARGET%\build.cmd"
exit /b %errorlevel%
