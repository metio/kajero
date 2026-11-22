---
type: decision
status: accepted
date: 2026-11-22
title: gumshoe findings enter the notebook as schema-checked data, raw run records stay with gumshoe and are cited by hash
supersedes: null
---

# ADR 0001: gumshoe findings enter the notebook as schema-checked data

## Decision

**A gumshoe book's structured findings are written into kajero as the
`data` of an entry, checked against a malli schema entry, and kajero itself
knows nothing about gumshoe.** The writer is gumshoe, or an agent that ran a
book, using kajero's ordinary write surface: MCP, the command line or the
library. Agents then read the findings as EDN like any other data
(`kajero_get` with `data_edn`, `/e/<id>.edn`, the `schema-revision=` and
`outdated-schema` selectors). Nothing in kajero depends on gumshoe, and
gumshoe works without a notebook.

1. **One schema entry per finding shape.** A book's `--output edn` report
   (`findings`, `checked`, `summary`) or one finding kind within it maps to
   a `kind=schema` entry. When gumshoe changes a shape, the schema lifecycle
   carries the data along: the old schema is deprecated or superseded, a
   `kind=migration` entry converts the existing entries in one step, and
   `outdated-schema` finds what is still on an older revision.
2. **A re-check is a new revision, and the claim it supports cites it.** A
   book run that re-checks a subject writes a new revision of the data
   entry. An observation on the entry that states the claim (a gotcha, a
   fact) cites that data revision as its evidence, written
   `kajero:<entry>@<revision>`, so "this held" points at the exact snapshot
   it rests on. kajero does not parse this form today; it is a convention
   readers follow, and resolving it to a link is a later change. The
   history of the data entry shows how the result changed over time.
3. **`checked` travels with the findings.** gumshoe marks a detective that
   read nothing as `unread`, and an empty finding list from it proves
   nothing. The schema keeps `checked`, so a reader can tell a clean result
   from a blind one. A writer does not record `held` on the strength of an
   empty result whose detectives were unread; kajero does not enforce this.
4. **Raw run records stay with gumshoe and are cited, not copied.** A run's
   recording directory (`recordings/<book>/<when>/` with `input.edn`,
   `selections.edn`, `commands.sh` and `meta.edn`) and any raw command
   output stay on disk under gumshoe. Evidence names them as
   `gumshoe:<book>/<when>@sha256-<hash>`, the hash taken over the recording's
   files in a fixed order, so the citation fails loudly when the files
   change. kajero treats such evidence as an opaque string unless a
   recordings directory is configured, in which case it can check the hash
   and link the files, the way `tik:<id>` evidence is checked only when a
   tik store is bound.
5. **Nothing secret is written.** The writer redacts before it writes:
   gumshoe already strips token, password and secret values from
   `input.edn`, and the same rule applies to findings and to any command
   output a schema admits. The notebook is plain files or SQLite, and its
   page server answers reads from every process on the host, so redaction
   cannot be left to each agent.

## Context

kajero revisions carry EDN `data` checked against malli schema entries, and
the schema lifecycle (deprecation, users of a schema, outdated entries,
declarative migrations) makes schemas maintainable as their sources change.
gumshoe books already return structured findings as JSON or EDN and leave a
recording of every run. Storing the findings as data gives agents direct,
queryable access to what a book found, which a sentence of evidence does
not.

Raw records are a different matter. A revision stores its data in full,
every revision of every entry is kept, and the page server refuses a
request body over 16 MiB. Findings and tables fit; whole command logs or
large kubectl dumps would grow the history quickly and gain nothing from a
schema, since an unstructured log is a string either way. Citing them by
hash keeps the notebook small and still ties a claim to its raw material.

## Consequences

- No kajero code is needed for the data path: a schema entry, writes and
  observations exist today. The exporter is a mapping from book output to
  schema entries and writes, which belongs to gumshoe as a post hook
  (`hooks/register-post-hook!` sees every finished book with its outcome and
  recording path) or to the agent that ran the book.
- kajero gains one optional piece: recognizing `gumshoe:` evidence, checking
  its hash against a configured recordings directory and linking the files
  on the entry page. Without the configuration such evidence is shown as
  written.
- gumshoe gains a hash over a recording, computed the same way on both
  sides; the exact file order and encoding are decided with that change.
- An entry may later name the book that re-checks it, so a sweep can run
  the book for every stale entry and record the observation. That is left
  open until the data path is in use.
