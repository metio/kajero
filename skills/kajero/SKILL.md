---
name: kajero
description: Read and write the team's kajero notebook - labelled entries of what agents and humans learned, with signed revisions and observations, structured data and tik tickets as evidence. Use when the user mentions kajero or the notebook, before investigating something another agent may already have worked out (a gotcha, a fact about a system, a how-to), after learning something worth keeping, when handing notes, data or a document to another agent or a later session, and whenever you checked an entry against reality and can say whether it held.
---

<!--
SPDX-FileCopyrightText: The kajero Authors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# kajero

kajero is a notebook for agents. An **entry** is one topic: a title, a
Markdown body and labels. Every change is a new **revision**; every check of
an entry against reality is an **observation** that it `held` or `failed`.
An entry's standing - how often it held, whether it is disputed or stale - is
derived from its observations every time it is read. Every record names its
author and its evidence, and is signed with that actor's tik key when the
notebook has one.

## Reaching the notebook

Through the MCP tools named `kajero_*`. Usually one shared kajero service
serves them over HTTP on `/mcp`, beside its pages for humans, so every
session reads and writes the same notebook and your writes are signed as
the actor the service was started for. The command line prints EDN and
reads `KAJERO_DIR` or `KAJERO_DB` (the notebook), `KAJERO_ACTOR` (who you
are) and `KAJERO_KEY` (your signing key):

```sh
bb kajero search 'label:system=calico not disputed'
```

Never write as another actor.

## Passing work to other agents

The notebook is the place to leave anything another agent or a later
session should pick up: notes from an investigation, a table of what you
measured, a short document, a plan you could not finish. Write it as an
entry with labels a reader would search for, cite the tik ticket you
worked under as `tik:<ticket id>`, and put raw data in `data_edn` rather
than in prose. Tell the user the entry id when you hand work over.

## Before you investigate

Search first. Another agent may already have written down what you are
about to find out.

```
kajero_search {"selector": "label:system=calico ~apiservices"}
```

Selector terms, all of which must hold, each optionally prefixed `not`:
`label:key`, `label:key=value`, `actor=name`, `ticket=<tik ticket id>`,
`schema-revision=<hash>`, `outdated-schema`, `disputed`, `stale`,
`unsigned`, `superseded`, and `~text` (or a bare word) for title, body and
labels. It is tik's `--where` grammar.

Read the standing before trusting an entry. `disputed` means someone found
the current revision wrong; `stale` means nobody confirmed it for longer
than its kind allows; a high `held` count from several actors with recent
dates is the strongest signal there is. An entry is a lead to verify, not a
fact to repeat.

## Writing an entry

- One topic per entry, so a page can pick it alone. A long note with three
  findings is three entries.
- Lead with the finding, then the evidence; keep the qualifiers the evidence
  needs ("on Calico v3.32", "on staging").
- Call `kajero_labels` first and reuse the labels in use. Common keys:
  `kind` (`fact`, `gotcha`, `howto`, `decision`, `reference`), `system`,
  `project`, `env`. A label nobody used before is accepted with a warning
  that lists the existing ones: if the warning shows the label you meant,
  revise the entry to use it.
- Give evidence a reader can repeat: the command and what it showed, a
  commit, a URL. Cite the tik ticket you worked under as `tik:<ticket id>`;
  the notebook checks it exists, and `ticket=<id>` later finds everything
  learned under that ticket.
- `kajero_write` returns `similar` entries with the same title or labels.
  When one of them covers your finding, revise it and leave the new entry
  unused, or link the two.

## Structured data

Raw data - a list of nodes, a table of versions, a map of settings - goes in
the entry's data as EDN (`data_edn`), with the prose that explains it in the
body. Humans see a list of maps as a table; you read the data directly from
`kajero_get`, which returns it as `data` and as `data_edn`.

When several entries hold data of the same shape, give the shape a schema:
write an entry labelled `kind=schema` whose data is a malli schema such as
`[:vector [:map [:name :string] [:cpus :int]]]`, then label each data entry
`schema=<schema entry id>`. A write whose data does not fit is refused with
malli's errors; fix the data, or revise the schema when the shape really
changed. Search `label:kind=schema` before inventing a new one.

When a shape changes, revise the schema entry if old data still fits it.
Otherwise write a new schema, link it as `supersedes` the old one, and write
a `kind=migration` entry whose data is
`{:from old :to new :steps [[:rename :name :host] ...]}`. Run
`kajero_migrate` with `dry_run` first, then without; it converts every entry
on the old schema or nothing. `outdated-schema` finds entries checked
against an older revision of their schema.

## Revising

`kajero_revise` needs `base`, the entry's current revision. A stale base is
refused with the current revision: read the entry again, fold your change
into what is there now, and retry with the new base. Never overwrite another
writer's revision from an older copy.

## Observing

Record an observation only from a fresh check - a command you ran, a page
you fetched - never from having read the entry. Reading and agreeing is not
evidence, and it counts for nothing.

- `held`: your check confirmed the current revision.
- `failed`: your check contradicts it. Evidence is required, and the entry
  shows as disputed until a new revision answers.

When you found it wrong and know what is right, revise the entry after the
`failed` observation, so the record shows both what was wrong and the
correction.

## Disagreeing

Two agents who disagree each record their own finding:

- On the same claim: both observe the same revision, one `held`, one
  `failed`, each with its evidence. Readers see both.
- On a different claim: write your own entry and link it
  (`kajero_link` with `contradicts`). Both entries show the contradiction
  until one is revised or superseded.
- When a newer entry replaces an older one, link it with `supersedes`;
  `not superseded` then keeps the old one out of searches.

## Handing over to a human

kajero writes are yours to make: unlike a cluster change, a note needs no
confirmation. Tell the user which entries you wrote, revised or observed,
by title and id, so they can read and correct them.
