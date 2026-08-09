@echo off
setlocal
set "JAVA_HOME=C:\Users\Einsli\.vscode\extensions\redhat.java-1.55.0-win32-x86_64\jre\21.0.11-win32-x86_64"
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo JAVA_HOME=%JAVA_HOME%
where java
where javac
java -version
javac -version
call gradlew.bat assembleDebug
endlocal
