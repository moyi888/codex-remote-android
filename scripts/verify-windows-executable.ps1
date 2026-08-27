param(
    [Parameter(Mandatory = $true)]
    [string]$Path,

    [Parameter(Mandatory = $true)]
    [ValidateSet('WindowsGui', 'Console')]
    [string]$ExpectedSubsystem
)

$ErrorActionPreference = 'Stop'

$resolvedPath = (Resolve-Path -LiteralPath $Path).Path
$image = [IO.File]::ReadAllBytes($resolvedPath)
if ($image.Length -lt 256 -or $image[0] -ne 0x4D -or $image[1] -ne 0x5A) {
    throw "Not a valid PE executable: $resolvedPath"
}

$peOffset = [BitConverter]::ToInt32($image, 0x3C)
if ($peOffset -lt 0 -or $peOffset + 94 -gt $image.Length -or
    $image[$peOffset] -ne 0x50 -or $image[$peOffset + 1] -ne 0x45 -or
    $image[$peOffset + 2] -ne 0 -or $image[$peOffset + 3] -ne 0) {
    throw "Not a valid PE executable: $resolvedPath"
}

$subsystem = [BitConverter]::ToUInt16($image, $peOffset + 24 + 68)
$expectedSubsystemValue = if ($ExpectedSubsystem -eq 'WindowsGui') { 2 } else { 3 }
if ($subsystem -ne $expectedSubsystemValue) {
    throw "Unexpected PE subsystem for ${resolvedPath}: expected $expectedSubsystemValue, got $subsystem"
}

if (-not ('CodexRemote.WindowsResourceReader' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

namespace CodexRemote {
    public static class WindowsResourceReader {
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        public static extern IntPtr LoadLibraryEx(string fileName, IntPtr file, uint flags);

        [DllImport("kernel32.dll", SetLastError = true)]
        public static extern IntPtr FindResource(IntPtr module, IntPtr name, IntPtr type);

        [DllImport("kernel32.dll", SetLastError = true)]
        public static extern uint SizeofResource(IntPtr module, IntPtr resourceInfo);

        [DllImport("kernel32.dll", SetLastError = true)]
        public static extern IntPtr LoadResource(IntPtr module, IntPtr resourceInfo);

        [DllImport("kernel32.dll", SetLastError = true)]
        public static extern IntPtr LockResource(IntPtr resourceData);

        [DllImport("kernel32.dll")]
        [return: MarshalAs(UnmanagedType.Bool)]
        public static extern bool FreeLibrary(IntPtr module);
    }
}
'@
}

$loadLibraryAsDataFile = 0x00000002
$manifestResourceType = [IntPtr]24
$manifestResourceName = [IntPtr]1
$module = [CodexRemote.WindowsResourceReader]::LoadLibraryEx(
    $resolvedPath,
    [IntPtr]::Zero,
    $loadLibraryAsDataFile
)
if ($module -eq [IntPtr]::Zero) {
    throw "Unable to load executable resources: $resolvedPath"
}

try {
    $resourceInfo = [CodexRemote.WindowsResourceReader]::FindResource(
        $module,
        $manifestResourceName,
        $manifestResourceType
    )
    if ($resourceInfo -eq [IntPtr]::Zero) {
        throw "Embedded application manifest RT_MANIFEST #1 is missing: $resolvedPath"
    }

    $resourceSize = [CodexRemote.WindowsResourceReader]::SizeofResource($module, $resourceInfo)
    $resourceData = [CodexRemote.WindowsResourceReader]::LoadResource($module, $resourceInfo)
    $resourcePointer = [CodexRemote.WindowsResourceReader]::LockResource($resourceData)
    if ($resourceSize -eq 0 -or $resourceData -eq [IntPtr]::Zero -or $resourcePointer -eq [IntPtr]::Zero) {
        throw "Unable to read embedded application manifest: $resolvedPath"
    }

    $manifestBytes = New-Object byte[] $resourceSize
    [Runtime.InteropServices.Marshal]::Copy($resourcePointer, $manifestBytes, 0, $resourceSize)
    $manifest = [Text.Encoding]::UTF8.GetString($manifestBytes)
    foreach ($text in @(
        'Microsoft.Windows.Common-Controls',
        'version="6.0.0.0"',
        'requestedExecutionLevel level="asInvoker" uiAccess="false"'
    )) {
        if (-not $manifest.Contains($text)) {
            throw "Embedded application manifest is missing required metadata: $text"
        }
    }
}
finally {
    [void][CodexRemote.WindowsResourceReader]::FreeLibrary($module)
}

Write-Output "Windows executable contract passed: $resolvedPath"
