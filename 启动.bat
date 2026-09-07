@echo off
cd /d "%~dp0"
start javaw -Djava.awt.headless=false --add-opens=java.base/java.lang=ALL-UNNAMED -jar lion-code-agent-harness-1.0.0-SNAPSHOT.jar
timeout /t 3 /nobreak >nul
start http://localhost:8080
