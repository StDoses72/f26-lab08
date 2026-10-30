# PLAN.md

One section per milestone. Fill them in order, with the plan before you run
anything, the guardrail before you run a task, and the run after.

Keep it short and specific. Point at modules, gates, and file paths.

---

## Milestone 1: The plan

Written on 2026-10-23 before any implementation task. The starter baseline was
subsequently verified on the same date, before implementation (results below).
For Milestone 3, run exactly Task 1, after configuring and demonstrating the
Milestone 2 guardrail. Tasks 2-4 describe the rest of the requested feature.

**Baseline evidence (2026-10-23):** Java 25.0.4.1, Maven 3.9.16, Python 3.13.11,
pytest 9.1.1 in a disposable virtual environment. `mvn -B test` passed all 418
Java tests: core-domain 167, storage 63, notifications 41, accounts 19, billing
19, http-api 78, cli 31; each module reported zero failures/errors/skips.

```text
[INFO] BUILD SUCCESS
[INFO] Total time:  12.121 s
[INFO] Finished at: 2026-10-23T11:50:14-04:00
============================= 137 passed in 0.21s =============================
```

The Python command was the virtual environment's `python -m pytest`, run from
`analytics/`, with `--basetemp=<task-temp>/pytest-tmp` and
`-o cache_dir=<task-temp>/pytest-cache`. The first run had 135 passes and two
PermissionErrors accessing the pre-existing shared `pytest-of-steph` directory;
the isolated rerun above passed all 137 without source/test edits. Temporary
environments, logs, and generated build directories are removed after verification.

### Requirement coverage

All requirement numbers below refer to the original numbered clauses in
`CHANGE-REQUEST.md`: clause 4 is waitlist promotion; clause 5 is history.

| Task | Requirements delivered |
|---|---|
| 1. History model | 5: immutable old/new details and timestamps, ordered history, no entry for identical details, and preservation on cancellation. Service integration and HTTP reads follow in Tasks 2 and 4. |
| 2. Modification and notifications | 1: ownership/timing; 2: conflict re-check and unchanged state on rejection; 3: request rules; 4: FIFO promotion; 5: append history on real changes and suppress all no-op side effects; 6: old/new owner notice and existing promotion notices; 8: define/publish the modification event; 9: update both event-type lists. |
| 3. Event log and utilization | 8: serialize/read `booking-modified` and count only the current interval through the shared ledger. Preserve the change request's revenue-report exclusions. |
| 4. HTTP and history reads | 7: owner-only PATCH; 5: history on existing owner/admin booking reads; 9: update the HTTP surface table. Verify requirements 1-4 through the HTTP boundary as well. |

### Design decisions

- Keep the booking ID, room, owner, creation time, and existing invoice unchanged.
  Retain AGENTS.md rule 2's whole-minute UTC, half-open, single-day intervals.
- Store history on `Booking` as an immutable, ordered list. Each entry contains
  the old/new intervals, old/new attendee counts, and modification time. Preserve
  existing construction and accessors; cancellation retains history. A real
  change appends one entry; identical details append none.
- Reuse `BookingRules.checkRequest` and `ConflictChecker`, filtering out the
  current booking by ID for both room and owner checks. Do not reuse the creation
  path's booking-cap check or its fallback to joining the waitlist.
- Use a modification result with success/rejection, the booking, and promotions.
  Reuse existing `RejectionReason` codes. Reject before any store write, history
  append, event, or promotion. Check owner and upcoming/active status even for a
  no-op. A valid no-op succeeds without history, events, notices, or promotions.
- Emit `BookingModifiedEvent` with old/new details after saving a real change;
  reuse `BookingService`'s FIFO promotion pass only when the interval changes.
  Notifications remain fire and forget (AGENTS.md rules 4 and 5).
- PATCH accepts time and/or attendee changes, preserving omitted fields; room
  changes are rejected. Return 200 on success (including a no-op), 401 for missing
  or invalid authentication, 403 for another owner, 404 for a missing booking,
  409 for conflicts or an inactive/started/cancelled booking, and 400 for invalid
  input/rule violations, through the existing auth and error-mapping paths.
  Existing booking reads expose history to owners and administrators.
