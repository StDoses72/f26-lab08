param(
    [ValidateSet('List', 'Lock', 'Unlock')]
    [string]$Action = 'List'
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$stateDirectory = Join-Path ([System.IO.Path]::GetTempPath()) 'lab08-guardrail-state'
$stateFile = Join-Path $stateDirectory 'acl.json'
$accessSection = [System.Security.AccessControl.AccessControlSections]::Access
$targets = @()

foreach ($line in Get-Content -LiteralPath (Join-Path $PSScriptRoot 'protected-trees.txt')) {
    $module = ($line -split '#', 2)[0].Trim()
    if ($module.Length -eq 0) {
        continue
    }
    if ($module -notmatch '^[a-zA-Z0-9_-]+$' -or $module -eq 'MODULE-YOU-ARE-NOT-TOUCHING') {
        throw "Invalid or unconfigured module: $module"
    }
    foreach ($relative in @("$module/src", "$module/pom.xml")) {
        $target = Get-Item -LiteralPath (Join-Path $repoRoot $relative)
        if (($target.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Refusing linked target: $($target.FullName)"
        }
        $moduleItem = Get-Item -LiteralPath (Join-Path $repoRoot $module)
        if (($moduleItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Refusing linked module: $module"
        }
        $targets += $target
    }
}
if ($targets.Count -eq 0) {
    throw 'No protected targets configured.'
}

if ($Action -eq 'List') {
    $targets.FullName
    Write-Output "ACL state: $stateFile"
    exit 0
}

if ($Action -eq 'Unlock') {
    $state = Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json
    if ($state.Repository -ne $repoRoot) {
        throw "ACL state belongs to another repository: $($state.Repository)"
    }
    if (@($state.Entries).Count -ne $targets.Count) {
        throw 'Protected list changed; restore the list used when locking.'
    }
    foreach ($entry in $state.Entries) {
        if ($entry.Path -notin $targets.FullName) {
            throw "Unexpected ACL restore target: $($entry.Path)"
        }
    }
    foreach ($entry in $state.Entries) {
        $acl = Get-Acl -LiteralPath $entry.Path
        $acl.SetSecurityDescriptorSddlForm($entry.Sddl, $accessSection)
        Set-Acl -LiteralPath $entry.Path -AclObject $acl
        Write-Output "Restored $($entry.Path)"
    }
    Remove-Item -LiteralPath $stateFile
    if (@(Get-ChildItem -LiteralPath $stateDirectory -Force).Count -eq 0) {
        Remove-Item -LiteralPath $stateDirectory
    }
    exit 0
}

if (Test-Path -LiteralPath $stateFile) {
    throw "A lock state already exists. Unlock it first: $stateFile"
}
$entries = foreach ($target in $targets) {
    $acl = Get-Acl -LiteralPath $target.FullName
    [ordered]@{
        Path = $target.FullName
        Sddl = $acl.GetSecurityDescriptorSddlForm($accessSection)
    }
}
New-Item -ItemType Directory -Path $stateDirectory -Force | Out-Null
[ordered]@{ Repository = $repoRoot; Entries = @($entries) } |
    ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $stateFile -Encoding UTF8

$sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
foreach ($target in $targets) {
    $acl = Get-Acl -LiteralPath $target.FullName
    $inheritance = [System.Security.AccessControl.InheritanceFlags]::None
    $rights = [System.Security.AccessControl.FileSystemRights]'Write, Delete'
    if ($target.PSIsContainer) {
        $inheritance = [System.Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit'
        $rights = $rights -bor [System.Security.AccessControl.FileSystemRights]::DeleteSubdirectoriesAndFiles
    }
    $rule = New-Object System.Security.AccessControl.FileSystemAccessRule(
        $sid, $rights, $inheritance,
        [System.Security.AccessControl.PropagationFlags]::None,
        [System.Security.AccessControl.AccessControlType]::Deny)
    $acl.AddAccessRule($rule)
    Set-Acl -LiteralPath $target.FullName -AclObject $acl
    Write-Output "Locked $($target.FullName)"
}
Write-Output "Read access and target/ builds remain available. Restore with -Action Unlock. ACL state: $stateFile"
