param(
    [Parameter(Mandatory = $true)][string]$NativeBin,
    [Parameter(Mandatory = $true)][string]$Jdk,
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = (Resolve-Path -LiteralPath $Jdk).Path
$nativePath = (Resolve-Path -LiteralPath $NativeBin).Path
$jpackage = Join-Path $env:JAVA_HOME 'bin/jpackage.exe'
$out = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath (Join-Path $out 'YunGet')) {
    throw 'Choose an output directory without an existing YunGet package.'
}

$version = (Select-String -LiteralPath (Join-Path $repo 'gradle.properties') -Pattern '^yungetVersion=(.+)$').Matches[0].Groups[1].Value
$options = @(':desktopApp:packageUberJarForCurrentOS', ':desktopApp:stageNative',
    "-PyungetDesktopNativeBin=$nativePath", '--console=plain', '--max-workers=1')
if ($Offline) { $options += '--offline' }
Push-Location $repo
try {
    & .\gradlew.bat @options
    if ($LASTEXITCODE -ne 0) { throw 'Windows client compilation failed' }
} finally {
    Pop-Location
}

$jars = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'build/compose/jars') -Filter '*.jar' -File)
if ($jars.Count -ne 1) { throw 'Could not identify the Compose application JAR' }
$inputDir = Join-Path $out ('input-' + [Guid]::NewGuid().ToString('N'))
$nativeInput = Join-Path $inputDir 'native'
New-Item -ItemType Directory -Path $nativeInput -Force | Out-Null
Copy-Item -LiteralPath $jars[0].FullName -Destination $inputDir
$nativeFiles = Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'build/native-resources/windows-x64') -File
foreach ($file in $nativeFiles) { Copy-Item -LiteralPath $file.FullName -Destination $nativeInput }

# An app image includes its own Java runtime and can start directly from the folder.
# The launcher's $APPDIR expands to the bundled app directory at runtime.
try {
    & $jpackage --type app-image --name YunGet --app-version $version --input $inputDir `
        --dest $out --main-jar $jars[0].Name --main-class com.yunget.desktop.MainKt `
        --java-options '-Dyunget.native.bin=$APPDIR/native' --add-modules 'java.se,jdk.crypto.ec'
    if ($LASTEXITCODE -ne 0) { throw 'Windows app image packaging failed' }
} finally {
    $resolvedInput = (Resolve-Path -LiteralPath $inputDir).Path
    $resolvedOutput = (Resolve-Path -LiteralPath $out).Path
    $parent = [IO.Path]::GetDirectoryName($resolvedInput)
    if ($parent -eq $resolvedOutput -and [IO.Path]::GetFileName($resolvedInput) -match '^input-[0-9a-f]{32}$') {
        Remove-Item -LiteralPath $resolvedInput -Recurse -Force
    }
}
Get-Item -LiteralPath (Join-Path $out 'YunGet/YunGet.exe')
