<!--
SPDX-FileCopyrightText: The kajero Authors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# kajero

A notebook for agents. Agents write short entries, tag them with labels, and
find them again by selector; a page is a saved selector, so one fact lives in
one entry and shows up wherever a selector matches it. History lives in
kajero's own append-only store, so writing needs no checkout, branch or merge.

## Entries, revisions and observations

- An **entry** is one topic: a title, a Markdown body and labels such as
  `kind=gotcha` or `system=calico`.
- Every change writes a new **revision** on top of the current one. A write
  that names an older revision as its base is refused, so two writers can not
  overwrite each other unseen.
- An **observation** records that an actor checked a revision and found it
  `held` or `failed`, with the evidence of that check. A `failed` observation
  needs evidence and disputes the revision until a new revision answers it.
- An entry's **standing** is derived from its observations on every read: how
  many independent `held` and `failed` observations it has (one per actor per
  30 days), when it last held, whether it is disputed, and whether it went
  unconfirmed for longer than its `kind` allows (90 days for a `fact`).
- **Links** say that one entry `supersedes`, `contradicts` or is `see-also`
  another.

Every revision, observation and link is hashed over its canonical bytes and,
when the writer has a key, signed with that actor's tik key. A notebook with
`KAJERO_SIGNING=required` refuses unsigned writes; one with `optional` takes
them and marks them unsigned. Signatures are checked against an OpenSSH
allowed-signers file (`KAJERO_ACTORS`) before anything is written.

## Structured data

A revision may carry data beside its body: any EDN value, written with
`--data` on the command line or `data_edn` over MCP. Agents read the data
itself; the pages render a list of maps as a sortable table, a map as a
definition list, and anything else as EDN.

An entry labelled `kind=coverage` records that a question was asked,
whatever it found, so a reader can tell an unasked question from an
unrecorded answer. Its data must name `:question`, `:population` (a
sentence, a count or the list examined), `:method` and `:asked` (the day,
`YYYY-MM-DD`), and may carry `:found`. It carries the same `system` and
`component` labels as the answers, so `coverage label:component=dns` lists
what has been surveyed about DNS.

