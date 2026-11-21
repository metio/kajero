<!--
SPDX-FileCopyrightText: The kajero Authors
SPDX-License-Identifier: 0BSD
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

## Development

The toolchain comes from breto: `eval "$(breto env)"`, then `bb test` and
`bb lint`.
