# Memory Policy — Compliance / Legal

Strict compliance posture: derived facts are suggestion-only and any conflicting
write is rejected rather than flagged. Removed sources cascade their facts out.

## Sources
    authored = vault/**
    corpus   = corpus/**
    on_overlap = error
    authored.extraction = assist-only

## Precedence
    precedence      = authored > derived
    source_priority = vault/legal/ > vault/people/ > vault/imports/

## Cardinality
    functional   = birthDate, homeCity, currentEmployer
    multi_valued = *

## Resolution
    resolve_by.authored = layer-precedence, source-priority
    resolve_by.derived  = layer-precedence, source-priority, confidence, recency

## Conflict Resolution
    on_conflict     = reject
    scope           = functional-only
    contested_read  = both-contested

## Ingest Gate
    on_exact_file_dup    = reject
    on_vault_leak        = reject
    on_authored_match    = corroborate
    on_authored_conflict = reject
    report               = full

## Resolution Quality
    resolution = strict

## Deletion
    on_source_removal = cascade
    re_extraction     = require-review
    negation          = authored-only

## Provenance
    retention = full
