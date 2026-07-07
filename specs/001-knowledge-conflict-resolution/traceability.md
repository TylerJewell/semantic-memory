# Traceability Matrix — Knowledge Conflict Resolution

> **Tier:** authoritative (specs/)
> **Purpose:** bidirectional trace — every FR/SC maps to ≥1 EC, and every EC maps back. EC-D06 enforces this.
> **Audience:** planners verifying coverage; the conform runner.
> **Lifecycle:** durable — regenerated whenever an FR, SC, or EC is added.

## Requirement → EC

| Req | EC(s) |
|---|---|
| FR-A1 | EC-002 |
| FR-A2 | EC-001 |
| FR-A3 | EC-003, EC-006 |
| FR-A4 | EC-002, EC-006, EC-007 |
| FR-A5 | EC-004 |
| FR-A6 | EC-005 |
| FR-B1 | EC-010 |
| FR-B2 | EC-011 |
| FR-B3 | EC-016 |
| FR-B4 | EC-012 |
| FR-B5 | EC-020 |
| FR-C1 | EC-010 |
| FR-C2 | EC-029 |
| FR-C3 | EC-020 |
| FR-C4 | EC-031, EC-033 |
| FR-C4a | EC-020 |
| FR-C5 | EC-023, EC-029 |
| FR-C6 | EC-023 |
| FR-C7 | EC-024, EC-028 |
| FR-D1 | EC-020, EC-021, EC-022 |
| FR-D2 | EC-020 |
| FR-D3 | EC-021 *(on_exact_file_dup: known gap — no dedicated EC)* |
| FR-D4 | EC-020 |
| FR-D5 | EC-030 |
| FR-E1 | EC-025 |
| FR-E2 | EC-025 |
| FR-E3 | EC-026 |
| FR-E4 | EC-027 |
| FR-F1 | EC-013 |
| FR-F2 | EC-013 |
| FR-F3 | EC-014 |
| FR-X1 | EC-010, EC-040 |
| FR-X1-live | EC-040, EC-041, EC-042, EC-043 (Phase G @live HTTP surface) |
| FR-X2 | EC-015 |
| FR-X3 | *(OPEN — no EC yet; workflow crash-safety, candidate EСTestKit probe)* |

## Success Criterion → EC

| SC | EC(s) |
|---|---|
| SC-001 | EC-001 |
| SC-002 | EC-010 |
| SC-003 | EC-020 |
| SC-004 | EC-021 |
| SC-005 | EC-023 |
| SC-006 | EC-014 |
| SC-007 | EC-025, EC-026 |

## EC → back-trace (every EC accounted for)

Product & meta: EC-001 EC-002 EC-003 EC-004 EC-005 EC-006 EC-007 EC-010 EC-011 EC-012 EC-013 EC-014 EC-015
EC-016 EC-020 EC-021 EC-022 EC-023 EC-024 EC-025 EC-026 EC-027 EC-028 EC-029 EC-030 EC-031
EC-032 EC-033 EC-040 EC-041 EC-042 EC-043 EC-050 EC-051 EC-052 EC-053 EC-054 EC-M1 EC-M2 — each maps to a Req/SC above (M1/M2 are process meta-ECs; EC-040..043 are the Phase G @live HTTP surface tracing FR-X1/FR-D4; EC-050..054 are the Phase H unified fact-store architecture — see architecture.md — tracing FR-X1 + the 2026-07-06 pure-fact-store realignment).

Documentation ECs (process/quality — trace to the artifact-integrity goal, not a product FR):
EC-D01 EC-D02 EC-D03 EC-D04 EC-D05 EC-D06 EC-D07 EC-D08 EC-D09 EC-D10 EC-D11 EC-D12.

Site & README ECs (cover README.md + site/, aligned to Akka principles — akka.ai/llms.txt):
EC-S01 EC-S02 EC-S03 EC-S04 EC-S05 EC-S06 EC-S07 EC-S08 EC-S09 —
EC-S01 Transparency About Identity · EC-S02 + EC-S08 Never Fail (tone + honesty) ·
EC-S03 + EC-S09 Self-Governing (claims-trace + governance) · EC-S04 accuracy ·
EC-S05 For Every Team (SDD) · EC-S06 Never Fail (portability) ·
EC-S07 Platform Integration (one coherent story).
