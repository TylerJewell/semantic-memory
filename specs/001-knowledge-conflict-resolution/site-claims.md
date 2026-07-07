# Site & README claim manifest

> **Tier:** authoritative (specs/)
> **Purpose:** Maps every load-bearing claim in README.md and site/ to the Exit Condition that proves it. EC-S03 asserts each claim appears in the docs AND its EC is green.
> **Audience:** anyone editing the README or landing page; the conform runner.
> **Lifecycle:** durable — a new marketing claim must be added here with a backing EC, or it fails EC-S03.

Format: `"<claim substring as it appears in README/site>" -> EC-<id>`. The claim string is matched
case-insensitively as a substring; the EC must have status GREEN in CONFORMANCE.md.

## Claims

    "Provenance"           -> EC-010    # every fact persists with {layer, source, conf}
    "Never blocks"         -> EC-020    # a contradicting fact is stored + flagged, never dropped
    "Deletion is scoped"   -> EC-026    # deletion runs an impact scan + adjudication
    "Declarative policy"   -> EC-013    # behavior is set in policy.md, parsed + validated
    "Runs without keys"    -> EC-001    # no-key ingest round-trip, in-JVM model, zero external network
    "surfaces the disagreement" -> EC-041   # cross-layer disagreement appears on /disagreements
