#!/usr/bin/env node
// conform — runs build-tier probes for the Documentation + Meta ECs of feature 001,
// updates status, prints a rollup, and appends a receipt to conformance/history.jsonl.
// Build-tier only (no running service). Live/product ECs are out of scope for this runner.
import { readFileSync, writeFileSync, existsSync, appendFileSync, readdirSync, statSync } from 'node:fs';
import { execSync } from 'node:child_process';
import { join } from 'node:path';

const ROOT = process.cwd();
const SPEC = 'specs/001-knowledge-conflict-resolution';
const read = (p) => existsSync(p) ? readFileSync(p, 'utf8') : '';
const walk = (d) => !existsSync(d) ? [] : readdirSync(d).flatMap(f => {
  const p = join(d, f); return statSync(p).isDirectory() ? walk(p) : [p];
});
const mdFiles = [...walk(SPEC), ...walk('docs')].filter(p => p.endsWith('.md'));
const docText = mdFiles.map(read).join('\n');

const results = [];
const ec = (id, pass, detail) => results.push({ id, status: pass ? 'GREEN' : 'RED', detail });

// EC-D01 — every doc file declares tier + purpose + audience + lifecycle
{
  const req = ['Tier:', 'Purpose:', 'Audience:', 'Lifecycle:'];
  const missing = mdFiles.filter(p => { const t = read(p); return !req.every(k => t.includes(k)); });
  const manifest = existsSync(join(SPEC, 'MANIFEST.md')) || docText.includes('## Authority');
  ec('D01', manifest && missing.length === 0, `manifest=${manifest}; ${missing.length} files missing header`);
}

