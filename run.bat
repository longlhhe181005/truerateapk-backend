@echo off
chcp 65001 >nul
rem Starts TrueRate on http://localhost:8080
rem Put your key in a file named .env next to this script:  ANTHROPIC_API_KEY=sk-ant-...

if exist "C:\Program Files\Java\jdk-17\bin\java.exe" set "JAVA_HOME=C:\Program Files\Java\jdk-17"
if defined JAVA_HOME set "PATH=%JAVA_HOME%\bin;%PATH%"

set "MVN=mvn"
where mvn >nul 2>nul
if errorlevel 1 set "MVN=%USERPROFILE%\.m2\wrapper\dists\apache-maven-3.9.6-bin\439sdfsg2nbdob9ciift5h5nse\apache-maven-3.9.6\bin\mvn.cmd"

cd /d "%~dp0"
call "%MVN%" -q spring-boot:run
