@echo off
setlocal enabledelayedexpansion
set FILE=app\build.gradle.kts
if "%~1"=="" (
  powershell -NoProfile -Command "$f=Join-Path (Get-Location) '%FILE%'; $s=Get-Content $f -Raw; if ($s -match 'versionName\s*=\s*"(\d+)\.(\d+)\.(\d+)"') { $major=$matches[1]; $minor=$matches[2]; $patch=[int]$matches[3]+1; $new=\"$major.$minor.$patch\"; $ns=$s -replace 'versionName\s*=\s*"\d+\.\d+\.\d+"', 'versionName = \"'+$new+'\"'; Set-Content $f $ns; Write-Output "Bumped version to $new" } else { Write-Output 'versionName pattern not found' }"
) else (
  powershell -NoProfile -Command "$f=Join-Path (Get-Location) '%FILE%'; $s=Get-Content $f -Raw; $new='%1'; $ns=$s -replace 'versionName\s*=\s*"\d+\.\d+\.\d+"', 'versionName = \"'+$new+'\"'; Set-Content $f $ns; Write-Output \"Set version to $new\""
)
echo Done.
endlocal
