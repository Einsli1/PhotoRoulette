@echo off
set "JAVA_HOME=C:\Users\Einsli\.vscode\extensions\redhat.java-1.55.0-win32-x64\jre\21.0.11-win32-x86_64"
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo JAVA_HOME=%JAVA_HOME%
echo PATH=%PATH:~0,200%
if exist "%JAVA_HOME%\bin\java.exe" echo java exists
if exist "%JAVA_HOME%\bin\javac.exe" echo javac exists
"%JAVA_HOME%\bin\java.exe" -version
"%JAVA_HOME%\bin\javac.exe" -version
call gradlew.bat -Dorg.gradle.java.home="%JAVA_HOME%" assembleDebug
if errorlevel 1 exit /b 1
echo BUILD COMPLETED
