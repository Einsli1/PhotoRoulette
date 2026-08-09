# Builds PhotoRoulette with Android Studio's JDK 21 (JBR), installs the debug APK on the
# connected device, and launches it. Run from anywhere; gradlew.bat lives next to this script.
$javaHome = 'E:\android studio\jbr'
$env:JAVA_HOME = $javaHome
$env:ANDROID_HOME = 'C:\Users\Einsli\AppData\Local\Android\Sdk'
$adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
& "$PSScriptRoot\gradlew.bat" installDebug --console=plain
if ($LASTEXITCODE -ne 0) { Write-Host "BUILD FAILED"; exit $LASTEXITCODE }
& $adb shell am start -n com.einsli.photoroulette/com.einsli.photoroulette.MainActivity
exit $LASTEXITCODE
