@echo off
setlocal
cd /d "%~dp0"
call build.cmd
if errorlevel 1 exit /b 1
java -cp out acn.Main interface-test
if errorlevel 1 exit /b 1
java -cp out acn.Main selftest
if errorlevel 1 exit /b 1
java -cp out acn.Main protocol-test
if errorlevel 1 exit /b 1
echo VALIDATION COMPLETE. Run experiments.cmd, then check the real-model chat.
