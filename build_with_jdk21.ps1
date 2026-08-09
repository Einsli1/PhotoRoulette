$javaHome = 'C:\Users\Einsli\.vscode\extensions\redhat.java-1.55.0-win32-x86_64\jre\21.0.11-win32-x86_64'
$env:JAVA_HOME = $javaHome
$env:PATH = "$javaHome\bin;" + $env:PATH
Write-Host "JAVA_HOME=$env:JAVA_HOME"
Write-Host "Checking java..."
& "$javaHome\bin\java.exe" -version
Write-Host "Checking javac..."
& "$javaHome\bin\javac.exe" -version
Write-Host "Running Gradle..."
& "$PSScriptRoot\gradlew.bat" assembleDebug -Dorg.gradle.java.home=$javaHome
exit $LASTEXITCODE
