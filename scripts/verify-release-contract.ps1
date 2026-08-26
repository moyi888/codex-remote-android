$ErrorActionPreference = 'Stop'

function ConvertFrom-Utf8Base64([string]$Value) {
    [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($Value))
}

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$readme = Get-Content -LiteralPath (Join-Path $repositoryRoot 'README.md') -Raw -Encoding UTF8
$release = Get-Content -LiteralPath (Join-Path $repositoryRoot '.github/workflows/release.yml') -Raw -Encoding UTF8
$ci = Get-Content -LiteralPath (Join-Path $repositoryRoot '.github/workflows/ci.yml') -Raw -Encoding UTF8

$requiredQuickStart = @(
    (ConvertFrom-Utf8Base64 'IyMg5Zub5q2l5byA5aeL5L2/55So'),
    (ConvertFrom-Utf8Base64 'MS4g5ZyoIFdpbmRvd3Mg5ZKMIEFuZHJvaWQg5a6J6KOF5bm255m75b2VIFRhaWxzY2FsZQ=='),
    (ConvertFrom-Utf8Base64 'Mi4g5omT5byAIFdpbmRvd3MgQXBw'),
    (ConvertFrom-Utf8Base64 'My4g5a6J6KOFIEFuZHJvaWQgQXBw'),
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

Write-Output 'Release/README contract passed.'
