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
  A `missed` observation records that the revision held the answer but a
  search that should have found it did not; it carries the selector that was
  run, does not count as `held` or `failed`, never disputes the revision, and
  stays on the entry until a new revision gives it better words.
- An entry's **standing** is derived from its observations on every read: how
  many independent `held` and `failed` observations it has (one per actor per
  30 days), when it last held, whether it is disputed, and whether it went
  unconfirmed for longer than its `kind` allows (90 days for a `fact`).
- **Links** say that one entry `supersedes`, `contradicts` or is `see-also`
  another.

Which verb a judgement about an entry calls for:

| Judgement | Verb |
| --- | --- |
| correct, and it saved work | none: cite it (`[[id]]` or `see-also`), which raises its rank |
| correct, but a selector that should have found it did not | `missed` with that selector, then revise its title or labels |
| found and misleading | `failed` with evidence, then revise |
| overtaken by reality since it was written | `failed` with evidence, then revise |

`stale` is not overtaken: it means nobody re-confirmed the entry within its
kind's window, which says nothing about whether it is still right.

Every revision, observation and link is hashed over its canonical bytes and,
when the writer has a key, signed with that actor's tik key. A notebook with
`KAJERO_SIGNING=required` refuses unsigned writes; one with `optional` takes
them and marks them unsigned. Signatures are checked against an OpenSSH
allowed-signers file (`KAJERO_ACTORS`) before anything is written.

Every read checks them again against the actors file as it is at that
moment, so a record copied in by `git pull` or by hand is trusted only when
its signature verifies. A record's `signature` is `verified`, `unsigned` or
`bad-signature` (a key the actors file does not give to that actor, a body
changed after signing, an actor removed from the file, or no actors file at
all), and `signed?` is true only for `verified`. Each verdict is cached per
record, signature and actors-file digest, so a change to the actors file
takes effect on the next read. The canonical bytes are printed under fixed
print settings; a record signed over the `#:ns{...}` rendering of a
namespaced map, which the command line and the stdio MCP server printed
before those settings were fixed, still verifies.

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

Both transports speak the MCP revisions of the `initialize` handshake
(2024-11-05 to 2025-06-18) and revision 2026-07-28, in which every request
names its revision and the client's capabilities in its own `_meta` and a
client may start with `server/discover`. On `/mcp` a 2026-07-28 request must
repeat its revision, method and name in the `MCP-Protocol-Version`,
`Mcp-Method` and `Mcp-Name` headers, and is refused with 400 when they
differ from the body. That revision has no sessions, so its searches and
reads count for no session in the usage counts.

A request is held to the JSON-RPC and MCP rules before any tool runs:

- A request whose `MCP-Protocol-Version` header names 2026-07-28 but whose
  `_meta` names no revision is malformed and answers 400 with -32602.
- A notification (a message without an `id`) runs nothing and gets no
  response; `/mcp` accepts it with 202, or refuses it with the status of a
  failed 2026-07-28 check.
- A `tools/call` naming an unknown tool, or with arguments that are not an
  object, answers the JSON-RPC error -32602. A refusal by a known tool, such
  as a stale base, stays a tool result with `isError` and the reason.
- An unknown method answers -32601 in both eras.
- Text after the JSON message answers -32700; a message without
  `"jsonrpc": "2.0"`, a string method, or a string or integer id answers
  -32600; `params` that are not an object answer -32602.
- Data sent as `data_edn` (or `--data` on the command line) is one EDN form
  of at most 16 MiB, nested at most 64 deep, whose only tags are `#inst` and
  `#uuid`; anything else is refused as `bad-edn`.

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
show each cited ticket with its title and the stages it derives, read from
`tik status` at most a minute before, and `ticket=<id>` selects every entry
whose records cite it. A write checks a citation against a remembered
answer only when that answer found the ticket, so a ticket minted a moment
ago is never refused from memory.

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

A running notebook holds an in-memory index in front of its store: the
current revision of every entry, the actors and evidence of all its
revisions, its observations, every link by source and target, and every
page. Searches, the label counts and the entry pages read the index; the
history of an entry is read from the store. A write goes to the store
first and joins the index once it landed, so the store alone decides
whether a revision with a taken number is refused. Records another process
writes - the command line, a `git pull` of a flat-file notebook, a second
server - appear within a second, when a read finds the index older than
that and re-lists the store. A record file read before the `.sig` beside it
was written is read again once the signature appears.

## Signed checkpoints

A server can sign checkpoints of the notebook: a Merkle tree over every
record, earlier revisions included, whose leaves are each record's hash
computed from its canonical bytes, the sha256 of its signature, its
verdict, its kind and the entries it belongs to. The tree is a trie on the
record hashes whose shape depends only on the set of records, so two copies
holding the same records have the same tree whichever order the records
arrived in. A checkpoint signs `sha256(format, actors-file digest, tree)`.

