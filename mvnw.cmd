@echo off
setlocal
set "MAVEN_VERSION=3.9.11"
set "MAVEN_DIR=%~dp0.mvn\apache-maven-%MAVEN_VERSION%"
if not exist "%MAVEN_DIR%\bin\mvn.cmd" (
  echo Downloading Apache Maven %MAVEN_VERSION%...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $u='https://archive.apache.org/dist/maven/maven-3/%MAVEN_VERSION%/binaries/apache-maven-%MAVEN_VERSION%-bin.zip'; $z='%TEMP%\ledgerbank-maven.zip'; Invoke-WebRequest $u -OutFile $z; Expand-Archive -Force $z '%~dp0.mvn'; Remove-Item $z"
  if errorlevel 1 exit /b 1
)
call "%MAVEN_DIR%\bin\mvn.cmd" %*

