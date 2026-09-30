@echo off
rem Starts a test server on this PC for the dev clients to join at 127.0.0.1 (not "localhost": NeoForge
rem resolves that to IPv6 ::1, and the server only listens on IPv4):
rem   tools\server.cmd neoforge [version]   NeoForge with the mod     (runs\<version>-neoforge\server)
rem   tools\server.cmd fabric [version]     Fabric with the mod       (runs\<version>-fabric\server)
rem   tools\server.cmd vanilla [version]    plain Minecraft, no mods  (runs\<version>-vanilla\server)
rem The version is a Minecraft version the mod is built for (see settings.gradle.kts); 1.21.1 when left out.
rem The first time, it writes a server.properties for testing: offline mode (the dev clients have no Microsoft
rem login), reachable from this PC only, creative, flat world. Minecraft's EULA is only ever accepted by hand.
setlocal
for %%I in ("%~dp0..") do set "ROOT=%%~fI"
set "LOADER=%~1"
set "MC=%~2"
if not defined MC set "MC=1.21.1"
set "PROJECT="
set "DIR="
if /i "%LOADER%"=="neoforge" set "PROJECT=%MC%-neoforge"
if /i "%LOADER%"=="fabric" set "PROJECT=%MC%-fabric"
if defined PROJECT set "DIR=%ROOT%\runs\%PROJECT%\server"
if /i "%LOADER%"=="vanilla" set "DIR=%ROOT%\runs\%MC%-vanilla\server"
if not defined DIR (
  echo Usage: tools\server.cmd neoforge ^| fabric ^| vanilla [Minecraft version, 1.21.1 if left out]
  exit /b 1
)
if not exist "%DIR%" mkdir "%DIR%"

if not exist "%DIR%\server.properties" (
  >"%DIR%\server.properties" echo online-mode=false
  >>"%DIR%\server.properties" echo server-ip=127.0.0.1
  >>"%DIR%\server.properties" echo gamemode=creative
  >>"%DIR%\server.properties" echo level-type=minecraft\:flat
  >>"%DIR%\server.properties" echo spawn-protection=0
  >>"%DIR%\server.properties" echo motd=Desktop Screens test - %LOADER% %MC%
)
rem NeoForge also lists its server as a "LAN world", at this PC's network address, where it doesn't listen.
if /i "%LOADER%"=="neoforge" if not exist "%DIR%\config\neoforge-server.toml" (
  mkdir "%DIR%\config" 2>nul
  >"%DIR%\config\neoforge-server.toml" echo advertiseDedicatedServerToLan = false
)

findstr /b /i /c:"eula=true" "%DIR%\eula.txt" >nul 2>&1
if errorlevel 1 (
  if not exist "%DIR%\eula.txt" >"%DIR%\eula.txt" echo eula=false
  echo.
  echo The server only starts once you accept Minecraft's EULA: https://aka.ms/MinecraftEULA
  echo If you agree, open this file, change eula=false to eula=true, and run this again:
  echo   %DIR%\eula.txt
  exit /b 1
)

if defined PROJECT (
  pushd "%ROOT%"
  call gradlew.bat :%PROJECT%:runServer
  popd
  exit /b
)

rem The vanilla server jar the build already downloaded from Mojang (same file in both caches).
set "JAR=%USERPROFILE%\.gradle\caches\fabric-loom\%MC%\minecraft-server.jar"
if not exist "%JAR%" set "JAR=%USERPROFILE%\.gradle\caches\neoformruntime\artifacts\minecraft_%MC%_server.jar"
if not exist "%JAR%" (
  echo Minecraft's server jar isn't in the Gradle cache yet. Run "gradlew build" once first.
  exit /b 1
)
set "JAVA=java"
if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java.exe"
pushd "%DIR%"
"%JAVA%" -jar "%JAR%" nogui
popd
