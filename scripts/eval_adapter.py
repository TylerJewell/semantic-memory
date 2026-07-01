"""
HotpotQA eval harness against the running Akka memory service.

Pulls real distractor-split validation items from cached HuggingFace data, ingests
ALL 10 paragraphs per item (gold + distractors — the actual HotpotQA challenge),
then asks each retriever strategy the multi-hop question. Scores with the official
HotpotQA token-level F1 and exact match.

Usage:
    python scripts/eval_adapter.py                # 10 items, all strategies
    python scripts/eval_adapter.py --items 5      # smaller run
    python scripts/eval_adapter.py --strategies RAG,GRAPH

Requires the Akka service on http://localhost:9000 and Fluree on :8090. Uses only
the Python stdlib.
"""

from __future__ import annotations

import argparse
import json
import re
import string
import sys
import time
import urllib.request
from collections import Counter

# Force UTF-8 on stdout so non-ASCII answers don't crash the Windows console.
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

BASE = "http://localhost:9000"
SAMPLE_FILE = "scripts/eval_data/hotpot_sample.json"
ALL_STRATEGIES = ["CHUNKS", "RAG", "GRAPH", "HYBRID", "FEELING_LUCKY"]


# ----------------------------- HTTP -----------------------------

def _post(path: str, payload: dict, timeout: int = 180) -> dict:
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        BASE + path, data=data, headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read())


# ------------------- HotpotQA-official scoring ------------------
# Reference: https://github.com/hotpotqa/hotpot/blob/master/hotpot_evaluate_v1.py

_ARTICLES = re.compile(r"\b(a|an|the)\b", re.UNICODE)
_PUNCT_TABLE = str.maketrans("", "", string.punctuation)


def _normalize(s: str) -> str:
    s = s.lower()
    s = s.translate(_PUNCT_TABLE)
    s = _ARTICLES.sub(" ", s)
    s = " ".join(s.split())
    return s


def f1_score(prediction: str, gold: str) -> float:
    p_toks = _normalize(prediction).split()
    g_toks = _normalize(gold).split()
    # HotpotQA special-cases yes/no/noanswer
    if gold.strip().lower() in {"yes", "no", "noanswer"}:
        return 1.0 if _normalize(prediction) == _normalize(gold) else 0.0
    if not p_toks or not g_toks:
        return 0.0
    common = Counter(p_toks) & Counter(g_toks)
    overlap = sum(common.values())
    if overlap == 0:
        return 0.0
    precision = overlap / len(p_toks)
    recall = overlap / len(g_toks)
    return 2 * precision * recall / (precision + recall)


def exact_match(prediction: str, gold: str) -> float:
    return 1.0 if _normalize(prediction) == _normalize(gold) else 0.0


# ----------------------------- Eval -----------------------------

def _flatten_paragraphs(context: dict) -> list:
    """A HotpotQA row's `context` is parallel arrays of titles and per-paragraph
    sentence-lists. Flatten each paragraph into one chunk including its title so
    the retriever has both topical and content signal."""
    titles = context["title"]
    sents = context["sentences"]
    out = []
    for title, ss in zip(titles, sents):
        para = "".join(ss).strip()
        if para:
            out.append(f"{title}. {para}")
    return out


def run(items: int, strategies: list) -> None:
    with open(SAMPLE_FILE, encoding="utf-8") as f:
        rows = [r["row"] for r in json.load(f)["rows"]][:items]

    print(f"\nLoaded {len(rows)} HotpotQA distractor-split items.")
    print(f"Strategies: {', '.join(strategies)}\n")

    # 1. Ingest all paragraphs of all items (including distractors).
    t_ingest = time.time()
    n_paras = 0
    for i, row in enumerate(rows, 1):
        for para in _flatten_paragraphs(row["context"]):
            try:
                _post("/api/remember", {"text": para}, timeout=180)
                n_paras += 1
            except Exception as e:
                print(f"  ! remember failed (item {i}): {e}", file=sys.stderr)
        print(f"  ingested item {i}/{len(rows)}  (total paras: {n_paras})")
    print(f"\nIngest done: {n_paras} paragraphs in {time.time()-t_ingest:.1f}s\n")

    # 2. Ask each strategy each question. Score per strategy.
    results = {s: {"em": 0.0, "f1": 0.0, "ms": 0.0, "n": 0} for s in strategies}
    for i, row in enumerate(rows, 1):
        q, gold = row["question"], row["answer"]
        print(f"Q{i}: {q}   [gold: {gold!r}]")
        for s in strategies:
            t0 = time.time()
            try:
                resp = _post("/api/recall",
                             {"question": q, "strategy": s}, timeout=180)
                answer = (resp.get("answer") or "").strip()
            except Exception as e:
                answer = ""
                print(f"  ! recall {s} failed: {e}", file=sys.stderr)
            dt = (time.time() - t0) * 1000
            em = exact_match(answer, gold)
            f1 = f1_score(answer, gold)
            r = results[s]
            r["em"] += em
            r["f1"] += f1
            r["ms"] += dt
            r["n"] += 1
            mark = "+" if em else ("~" if f1 >= 0.5 else " ")
            print(f"  {mark} {s:<14} f1={f1:.2f} em={em:.0f}  ms={dt:.0f}   "
                  f"answer={answer[:90]!r}")
        print()

    # 3. Report.
    print("=" * 72)
    print(f"{'strategy':<16}{'EM':>8}{'F1':>10}{'mean_ms':>12}{'n':>6}")
    print("-" * 72)
    for s in strategies:
        r = results[s]
        n = max(r["n"], 1)
        print(f"{s:<16}{r['em']/n:>8.2f}{r['f1']/n:>10.2f}"
              f"{r['ms']/n:>12.0f}{r['n']:>6}")
    print("=" * 72)


# ----------------------------- CLI ------------------------------

if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--items", type=int, default=10,
                    help="how many HotpotQA items to evaluate (max 20)")
    ap.add_argument("--strategies", default=",".join(ALL_STRATEGIES),
                    help="comma-separated subset of CHUNKS,RAG,GRAPH,HYBRID,"
                         "FEELING_LUCKY,LEXICAL")
    args = ap.parse_args()
    strategies = [s.strip().upper() for s in args.strategies.split(",") if s.strip()]
    run(items=max(1, min(20, args.items)), strategies=strategies)
