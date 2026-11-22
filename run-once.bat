@echo off
setlocal EnableExtensions

set "PROJECT_DIR=C:\Users\ZBABUR\AI\AI-Agnet"
set "TRUSTSTORE=%PROJECT_DIR%\truststore.p12"
set "TRUSTSTORE_PASS=changeit"
set "LOG_DIR=%PROJECT_DIR%\logs"

cd /d "%PROJECT_DIR%"
if errorlevel 1 (
    echo [ERROR] Failed to change directory to "%PROJECT_DIR%".
    exit /b 1
)

if not exist "%TRUSTSTORE%" (
    echo [ERROR] Truststore not found at "%TRUSTSTORE%".
    exit /b 1
)

if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

rem Set JVM options for Maven's JVM (and pass to exec:java as well)
set "SSL_OPTS=-Djavax.net.ssl.trustStore=%TRUSTSTORE% -Djavax.net.ssl.trustStorePassword=%TRUSTSTORE_PASS% -Djavax.net.ssl.trustStoreType=PKCS12"
set "MAVEN_OPTS=%SSL_OPTS%"

rem Optional: if JAVA_HOME is not set in Task Scheduler's environment, uncomment and set your JDK path
rem set "JAVA_HOME=C:\Program Files\Java\jdk-21"
rem set "PATH=%JAVA_HOME%\bin;%PATH%"

for /f "tokens=2 delims==" %%I in ('wmic os get LocalDateTime /value 2^>nul') do if not "%%I"=="" set "LDT=%%I"
if defined LDT (
    set "STAMP=%LDT:~0,4%-%LDT:~4,2%-%LDT:~6,2%_%LDT:~8,2%-%LDT:~10,2%"
) else (
    set "STAMP=run"
)
set "LOG_FILE=%LOG_DIR%\agent-%STAMP%.log"

echo [%date% %time%] Starting single-cycle run... >> "%LOG_FILE%"

mvn -q -B exec:java ^
    -Dexec.mainClass="com.goldagent.Main" ^
    -Dexec.vmArgs="%SSL_OPTS%" ^
    >> "%LOG_FILE%" 2>&1

set "EXIT_CODE=%ERRORLEVEL%"
echo [%date% %time%] Finished with exit code %EXIT_CODE%. >> "%LOG_FILE%"

exit /b %EXIT_CODE%