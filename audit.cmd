@echo off
setlocal
cd /d "%~dp0"
if "%~1"=="" (
  echo Usage: audit.cmd "runs\experiments-v7-BATCH"
  exit /b 1
)
java -cp out acn.Main audit "%~1"
exit /b %errorlevel%
