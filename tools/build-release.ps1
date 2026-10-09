param([switch]$AndroidOnly)
$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path $PSScriptRoot -Parent
$releaseDirectory = Join-Path $repositoryRoot 'artifacts'
$appVersion = (Get-Content -LiteralPath (Join-Path $repositoryRoot 'desktop/package.json') -Raw | ConvertFrom-Json).version
foreach ($variable in @('SFLINK_KEYSTORE_PATH','SFLINK_KEYSTORE_PASSWORD','SFLINK_KEY_ALIAS','SFLINK_KEY_PASSWORD','JAVA_HOME')) {
    if (-not [Environment]::GetEnvironmentVariable($variable)) { throw "Configure $variable antes de compilar a release." }
}
function Assert-BuildExit {
    if ($LASTEXITCODE -ne 0) { throw 'A compilação falhou. Nenhuma release foi publicada.' }
}
New-Item -ItemType Directory -Path $releaseDirectory -Force | Out-Null
if (-not $AndroidOnly) {
    # Rust panic locations can otherwise embed the builder's personal paths.
    $pathRemap = "--remap-path-prefix=$($env:USERPROFILE)=/build"
    $env:CARGO_ENCODED_RUSTFLAGS = if ($env:CARGO_ENCODED_RUSTFLAGS) { $env:CARGO_ENCODED_RUSTFLAGS + [char]31 + $pathRemap } else { $pathRemap }
    Push-Location (Join-Path $repositoryRoot 'desktop')
    try {
        npm ci
        Assert-BuildExit
        npm run desktop:build
        Assert-BuildExit
        $targetDirectory = if ($env:CARGO_TARGET_DIR) { $env:CARGO_TARGET_DIR } else { Join-Path $repositoryRoot 'desktop/src-tauri/target' }
        $installerName = "SFLink_${appVersion}_x64-setup.exe"
        Copy-Item -LiteralPath (Join-Path $targetDirectory "release/bundle/nsis/$installerName") -Destination (Join-Path $releaseDirectory $installerName)
    } finally { Pop-Location }
}
Push-Location (Join-Path $repositoryRoot 'android')
try {
    & .\gradlew.bat :app:assembleRelease :app:lintRelease --console=plain
    Assert-BuildExit
    Copy-Item -LiteralPath 'app/build/outputs/apk/release/app-release.apk' -Destination (Join-Path $releaseDirectory "SFLink_${appVersion}_android.apk")
} finally { Pop-Location }
$checksums = Get-ChildItem -LiteralPath $releaseDirectory -File | Where-Object { $_.Name -in @("SFLink_${appVersion}_x64-setup.exe", "SFLink_${appVersion}_android.apk") } | Sort-Object Name | ForEach-Object {
    "$( (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() )  $($_.Name)"
}
[IO.File]::WriteAllText((Join-Path $releaseDirectory 'SHA256SUMS.txt'), (($checksums -join "`n") + "`n"), [Text.UTF8Encoding]::new($false))
Write-Host "Builds locais prontos em $releaseDirectory. Este script não publica nem usa GitHub Actions."
