# Memory Policy — Engineering Runbook

Runbook posture: authored conflicts are rejected outright and re-extraction always
requires review, so operational facts stay authoritative and auditable.

## Sources
    authored = vault/**
    corpus   = corpus/**
    on_overlap = error
    authored.extraction = assist-only

## Precedence
    precedence      = authored > derived
    source_priority = vault/runbooks/ > vault/services/ > vault/imports/

## Cardinality
    functional   = owner, onCallRotation, primaryRegion
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
    on_authored_conflict = reject
    report               = full

## Resolution Quality
    resolution = embedding-assisted

## Deletion
    on_source_removal = freeze
    re_extraction     = require-review
    negation          = authored-only

## Provenance
    retention = full