An entry labelled `kind=schema` holds a [malli](https://github.com/metosin/malli)
schema as its data, for example
`[:vector [:map [:name :string] [:cpus :int]]]`. A revision labelled
`schema=<that entry>` is checked against the schema's current revision when
it is written, records which schema revision it fit, and orders its table
columns by the schema. Schemas are data only: a schema that would evaluate a
function is refused.

### Changing a schema

A schema entry is revised like any other entry, and each data revision keeps
the schema revision it was checked against. `outdated-schema` selects the
entries checked against an older schema revision, `schema-revision=<hash>`
those checked against one revision, and a schema entry lists the entries
using it.

A schema labelled `deprecated` (with an optional reason as the value), or
superseded by another entry through a `supersedes` link, takes no new
entries; revisions of existing entries still pass, with a warning, so they
stay correctable until they move.

An entry labelled `kind=migration` moves them:

```clojure
{:from "<old schema entry>" :to "<new schema entry>"
 :steps [[:rename :name :host] [:drop :legacy] [:default :role "worker"]
         [:set :checked true] [:map-values :role {"master" "control-plane"}]
         [:move [:spec :cpus] [:cores]]]}
```

`kajero migrate <id> dry-run` (or `kajero_migrate` with `dry_run`) shows the
converted data; without it, every entry on the old schema gets a new
revision on the new one, citing the migration revision as evidence. If any
entry fails to convert or to fit the new schema, nothing is written. Steps
are data only; a migration can not run a function.

## MCP over HTTP

With `KAJERO_MCP_TOKEN_FILE` set, the page server also speaks MCP on `/mcp`,
so one long-running service serves every client:

```sh
claude mcp add --scope user --transport http kajero http://127.0.0.1:7270/mcp \
  --header "Authorization: Bearer $(cat /path/to/mcp-token)"
```

A request needs that bearer token, and one whose `Origin` is not the server's
own is refused. Writes over MCP name `KAJERO_MCP_ACTOR` and are signed with
`KAJERO_MCP_KEY`; the pages keep writing as `KAJERO_ACTOR`. The endpoint
answers each JSON-RPC message with JSON and opens no event stream.

`KAJERO_SURFACES` picks what the server offers: `http` for the pages and
their EDN, `mcp` for the tools, or `http,mcp`. Without it, the server
offers `http`, and `mcp` too when a token file is given; a surface left
out answers 404.

## Reading the notebook from tools

The pages answer with EDN instead of HTML when the path ends in `.edn`, for
probes and scripts that read the same data the pages show:

| Path | Returns |
|---|---|
| `/view.edn?selector=…` | the summaries of the entries the selector picks |
| `/e/<id>.edn` | one entry with its data, standing, links and cited tickets |
| `/e/<id>/history.edn` | every revision, observation and link of the entry |
| `/labels.edn` | every label with its value counts |

A selector the notebook refuses answers 400 with `{:refused … :message …}`.

## tik tickets as evidence

Evidence of the form `tik:<ticket id>` cites a ticket of the tik store named
by `KAJERO_TIK_ROOT`. A write citing a ticket that store does not hold is
refused; a notebook bound to no store takes citations unchecked. Entries
show each cited ticket with its title and the stages it derives at the time
of reading, and `ticket=<id>` selects every entry whose records cite it.

## Storage

A notebook lives in one SQLite database (`KAJERO_DB`, default `notes.db`),
or, for local testing, in a directory of flat files (`KAJERO_DIR`):

```
notes/entries/<entry>/revisions/00000001.edn
notes/entries/<entry>/observations/<hash>.edn
notes/links/<hash>.edn
notes/pages/<hash>.edn
```

Each file holds one record as EDN, with its signature beside it in a `.sig`
file, so a flat-file notebook can be read, diffed and grepped without kajero.
Both stores create files and rows once and never change them. Two writers
landing a revision with the same number are told apart by the store itself,
and the second is refused.

## Selectors

Selectors use the grammar of tik's `--where`: whitespace-separated terms, all
of which must hold, each optionally prefixed `not`.

```
label:system=calico label:kind not disputed ~apiservices
```

| Term | Picks entries |
| --- | --- |
| `label:key` | with the label |
| `label:key=value` | with the label set to the value |
| `actor=name` | that the actor wrote or observed |
| `ticket=<id>` | whose records cite the tik ticket as evidence |
| `schema-revision=<hash>` | whose data was checked against that schema revision |
| `outdated-schema` | whose data was checked against an older schema revision |
| `disputed`, `stale`, `unsigned`, `superseded` | in that derived state |
| `retired` | that somebody retired |
| `coverage` | labelled `kind=coverage`: the records of questions asked |
| `data:field` | whose data has the field |
| `data:field=value` | whose data has the field with the value |
| `~text` or a bare word | whose title, body, labels or data contain the text |
| `a\|b\|c` | for which any of the terms holds |

An alternation is one term among the ANDed ones. `calico|coredns` picks
entries holding either word, `label:kind=gotcha|label:kind=fact` either
kind, and `not a|b` the entries holding none of them. Its text alternatives
all count towards ranking, so an entry holding more of them comes first. A
`data:` term cannot be an alternative, because the data terms of a selector
must hold together in one element.

A retired entry is left out of every selector that does not name `retired`,
so `retired` lists the retired entries and `not retired` the others.

A data field is a dotted path of keys, each read as a keyword or a string. A
step into a map reads the key; a step into a list tries every map in it, so
`data:hosts.domain=lms.example.org` matches a reading with that host among its
`:hosts`, and when the data is itself a list, each of its maps is tried. A
value is compared with the field's printed form, a keyword without its colon,
so `data:ttl=300` and `data:record=aaaa` both match; the whole value must
match, and a list of plain values matches when any element does.

The positive `data:` terms of a selector must hold together: two terms about
the elements of one list must hold in the same element, so
`data:hosts.domain=pad.example.org data:hosts.flavor=gateway` asks whether
that host is a gateway host, while a term about the whole reading, such as
`data:cluster=kube.example.org`, holds for each of its hosts. Each result
carries as `:rows` the deepest elements where all of them hold - the matching
hosts, not the whole reading - and `not data:...` picks entries in which the
term holds nowhere.

A search with text terms puts the best match first. Per term, a match in the
title weighs 12, in the labels 6, in the body 2 and in the data 1, and up to
five matches of a term in one field count, so a title naming a word outranks
a body repeating it. Each result carries its `:score` and, per term, a
`:matches` item with the field it matched in and a snippet of the text around
the match.

Citations then lift an entry within its neighbourhood of term scores. An
entry cites another when its current revision names it in the body as
`[[<id or prefix>]]` or holds a `see-also` link to it. Each result carries
`:citations`, the number of entries in the notebook citing it, and is
ordered by

```
rank = score + min(citations, 3)
```

highest first, carried as `:rank`. The bound of 3 is less than the gap of 4
between one match in the labels (6) and one in the body (2), so a heavily
cited entry mentioning a term once in its body stays below an entry
carrying it in a label or the title, and citations decide between close
term scores only. A body repeating the term up to five times can still
pass a title match once cited, as it can come within 2 of one uncited. A citation is a costly signal, since it means the
entry shaped a later write. A `supersedes` or `contradicts` link points at
a replaced or disputed entry and does not count; each citing entry counts
once per target however many references and links it holds; an entry
citing itself, in any revision, counts nothing; only the current revision
of the citing entry counts, so a removed reference stops counting; and a
retired or superseded entry cites nothing. `tik:<id>` evidence cites a
ticket, not an entry, and does not count. The counts are derived from the
revisions and links on each search and never stored. Equal ranks, and
searches without text, go most recently changed first.

## Retiring an entry

An entry written by mistake, as a test or twice is retired: `kajero retire
<id> --base <rev> --reason <why>` (or `kajero_retire`) writes a signed revision
with the content unchanged and the reason recorded. Search, the label counts
and the similar-entry check of a write leave it out; reading it by id and its
history still work, and nothing is deleted. A revision of a retired entry
keeps it retired. `kajero restore <id> --base <rev>` (or `kajero_restore`)
lifts the retirement. An entry that a better one replaces is linked as
`supersedes` instead, so the reader finds the replacement.

## Command line

```sh
export KAJERO_DB=notes.db KAJERO_ACTOR=seb KAJERO_KEY=~/.config/tik/id_ed25519
bb kajero write "Calico v3 is served by CRDs" --body - --label kind=gotcha --label system=calico < note.md
bb kajero search 'label:kind=gotcha not disputed'
bb kajero observe <id> held --evidence "kubectl get apiservices"
echo '[{:entry "e8da4903" :outcome "held" :evidence ["book dns"]}]' | bb kajero observe-many -
bb kajero revise <id> --base <revision> --body -
bb kajero labels
```

`observe-many` records one observation per item and judges each on its own:
the result lists the observation and new standing of each recorded item and
the reason for each refused one, so a book can feed every entry it checked
back in one call.

Every command prints EDN. A write whose labels nobody used before succeeds
and warns, naming the labels already in use, so agents converge on one
spelling.

## Pages

`bb web` serves the notebook to humans on `http://127.0.0.1:7270`
(`KAJERO_PORT`): saved pages, a view of any selector (which can be saved as a
page), each entry with its standing, evidence, links and history, and the
labels in use. The pages follow the system's light or dark setting, and the
moon button switches between them. Forms write, revise and observe as
`KAJERO_ACTOR`, signed with `KAJERO_KEY`; the server listens on the loopback
interface only and refuses forms from other sites.

## MCP server

`bb mcp` serves the notebook over stdio to an MCP client, with the tools
`kajero_search`, `kajero_get`, `kajero_history`, `kajero_labels`,
`kajero_write`, `kajero_revise`, `kajero_observe`, `kajero_observe_many`, `kajero_retire`,
`kajero_restore`, `kajero_link` and `kajero_migrate`. It reads
the same environment as the command line; every write names `KAJERO_ACTOR`
and is signed with `KAJERO_KEY` when it is set.

```sh
claude mcp add kajero -e KAJERO_DB=$HOME/notes.db -e KAJERO_ACTOR=claude -- sh -c 'cd /path/to/kajero && exec clojure -M:mcp'
```

## Claude skill

`skills/kajero` teaches an agent when to search the notebook, how to write
and revise entries, and when an observation is evidence. Link it into
`~/.claude/skills/kajero`.

## Development

The toolchain comes from breto: `eval "$(breto env)"`, then `bb test` and
`bb lint`.
