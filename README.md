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

An entry labelled `kind=schema` holds a [malli](https://github.com/metosin/malli)
schema as its data, for example
`[:vector [:map [:name :string] [:cpus :int]]]`. A revision labelled
`schema=<that entry>` is checked against the schema's current revision when
it is written, records which schema revision it fit, and orders its table
columns by the schema. Schemas are data only: a schema that would evaluate a
function is refused.

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
| `disputed`, `stale`, `unsigned`, `superseded` | in that derived state |
| `~text` or a bare word | whose title, body or labels contain the text |

## Command line

```sh
export KAJERO_DB=notes.db KAJERO_ACTOR=seb KAJERO_KEY=~/.config/tik/id_ed25519
bb kajero write "Calico v3 is served by CRDs" --body - --label kind=gotcha --label system=calico < note.md
bb kajero search 'label:kind=gotcha not disputed'
bb kajero observe <id> held --evidence "kubectl get apiservices"
bb kajero revise <id> --base <revision> --body -
bb kajero labels
```

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
`kajero_write`, `kajero_revise`, `kajero_observe` and `kajero_link`. It reads
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