At start, the server answers as soon as its index has loaded and warms up
in the background: it reads the latest valid checkpoint, takes its verdicts
for every record it covers with the same hash and signature under the same
actors file, and judges only the records added or changed since, in
parallel. Until the warm-up ends, a page or tool that needs a verdict judges
the signature itself, so no record reads as verified on the checkpoint's
word alone; `kajero_checkpoint` and the home page say the notebook is
warming up, missing records are pending, a `missing-records` search is
refused (HTTP 503 on the pages), and no checkpoint is signed, at shutdown
neither. Records written meanwhile join the tree before the warm-up ends. A record edited in place has another hash and is judged again. A
changed actors file invalidates every checkpoint's verdicts. A checkpoint
whose signature, format or digests do not hold is ignored whole, with a
warning, and nothing of it is used.

A record the latest checkpoint held that is gone from the store is reported
as missing: on the home page, by `kajero checkpoint`, by the MCP tool
`kajero_checkpoint` and by the selector term `missing-records`. A notebook
only grows, so a missing record was deleted or edited in place. The report
is a warning and the notebook keeps serving. Each new checkpoint carries the
missing records forward until they are back; to accept a loss, delete the
checkpoint directory, and the next start judges every record and signs a
fresh checkpoint.

| Variable | Meaning |
| --- | --- |
| `KAJERO_CHECKPOINT_KEY` | the private key that signs checkpoints, used by `serve` and `mcp` only |
| `KAJERO_CHECKPOINT_PUBKEY` | the pinned public key, as its line or a file holding it; defaults to the signing key's own |
| `KAJERO_CHECKPOINT_DIR` | where checkpoints live, by default `<KAJERO_DIR>.checkpoints` or `<KAJERO_DB>.checkpoints` |
| `KAJERO_CHECKPOINT_INTERVAL` | seconds between checkpoints, 600 |
| `KAJERO_CHECKPOINT_WRITES` | writes that cause a checkpoint, 64 |
| `KAJERO_CHECKPOINT_KEEP` | how many checkpoints are kept, 8 |

The checkpoint key must be a dedicated ed25519 key that signs no records: a
key the actors file grants is refused, and checkpoint signatures use their
own namespace, `kajero-checkpoint`. The server signs a checkpoint once warm,
every interval, after the given number of writes and when it stops; a
notebook whose root did not change signs none. Checkpoints sit beside the
store, never in it, so neither `git` nor a sync of the store carries them.
The command line reads checkpoints with `KAJERO_CHECKPOINT_PUBKEY` set, and
never signs one.

```sh
ssh-keygen -t ed25519 -N '' -C kajero-checkpoint -f ~/.config/kajero/checkpoint_ed25519
export KAJERO_CHECKPOINT_KEY=~/.config/kajero/checkpoint_ed25519
export KAJERO_CHECKPOINT_PUBKEY=~/.config/kajero/checkpoint_ed25519.pub
```

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
| `disputed`, `stale`, `superseded` | in that derived state |
| `unsigned` | whose current revision carries no signature |
| `bad-signature` | whose current revision carries a signature that does not verify against the actors file |
| `unverified` | whose current revision is unsigned or carries a bad signature |
| `retired` | that somebody retired |
| `missed` | whose current revision a search missed: they need better titles or labels |
| `missing-records` | with a record that the latest signed checkpoint held and the store no longer does |
| `coverage` | labelled `kind=coverage`: the records of questions asked |
| `never-returned` | that no search returned since the counting began |
| `never-returned=YYYY-MM-DD` | that no search returned on that day or later |
| `returned-unopened` | that searches returned at least 3 times and no session opened after any of them |
| `returned-unopened=N` | the same, for at least N returns |
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

## Search retrievals and opens

kajero counts, per entry and UTC day, how often a search returned the entry
(`returned`), how often it was read (`opened`), and how many of those reads
followed a search of the same MCP session that returned it
(`opened-after-return`). Searches over MCP, the command line and the `/view`
page count, as do reads by `kajero_get`, `kajero get` and the entry page; the
front page, saved pages and the `.edn` reads for tools do not. A search with
an empty selector, or one naming `never-returned` or `returned-unopened`,
counts nothing. A search naming `missed`, like one naming `disputed` or
`coverage`, counts the entries it returns. Recording a `missed` observation
runs no search and counts nothing: the search it reports was counted when
it ran, with the results it returned. No selector, query text, result
position or actor is kept, and ranking, citations included, does not read
the counts.

A session is the `Mcp-Session-Id` the HTTP endpoint answers an `initialize`
with, or one stdio connection. It is held in memory for 30 minutes after
its last search, and an id the server did not mint counts as none. Reads
from the command line and the pages have no session, so they add to
`opened` only.

The counts live beside the notebook and never in it: in the `usage`,
`usage_folded` and `usage_meta` tables of the database, or in
`<dir>.usage.edn` next to a flat-file notebook. They are unsigned, deleting
them loses only the measurement, and daily rows older than 180 days fold
into one total per entry. A count is kept in memory and written every 30
seconds, when the server shuts down, and when a command-line command ends,
so the search or read never waits for the usage file. A report reads the
counts written and those still in memory. Counts that can not be written
are logged on stderr and kept for the next attempt, up to 100,000 daily
per-entry counts in memory; the search or read answers as it would have, and a report that
can not read the counts is refused rather than answered as if nothing was
returned.

