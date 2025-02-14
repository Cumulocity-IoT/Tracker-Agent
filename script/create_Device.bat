@echo off
setlocal enabledelayedexpansion

:: CSV file containing device details
set "CSV_FILE=devices.csv"
set "LOG_FILE=device_creation.log"

:: Supported operations (comma-separated), default: c8y_Restart,c8y_Command
set "SUPPORTED_OPERATIONS=%~1"
if "%SUPPORTED_OPERATIONS%"=="" set "SUPPORTED_OPERATIONS=c8y_Restart,c8y_Command"

:: Required availability interval (default to 600 seconds)
set "REQUIRED_INTERVAL=%~2"
if "%REQUIRED_INTERVAL%"=="" set "REQUIRED_INTERVAL=10"

:: Owner assignment
set "OWNER=service_tcp-agent"

:: Convert supported operations to JSON array
set "SUPPORTED_OPS_JSON=["
for %%O in (%SUPPORTED_OPERATIONS%) do (
    set "SUPPORTED_OPS_JSON=!SUPPORTED_OPS_JSON!\"%%O\"," 
)
set "SUPPORTED_OPS_JSON=!SUPPORTED_OPS_JSON:~0,-1!]"  :: Remove trailing comma

:: Initialize log file
echo Device Creation Log - %DATE% %TIME% > "%LOG_FILE%"
echo ---------------------------------------- >> "%LOG_FILE%"

:: Enable create mode for c8y
:: c8y settings update mode.enableCreate true

echo about to process csv file !CSV_FILE!
:: Processing CSV (skip header)
set "SKIP_HEADER=1"
for /f "skip=1 tokens=1-5 delims=[]," %%a in (%CSV_FILE%) do (
    set "IMEI=%%~a"
    set "TYPE=%%~b"
    set "MODEL=%%~c"
    set "MANUFACTURER=%%~d"
    set "PROTOCOL=%%~e"
    echo Raw Data: %%a - %%b - %%c - %%d - %%e
    echo Variables: !IMEI! - !TYPE! - !MODEL! - !MANUFACTURER! - !PROTOCOL! 

    :: Validate IMEI format (15 digits)
    call :CREATE_DEVICE "!IMEI!" "!TYPE!" "!MODEL!" "!MANUFACTURER!" "!PROTOCOL!"
)

echo 🚀 Device creation process completed. >> "%LOG_FILE%"
endlocal
goto :EOF  :: Ensure the script doesn't accidentally fall into a label

::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
:: Function to create a device
:CREATE_DEVICE
setlocal
set "IMEI=%~1"
set "TYPE=%~2"
set "MODEL=%~3" 
set "MANUFACTURER=%~4"
set "PROTOCOL=%~5"
set "DEVICE_NAME=Tracker-%IMEI%"

echo Variables: !IMEI! - !TYPE! - !MODEL! - !MANUFACTURER! - !PROTOCOL! 

echo Creating device %DEVICE_NAME% with IMEI: %IMEI%
for /f "usebackq tokens=*" %%D in (`c8y inventory create -f --name "%DEVICE_NAME%" --type "%TYPE%" --data "{\"owner\":\"%OWNER%\",\"c8y_Manufacturer\":\"%MANUFACTURER%\",\"c8y_IsDevice\":{},\"c8y_Mobile\":{\"imei\":\"%IMEI%\"},\"c8y_Hardware\":{\"model\":\"%MODEL%\"},\"c8y_CommunicationMode\":{\"mode\":\"%PROTOCOL%\"},\"com_cumulocity_model_Agent\":{},\"c8y_SupportedOperations\":%SUPPORTED_OPS_JSON%,\"c8y_RequiredAvailability\":{\"responseInterval\":%REQUIRED_INTERVAL%}}" --output json ^| jq -r ".id"`) do (
    set "DEVICE_ID=%%D"
)

if defined DEVICE_ID (
    echo ✅ Device created with ID: %DEVICE_ID% >> "%LOG_FILE%"
    call :CREATE_IDENTITY "!IMEI!" "!DEVICE_ID!"
    endlocal
    goto :EOF
) else (
    echo ❌ Device creation failed for IMEI: %IMEI% >> "%LOG_FILE%"
)

endlocal
goto :EOF

::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
:: Function to create identity
:CREATE_IDENTITY
setlocal
set "IMEI=%~1"
set "DEVICE_ID=%~2"
echo About to create identity with variables: !IMEI! - !DEVICE_ID!

c8y identity create -f --name "%IMEI%" --type "c8y_IMEI" --device "%DEVICE_ID%" >nul 2>&1
if %ERRORLEVEL% EQU 0 (
    echo 🔗 Identity created for IMEI: %IMEI% >> "%LOG_FILE%"
) else (
    echo ❌ Failed to create identity for IMEI: %IMEI% >> "%LOG_FILE%"
)
endlocal
goto :EOF
