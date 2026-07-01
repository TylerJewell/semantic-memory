@echo off
setlocal enabledelayedexpansion

if "%GOOGLE_AI_GEMINI_API_KEY%"=="" (
  echo Please set GOOGLE_AI_GEMINI_API_KEY before running.
  exit /b 1
)

if "%FLUREE_DIR%"=="" set FLUREE_DIR=.fluree-runtime
if "%FLUREE_BIN%"=="" set FLUREE_BIN=fluree
if "%LEDGER%"=="" set LEDGER=memory

where %FLUREE_BIN% >nul 2>nul
if errorlevel 1 (
  echo Fluree not found on PATH. Download from https://github.com/fluree/db/releases
  echo or set FLUREE_BIN to the fluree.exe path.
  exit /b 1
)

if not exist %FLUREE_DIR% mkdir %FLUREE_DIR%
pushd %FLUREE_DIR%
if not exist .fluree (
  echo -^> initializing Fluree in %FLUREE_DIR%
  %FLUREE_BIN% init
)

%FLUREE_BIN% server status >nul 2>nul
if errorlevel 1 (
  echo -^> starting Fluree server on 127.0.0.1:8090
  %FLUREE_BIN% server start --listen-addr 127.0.0.1:8090
  timeout /t 2 /nobreak >nul
)

%FLUREE_BIN% list 2>nul | findstr /r "^%LEDGER%$" >nul
if errorlevel 1 (
  echo -^> creating ledger %LEDGER%
  %FLUREE_BIN% create %LEDGER%
)

popd
echo -^> starting Akka service on http://localhost:9000
call mvn -q compile exec:java