```sh
bb kajero usage                       # both lists, and the day counting began
bb kajero usage --since 2026-11-01 --min 10
```

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
bb kajero observe <id> missed --selector 'etherpad lernraum'
echo '[{:entry "e8da4903" :outcome "held" :evidence ["book dns"]}]' | bb kajero observe-many -
bb kajero revise <id> --base <revision> --body -
bb kajero labels
bb kajero usage
bb kajero checkpoint
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

## Agent skill

`skills/kajero` teaches an agent when to search the notebook, how to write
and revise entries, and when an observation is evidence. The MCP server
serves it, over stdio and on `/mcp`, so an agent connected to kajero reads
the skill of the version it talks to without installing anything. Linking
the directory into `~/.claude/skills/kajero` still works.

Clients that support the MCP Skills extension (`io.modelcontextprotocol/skills`)
list it with `skills/list` and `skills/get`. Claude Code from v2.1.331 does
so over revision 2026-07-28 only, and names the skill `kajero:kajero`. Every other client finds
`skill://kajero/SKILL.md` with `resources/list` and `resources/read`, and
the server instructions name that URI. The jar and the binary carry the
skill as classpath resources the build copies from `skills/`, so a release
serves exactly the files of its commit; a run from the checkout with
`clojure -M:mcp` serves none.

The extension's manifest gives each file's sha256 digest and size, which
tells a client the files it read are the ones listed, but not who listed
them. kajero therefore signs each manifest with the key the server writes
with (`KAJERO_MCP_KEY` on `/mcp`, `KAJERO_KEY` over stdio), under the
`ssh-keygen -Y` namespace `kajero-skill-manifest`, and carries the signature
in the skill entry's `_meta` under `wtf.metio/skill-manifest`. A server
without a key serves the manifest with `"signed": false` and the reason. To
check a listing, save the `skills/list` or `skills/get` result and run

```sh
KAJERO_ACTORS=~/.config/kajero/allowed_signers kajero skill verify listing.json --root skills
```

which exits 0 only when every entry's signature verifies as its actor under
that actors file and, with `--root`, every file under the directory matches
the manifest. An actors line that restricts its key with `namespaces="kajero"`
must also name `kajero-skill-manifest` for the key's manifests to verify.
A manifest the format refuses fails as `bad-manifest` before its signature
is read: a path with a `.` or `..` segment, an entry whose URI is not its
skill's `SKILL.md` or that does not list it, a file of another skill, more
than 256 files, a file over 100000 bytes or a skill over 400000 bytes. A
signature no reader could judge fails as `unchecked`.
`kajero skill manifest` prints the entries this build serves, signed as
`KAJERO_ACTOR` when `KAJERO_KEY` is set.

## Binary and uberjar

Each release on GitHub carries a native binary for Linux on amd64 with
glibc (`kajero-linux-amd64-glibc`), an uberjar for any platform with a JDK
25 (`kajero.jar`), and a `SHA256SUMS` file signed with cosign keyless
(`SHA256SUMS.bundle`). Check a download before running it:

```sh
cosign verify-blob SHA256SUMS --bundle SHA256SUMS.bundle \
  --certificate-identity-regexp '^https://github.com/metio/kajero/' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
sha256sum --check --ignore-missing SHA256SUMS
```

The first argument picks what runs, and every surface reads the same
environment as `clojure -M:web`, `-M:mcp` and `-M:run`:

```sh
kajero serve                  # the pages, and MCP on /mcp with KAJERO_MCP_TOKEN_FILE
kajero mcp                    # the MCP server over stdio
kajero search 'label:kind=gotcha'   # any command of the command line
java --enable-native-access=ALL-UNNAMED -jar kajero.jar serve
```

The binary needs no JVM. Signing a write and checking a `tik:` citation
still run `ssh-keygen` and `tik` from `PATH`, as on the JVM. It hands every
argument to kajero, an argument beginning with `-X`, `-XX:` or `-D`
included, and takes no runtime options; its heap is capped at 128 MB when
it is built. The uberjar takes the JVM's options as usual.

To build both locally, with the toolchain from breto and a static zlib
(`zlib1g-dev` on Debian and Ubuntu, `zlib-ng-compat-static` on Fedora):

```sh
eval "$(breto env)"
bb uber            # target/kajero.jar
bb build-native    # target/kajero.jar and target/kajero
bb smoke           # serve, MCP, the served skill, the command line and signatures, against a throwaway notebook
bb smoke java --enable-native-access=ALL-UNNAMED -jar target/kajero.jar
```

`dev/build-native.sh` reads extra native-image options from
`NATIVE_IMAGE_OPTS`, for example `-Ob` for a quicker build while developing,
or `--native-compiler-options=-L<dir>` for a `libz.a` outside the linker's
search path.

## Development

The toolchain comes from breto: `eval "$(breto env)"`, then `bb test` and
`bb lint`.
