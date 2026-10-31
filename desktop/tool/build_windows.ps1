[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:FlutterExe = 'D:\env\flutter\bin\flutter.bat'
$script:JreModules = @(
    'java.base',
    'java.logging',
    'java.management',
    'jdk.crypto.ec'
)
$script:LastExitCode = $null

function Write-Step {
    param([Parameter(Mandatory = $true)][string]$Message)

    Write-Host ''
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Write-Info {
    param([Parameter(Mandatory = $true)][string]$Message)

    Write-Host "    $Message"
}

function Write-Warn {
    param([Parameter(Mandatory = $true)][string]$Message)

    Write-Host "WARNING: $Message" -ForegroundColor Yellow
}

function Get-NormalizedPath {
    param([Parameter(Mandatory = $true)][string]$Path)

    $fullPath = [System.IO.Path]::GetFullPath($Path)
    $root = [System.IO.Path]::GetPathRoot($fullPath)
    if ([string]::Equals($fullPath, $root, [System.StringComparison]::OrdinalIgnoreCase)) {
        return $root
    }

    return $fullPath.TrimEnd([char[]]@('\', '/'))
}

function Assert-DirectChildDirectoryPath {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Parent,
        [string]$ExpectedLeaf,
        [string]$ExpectedLeafPrefix
    )

    $fullPath = Get-NormalizedPath -Path $Path
    $parentPath = Get-NormalizedPath -Path $Parent
    $actualParent = [System.IO.Path]::GetDirectoryName($fullPath)
    if (-not [string]::Equals(
        $actualParent,
        $parentPath,
        [System.StringComparison]::OrdinalIgnoreCase
    )) {
        throw "Unsafe path rejected: '$fullPath' is not a direct child of '$parentPath'."
    }

    $leaf = [System.IO.Path]::GetFileName($fullPath)
    if (
        -not [string]::IsNullOrEmpty($ExpectedLeaf) -and
        -not [string]::Equals($leaf, $ExpectedLeaf, [System.StringComparison]::OrdinalIgnoreCase)
    ) {
        throw "Unsafe path rejected: expected leaf '$ExpectedLeaf', got '$leaf'."
    }

    if (
        -not [string]::IsNullOrEmpty($ExpectedLeafPrefix) -and
        -not $leaf.StartsWith($ExpectedLeafPrefix, [System.StringComparison]::OrdinalIgnoreCase)
    ) {
        throw "Unsafe path rejected: '$leaf' does not start with '$ExpectedLeafPrefix'."
    }

    if (Test-Path -LiteralPath $fullPath) {
        $item = Get-Item -LiteralPath $fullPath -Force
        if (-not $item.PSIsContainer) {
            throw "Expected a directory but found a file: '$fullPath'."
        }
        if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Unsafe path rejected: refusing to recursively remove reparse point '$fullPath'."
        }
    }
}

function Remove-DirectChildDirectory {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Parent,
        [string]$ExpectedLeaf,
        [string]$ExpectedLeafPrefix
    )

    Assert-DirectChildDirectoryPath `
        -Path $Path `
        -Parent $Parent `
        -ExpectedLeaf $ExpectedLeaf `
        -ExpectedLeafPrefix $ExpectedLeafPrefix

    if (Test-Path -LiteralPath $Path) {
        Write-Info "Removing validated directory: $Path"
        Remove-Item -LiteralPath $Path -Recurse -Force
    }
}

function Assert-RequiredFile {
    param([Parameter(Mandatory = $true)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Required file is missing: '$Path'."
    }

    $item = Get-Item -LiteralPath $Path
    if ($item.Length -le 0) {
        throw "Required file is empty: '$Path'."
    }
}

function Assert-RequiredDirectory {
    param([Parameter(Mandatory = $true)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
        throw "Required directory is missing: '$Path'."
    }
}

function Invoke-ExternalCommand {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$ArgumentList,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory
    )

    $resolvedFile = (Resolve-Path -LiteralPath $FilePath).Path
    $quotedArguments = foreach ($argument in $ArgumentList) {
        if ($argument -match '[\s"]') {
            '"' + $argument.Replace('"', '\"') + '"'
        }
        else {
            $argument
        }
    }

    Write-Host ''
    Write-Host ("> `"$resolvedFile`" " + ($quotedArguments -join ' ')) -ForegroundColor DarkGray
    Push-Location -LiteralPath $WorkingDirectory
    try {
        $global:LASTEXITCODE = 0
        & $resolvedFile @ArgumentList
        $script:LastExitCode = if ($null -eq $LASTEXITCODE) { 0 } else { $LASTEXITCODE }
    }
    finally {
        Pop-Location
    }
}

function Test-JavaRuntimeHome {
    param([string]$Path)

    if ([string]::IsNullOrWhiteSpace($Path)) {
        return $null
    }

    $candidate = $Path.Trim().Trim([char]'"')
    try {
        $resolved = (Resolve-Path -LiteralPath $candidate -ErrorAction Stop).Path
    }
    catch {
        return $null
    }

    $javaW = Join-Path $resolved 'bin\javaw.exe'
    if (-not (Test-Path -LiteralPath $javaW -PathType Leaf)) {
        return $null
    }

    return Get-NormalizedPath -Path $resolved
}

function Resolve-JavaRuntimeHome {
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $fromJavaHome = Test-JavaRuntimeHome -Path $env:JAVA_HOME
        if (-not [string]::IsNullOrEmpty($fromJavaHome)) {
            Write-Info "Java runtime: $fromJavaHome (source: JAVA_HOME)"
            return $fromJavaHome
        }

        Write-Warn "JAVA_HOME is set but does not contain bin\javaw.exe; falling back to PATH."
    }

    $javaCommand = Get-Command -Name 'java.exe' -CommandType Application -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($null -eq $javaCommand) {
        throw 'No usable JRE found: set JAVA_HOME or put java.exe on PATH.'
    }

    $javaHome = Split-Path -Parent (Split-Path -Parent $javaCommand.Source)
    $fromPath = Test-JavaRuntimeHome -Path $javaHome
    if ([string]::IsNullOrEmpty($fromPath)) {
        throw "java.exe was found at '$($javaCommand.Source)', but its parent is not a valid JRE."
    }

    Write-Info "Java runtime: $fromPath (source: PATH java.exe)"
    return $fromPath
}

function Assert-FlutterVersion {
    param([Parameter(Mandatory = $true)][string]$FlutterExe)

    $flutterRoot = Split-Path -Parent (Split-Path -Parent $FlutterExe)
    $versionFile = Join-Path $flutterRoot 'bin\cache\flutter.version.json'
    if (-not (Test-Path -LiteralPath $versionFile -PathType Leaf)) {
        Write-Warn "Flutter version metadata was not found at '$versionFile'; using the pinned executable."
        return
    }

    $versionData = Get-Content -LiteralPath $versionFile -Raw | ConvertFrom-Json
    $frameworkVersion = [string]$versionData.frameworkVersion
    if ([string]::IsNullOrWhiteSpace($frameworkVersion)) {
        Write-Warn "Flutter version metadata has no frameworkVersion; using the pinned executable."
        return
    }
    if ($frameworkVersion -notmatch '^3\.47(?:\.|$)') {
        throw "Flutter 3.47 is required, but '$FlutterExe' reports $frameworkVersion."
    }

    Write-Info "Flutter: $frameworkVersion (required 3.47.x)"
}

function Copy-DirectoryContents {
    param(
        [Parameter(Mandatory = $true)][string]$Source,
        [Parameter(Mandatory = $true)][string]$Destination
    )

    Assert-RequiredDirectory -Path $Source
    New-Item -ItemType Directory -Path $Destination -Force | Out-Null
    foreach ($item in @(Get-ChildItem -LiteralPath $Source -Force)) {
        Copy-Item -LiteralPath $item.FullName -Destination $Destination -Recurse -Force
    }
}

function Copy-RuntimeDirectory {
    param(
        [Parameter(Mandatory = $true)][string]$Source,
        [Parameter(Mandatory = $true)][string]$Destination
    )

    Assert-RequiredDirectory -Path $Source
    $target = Join-Path $Destination ([System.IO.Path]::GetFileName($Source))
    Copy-Item -LiteralPath $Source -Destination $target -Recurse -Force
}

function Copy-RuntimeImage {
    param(
        [Parameter(Mandatory = $true)][string]$JavaHome,
        [Parameter(Mandatory = $true)][string]$Destination
    )

    Write-Info "Copying a minimal runtime image from $JavaHome"
    New-Item -ItemType Directory -Path (Join-Path $Destination 'bin') -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $Destination 'lib') -Force | Out-Null

    $sourceBin = Join-Path $JavaHome 'bin'
    foreach ($exeName in @('java.exe', 'javaw.exe')) {
        $sourceExe = Join-Path $sourceBin $exeName
        Assert-RequiredFile -Path $sourceExe
        Copy-Item -LiteralPath $sourceExe -Destination (Join-Path $Destination 'bin') -Force
    }

    foreach ($dll in @(Get-ChildItem -LiteralPath $sourceBin -Filter '*.dll' -File -Force)) {
        Copy-Item -LiteralPath $dll.FullName -Destination (Join-Path $Destination 'bin') -Force
    }
    Copy-RuntimeDirectory `
        -Source (Join-Path $sourceBin 'server') `
        -Destination (Join-Path $Destination 'bin')

    foreach ($directoryName in @('conf', 'legal')) {
        $sourceDirectory = Join-Path $JavaHome $directoryName
        if (Test-Path -LiteralPath $sourceDirectory -PathType Container) {
            Copy-RuntimeDirectory -Source $sourceDirectory -Destination $Destination
        }
    }

    $sourceLib = Join-Path $JavaHome 'lib'
    foreach ($fileName in @('modules', 'jvm.cfg', 'classlist', 'tzdb.dat', 'tzmappings', 'jrt-fs.jar')) {
        $sourceFile = Join-Path $sourceLib $fileName
        if (Test-Path -LiteralPath $sourceFile -PathType Leaf) {
            Copy-Item -LiteralPath $sourceFile -Destination (Join-Path $Destination 'lib') -Force
        }
    }

    $sourceSecurity = Join-Path $sourceLib 'security'
    Copy-RuntimeDirectory -Source $sourceSecurity -Destination (Join-Path $Destination 'lib')

    $sourceRelease = Join-Path $JavaHome 'release'
    Assert-RequiredFile -Path $sourceRelease
    Copy-Item -LiteralPath $sourceRelease -Destination $Destination -Force
}

function New-TrimmedRuntime {
    param(
        [Parameter(Mandatory = $true)][string]$JavaHome,
        [Parameter(Mandatory = $true)][string]$Destination
    )

    $jlink = Join-Path $JavaHome 'bin\jlink.exe'
    if (Test-Path -LiteralPath $jlink -PathType Leaf) {
        $moduleList = $script:JreModules -join ','
        Write-Info "Creating a trimmed runtime with jlink: $moduleList"
        Invoke-ExternalCommand `
            -FilePath $jlink `
            -ArgumentList @(
                '--add-modules', $moduleList,
                '--output', $Destination,
                '--strip-debug',
                '--no-header-files',
                '--no-man-pages',
                '--compress=2'
            ) `
            -WorkingDirectory $JavaHome

        $jlinkJavaW = Join-Path $Destination 'bin\javaw.exe'
        $jlinkModules = Join-Path $Destination 'lib\modules'
        if (
            $script:LastExitCode -eq 0 -and
            (Test-Path -LiteralPath $jlinkJavaW -PathType Leaf) -and
            (Test-Path -LiteralPath $jlinkModules -PathType Leaf)
        ) {
            return 'jlink'
        }

        Write-Warn "jlink failed with exit code $script:LastExitCode; falling back to a copied runtime."
        if (Test-Path -LiteralPath $Destination) {
            Remove-DirectChildDirectory `
                -Path $Destination `
                -Parent (Split-Path -Parent $Destination) `
                -ExpectedLeaf 'jre'
        }
    }
    else {
        Write-Info "jlink is unavailable; copying the runtime image directly."
    }

    Copy-RuntimeImage -JavaHome $JavaHome -Destination $Destination
    return 'copy'
}

function Assert-PackagedLayout {
    param([Parameter(Mandatory = $true)][string]$PackageRoot)

    Assert-RequiredFile -Path (Join-Path $PackageRoot 'YunGet.exe')
    Assert-RequiredFile -Path (Join-Path $PackageRoot 'flutter_windows.dll')
    Assert-RequiredFile -Path (Join-Path $PackageRoot 'data\app.so')
    Assert-RequiredDirectory -Path (Join-Path $PackageRoot 'data\flutter_assets')
    Assert-RequiredFile -Path (Join-Path $PackageRoot 'host\yunget-host.jar')
    Assert-RequiredFile -Path (Join-Path $PackageRoot 'jre\bin\javaw.exe')
    Assert-RequiredFile -Path (Join-Path $PackageRoot 'jre\lib\modules')
}

$stagingPath = $null
$failure = $null
$finalTarget = $null
$distRoot = $null
$runtimeMode = $null

try {
    Write-Step 'YunGet Windows desktop packaging'

    if ([string]::IsNullOrWhiteSpace($PSScriptRoot)) {
        throw 'PSScriptRoot is unavailable; run this script from a file.'
    }

    $desktopDir = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
    $repoRoot = (Resolve-Path -LiteralPath (Join-Path $desktopDir '..')).Path
    $desktopDir = Get-NormalizedPath -Path $desktopDir
    $repoRoot = Get-NormalizedPath -Path $repoRoot

    if (-not [string]::Equals(
        [System.IO.Path]::GetDirectoryName($desktopDir),
        $repoRoot,
        [System.StringComparison]::OrdinalIgnoreCase
    )) {
        throw "Script location validation failed: '$desktopDir' is not directly under '$repoRoot'."
    }

    $distCandidate = Join-Path $desktopDir 'dist'
    New-Item -ItemType Directory -Path $distCandidate -Force | Out-Null
    $distRoot = Get-NormalizedPath -Path (Resolve-Path -LiteralPath $distCandidate).Path
    if (-not [string]::Equals(
        [System.IO.Path]::GetDirectoryName($distRoot),
        $desktopDir,
        [System.StringComparison]::OrdinalIgnoreCase
    )) {
        throw "Distribution directory validation failed: '$distRoot'."
    }

    $finalTarget = Get-NormalizedPath -Path (Join-Path $distRoot 'YunGet')
    Assert-DirectChildDirectoryPath `
        -Path $finalTarget `
        -Parent $distRoot `
        -ExpectedLeaf 'YunGet'

    $gradlew = Join-Path $repoRoot 'gradlew.bat'
    Assert-RequiredFile -Path $gradlew
    Assert-RequiredFile -Path $script:FlutterExe

    Write-Info "Workspace: $repoRoot"
    Write-Info "Output:    $finalTarget"

    Assert-FlutterVersion -FlutterExe $script:FlutterExe
    $javaHome = Resolve-JavaRuntimeHome

    Write-Step '[2/6] Building the JVM host jar'
    Invoke-ExternalCommand `
        -FilePath $gradlew `
        -ArgumentList @(':host:jar') `
        -WorkingDirectory $repoRoot
    if ($script:LastExitCode -ne 0) {
        throw "gradlew :host:jar failed with exit code $script:LastExitCode."
    }

    $hostJar = Join-Path $repoRoot 'host\build\libs\yunget-host.jar'
    Assert-RequiredFile -Path $hostJar
    Write-Info "Host jar: $hostJar"

    Write-Step '[3/6] Building the Flutter Windows release'
    Invoke-ExternalCommand `
        -FilePath $script:FlutterExe `
        -ArgumentList @('build', 'windows', '--release') `
        -WorkingDirectory $desktopDir
    if ($script:LastExitCode -ne 0) {
        throw "flutter build windows --release failed with exit code $script:LastExitCode."
    }

    $releaseDir = Join-Path $desktopDir 'build\windows\x64\runner\Release'
    Assert-RequiredDirectory -Path $releaseDir

    Write-Step '[4/6] Staging the Windows package'
    $stagingPath = Join-Path $distRoot (
        '.YunGet.staging.' + [System.Guid]::NewGuid().ToString('N')
    )
    New-Item -ItemType Directory -Path $stagingPath -Force | Out-Null
    Assert-DirectChildDirectoryPath `
        -Path $stagingPath `
        -Parent $distRoot `
        -ExpectedLeafPrefix '.YunGet.staging.'

    Copy-DirectoryContents -Source $releaseDir -Destination $stagingPath
    $hostDestination = Join-Path $stagingPath 'host'
    New-Item -ItemType Directory -Path $hostDestination -Force | Out-Null
    Copy-Item -LiteralPath $hostJar -Destination (Join-Path $hostDestination 'yunget-host.jar') -Force

    Write-Step '[5/6] Adding the Java runtime'
    $jreDestination = Join-Path $stagingPath 'jre'
    $runtimeMode = New-TrimmedRuntime -JavaHome $javaHome -Destination $jreDestination
    Write-Info "Java runtime prepared via: $runtimeMode"

    Write-Step '[6/6] Validating and installing the package'
    Assert-PackagedLayout -PackageRoot $stagingPath
    Remove-DirectChildDirectory -Path $finalTarget -Parent $distRoot -ExpectedLeaf 'YunGet'
    Move-Item -LiteralPath $stagingPath -Destination $finalTarget
    $stagingPath = $null
    Assert-PackagedLayout -PackageRoot $finalTarget
}
catch {
    $failure = $_
}
finally {
    if (
        -not [string]::IsNullOrWhiteSpace($stagingPath) -and
        -not [string]::IsNullOrWhiteSpace($distRoot) -and
        (Test-Path -LiteralPath $stagingPath)
    ) {
        try {
            Remove-DirectChildDirectory `
                -Path $stagingPath `
                -Parent $distRoot `
                -ExpectedLeafPrefix '.YunGet.staging.'
        }
        catch {
            Write-Warn "Could not remove staging directory '$stagingPath': $($_.Exception.Message)"
        }
    }
}

if ($null -ne $failure) {
    Write-Host ''
    Write-Host "BUILD FAILED: $($failure.Exception.Message)" -ForegroundColor Red
    exit 1
}

$fileCount = @(Get-ChildItem -LiteralPath $finalTarget -Recurse -File -Force).Count
$totalBytes = (Get-ChildItem -LiteralPath $finalTarget -Recurse -File -Force |
    Measure-Object -Property Length -Sum).Sum

Write-Host ''
Write-Host 'BUILD SUCCEEDED' -ForegroundColor Green
Write-Info "Output:    $finalTarget"
Write-Info "Runtime:   $runtimeMode"
Write-Info ("Size:      {0:N2} MB across {1} files" -f ($totalBytes / 1MB), $fileCount)
