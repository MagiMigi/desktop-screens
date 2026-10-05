@echo off
rem Starts a test server on this PC for the dev clients to join at 127.0.0.1 (not "localhost": NeoForge
rem resolves that to IPv6 ::1, and the server only listens on IPv4):
rem   tools\server.cmd neoforge [version]   NeoForge with the mod     (runs\<version>-neoforge\server)
rem   tools\server.cmd fabric [version]     Fabric with the mod       (runs\<version>-fabric\server)
rem   tools\server.cmd forge [version]      Forge with the mod        (runs\<version>-forge\server; 1.20.1, 1.12.2)
rem   tools\server.cmd forge-nomod 1.12.2   Forge without the mod     (runs\1.12.2-forge\server-nomod)
rem   tools\server.cmd forge-ftbu 1.12.2    Forge, the mod and FTB Utilities' land claims (runs\1.12.2-forge\server-ftbu)
rem   tools\server.cmd vanilla [version]    plain Minecraft, no mods  (runs\<version>-vanilla\server)
rem The version is a Minecraft version the mod is built for (see settings.gradle.kts); 1.21.1 when left out.
rem The first time, it writes a server.properties for testing: offline mode (the dev clients have no Microsoft
rem login), reachable from this PC only, creative, flat world, no whitelist. Minecraft's EULA is only ever
rem accepted by hand.
setlocal
for %%I in ("%~dp0..") do set "ROOT=%%~fI"
set "LOADER=%~1"
set "MC=%~2"
if not defined MC set "MC=1.21.1"
set "PROJECT="
set "DIR="
if /i "%LOADER%"=="neoforge" set "PROJECT=%MC%-neoforge"
if /i "%LOADER%"=="fabric" set "PROJECT=%MC%-fabric"
if /i "%LOADER%"=="forge" set "PROJECT=%MC%-forge"
if defined PROJECT set "DIR=%ROOT%\runs\%PROJECT%\server"
rem A Forge server without the mod, for what a game with it does there: only 1.12.2's run can leave it out.
if /i "%LOADER%"=="forge-nomod" if "%MC%"=="1.12.2" set "DIR=%ROOT%\runs\1.12.2-forge\server-nomod"
rem With a claim mod (newer versions: -Pclaims on their runs instead).
if /i "%LOADER%"=="forge-ftbu" if "%MC%"=="1.12.2" set "DIR=%ROOT%\runs\1.12.2-forge\server-ftbu"
if /i "%LOADER%"=="vanilla" set "DIR=%ROOT%\runs\%MC%-vanilla\server"
if not defined DIR (
  echo Usage: tools\server.cmd neoforge ^| fabric ^| forge ^| vanilla [Minecraft version, 1.21.1 if left out]
  echo    or: tools\server.cmd forge-nomod 1.12.2 ^| forge-ftbu 1.12.2
  exit /b 1
)
if not exist "%DIR%" mkdir "%DIR%"

set "GAMEMODE=creative"
set "LEVELTYPE=minecraft\:flat"
rem 1.12.2 numbers its game modes and names its world types without "minecraft:".
if "%MC%"=="1.12.2" (
  set "GAMEMODE=1"
  set "LEVELTYPE=flat"
)
if not exist "%DIR%\server.properties" (
  >"%DIR%\server.properties" echo online-mode=false
  >>"%DIR%\server.properties" echo server-ip=127.0.0.1
  >>"%DIR%\server.properties" echo gamemode=%GAMEMODE%
  >>"%DIR%\server.properties" echo level-type=%LEVELTYPE%
  >>"%DIR%\server.properties" echo spawn-protection=0
  rem From 26.3 on, a new server only lets in the players on its whitelist.
  >>"%DIR%\server.properties" echo white-list=false
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

if /i "%LOADER%"=="forge-nomod" (
  pushd "%ROOT%"
  call gradlew.bat -p legacy runServer -PwithoutMod
  popd
  exit /b
)
if /i "%LOADER%"=="forge-ftbu" (
  pushd "%ROOT%"
  call gradlew.bat -p legacy runServer -Pclaims=ftbu
  popd
  exit /b
)
if defined PROJECT (
  pushd "%ROOT%"
  rem 1.12.2 is a build of its own, in legacy\.
  if "%MC%"=="1.12.2" (
    call gradlew.bat -p legacy runServer
  ) else (
    call gradlew.bat :%PROJECT%:runServer
  )
  popd
  exit /b
)

rem The vanilla server jar the build already downloaded from Mojang (same file in both caches).
set "JAR=%USERPROFILE%\.gradle\caches\fabric-loom\%MC%\minecraft-server.jar"
if not exist "%JAR%" set "JAR=%USERPROFILE%\.gradle\caches\neoformruntime\artifacts\minecraft_%MC%_server.jar"
rem 1.12.2's, as RetroFuturaGradle downloaded it.
if not exist "%JAR%" set "JAR=%USERPROFILE%\.gradle\caches\retro_futura_gradle\mc-vanilla\%MC%\server.jar"
if not exist "%JAR%" (
  echo Minecraft's server jar isn't in the Gradle cache yet. Run "gradlew build" once first.
  exit /b 1
)
set "JAVA=java"
if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java.exe"
rem 1.12.2 runs on Java 8: the one Gradle keeps for its build.
if "%MC%"=="1.12.2" for /d %%D in ("%USERPROFILE%\.gradle\jdks\*-8-*") do if exist "%%D\bin\java.exe" set "JAVA=%%D\bin\java.exe"
rem 26.x runs on Java 25: the same.
if "%MC:~0,3%"=="26." for /d %%D in ("%USERPROFILE%\.gradle\jdks\*-25-*") do if exist "%%D\bin\java.exe" set "JAVA=%%D\bin\java.exe"
pushd "%DIR%"
"%JAVA%" -jar "%JAR%" nogui
popd
