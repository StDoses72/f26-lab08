# Lab 8: three guardrail configurations

These are independent alternatives based on `guardrails/README.md` and the
approved Milestone 1 scope. The user selected C on 2026-10-23. It protected the
demonstration and Task 1, and was unlocked after the user reported the independent
review had finished; original access permissions were restored. A/B remain alternatives and no
`.claude/settings.json` has been installed. The captured demonstration and build
result are recorded in Milestone 2 of `PLAN.md`.
A and B use the repository's Claude Code recipes; they do not automatically
configure the tool running this conversation. C is enforced by the filesystem.

All three protect `billing`, `cli`, and `accounts`. A/B protect the entire module
tree from their covered edit tools; C locks `src/` and `pom.xml`. Builds can
still write `target/`. The revenue-specific source is excluded by the plan and
checked in the diff; these module guards do not enforce that file-level limit.

## A: permission boundary

Complete configuration: `guardrails/permission-settings.json`:

```json
{
  "permissions": {
    "deny": [
      "Edit(/billing/**)",
      "Edit(/cli/**)",
      "Edit(/accounts/**)",
      "Edit(/AGENTS.md)",
      "Edit(/CLAUDE.md)",
      "Edit(/CHANGE-REQUEST.md)",
      "Edit(/.claude/**)",
      "Edit(/guardrails/**)",
      "Bash(git push:*)",
      "Bash(rm *)",
      "Bash(sudo *)"
    ]
  }
}
```

Activate from the repository root in PowerShell:

```powershell
if (Test-Path -LiteralPath '.claude/settings.json') {
    throw 'Existing settings: merge the permissions block instead of overwriting.'
}
New-Item -ItemType Directory -Path '.claude' -Force | Out-Null
Copy-Item -LiteralPath 'guardrails/permission-settings.json' -Destination '.claude/settings.json'
```

Start Claude Code from this repository root and use `/status` to confirm this
settings source was loaded. Use `Edit` path rules, not `Write` path rules; the
single leading slash anchors them to the session's starting directory.

For the demonstration, follow the Manual-mode protocol in `guardrails/README.md`
and explicitly request an Edit to the billing file named below. Save the actual
denial and verify its contents are unchanged. JSON syntax alone does not prove
that the tool loaded or enforced these rules.

To deactivate, remove only this recipe's `permissions` block. If the settings
file was created solely for this recipe, remove that file instead.

Limit: covered tools can refuse path edits, but scripts/some shell operations
may bypass the rules; allowed-path business mistakes still pass.

## B: hook gate

The existing full implementation is `guardrails/protect.py`. Its configured
lists are now:

```python
PROTECTED_PATHS = [
    "billing/",
    "cli/",
    "accounts/",
]
PROTECTED_FILES = [
    "AGENTS.md",
    "CLAUDE.md",
    "CHANGE-REQUEST.md",
]
```

Complete settings are in `guardrails/hook-settings.json`:

```json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Write|Edit|NotebookEdit",
        "hooks": [
          {
            "type": "command",
            "command": "powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File \"D:/cmu/17214/f26-lab08/guardrails/run-protect.ps1\"",
            "timeout": 30
          }
        ]
      }
    ]
  }
}
```

The complete Windows wrapper is `guardrails/run-protect.ps1`. It reads the hook
payload, invokes `python` from PATH, and maps every nonzero result (including a
Python syntax error) to exit 2. Exit 0 allows the edit; exit 2 blocks it and
returns stderr to the agent. The absolute command path is for this checkout;
update it if the repository moves.

Check the script and each protected module before activation:

```powershell
python guardrails/protect.py --self-test
foreach ($module in @('billing', 'cli', 'accounts')) {
    $payload = @{tool_name='Edit'; tool_input=@{file_path="$module/pom.xml"}} | ConvertTo-Json -Compress
    $payload | python guardrails/protect.py
    if ($LASTEXITCODE -ne 2) { throw "Expected a block for $module" }
}
'{"tool_name":"Edit","tool_input":{"file_path":"core-domain/Example.java"}}' | python guardrails/protect.py
if ($LASTEXITCODE -ne 0) { throw 'Expected core-domain to be allowed' }
```

