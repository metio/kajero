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
`path=<repository path>` (the title, body or evidence names that file),
`link=<entry id>` (linked to that entry, in either direction),
`link:<type>=<entry id>` (linked by `supersedes`, `contradicts` or
`see-also`), `schema-revision=<hash>`, `outdated-schema`, `disputed`, `stale`,
`unsigned`, `superseded`, `retired`, `coverage` (the records of questions
asked), and `~text` (or a bare word) for title,
body, labels and data. It is tik's `--where` grammar. Retired entries are
left out unless the selector names `retired`.

Join terms with `|` to accept any of them: `calico|coredns` finds entries
holding either word, and `label:kind=gotcha|label:kind=fact` either kind.
Reach for it when a topic's wording is a guess. Two loose words as separate
terms must both match, and often find nothing where either alone finds
plenty, while `cert-manager|certificate|issuer` returns the union, with the
entries holding more of the words first. `not a|b` excludes entries holding
any of them. A `data:` term cannot be one of the alternatives.

With text terms, results come ordered by `rank = score + min(citations,
3)`: `score` weighs a match in the title 12, labels 6, body 2 and data 1,
and `citations` counts the entries whose current revision names the result
as `[[<id>]]` in the body or links to it by `see-also`. Citing an entry
that shaped your write is how it ranks higher for the next reader, so name
the entries you built on as `[[<id>]]`. A `supersedes` or `contradicts`
link, a reference to itself, and a retired or superseded citer count
nothing, and the bound keeps a heavily cited single body mention below an entry
carrying the term in a label or its title.

Before opening a file, search for it with `path=`: two paths name the same
file when one ends with the other at a segment boundary, so
`path=helm-charts/cluster-info/templates/_cert-issuer.tpl` finds an entry
that cites `templates/_cert-issuer.tpl`, and `path=_cert-issuer.tpl` finds
both. Name the files an entry is about by their path, so the next reader
finds it this way.

With text terms, the best match comes first, and each result carries
`matches`: per term, the field it matched in (`title`, `labels`, `body` or
`data`) and a `snippet` of the text around it. Judge relevance from the
snippets and `get` only the entries worth reading. A term is a substring,
so a short one matches inside longer words; prefer a distinctive word.

To ask about a field of the data, use `data:field=value` (or `data:field`
for presence). A dotted field walks maps and steps into lists:
`kajero_search {"selector": "data:hosts.domain=lms.example.org"}` returns
the readings with that host, each carrying only the matching host maps as
`rows`, so there is no need to read a whole table and filter it by hand.
Terms about one list must hold in the same element
(`data:hosts.domain=x data:hosts.flavor=gateway` asks about host x); a term
about the whole entry, such as `data:cluster=…`, combines with any of its
elements. Values compare as printed, a keyword without its colon, and a
list of plain values matches when any element does. Check the data's shape
with `kajero_get` first if you do not know the field names.

An empty result does not mean nobody looked. Search `coverage` with the
subject's labels - `coverage label:component=dns` - for the records of
surveys already run: each names the question, the population examined, the
method and the day. A coverage record with no answer entries means the
question was asked and found nothing; no coverage record means it may never
have been asked. Judge from its population and date whether the survey
covers what you need before you re-run it.

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

## Recording a survey

After any survey - a book run over a population, a sweep of hosts or
repositories, a search through an estate - write a `kind=coverage` entry
alongside the answers, even when the survey found nothing. Give it the
subject's labels (`system`, `component`, `env`), so `coverage` with those
labels finds it, and data naming what was asked:

```
kajero_write {"title": "Surveyed: AAAA records of the fahrplan hosts",
              "labels": {"kind": "coverage", "component": "dns", "project": "infra.run"},
              "data_edn": "{:question \"Which fahrplan hosts answer AAAA?\"
                            :population \"the 41 hosts in fahrplan's DNS zone on 2026-11-23\"
                            :method \"gumshoe book dns/records, every host\"
                            :asked \"2026-11-23\"
                            :found \"no host answers AAAA\"}"}
```

`:question`, `:population`, `:method` and `:asked` (YYYY-MM-DD) are
required, and a write without them is refused. `:population` may be a
sentence, a count or the list itself; `:found` is optional. Link the
answer entries `see-also` the coverage record.

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

## Entry ids

Every tool that takes an entry - `kajero_get`, `kajero_history`,
`kajero_revise`, `kajero_observe`, `kajero_link` and the rest - accepts the
full uuid or any prefix of four or more characters that fits exactly one
entry, such as the eight-character form prose and tik quote. A prefix that
fits several is refused with `ambiguous-id` and lists them. A call with a
parameter the tool does not take, or without one it requires, is refused
with `unknown-parameter` or `missing-parameter`, naming that parameter and
the ones the tool takes.

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

A check that covered several entries - a gumshoe book, a survey, a session
that re-verified what it read - records them all in one call with
`kajero_observe_many`: one item per entry, each with its own outcome and
evidence. Each item is judged on its own, so a refused one (a missing
revision, a `failed` without evidence) leaves the rest recorded, and the
result names the reason. From a book or script, `kajero observe-many -`
reads the same items as an EDN list on stdin. Feed every confirmation back
this way; standing stays empty when each costs a call.

When you found it wrong and know what is right, revise the entry after the
`failed` observation, so the record shows both what was wrong and the
correction.

When an observation of yours, or anyone's, turns out to rest on wrong
evidence or reasoning, correct it on the same entry: `kajero_observe` with
`corrects` set to its hash from `kajero_history`, evidence saying what was
wrong, and the outcome your new check supports - or `withdrawn` to take the
verdict back without a new one. The earlier observation stays in the
history marked corrected, stops counting toward the standing, and
`kajero_get` lists it under `corrections`.

## Disagreeing

Two agents who disagree each record their own finding:

- On the same claim: both observe the same revision, one `held`, one
  `failed`, each with its evidence. Readers see both.
- On a different claim: write your own entry and link it
  (`kajero_link` with `contradicts`). Both entries show the contradiction
  until one is revised or superseded.
- When a newer entry replaces an older one, link it with `supersedes`;
  `not superseded` then keeps the old one out of searches.
- `link=<entry id>` finds every entry linked to one, so the neighbours of
  an entry you are reading are one search away.

## Retiring

Retire an entry you wrote by mistake, as a test, or twice:
`kajero_retire` with its current revision as `base` and a `reason`. It
leaves searches, the label counts and the similar-entry check, keeps its
history, and `kajero_restore` brings it back. Test the write path freely and
retire the probe afterwards. Retire only what carries no knowledge; an entry
that turned out wrong gets a `failed` observation or a revision, and one a
better entry replaces gets a `supersedes` link, so readers see why.

## Handing over to a human

kajero writes are yours to make: unlike a cluster change, a note needs no
confirmation. Tell the user which entries you wrote, revised or observed,
by title and id, so they can read and correct them.
