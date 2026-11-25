---
type: decision
status: accepted
date: 2026-11-24
title: Search retrievals and opens are counted beside the notebook, never recorded in it, and ranking does not use them
supersedes: null
---

# ADR 0003: Retrievals and opens are counted beside the notebook

## Decision

Accepted on 2026-11-25.

**kajero counts, per entry and per day, how often a search returned the
entry and how often it was opened. The counts live in a local usage file
beside the notebook, are unsigned, are never synced, and feed reports.
Search ranking does not read them.** The notebook stays a set of signed
claims that somebody meant. Usage is a measurement of one server, so it
lives with that server.

1. **What is counted.** For each entry and UTC day, the server keeps three
   numbers:
   - `returned`: how many search results included the entry;
   - `opened`: how many `kajero_get` calls or page views read it;
   - `opened-after-return`: how many of those opens followed a search that
     returned it within the same client session, as defined in point 2.

   No selector, query text or result position is kept. A count never names
   an actor.
2. **A session is the MCP session, or nothing.** Every MCP write is signed
   as one actor (`KAJERO_MCP_ACTOR`), so the actor cannot tell two agents
   apart. The server uses the `Mcp-Session-Id` it assigns, held in memory
   for 30 minutes after the last search. A client without a session id
   still adds to `returned` and `opened`, but never to
   `opened-after-return`.
3. **Where the counts live.** In SQLite, a `usage` table in the notebook
   database that is outside the record tables. In the flat-file layout, a
   `usage.edn` beside `notes/`. `kajero sync` copies records only, so usage
   never travels. Deleting the usage data loses only the measurement.
4. **What reads them.** Two selector terms and a report:
   - `never-returned`: entries with no `returned` count since a given day,
     a findability defect in their title or labels;
   - `returned-unopened`: entries returned at least N times and opened
     after none of them, a title or summary that does not match what the
     entry holds;
   - `bb kajero usage` prints both lists.

   The `missed` observation outcome (request 03ff7fc0) is where an agent
   records the fix.
5. **Ranking does not use them.** Ranking is term match plus incoming
   citations (request c5465ecf). Usage stays out of it, for two reasons:
   - a retrieval count rewards whatever already ranks high, so it feeds
     back on itself;
   - in a notebook one agent mostly writes and reads, opens measure that
     agent's recent attention rather than an entry's worth.

   A later ADR can revisit this with data from point 4.
6. **Retention.** Daily rows older than 180 days fold into one total per
   entry. `never-returned` needs the date the counting began, so the usage
   file records it.

## Context

Request b5678d32 asks kajero to record, as a side effect of use, that a
search returned an entry and whether the same session then read it. That
would make two defects visible that nothing shows today: entries no search
ever returns, and entries returned often and never opened. The request
argues against a usefulness vote, because a vote is free to cast and cannot
be checked, and it lets ranking use the records.

Three facts of the current design decide where such records can live:

- **Every notebook record is a signed claim, and the set only grows**
  (ADR 0002). Copies merge by union, and nothing is ever deleted. A
  retrieval record in that set would be one signed record per entry per
  search, forever, on every copy, with the server's key as signer. Every
  copy would then hold a log of what one server's clients looked at.
- **kajero has no notion of a session.** All MCP writes are signed as one
  actor. "The same session" needs an identity first, and the
  `Mcp-Session-Id` of the HTTP transport is the only one available.
- **Standing is derived from observations, and observations mean "I checked
  this against reality".** A retrieval is not a check, so it must not
  become an observation, or standing would turn into a popularity count.

## Options considered

1. **Signed retrieval records in the notebook.** The records would be
   replicated, auditable and comparable across copies. Against that, the
   volume grows with every search and can never be deleted, every copy
   holds a read log of every client, and the signature claims nothing that
   anybody meant. Declined.
2. **Local, unsigned counts beside the notebook, for reports only.** This
   option is cheap and deletable, and it puts no read log in the replicated
   set. It answers both of the request's questions per server. Against it,
   the counts are not comparable across copies, and they cannot be audited.
   Accepted, as the decision above.
3. **Record nothing.** The `missed` outcome (request 03ff7fc0) and citation
   ranking (request c5465ecf) cover part of the need: a failed search that
   a later route recovers, and an entry that shaped other work. Neither
   shows an entry that is never found at all. That gap is the reason not
   to choose this option.

## Consequences

- The request's done-when is met except for "search ranking may use them",
  which this decision declines for now (point 5). The request should be
  answered with that difference stated.
- The MCP server learns to keep its session ids in memory, and the page
  server counts opens.
- A notebook served by several replicas has one usage file per replica.
  Reports read the local file, so a fleet-wide view would mean collecting
  them, which this decision leaves out.

## Open questions for the maintainer

- Should ranking use usage after all, and if so only as a tie-break?
- Is 180 days the right retention?
- Should page views count as opens, or only MCP and CLI reads?