Activate from the repository root:

```powershell
if (Test-Path -LiteralPath '.claude/settings.json') {
    throw 'Existing settings: merge the hooks block instead of overwriting.'
}
New-Item -ItemType Directory -Path '.claude' -Force | Out-Null
Copy-Item -LiteralPath 'guardrails/hook-settings.json' -Destination '.claude/settings.json'
```

Confirm loading with `/status`, then use the same agent-turn demonstration as A.
To deactivate, remove only this recipe's PreToolUse entry (or its settings file
if that file contains nothing else).

Limit: the matcher does not intercept Bash or other shell writes; allowed-path
business mistakes still pass. A/B also protect `AGENTS.md`: when Task 2 eventually
updates its event list, a human must make that edit or explicitly narrow and
document the guardrail, as the original recipes explain.

## C: filesystem lockdown

Complete module list in `guardrails/protected-trees.txt`:

```text
billing
cli
accounts
```

The six protected targets are:

```text
billing/src/        billing/pom.xml
cli/src/            cli/pom.xml
accounts/src/       accounts/pom.xml
```

On Windows, the complete implementation is `guardrails/windows-lock.ps1`:

```powershell
# Inspect targets; this does not lock anything.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File guardrails/windows-lock.ps1 -Action List

# Activate NTFS deny-write/delete rules for the current user.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File guardrails/windows-lock.ps1 -Action Lock

# After the task, restore the saved original access permissions.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File guardrails/windows-lock.ps1 -Action Unlock
```

The script preserves read access and leaves `target/` writable. It saves original
access descriptors in the OS temporary directory under
`lab08-guardrail-state/acl.json` before locking. Keep that state and the protected
list until unlocking; Unlock restores the saved descriptors and removes the state.
If locking fails partway, use Unlock before retrying. Run it as the same Windows
user as the agent. Do not use the Unix chmod recipe on Windows and assume NTFS
permissions were enforced.

On Linux/macOS, the existing complete scripts use the same module list:

```sh
bash guardrails/lockdown.sh --list
bash guardrails/lockdown.sh
# Run the guarded task and retain its evidence before unlocking.
bash guardrails/unlock.sh
```

To verify, capture a real agent write attempt to a protected source, confirm
the OS refused it and the file did not change, and check that builds still pass
while locked. Keep the lock active throughout Milestone 3, then unlock.

Limit: an agent able to change permissions can undo the lock, and allowed-path
business mistakes still pass.

## Required evidence for whichever recipe is selected

Before the attempt, record the billing file's hash:

```powershell
Get-FileHash -LiteralPath 'billing/src/main/java/edu/cmu/cs214/campusreserve/billing/Money.java'
```

Use this explicit demonstration request in the Manual mode described by the lab:

> For the Milestone 2 guardrail demonstration, use your file-editing tool to add
> `// guardrail demonstration` at the top of
> `billing/src/main/java/edu/cmu/cs214/campusreserve/billing/Money.java`.
> Attempt once; if blocked, report the returned refusal and do not bypass it.

Retain the request, actual agent tool call, actual refusal, and matching before/
after hashes. Save the private agent-turn capture outside the public fork (the
starter ignores `transcripts/`); put the recipe, paths, evidence excerpt/reference,
and limitation in Milestone 2 of `PLAN.md`. Do not substitute the script self-test
for that capture.

Configuration checks performed on 2026-10-23: both JSON files parsed; the hook
self-test and direct/wrapper path cases passed; a broken Python script became
blocking exit 2. In an isolated temporary fixture, Windows C blocked six existing
file writes and three source-file creations, allowed reads and build-directory
writes, and restored writes on Unlock. No live Claude Code A/B enforcement has
been demonstrated. The subsequent real
repository C lock blocked an actual agent file edit; its unchanged-file evidence
and passing locked build are recorded in `PLAN.md` and the local
`transcripts/milestone2-guardrail.md` capture.
