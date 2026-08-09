@echo off
set "JAVA_HOME=C:\Users\Einsli\.vscode\extensions\redhat.java-1.55.0-win32-x86_64\jre\21.0.11-win32-x86_64"
echo JAVA_HOME=%JAVA_HOME%
if exist "%JAVA_HOME%\bin\java.exe" echo java exists
if exist "%JAVA_HOME%\bin\javac.exe" echo javac exists
dir "%JAVA_HOME%\bin\java.exe"
dir "%JAVA_HOME%\bin\javac.exe"
"%JAVA_HOME%\bin\java.exe" -version
"%JAVA_HOME%\bin\javac.exe" -version
