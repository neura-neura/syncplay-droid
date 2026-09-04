[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$FixturePath,

    [ValidateNotNullOrEmpty()]
    [ValidatePattern('^\S+$')]
    [string]$Serial,

    [ValidatePattern('^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)*$')]
    [string]$PackageName = "dev.neura.syncplay",

    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]*\.mkv$')]
    [string]$FixtureName = "mpv-fixture.mkv"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$fixture = Get-Item -LiteralPath $FixturePath -ErrorAction Stop
if ($fixture.PSIsContainer) {
    throw "FixturePath must point to a file, not a directory."
}
if ($fixture.Length -le 0) {
    throw "FixturePath is empty: $($fixture.FullName)"
}
if ([IO.Path]::GetExtension($fixture.Name) -ine ".mkv") {
    throw "FixturePath must have an .mkv extension: $($fixture.Name)"
}

$adbCommand = Get-Command adb -CommandType Application -ErrorAction SilentlyContinue
if ($null -eq $adbCommand) {
    throw "adb was not found on PATH. Install Android platform-tools or add them to PATH."
}
$script:adbPath = $adbCommand.Path

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    & $script:adbPath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed (exit $LASTEXITCODE): adb $($Arguments -join ' ')"
    }
}

function Invoke-AdbOutput {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    $lines = @(& $script:adbPath @Arguments 2>&1)
    $exitCode = $LASTEXITCODE
    $text = ($lines | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine
    if ($exitCode -ne 0) {
        $detail = $text.Trim()
        if ([string]::IsNullOrEmpty($detail)) {
            $detail = "no diagnostic output"
        }
        throw "adb failed (exit $exitCode): $detail"
    }
    return $text
}

if ([string]::IsNullOrWhiteSpace($Serial)) {
    $deviceLines = @(& $script:adbPath devices)
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to list Android devices with adb."
    }
    $readyDevices = @(
        foreach ($line in $deviceLines) {
            if ($line -match '^(?<id>\S+)\s+device(?:\s|$)') {
                $Matches["id"]
            }
        }
    )
    if ($readyDevices.Count -ne 1) {
        $found = if ($readyDevices.Count -eq 0) { "none" } else { $readyDevices -join ", " }
        throw "Expected exactly one ready adb device; found $found. Pass -Serial when more than one device is connected."
    }
    $Serial = $readyDevices[0]
} else {
    $state = (Invoke-AdbOutput @("-s", $Serial, "get-state")).Trim()
    if ($state -ne "device") {
        throw "adb device '$Serial' is not ready (state: '$state')."
    }
}

$packagePath = Invoke-AdbOutput @("-s", $Serial, "shell", "pm", "path", $PackageName)
if ($packagePath -notmatch '(?m)^package:') {
    throw "Package '$PackageName' is not installed on adb device '$Serial'. Install the debug APK first."
}

# run-as is deliberately required: it verifies the debug package and keeps the fixture in the
# app-private files directory rather than making media readable through shared device storage.
Invoke-AdbOutput @("-s", $Serial, "shell", "run-as", $PackageName, "id") | Out-Null

$token = [Guid]::NewGuid().ToString("N")
$remoteUpload = "/data/local/tmp/syncplay-mpv-fixture-$token.mkv"
$remoteStage = "files/.$FixtureName.$token.tmp"
$remoteDestination = "files/$FixtureName"

try {
    Write-Host "Pushing $($fixture.Name) to adb device $Serial..."
    Invoke-Adb @("-s", $Serial, "push", $fixture.FullName, $remoteUpload)

    Invoke-Adb @("-s", $Serial, "shell", "run-as", $PackageName, "mkdir", "-p", "files")
    Invoke-Adb @("-s", $Serial, "shell", "run-as", $PackageName, "cp", $remoteUpload, $remoteStage)

    $stagedSizeText = (Invoke-AdbOutput @(
            "-s", $Serial, "shell", "run-as", $PackageName, "stat", "-c", "%s", $remoteStage
        )).Trim()
    [long]$stagedSize = 0
    if (-not [long]::TryParse(
            $stagedSizeText,
            [Globalization.NumberStyles]::Integer,
            [Globalization.CultureInfo]::InvariantCulture,
            [ref]$stagedSize
        ) -or $stagedSize -ne $fixture.Length) {
        throw "Fixture size verification failed (local $($fixture.Length) bytes, device $stagedSizeText bytes)."
    }

    # Rename only after the size check so a failed upload cannot leave a partial fixture that the
    # instrumentation tests mistake for a usable media file.
    Invoke-Adb @("-s", $Serial, "shell", "run-as", $PackageName, "mv", "-f", $remoteStage, $remoteDestination)
    $finalSizeText = (Invoke-AdbOutput @(
            "-s", $Serial, "shell", "run-as", $PackageName, "stat", "-c", "%s", $remoteDestination
        )).Trim()
    if ($finalSizeText -ne $fixture.Length.ToString([Globalization.CultureInfo]::InvariantCulture)) {
        throw "Final fixture size verification failed (local $($fixture.Length) bytes, device $finalSizeText bytes)."
    }

    Write-Host "Provisioned files/$FixtureName in the private files directory for $PackageName."
} finally {
    # The upload lives outside the app sandbox and is always removed, including when staging or
    # verification fails. The staged app-private path is also safe to remove because it is unique.
    try {
        Invoke-Adb @("-s", $Serial, "shell", "rm", "-f", $remoteUpload)
    } catch {
        Write-Warning "Could not remove temporary adb upload '$remoteUpload': $($_.Exception.Message)"
    }
    try {
        Invoke-Adb @("-s", $Serial, "shell", "run-as", $PackageName, "rm", "-f", $remoteStage)
    } catch {
        Write-Warning "Could not remove temporary app-private stage '$remoteStage': $($_.Exception.Message)"
    }
}
