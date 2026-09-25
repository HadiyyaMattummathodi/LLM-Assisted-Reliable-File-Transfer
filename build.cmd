@echo off
setlocal
cd /d "%~dp0"
if not exist out mkdir out
javac -encoding UTF-8 -source 1.8 -target 1.8 -d out src\acn\*.java
if errorlevel 1 exit /b 1
echo Build successful.
