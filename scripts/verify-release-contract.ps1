$ErrorActionPreference = 'Stop'

function ConvertFrom-Utf8Base64([string]$Value) {
    [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($Value))
}

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$readme = Get-Content -LiteralPath (Join-Path $repositoryRoot 'README.md') -Raw -Encoding UTF8
$englishReadmePath = Join-Path $repositoryRoot 'README.en.md'
if (-not (Test-Path -LiteralPath $englishReadmePath -PathType Leaf)) {
    throw 'English README is missing: README.en.md'
}
$englishReadme = Get-Content -LiteralPath $englishReadmePath -Raw -Encoding UTF8
$release = Get-Content -LiteralPath (Join-Path $repositoryRoot '.github/workflows/release.yml') -Raw -Encoding UTF8
$ci = Get-Content -LiteralPath (Join-Path $repositoryRoot '.github/workflows/ci.yml') -Raw -Encoding UTF8
$androidBuild = Get-Content -LiteralPath (Join-Path $repositoryRoot 'android/app/build.gradle.kts') -Raw -Encoding UTF8

$requiredQuickStart = @(
    (ConvertFrom-Utf8Base64 'IyMg5Zub5q2l5byA5aeL5L2/55So'),
    'https://tailscale.com/download/windows',
    'https://tailscale.com/download/android',
    'https://github.com/moyi888/codex-remote-android/releases/tag/v0.2.0-alpha.1',
    (ConvertFrom-Utf8Base64 'NC4g55SoIEFuZHJvaWQgQXBwIOaJq+aPjyBXaW5kb3dzIEFwcCDmmL7npLrnmoTkuoznu7TnoIE=')
)
foreach ($text in $requiredQuickStart) {
    if (-not $readme.Contains($text)) {
        throw "README is missing the four-step quick start entry: $text"
    }
}

$heading = [regex]::Escape($requiredQuickStart[0])
$quickStartMatch = [regex]::Match(
    $readme,
    "(?ms)^$heading\s*`$.*?(?=^##\s|\z)"
)
if (-not $quickStartMatch.Success) {
    throw 'README is missing a recognizable four-step quick start section'
}
foreach ($forbidden in @('--listen', '--advertise-url', 'projects.json')) {
    if ($quickStartMatch.Value.Contains($forbidden)) {
        throw "README quick start should not require an advanced option: $forbidden"
    }
}

foreach ($text in @(
    '[English](README.en.md)',
    'https://tailscale.com/download/windows',
    'https://tailscale.com/download/android',
    'https://github.com/moyi888/codex-remote-android/releases/tag/v0.2.0-alpha.1'
)) {
    if (-not $readme.Contains($text)) {
        throw "Chinese README is missing bilingual/download contract: $text"
    }
}

foreach ($text in @(
    '[简体中文](README.md)',
    '## Get started in four steps',
    'https://tailscale.com/download/windows',
    'https://tailscale.com/download/android',
    'https://github.com/moyi888/codex-remote-android/releases/tag/v0.2.0-alpha.1'
)) {
    if (-not $englishReadme.Contains($text)) {
        throw "English README is missing bilingual/download contract: $text"
    }
}

foreach ($text in @(
    'name: Build Windows App',
    'go build -ldflags "-H=windowsgui -X main.version=$version" -o "codex-remote-windows-$version.exe" ./cmd/codex-remote',
    'go build -ldflags "-X main.version=$version" -o "codex-remote-cli-windows-$version.exe" ./cmd/codex-remote',
    'name: windows-app',
    'bridge/codex-remote-windows-*.exe',
    'bridge/codex-remote-cli-windows-*.exe'
)) {
    if (-not $release.Contains($text)) {
        throw "Release workflow is missing the Windows App contract: $text"
    }
}

foreach ($text in @(
    'bridge-race:',
    'go test -race ./...'
)) {
    if (-not $ci.Contains($text)) {
        throw "CI workflow is missing the Bridge race contract: $text"
    }
}

foreach ($text in @(
    'versionCode = 2',
    'versionName = "0.2.0-alpha.1"'
)) {
    if (-not $androidBuild.Contains($text)) {
        throw "Android release metadata is missing: $text"
    }
}

Write-Output 'Release/README contract passed.'
