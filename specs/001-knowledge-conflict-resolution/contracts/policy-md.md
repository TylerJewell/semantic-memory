# Contract — `policy.md` directive grammar

> **Tier:** authoritative (specs/)
> **Purpose:** Specifies the `policy.md` directive grammar — parsing rules, sections, and keys.
> **Audience:** Implementers of the policy parser and authors writing policy files.
> **Lifecycle:** durable

**The config surface.** Markdown with embedded `key = value` directives: prose sections carry
the *why*; indented directive blocks under `##` headings carry the machine-parseable *what*.
(Chosen over nested YAML and pure INI — see feedback note in design doc §8.)

## Parsing rules (FR-F1, FR-F2)

1. A directive is a line matching `^\s+<key>\s*=\s*<value>` **indented** under an `##` section.
2. Non-indented lines and prose are ignored by the parser.
3. `#` begins an inline comment; text after it is stripped.
4. `*` is the wildcard/default token (e.g. `multi_valued = *`).
5. Values are comma-separated lists where a list is expected; `>` denotes ordering; grouped
   items separated by `,` within a `>`-segment form an equal-priority bucket (R7).
6. **Load-time validation**: unknown keys → error; contradictory directives → error; a path
   claimed by both `authored` and `corpus` globs → error (`on_overlap`).

## Recognized sections & keys

| Section | Key | Values |
|---|---|---|
| Sources | `authored` | glob (e.g. `vault/**`) |
| | `corpus` | glob (e.g. `corpus/**`) |
| | `on_overlap` | `error` |
| | `authored.extraction` | `assist-only` |
| Precedence | `precedence` | `authored > derived` |
| | `source_priority` | ordered/bucketed glob list |
| Cardinality | `functional` | predicate list |
| | `multi_valued` | predicate list or `*` |
| Resolution | `resolve_by.authored` | ordered discriminators (no `recency`) |
| | `resolve_by.derived` | ordered discriminators |
| Conflict Resolution | `on_conflict` | `flag \| reject \| replace` |
| | `scope` | `functional-only` |
| | `contested_read` | `freeze-incumbent \| both-contested \| unknown` |
| Ingest Gate | `on_exact_file_dup` | `reject` |
| | `on_vault_leak` | `reject` |
| | `on_authored_match` | `corroborate` |
| | `on_authored_conflict` | `flag` |
| | `report` | `summary \| full \| none` |
| Resolution Quality | `resolution` | `strict \| alias-aware \| embedding-assisted` |
| Deletion | `on_source_removal` | `cascade \| orphan \| freeze \| archive` |
| | `re_extraction` | `overwrite \| append-versioned \| diff-add-only \| require-review` |
| | `negation` | `authored-only` |
| Provenance | `retention` | `full` |

## Reference policy (default "personal assistant" — must parse & validate)

```markdown
# Memory Policy — Personal Assistant

## Sources
    authored = vault/**
    corpus   = corpus/**
    on_overlap = error
    authored.extraction = assist-only

## Precedence
    precedence      = authored > derived
    source_priority = vault/people/ > vault/orgs/ > vault/imports/

## Cardinality
    functional   = birthDate, homeCity, currentEmployer
    multi_valued = *

## Resolution
    resolve_by.authored = layer-precedence, source-priority
    resolve_by.derived  = layer-precedence, source-priority, confidence, recency

## Conflict Resolution
    on_conflict     = flag
    scope           = functional-only
    contested_read  = freeze-incumbent

## Ingest Gate
    on_exact_file_dup    = reject
    on_vault_leak        = reject
    on_authored_match    = corroborate
    on_authored_conflict = flag
    report               = summary

## Resolution Quality
    resolution = alias-aware

## Deletion
    on_source_removal = orphan
    re_extraction     = require-review
    negation          = authored-only

## Provenance
    retention = full
```

## Preset personalities (FR-F3) — one-line changes flip behavior

| Preset | Key edits |
|---|---|
| Compliance / legal | `on_conflict = reject`; derived is suggestion-only |
| CRM / enrichment | `on_conflict = replace`, drop precedence, add `ttl` + `min_confidence` |
| Eng runbook | `on_authored_conflict = reject`, `re_extraction = require-review` |
| Personal assistant | the reference above (`flag` + `orphan` + `corroborate`) |