- `storage` writes the new event's current details using `EventLogFormat`;
  `analytics` updates the existing ledger record without resetting its creation,
  promotion, or cancellation metadata. Both reports continue using that one fold
  (AGENTS.md rule 6); do not add a JSON dependency or a separate revenue reader.

### Task 1: Retain immutable booking modification history

**Behavior and scope:** `core-domain` only; extend `core/domain/Booking.java`,
add `core/domain/BookingModification.java`, and add a new
`core/domain/BookingHistoryTest.java` under the matching test package. Provide a
pure copy operation for recording changed details, retaining identity and prior
snapshots; service authorization and side effects belong to Task 2. No event,
service, port, or other module changes. Depends on nothing.

**Checkpoint:** `mvn -B -pl core-domain -am test` must report BUILD SUCCESS and
zero failures/errors. New tests demonstrate empty initial history, exact old/new
values and supplied timestamp, two changes in order, no entry for identical
details, immutable history (including no alias to a caller's mutable list),
unchanged prior booking snapshots, unchanged identity/creation time, and retained
history on cancellation. Review the diff for this scope and no shipped-test edits.

**Gate blind spot:** Correct entity behavior does not prove that the future
service excludes the booking itself from conflict checks or records each change.

### Task 2: Modify bookings safely and notify their owners

**Behavior and scope:** Depends on Task 1. `core-domain` owns the modification
result/event, `BookingService`, and any necessary changes in `BookingRules` or
`ConflictChecker`; `notifications` owns kind mapping, template registration,
old/new message content, and new tests. Update only the event-type lists in
`AGENTS.md` and `ARCHITECTURE.md`. Keep new events and their notification handlers
together because the existing sealed-event switches must remain exhaustive.

**Checkpoint:** `mvn -B -pl notifications -am test` must pass both owning modules
with new tests. Demonstrate shorten/extend/move, attendee-only changes, a move
overlapping its own old slot, back-to-back intervals, and modification at the
booking cap. Test every request rule, ownership, start-time boundary,
cancellation, and other-room owner conflicts; rejected calls leave booking,
history, waitlist, and recorded events unchanged. Test FIFO promotion against
both calendars, no promotion pass for attendee-only/no-op changes, and interval
extension without an inappropriate promotion. A real change has one history
entry and an owner notice containing old/new times and counts; no-op has neither
event nor notice, and publisher failure does not undo success. Audit changed
production files and all conflict/waitlist call sites for rules 3 and 4.

**Gate blind spot:** These tests can pass while the HTTP adapter supplies the
wrong owner or replaces an omitted PATCH field with a default.

### Task 3: Log modifications and count only the current booked interval

**Behavior and scope:** Depends on Task 2. `storage/EventLogFormat.java`,
`analytics/campusreserve_analytics/events.py`, utilization code only if needed,
and new tests in both modules. Register `booking-modified`, serialize the current
details, and update the shared ledger in place. Review the event-list updates
from Task 2 against the actual wire format (AGENTS.md rule 6).

**Checkpoint:** `mvn -B -pl storage -am test` from the root and `pytest` from
`analytics/` must both pass. New tests cover Java serialization, Python parsing,
two successive modifications, attendee-only changes, moving to another UTC day,
and cancellation after modification. Utilization counts the latest interval once;
ledger creation/promotion/cancellation metadata survives. Also feed a new Java
serialization test's captured JSONL into a new Python test and check its ledger
and utilization result, so independently invented fixtures cannot hide a wire
mismatch. Revenue keeps reading the same ledger without excluding modified IDs.

**Gate blind spot:** A short sample log can miss a longer sequence, such as a
waitlist promotion followed by several modifications and eventual cancellation.

### Task 4: Expose owner-only PATCH and history on existing reads

**Behavior and scope:** Depends on Tasks 1-3. `http-api` routing, booking handler,
input parsing, JSON views, existing error mapping if needed, and new endpoint
tests. Add `PATCH /bookings/{bookingId}` to `ARCHITECTURE.md`'s HTTP table. Reuse
accounts authorization without changing `accounts` or adding module dependencies.

**Checkpoint:** `mvn -B -pl http-api -am test` must pass. New HTTP tests cover
time-only, attendee-only, combined, omitted-field preservation and no-op requests;
malformed input, attempted room changes, unauthenticated/non-owner access
(including a non-owner administrator), missing records, conflicts, started and
cancelled bookings, and owner/admin history reads. Assert status, current details,
history and unchanged state after rejection. Finish with `mvn -B clean test` and
the Python gate; repeat the rules 3/4 diff audit before declaring the feature done.

**Gate blind spot:** HTTP responses do not expose the notification outbox, so
only Task 2's notification tests can establish correct old/new message content.

**AGENTS.md rules 3 and 4:** Task 2's checkpoint combines tests with source review
to catch duplicated overlap predicates and waitlist writes outside
`BookingService`; Task 4 repeats that review on the completed change. Green
behavioral tests alone cannot prove either structural rule.

### Out of scope, with reasons

- `billing/`: the change request freezes the original price and forbids
  repricing, replacing, or voiding invoices.
- `cli/`: the change request explicitly requires no CLI changes or new commands.
- `accounts/`: the change request explicitly reuses existing tokens and ownership
  checks; account management does not change.
- Within `analytics/`, `revenue.py` and revenue-specific reading paths are out of
  scope: the change request accepts disagreement with frozen invoices and forbids
  filtering modified bookings or splitting the shared ledger.
- Moving bookings between rooms, new history endpoints, new module dependencies,
  and JSON libraries are excluded by the change request and AGENTS.md rules 1/6.
- Shipped tests are never edited or deleted (AGENTS.md rule 7). Add new tests in
  each owning module. No unrelated code, build, or CI changes.

---

## Milestone 2: The guardrail

**The recipe:** C, filesystem lockdown, selected by the user on 2026-10-23.
`guardrails/protected-trees.txt` lists `billing`, `cli`, and `accounts` from the
Milestone 1 exclusions. On Windows, `guardrails/windows-lock.ps1 -Action Lock`
denies the current user's writes/deletes on each module's `src/` (inherited by
descendants) and `pom.xml`. Read access and `target/` writes remain available.
The real repository was locked for the demonstration and Task 1, then unlocked
after the student reported that the independent review had finished. A/B settings
were not activated.

**What it cannot catch:** The owner can undo the ACL lock, and edits in allowed
trees can still implement incorrect or out-of-scope behavior; audit the diff.

**The captured block:** Local agent-turn capture:
`transcripts/milestone2-guardrail.md` (ignored by Git, retained for recitation).
It includes the user's selection, the agent's announcement of a single comment
insertion attempt, the exact `tools.apply_patch` payload sent via
`functions.exec`, the returned refusal, hashes, and the locked-build result.
The actual file-editing response was:

```text
Exit code: 1
Wall time: 0 seconds
Output:
Failed to write file D:\cmu\17214\f26-lab08\billing\src\main\java\edu\cmu\cs214\campusreserve\billing\Money.java
```

This was the OS denying a direct file edit, not a hook return code or a simulated
payload check. The agent did not retry or bypass the lock. This session has no
Claude Code Manual-mode selector; the explicit direct editing call follows the
recipe's intent of making the file-edit attempt visible instead of using a shell.

**Unchanged-file evidence:** Before and after SHA-256 for `Money.java` both equal
`1979C5EC82AA3F87E3980A5E681D83A11189886C6B1BD03C2F97F87669ADDE6C`.
`git diff --exit-code -- billing cli accounts` exited 0.

**Build while locked:** `mvn -B test` passed all 418 Java tests with no failures,
errors, or skips (module counts match the baseline). Its actual result lines:

```text
[INFO] BUILD SUCCESS
[INFO] Total time:  9.613 s
[INFO] Finished at: 2026-10-23T12:14:48-04:00
```

**Lifecycle:** The lock stayed active for Task 1 and its green builds. Original
access permissions were stored temporarily at
`C:\Users\steph\AppData\Local\Temp\lab08-guardrail-state\acl.json`.
After the student reported that the independent review had finished,
`powershell.exe -NoProfile -ExecutionPolicy Bypass -File
guardrails/windows-lock.ps1 -Action Unlock` restored all six access descriptors;
each was compared with its saved original and matched exactly. The operational
state directory was removed. Unlock verification: 2026-10-23 12:50:38 -04:00.

---

## Milestone 3: The run

Run exactly ONE task from your plan with the agent, under the guardrail, through
its gate. Any task whose dependencies are done qualifies, including the
smallest one.

**The takeover point (written before coding, 2026-10-23):** Stop the agent if it
tries to change/delete a shipped test, edit a functional file outside the three
paths below, or bypass the filesystem guardrail. The student will inspect the
diff, remove only the offending task changes, and implement the affected part
manually. A red test or compile error alone is not permission to expand scope.

**Selected task and scope:** Task 1, retain immutable booking modification
history. Functional changes are limited to:

- `core-domain/src/main/java/edu/cmu/cs214/campusreserve/core/domain/Booking.java`
- `core-domain/src/main/java/edu/cmu/cs214/campusreserve/core/domain/BookingModification.java` (new)
- `core-domain/src/test/java/edu/cmu/cs214/campusreserve/core/domain/BookingHistoryTest.java` (new)

`PLAN.md`, the required README tool/model credit, and local ignored evidence are
lab reporting changes, not additional feature tasks. Preserve the existing
seven-argument Booking constructor and confirmed factory. Add an immutable
history component and a pure `modified(interval, attendees, modifiedAt)` copy
operation; service authorization, validation policies, events, notifications,
and HTTP remain assigned to Tasks 2-4. Do not change their code in this run.

**Execution order:** New tests and a red run; history value/model implementation;
the core-domain gate; one closing `mvn -B clean test`; file-by-file diff review;
then a fresh-session refuter given only the change request, task diff, and scope.
Keep C active throughout implementation and the two green builds. Settle the
refuter's strongest claim with a new failing test or dismissal evidence. Record
results as they occur; do not treat this pre-run plan as completed evidence.

**The evidence:** Task 1 only. Twelve new tests were written first; the initial
`mvn -B -pl core-domain -am test` exited 1 at test compilation because the new
history type/accessor/copy operation did not exist. This was a missing-API red
run, not a failed assertion. The original tests were not changed.

After implementation, `mvn -B -pl core-domain -am test` returned:

```text
[INFO] Tests run: 179, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Finished at: 2026-10-23T12:24:59-04:00
```

The subsequent closing `mvn -B clean test` returned:

```text
[INFO] BUILD SUCCESS
[INFO] Total time:  8.899 s
[INFO] Finished at: 2026-10-23T12:25:37-04:00
```

All 430 Java tests passed (core-domain 179, storage 63, notifications 41,
accounts 19, billing 19, http-api 78, cli 31), with no failures/errors/skips. The
filesystem guardrail remained active during both green builds. Maven compiled
against the configured Java 21 release; no separate linter is configured. Python
behavior was unchanged; its 137-test baseline is recorded above. Local run
evidence is retained in `transcripts/task1-run.md`.

**The diff:** `git add -N .` includes new files in `git diff`; no file contents
were staged or committed. File-by-file accounting for this run:

- `core-domain/.../core/domain/Booking.java`: immutable `history`, the compatible
  seven-argument constructor, a pure `modified` copy operation with no-op handling,
  and history-preserving cancellation. Matches Task 1's declared model scope.
- `core-domain/.../core/domain/BookingModification.java` (new): old/new intervals,
  counts and supplied timestamp, with existing-style nonnull/positive value
  checks. Matches the declared history value type.
- `core-domain/src/test/.../core/domain/BookingHistoryTest.java` (new): 12 tests
  for construction, changed fields, two modifications, snapshot isolation,
  no-ops, list aliasing/immutability, cancellation, and invalid values.
- `README.md`: one required tool/model credit line; lab reporting only.
- `PLAN.md`: pre-run takeover/scope plus evidence and diff accounting; lab
  reporting only. Ignored `transcripts/task1-run.md` and
  `transcripts/task1-refuter-brief.md` retain the run and the handoff materials;
  `transcripts/task1-refuter-result.md` and `transcripts/task1-settlement.md`
  retain the supplied review and actual settlement evidence.

All `guardrails/` changes predate Task 1 and belong to Milestone 2. No additional
guardrail changes were needed during implementation. No unexpected functional
files or shipped tests changed. `git diff --check` passed; `git diff --exit-code
-- billing cli accounts storage notifications http-api analytics` exited 0.
The change adds no conflict predicate or waitlist write path (rules 3 and 4).

**The line read hardest:** `history = List.copyOf(history);` in Booking's compact
constructor. I checked that this copies a caller-owned mutable list rather than
wrapping it, makes accessor results immutable, and is used by every construction
path. The aliasing and accessor-mutation tests both passed. Cancellation explicitly
passes the current history to this constructor so it is not reset to an empty list.

**The refuter: Fable, no substantiated Task 1 defect.** The student supplied the
full review, retained locally as `transcripts/task1-refuter-result.md`. Fable
reported a successful repository test run, 12/12 history tests, compatibility
checks, and a JShell probe script outside the repository. The supplied handoff was
`transcripts/task1-refuter-brief.md`: only the change request, task-only diff
(including new files), the scope line, and the refutation instructions. It
contains no conversation, implementation rationale, gate results, or PLAN.md.

Brief instructions: "Find reproducible inputs or call sequences where this task
does the wrong thing. Report the strongest concrete claim with a proposed new
test or exact reproduction. Do not implement fixes or edit shipped tests. If no
claim holds, explain the strongest attempted counterexample."

**Strongest claim and settlement:** The eight-argument constructor accepts a
supplied list containing no-op or inconsistent history entries. Re-running
Fable's actual `%TEMP%/lab8-refute/probe.jsh` reproduced its P4: two such entries
were accepted. A separate JShell check confirmed that a subsequent no-op
`modified(...)` appends zero entries to both a normal booking and this restored
booking, while a real change appends exactly one. Both checks passed.

Dismissed as a Task 1 correctness finding: requirement 5 says "A modification
that changes nothing succeeds and records nothing at all"; the modification
operation satisfies that contract. The constructor copies supplied snapshots;
history-chain authentication/repair on reconstruction was not specified in Task 1
or the change request. Production `new Booking(...)` callers remain within
Booking's own factories/copy methods; the eight-argument restoration example is
not an externally exposed write path. Storage currently retains Booking objects
by ID and HTTP renders fields explicitly. Keep supplied-history consistency in
mind if a future durable read path is introduced; do not add that feature now.

**Other attempted claims:** Cancelled/started copies deliberately defer lifecycle
checks to Task 2, as declared before implementation and in the method's javadoc.
History order is append order, not a claim that caller-supplied timestamps are
monotonic. Null history is rejected with NPE; re-running P5 confirmed the generic
JDK message instead of "history". That diagnostic inconsistency is real, but no
requirement promises a specific exception message; the proposed message-only test
would add a new contract. No implementation change or artificial failing test was
needed to settle these observations.

**Evidence and cleanup:** `transcripts/task1-settlement.md` retains the commands,
actual probe output, and dismissal. The current task diff exactly matched the
diff given to the refuter; no source or test changes followed the closing build.
Fable's temporary probe and the settlement probes/logs were removed after their
evidence was saved. Generated build directories were cleaned. Original filesystem
permissions and ACL-state cleanup were verified as recorded in Milestone 2.
Milestone 3's implementation, builds, diff accounting, independent refutation,
and strongest-claim settlement are complete. Fable's underlying model was not
provided; README records that limitation rather than guessing.
