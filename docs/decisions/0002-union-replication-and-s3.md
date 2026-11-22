---
type: decision
status: accepted
date: 2026-11-22
title: A notebook is a grow-only set of content-addressed records that replicates by union, and an S3 store shares the flat-file layout
supersedes: null
---

# ADR 0002: A notebook replicates by union, and S3 shares the flat-file layout

## Decision

**Every record a notebook holds is immutable and named by its content
hash, two copies of a notebook merge by taking the union of their records,
and disagreement after a merge is derived and shown, never refused.**
Revisions become content-addressed like observations, links and page
versions already are. A notebook is then a grow-only set, the same shape
tik's event store has (tik ADR 0021, tik ADR 0003), and that is what makes a
shared notebook and an S3 store cheap.

1. **A revision is stored under its hash.** The flat-file layout becomes
   `entries/<entry>/revisions/<hash>.edn`, like `observations/<hash>.edn`,
   and the SQLite store drops its `UNIQUE (entry, seq)` constraint; the
   hash is already its primary key. `:seq` stays in the record as the
   writer's claim of depth, for ordering and display, and no store enforces
   its uniqueness. Signatures cover the record's canonical bytes without
   `:sig`, never the file name, so renaming the files of an existing
   notebook keeps every signature valid; a migration does exactly that.
2. **The current revision is derived from the bases.** A revision names the
   revision it builds on as `:base`. The heads of an entry are its
   revisions that no other revision names as a base. One head is the
   current revision. Several heads mean the entry is **forked**: two
   writers revised the same base without seeing each other.
3. **A fork is shown and resolved by a person or an agent.** A forked entry
   carries `:forked? true` in its standing, the selector `forked` finds it,
   its page shows every head, and a write to it is refused with the heads
   listed until a revision names all of them as its bases (`:bases`), which
   makes it the single head again. Nothing picks a winner by time or by
   actor, since either revision is signed by somebody who meant it.
4. **A local write still checks its base.** Within one store, a revision
   whose base is not the current head is refused as `:kajero/stale-base`, as
   today, because a writer that has seen the newer revision should build on
   it. Forks arise only between copies that were apart, which is the case
   this decision is for.
5. **Replication is a union of records.** `kajero sync FROM TO` copies every
   record and signature that `TO` lacks; records are immutable and named by
   their hash, so copying is idempotent, the order does not matter, and two
   syncs in opposite directions leave both copies equal. A flat-file
   notebook in git replicates with `git pull` and `git push`, since two
   copies never write the same file with different bytes.
6. **The S3 store uses the flat-file layout as object keys.** Under a
   configured bucket and prefix, each file of the layout is one object with
   the same relative key, and each signature is the object beside it with
   `.sig` appended. Both stores are built from one layout over a small blob
   interface (list the keys under a prefix, read a key, write a key that is
   absent), implemented once for a directory and once for S3, so the layout
   code is shared and a notebook moves between them by `kajero sync`.
   Writes need no conditional request for correctness: a key names its
   content, so two writers of one key write the same bytes. A conditional
   write (`If-None-Match: *`) only saves the upload where the provider
   supports it. Immutable objects can be cached locally by key for as long
   as the cache lives.
7. **No text CRDT.** Merging concurrent edits of a body character by
   character, as Automerge or Yjs do, produces text that no actor wrote or
   signed, and every kajero revision is signed as a whole. Entries are short
   and revised whole, so a fork that somebody resolves costs little and
   keeps authorship true.

## Context

A notebook today is one store on one machine: SQLite or a directory of EDN
files. Observations, links and page versions are already append-only and
named by their hash, so two copies of them merge by union. Revisions are
not. They are numbered per entry, and the store refuses a second revision
with the same number, which is optimistic locking: correct within one
store, and unmergeable across two copies that both wrote revision N while
apart. Sharing a notebook between machines or people, a notebook per
organization, and an S3 store all need copies that merge.

tik solved the same problem by never storing what can be derived: a store
is a grow-only set of content-addressed events merged by union, and
concurrent claims derive as `:conflicted` for a person to resolve. Applying
that to revisions is the smallest change that makes a notebook mergeable.

## Consequences

- A migration renames the revision files of every flat-file notebook and
  drops the constraint from every SQLite notebook. It is mechanical and
  keeps every signature valid.
- `:bases` is new in the revision record; a revision without it has the
  single `:base` it has today.
- Standing, the selector language, MCP and the pages learn `forked`.
- The S3 store needs an S3 client among the dependencies and credentials
  from the environment. Reads list keys under an entry's prefix, so a large
  notebook on S3 wants the local cache before it is pleasant to use.
- Access control per organization (who may read a notebook) is a separate
  decision; replication by union assumes every copy may hold every record.
