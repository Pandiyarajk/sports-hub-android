---
name: remediation-tooling
description: Building a tool that fixes what it reports - the failure modes that make a remediation feature lie about its own work. Covers verifying at the wrong granularity so every success reports failure, a client-side timeout reported as a failure when the operation is still running, layered timeouts where the outer one is shorter, an audit written too late to record the case it exists for, filtering after LIMIT so the page comes back empty, and generated output whose volume buries the part that matters. Use when adding an "apply fix", "auto-remediate", "one-click repair" or bulk-fix feature, when a tool acts on a system it also measures, or when a successful operation reports that it failed.
---

# remediation-tooling

A tool that only reports is easy to be honest about: it either saw something or it did not. A
tool that *acts* has a second set of failure modes, and they share a shape — **the operation
works and the tool says otherwise, or the operation's fate is unknown and the tool picks an
answer.**

Every item below was measured while adding a remediation feature to a diagnostics app (SQL
Server over JDBC, Kotlin), but none of them is about SQL. The same failures appear in a deploy
script, a bulk linter fixer, a data migration, or anything with a "fix it for me" button.

## Verify at the granularity you acted at, not the one you found at

The first write that feature ever made succeeded, and reported:

```
verified=false [expected = 0 but got 79]
```

Correct arithmetic, wrong signal. The finding was an aggregate — *"80 statistics are stale"* —
and the fix was per-row. Re-running the finding's own check after repairing one row left 79, so
a statement that did exactly what it was asked was marked "ran but did not fix".

Do it eighty times and every single success reports failure. That is worse than having no
verification, because the one signal worth having is *"this ran and the problem is still
there"* — it is how you learn your model of the problem was wrong — and a false positive on
every row trains the reader to ignore it.

**So:** when an aggregate finding produces per-item actions, give each action a check scoped to
the item it fixed.

```kotlin
// The finding: SELECT COUNT(*) ... WHERE last_updated < DATEADD(day,-7,GETDATE())  -> 80
// Wrong: re-run that after fixing one row.
// Right: verify the row you touched.
"SELECT COUNT(*) FROM sys.stats s CROSS APPLY sys.dm_db_stats_properties(...) sp " +
"WHERE s.object_id = OBJECT_ID(@table) AND s.name = @stat " +
"  AND sp.last_updated >= DATEADD(day, -7, GETDATE())"     // expected: > 0
```

The aggregate check still has a job — re-run it *once* after the whole batch, to answer "is the
finding closed".

## A client-side timeout is an unknown outcome, not a failure

When the client stops waiting, the server does not stop working. A `CREATE INDEX` that blows
through the driver's timeout is still building.

Report that as `FAILED` and the obvious next move is to run it again — now two index builds
race on the same table. Report it as `SUCCEEDED` and you have invented a result. Neither is
true, so model the third state explicitly:

```kotlin
enum class RemediationStatus {
    SUCCEEDED,
    FAILED,             // the server rejected it; nothing changed
    UNKNOWN_TIMED_OUT,  // we stopped waiting; it may still be running
    NOT_ATTEMPTED,
}
```

and say so in the words the user reads:

> The app stopped waiting after 600 s. The statement may still be running on the server. Do not
> run it again — check `sys.dm_exec_requests` first.

This is the same discipline as keeping `PERMISSION_DENIED` distinct from `UNREACHABLE`:
collapsing two causes into one label throws away the question the reader most needs answered.

A timeout must also **halt the batch**, whatever the "continue on failure" setting says.
Issuing more work down a connection whose last operation may still be running is how a lock
pile-up starts.

## Layered timeouts: the outer one must outlast the inner

Almost every client has two, and the transport-level one usually defaults far *shorter* than
the operation you are about to run.

Measured, mssql-jdbc:

| Setting | Scope | Default |
|---|---|---|
| `queryTimeout` | statement | 0 (none) |
| `socketTimeout` | connection | **30 000 ms** |

So a statement granted 600 s has its socket dropped at 30 s, and the client reports a timeout
for an operation the server is still performing. The failure is invisible in testing because
every quick statement passes.

**Size the transport timeout from the operation**, and cap it so a pathological value cannot
produce a socket that never gives up:

```kotlin
fun socketTimeoutFor(statementSeconds: Int, marginSeconds: Int): Int =
    ((statementSeconds.toLong() + marginSeconds) * 1000L)
        .coerceIn(60_000L, 4 * 60 * 60 * 1000L).toInt()
```

Test the invariant rather than the arithmetic:

```kotlin
listOf(1, 30, 600, 3600).forEach {
    assertTrue(socketTimeoutFor(it, 30) > it * 1000)
}
```

The same trap: HTTP client read-timeout under a long server-side job, `ssh` `ServerAliveInterval`
under a long build, a load balancer idle timeout under a slow request.

## Write the audit before the action, not after

An audit written only on completion cannot record the case it exists for.