// EC-D03 — every /api route is documented in a contract
{
  const ep = read(join(SPEC, '..', '..', 'src/main/java/com/example/api/MemoryEndpoint.java'))
    || read('src/main/java/com/example/api/MemoryEndpoint.java');
  const routes = [...ep.matchAll(/@(?:Get|Post|Put|Delete)\("([^"]+)"\)/g)].map(m => m[1]);
  const api = read(join(SPEC, 'contracts/http-api.md'));
  const undoc = routes.filter(r => !api.includes(r));
  ec('D03', routes.length > 0 && undoc.length === 0, `${routes.length} routes; undocumented: ${undoc.join(',') || 'none'}`);
}

// EC-D04 — every code reference resolves to a real file OR is a plan-declared future file
{
  const plan = read(join(SPEC, 'plan.md'));
  const refs = [...docText.matchAll(/([\w./-]+\.java)(?::\d+)?/g)].map(m => m[1]);
  const uniq = [...new Set(refs)];
  const dangling = uniq.filter(r => {
    const base = r.split('/').pop();
    let tracked = false;
    try { tracked = execSync(`git ls-files "**/${base}"`, { encoding: 'utf8' }).trim() !== ''; } catch {}
    if (tracked) return false;
    return !plan.includes(base);   // unbuilt ref is OK only if the plan declares it
  });
  ec('D04', dangling.length === 0, `${uniq.length} refs; undeclared-dangling: ${dangling.join(',') || 'none'}`);
}

// EC-D05 — glossary exists + no banned usage ("contested" applied cross-layer)
{
  const glossary = docText.includes('## Glossary');
  // Violation = "contested" ASSERTED of a cross-layer case. Excused: negated/corrective usage,
  // and lines that DEFINE the ban itself (self-reference in EC-D05).
  const scan = docText.split('\n').filter(l => !/banned|EC-D05/i.test(l)).join('\n');
  const banned = [...scan.matchAll(/.{0,60}contested.{0,60}/gi)]
    .filter(m => /cross-layer/i.test(m[0]) && !/\b(not|never|n't|reserved|only)\b/i.test(m[0])).length;
  ec('D05', glossary && banned === 0, `glossary=${glossary}; banned cross-layer+contested hits=${banned}`);
}

// EC-D06 — bidirectional trace: every FR/SC and every EC appears in the traceability matrix
{
  const spec = read(join(SPEC, 'spec.md'));
  const conf = read(join(SPEC, 'CONFORMANCE.md'));
  const trace = read(join(SPEC, 'traceability.md'));
  const specIds = [...new Set([...spec.matchAll(/\b(FR-[A-Z]?\d+[a-z]?|SC-\d+)\b/g)].map(m => m[1]))];
  const ecIds = [...new Set([...conf.matchAll(/\bEC-[A-Z]?\d+\b/g)].map(m => m[0]))];
  const idOrphans = specIds.filter(id => !trace.includes(id));
  const ecOrphans = ecIds.filter(id => !trace.includes(id));
  ec('D06', !!trace && idOrphans.length === 0 && ecOrphans.length === 0,
    `matrix=${!!trace}; untraced FR/SC: ${idOrphans.join(',') || 'none'}; untraced EC: ${ecOrphans.join(',') || 'none'}`);
}

// EC-D07 — env vars follow the canonical registry
{
  const allow = new Set(['ANTHROPIC_API_KEY','OPENAI_API_KEY','GOOGLE_AI_GEMINI_API_KEY','VERTEX_AI_API_KEY']);
  // Scan USAGE only — drop lines that define/ban generic names (self-reference in EC-D07).
  const usage = docText.split('\n').filter(l => !/generic|non-canonical|canonical registry|allowlist|allowed list/i.test(l)).join('\n');
  const vars = [...new Set([...usage.matchAll(/\b[A-Z][A-Z0-9]+_(?:API_)?KEY\b/g)].map(m => m[0]))];
  const bad = vars.filter(v => !allow.has(v));
  ec('D07', bad.length === 0, `env vars seen: ${vars.join(',') || 'none'}; non-canonical: ${bad.join(',') || 'none'}`);
}

// EC-D08 — every open question is resolved-with-a-decision or explicitly [OPEN: owner]  (deterministic)
{
  const research = read(join(SPEC, 'research.md'));
  // Each research "## R<n>" section corresponds to an open question; it MUST carry a **Decision**.
  const sections = research.split(/\n(?=## R\d)/).filter(s => /^## R\d/.test(s));
  const undecided = sections.filter(s => !/\*\*Decision\*\*/.test(s)).map(s => (s.match(/## (R\d\S*)/) || [])[1]);
  // Any literal [OPEN ...] marker in the open-question docs must name an owner (contain a ':').
  // Scope to research.md + plan.md — where open questions live — not CONFORMANCE's own format examples.
  const oqText = research + read(join(SPEC, 'plan.md'));
  const orphanOpen = [...oqText.matchAll(/\[OPEN(?![^\]]*:)[^\]]*\]/gi)].length;
  ec('D08', undecided.length === 0 && orphanOpen === 0,
    `${sections.length} research questions; undecided: ${undecided.join(',') || 'none'}; ownerless [OPEN] markers: ${orphanOpen}`);
}

// EC-D09 — every fenced json block in contracts parses
{
  const contracts = walk(join(SPEC, 'contracts')).map(read).join('\n') + read(join(SPEC, 'quickstart.md'));
  const blocks = [...contracts.matchAll(/```json\n([\s\S]*?)```/g)].map(m => m[1]);
  let bad = 0, notes = [];
  for (const b of blocks) { try { JSON.parse(b); } catch (e) { bad++; notes.push(e.message.slice(0, 40)); } }
  ec('D09', bad === 0, `${blocks.length} json blocks; invalid: ${bad} ${notes.join('|')}`);
}

// EC-M1 — every SC traces to an EC (subset of D06, kept for the meta rollup)
{
  const spec = read(join(SPEC, 'spec.md'));
  const conf = read(join(SPEC, 'CONFORMANCE.md'));
  const scs = [...new Set([...spec.matchAll(/\bSC-\d+\b/g)].map(m => m[0]))];
  const orphans = scs.filter(id => !conf.includes(id));
  ec('M1', scs.length > 0 && orphans.length === 0, `${scs.length} SCs; untraced: ${orphans.join(',') || 'none'}`);
}

// EC-D02 / EC-D10 — Model-1 durability guard (auto portion; full semantic review stays human-signoff)
// Any docs/ file that touches the superseded contested-semantics MUST carry a supersede marker,
// and the specs/ authority MUST state the RESOLVED-vs-CONTESTED distinction.
{
  const norm = (p) => p.replace(/\\/g, '/');
  const glossary = read(join(SPEC, 'glossary.md'));
  const spec = read(join(SPEC, 'spec.md'));
  const authority = /RESOLVED/.test(glossary) && /CONTESTED/.test(glossary) && /FR-C4a/.test(spec);
  const touchers = mdFiles.filter(p => norm(p).startsWith('docs/') &&
    /(contested|freeze-incumbent|both-contested)/i.test(read(p)));
  const unmarked = touchers.filter(p => !/supersed/i.test(read(p)));
  ec('D02', authority && unmarked.length === 0,
    `authority=${authority}; unannotated docs divergences: ${unmarked.map(p => norm(p).split('/').pop()).join(',') || 'none'}`);
  ec('D10', touchers.length > 0 && unmarked.length === 0,
    `${touchers.length} docs touch superseded semantics; unmarked: ${unmarked.map(p => norm(p).split('/').pop()).join(',') || 'none'}`);
}

// EC-D11 — local-LLM provider decision is consistent (Jlama chosen; Ollama only as rejected alt)
{
  const norm = (p) => p.replace(/\\/g, '/');
  const jlamaChosen = /Decision\**:\s*\**Jlama/i.test(read(join(SPEC, 'research.md')));
  const asChoice = [/LLM:\s*ollama/i, /provider\s*\(ollama/i, /ollama running on/i, /Decision\**:\s*\**ollama/i];
  const specMd = mdFiles.filter(p => norm(p).startsWith('specs/'));
  const offenders = specMd.filter(p => asChoice.some(re => re.test(read(p))));
  ec('D11', jlamaChosen && offenders.length === 0,
    `jlama-chosen=${jlamaChosen}; ollama-as-active-choice in: ${offenders.map(p => norm(p).split('/').pop()).join(',') || 'none'}`);
}

// EC-006 — no model call site bypasses the swap seam (embeddings via SPI, generative via ModelProvider)
{
  const norm = (p) => p.replace(/\\/g, '/');
  const srcFiles = walk('src/main/java').filter(p => p.endsWith('.java'));
  const bypass = srcFiles.filter(p => {
    const t = read(p), n = norm(p);
    const inSpi = n.includes('/application/embeddings/');
    const hit = /GeminiEmbeddings\.embed\s*\(/.test(t) || /generativelanguage\.googleapis\.com/.test(t);
    return hit && !inSpi;   // a model host / static embed ref outside the sanctioned SPI package = bypass
  });
  ec('006', bypass.length === 0,
    `${srcFiles.length} src files; call sites bypassing the seam: ${bypass.map(p => norm(p).split('/').pop()).join(',') || 'none'}`);
}

// EC-D12 — no nagging footnotes: CONFORMANCE.md is free of perpetual-hedge language (self-referential, deterministic)
{
  // Scan CONFORMANCE.md EXCEPT the EC-D12 block itself (which must name the banned phrases to define them).
  const confRaw = read(join(SPEC, 'CONFORMANCE.md'));
  const conf = confRaw.replace(/EC-D12[\s\S]*?\n  status:\s*\S+[^\n]*/m, 'EC-D12 <self-definition elided>');
  const hedges = ['manual ' + 'audit', 'sign-off ' + 'pending', 'signoff ' + 'pending',
    'still ' + 'required', 'revisit', 'for ' + 'now'];
  const hedgeHits = hedges.filter(h => new RegExp(h, 'i').test(conf));
  const todoHits = (conf.match(/\bTODO\b/g) || []).length;
  const badStatus = [...conf.matchAll(/^\s*status:\s*(\S+)/gim)].map(m => m[1])
    .filter(s => !['GREEN', 'RED', 'OPEN'].includes(s));
  ec('D12', hedgeHits.length === 0 && todoHits === 0 && badStatus.length === 0,
    `hedge phrases: ${hedgeHits.join(',') || 'none'}; TODO: ${todoHits}; non-terminal status: ${badStatus.join(',') || 'none'}`);
}

// ---- build-tier Java lane (opt-in via CONFORM_MVN=1; runs mvn compile+test) ----
if (process.env.CONFORM_MVN === '1') {
  const sh = (cmd) => { try { execSync(cmd, { stdio: 'pipe' }); return 0; } catch (e) { return e.status || 1; } };
  const compileOk = sh('mvn -q -DskipTests compile') === 0;
  const testOk = compileOk && sh('mvn -q test') === 0;
  let surefire = '';
  try { surefire = readdirSync('target/surefire-reports').join(' '); } catch {}
  const has = (re) => re.test(surefire);
  const gate = (id, re, label) => ec(id, testOk && has(re), `mvn compile=${compileOk} test=${testOk}; ${label} ${has(re) ? 'passed' : 'absent'}`);
  gate('003', /EmbeddingsSeamTest/, 'EmbeddingsSeamTest');
  ['023', '029', '031', '032', '033'].forEach(id => gate(id, /ResolutionCascadeTest/, 'ResolutionCascadeTest'));
  gate('002', /ModelSelectorTest/, 'ModelSelectorTest');
  gate('007', /JlamaCompatTest/, 'JlamaCompatTest');
  gate('024', /ContestedReadTest/, 'ContestedReadTest');
  gate('028', /ContestedReadTest/, 'ContestedReadTest');
  gate('030', /EntityResolverTest/, 'EntityResolverTest');
  gate('013', /PolicyParserTest/, 'PolicyParserTest');
  gate('015', /AuthorLintTest/, 'AuthorLintTest');
  ['020', '021', '022'].forEach(id => gate(id, /IngestGateTest/, 'IngestGateTest'));
  gate('025', /DeletionTest/, 'DeletionTest');
  gate('027', /NegationTest/, 'NegationTest');
  gate('005', /StructuredOutputRepairTest/, 'StructuredOutputRepairTest');
  gate('014', /PresetBehaviorTest/, 'PresetBehaviorTest');
  gate('011', /SourceLayerResolverTest/, 'SourceLayerResolverTest');
  ['010', '016'].forEach(id => gate(id, /VaultSyncTest/, 'VaultSyncTest'));
  gate('004', /ModelIdGuardTest/, 'ModelIdGuardTest');
  gate('012', /SourceLayerResolverTest/, 'SourceLayerResolverTest');   // vault-leak detection (auto guard; sign-off pending)
  gate('026', /DeletionImpactTest/, 'DeletionImpactTest');             // deletion-impact scan (revised); sign-off pending
}

// ---- @live lane (opt-in via CONFORM_LIVE=1; runs failsafe ITs against real Fluree) ----
if (process.env.CONFORM_LIVE === '1') {
  const sh = (cmd) => { try { execSync(cmd, { stdio: 'pipe' }); return 0; } catch (e) { return e.status || 1; } };
  const ok = sh('mvn -q test-compile failsafe:integration-test failsafe:verify') === 0;
  let fr = '';
  try { fr = readdirSync('target/failsafe-reports').join(' '); } catch {}
  const g = (id, re, label) => ec(id, ok && re.test(fr), `mvn verify=${ok}; ${label} ${re.test(fr) ? 'passed' : 'absent'}`);
  g('040', /SyncEndpointIT/, 'SyncEndpointIT');
  g('043', /SyncEndpointIT|SyncGuardsIT/, 'sync guards IT');
  g('041', /SurfaceIT|DisagreementsIT/, 'disagreements IT');
  g('042', /SurfaceIT|ConflictsIT/, 'conflicts IT');
  g('051', /UnifiedIngestIT/, 'UnifiedIngestIT');   // Phase H — /remember fact reaches the engine
  g('052', /FactLookupIT/, 'FactLookupIT');         // Phase H — GET /fact served value
  g('054', /ObserveIT/, 'ObserveIT');               // Phase H — stats/recent over assertions
  g('001', /NoKeyRoundTripIT/, 'NoKeyRoundTripIT');  // EC-001 — no-key round-trip auto-parts (zero-network is human-signoff)
}

// EC-050 / EC-053 — Phase H static guards (drive the pure-fact-store refactor; RED until done)
{
  const norm = (p) => p.replace(/\\/g, '/');
  const src = walk('src/main/java').filter(p => p.endsWith('.java'));
  // EC-050: no fact write goes through the legacy FlureeClient.remember (outside FlureeClient itself)
  const legacy = src.filter(p => !norm(p).endsWith('/FlureeClient.java') && /FlureeClient\.remember\s*\(/.test(read(p)));
  ec('050', legacy.length === 0, `legacy FlureeClient.remember call sites: ${legacy.map(p => norm(p).split('/').pop()).join(',') || 'none'}`);
  // EC-053: the RAG-demo classes are gone
  const dead = ['QaAgent', 'SearchTypeClassifierAgent', 'ChunksRetriever', 'RagCompletionRetriever',
    'GraphCompletionRetriever', 'HybridRetriever', 'LexicalChunksRetriever', 'FeelingLuckyRetriever'];
  const present = dead.filter(c => src.some(p => norm(p).endsWith('/' + c + '.java')));
  ec('053', present.length === 0, `decommissioned classes still present: ${present.join(',') || 'none'}`);
}

// EC-M2 — uncovered-behavior guard (auto portion; sign-off pending): every ingest Outcome has an owning EC
{
  const outcomeFile = read('src/main/java/com/example/application/ingest/Outcome.java');
  const outcomes = [...new Set([...outcomeFile.matchAll(/\b(NEW|CORROBORATING|CONFLICTING|SUPPRESSED|REJECTED)\b/g)].map(m => m[1]))];
  const map = { NEW: 'EC-022', CORROBORATING: 'EC-021', CONFLICTING: 'EC-020', SUPPRESSED: 'EC-027', REJECTED: 'EC-014' };
  const conf = read(join(SPEC, 'CONFORMANCE.md'));
  const uncovered = outcomes.filter(o => !map[o] || !conf.includes(map[o]));
  ec('M2', outcomes.length > 0 && uncovered.length === 0, `${outcomes.length} ingest outcomes; uncovered: ${uncovered.join(',') || 'none'} [auto guard; sign-off pending]`);
}

// ---- rollup + receipt ----
const green = results.filter(r => r.status === 'GREEN').length;
const red = results.filter(r => r.status === 'RED').length;
console.log('\nconform — build-tier Docs/Meta probes (feature 001)\n');
for (const r of results) console.log(`  ${r.status === 'GREEN' ? 'GREEN' : 'RED  '} EC-${r.id}  ${r.detail}`);
console.log(`\n  → ${green} GREEN / ${red} RED  of ${results.length} runnable ECs\n`);

let git_sha = 'unknown';
try { git_sha = execSync('git rev-parse --short HEAD', { encoding: 'utf8' }).trim(); } catch {}
const stamp = process.env.CONFORM_TS || new Date().toISOString();
const receipt = { run_id: `${stamp.replace(/[-:.]/g,'').slice(0,15)}Z`, git_sha, branch: 'main',
  model: 'claude-opus-4-8', runner: 'conform.mjs@0.1', timestamp: stamp,
  scope: 'docs+meta build-tier', ec_green: green, ec_red: red,
  reds: results.filter(r => r.status === 'RED').map(r => `EC-${r.id}`) };
appendFileSync('conformance/history.jsonl', JSON.stringify(receipt) + '\n');
console.log(`  receipt appended → conformance/history.jsonl (${git_sha})`);
process.exit(red > 0 ? 1 : 0);
