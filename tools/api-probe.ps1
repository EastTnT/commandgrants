# Reusable javap probe for the 26.2 client jar.
# Usage:  .\tools\api-probe.ps1 -Classes a,b,c [-Pattern regex] [-Private] [-Depth n]
param(
    [Parameter(Mandatory = $true)][string[]]$Classes,
    [string]$Pattern,
    [switch]$Private,
    [int]$Depth = 200
)
$jdk = "$env:APPDATA\.minecraft\runtime\java-runtime-epsilon\bin"
$base = Split-Path -Parent $PSScriptRoot
$jar = "$base\.cache\mc262-client.jar"
$flags = if ($Private) { @("-p") } else { @() }
foreach ($c in $Classes) {
    Write-Output "=== $c ==="
    $out = & "$jdk\javap.exe" @flags -cp $jar $c 2>&1
    if ($Pattern) { $out = $out | Select-String -Pattern $Pattern }
    $out | Select-Object -First $Depth
}
