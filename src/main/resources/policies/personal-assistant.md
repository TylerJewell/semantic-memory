# Memory Policy — Personal Assistant

Everyday personal-assistant defaults: conflicts are flagged for review, never
silently dropped, and removed sources leave their derived facts orphaned.

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