The likeliest moment for a mobile or CLI process to be killed is *during* the long operation —
holding a socket open, in the background, on a device with aggressive memory management. That
is exactly when you most need to know what was sent.

**Two records per operation**: the attempt, carrying the exact payload, before it goes; the
outcome after. If the process dies between them, the attempt still stands and someone can go
and look.

- **Append-only, one self-contained record per line** (JSONL). A new entry is a seek and a
  write, so a crash mid-write costs the last line rather than the file. Serialise the payload
  with newlines escaped, or one record becomes two half-records.
- **Not a whole-document store.** Anything that rewrites the file per write — a preferences
  store, a JSON array — is the wrong shape for a ledger and gets slower as it fills.
- **Never let an audit failure abort the operation being audited.** It has already been sent.
  Losing the record is bad; losing the ability to finish and report is worse.
- **Redact on write, not on export**, so a credential cannot sit in the file waiting for a code
  path that forgets.

If the tool also produces a report, **the report must say the system was changed**. One that
omits it is a false document: every other number in it was measured *before* the changes, and
the reader cannot tell an observation of the system from an observation of the system after
your tool edited it.

## Filtering after LIMIT returns an empty page

If you fetch the top N and then filter client-side, the filter can remove everything.

Measured: a diagnostics tool excluded its own queries from a "slowest queries" list. On a quiet
server the tool was the busiest thing running, so its own statements filled every one of the
top slots — and the screen went from "wrong data" to "no data".

**Over-fetch by the maximum the filter could remove, filter, rank, then cap.**

```kotlin
val fetch = topN + ANALYZER_ALLOWANCE   // ~ how many rows the filter can plausibly eat
val kept  = readTop(fetch).filterNot { isOurOwn(it) }
return rank(kept).take(topN)
```

And distinguish the two empty results, because they are opposite findings:

> All 12 cached statements were this app's own.

is not

> No query statistics were returned.

## Generated output: group, don't cap

A four-failure report generated **266 fix statements**, of which 147 were the same template
differing only in a table name. Flat, that buried the four that could actually be run under a
screenful that could not.

Capping the generator is the wrong fix — the exported script must stay complete, because 147
statements are entirely reasonable in a query window even where 147 cards are not.

**Group by originating finding, and sort groups with something actionable first.** Fold the
display; keep the export whole. State the elision where it happens ("… and 135 more; export the
script for all of them").

## Two structural rules worth enforcing rather than intending

**One execution path.** Exactly one class may perform the write. Every other access is a read.
That is checkable, so check it:

```bash
grep -rn "executeUpdate\|\.execute(" --include=*.kt src/main | grep -v executeQuery
```

The preflight gate, the confirmation flow and the audit all live on that one path; a second
entry point bypasses all three regardless of how careful it is.

**Make an unrunnable operation unrepresentable at the boundary.** If some generated actions
carry placeholders a human must fill, do not let a runtime check be the only thing standing
between a template and execution:

```kotlin
sealed interface Materialised {
    data class Ready(val payload: String) : Materialised
    data class Incomplete(val missing: List<Placeholder>) : Materialised
    data class Withheld(val reason: String) : Materialised
}

fun execute(action: Action, statement: Materialised.Ready): Outcome   // Ready only
```

No dialog, screen state or future caller can route an unfilled template into execution, because
the type will not allow it.

## Check what the caller may actually do, before offering it

Probe permissions up front rather than discovering them by failing halfway through a batch, and
keep three verdicts, not two:

- `GRANTED`
- `DENIED` — "you do not have it"
- `UNKNOWN` — "the probe itself was refused"

Both of the last two block, and they must be *worded differently*. "Could not determine" and
"you do not have it" are different facts, and a tool that reports the second when it means the
first sends someone to fix a permission that was never the problem.

Order the gates so the most fundamental answer wins: a target that is offline or read-only is
reported as *that*, not as a permission problem — the permission answer is irrelevant and
misleading when the system is mid-restore.

Keep the permission probe free of anything privileged. Folding an advisory extra (a "how busy
is the server right now" DMV read) into the same query means a caller lacking *that* permission
loses every permission answer to a diagnostic that was never load-bearing.

## What this does not make safe

None of the above changes who is allowed to do what. If the tool's credentials are broad enough
to perform the fix, they are broad enough to perform it wrongly, and a confirmation dialog is a
usability affordance rather than a control — say so in the docs rather than implying otherwise.

Two things worth stating plainly wherever the feature is described:

- **The transport caps the safety story.** Executing changes over a connection whose certificate
  is unverified means an on-path attacker can rewrite the payload; before the feature existed
  they could only read it. No amount of confirmation UX raises that ceiling.
- **A statement succeeding is not proof the problem is solved.** Creating an index proves the
  index exists, not that a query got faster. Where no check can prove the fix worked, say
  "not verified" rather than showing a tick.
