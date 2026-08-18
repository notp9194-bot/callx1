#!/usr/bin/env node
/**
 * ══════════════════════════════════════════════════════════════════════
 *  check_jank_regression.js — Pre-merge jank regression gate
 *  ─────────────────────────────────────────────────────────────────────
 *  compare_results.js (existing script) prints a nice before/after table
 *  for humans but never fails the build. This script is the actual CI
 *  gate: it loads the checked-in baseline (macrobenchmark/baselines/
 *  jank_baseline.json), computes P50/P90/P99 from the *current* run's
 *  benchmark JSON output, and exits non-zero if any tracked metric's P99
 *  regressed beyond the allowed tolerance — which is what blocks the PR
 *  from merging (see .github/workflows/macrobenchmark.yml).
 *
 *  USAGE:
 *    node macrobenchmark/scripts/check_jank_regression.js <results-dir>
 *      [--baseline <path>] [--tolerance-pct <n>] [--update-baseline]
 *
 *    <results-dir>     Directory containing the *-benchmarkData.json files
 *                      the AndroidX Benchmark test task writes out (under
 *                      macrobenchmark/build/outputs/connected_android_test_
 *                      additional_output/.../ by default).
 *    --baseline        Defaults to macrobenchmark/baselines/jank_baseline.json
 *    --tolerance-pct   How much P99 may regress before failing. Default 20.
 *                      (GMDs are noisier than real hardware, hence a wider
 *                      margin than you'd use for a real-device baseline.)
 *    --update-baseline Instead of gating, overwrite the baseline file with
 *                       this run's numbers. Use deliberately after a real
 *                       perf-affecting change — never run this automatically
 *                       in CI.
 *
 *  EXIT CODE:
 *    0 — no metric regressed beyond tolerance (or --update-baseline ran)
 *    1 — at least one metric regressed; CI step fails, PR is blocked
 * ══════════════════════════════════════════════════════════════════════
 */

const fs   = require('fs');
const path = require('path');

const args = process.argv.slice(2);
const resultsDir = args[0] && !args[0].startsWith('--') ? args[0] : null;

function flagValue(name, fallback) {
  const idx = args.indexOf(name);
  return idx !== -1 && args[idx + 1] ? args[idx + 1] : fallback;
}

const baselinePath   = flagValue('--baseline', path.join(__dirname, '..', 'baselines', 'jank_baseline.json'));
const tolerancePct    = parseFloat(flagValue('--tolerance-pct', process.env.JANK_REGRESSION_TOLERANCE_PCT || '20'));
const updateBaseline  = args.includes('--update-baseline');

if (!resultsDir) {
  console.error('Usage: check_jank_regression.js <results-dir> [--baseline <path>] [--tolerance-pct <n>] [--update-baseline]');
  process.exit(2);
}

if (!fs.existsSync(resultsDir)) {
  console.error(`Results directory not found: ${resultsDir}`);
  console.error('Did the benchmark AndroidTest task actually run? Check the previous CI step.');
  process.exit(2);
}

// ── Load current run's benchmark JSON files ────────────────────────────
const jsonFiles = fs.readdirSync(resultsDir)
  .filter(f => f.endsWith('.json'))
  .map(f => {
    try { return JSON.parse(fs.readFileSync(path.join(resultsDir, f), 'utf8')); }
    catch { return null; }
  })
  .filter(Boolean);

if (!jsonFiles.length) {
  console.error(`No benchmark JSON files found in: ${resultsDir}`);
  process.exit(2);
}

// current[testName][metricName] = { p50, p90, p99 }
const current = {};
for (const data of jsonFiles) {
  for (const bench of data.benchmarks || []) {
    // e.g. "com.callx.benchmark.ChatScrollBenchmark_chatListFlingWithProfile"
    // → keep just the method name so it matches the baseline's keys.
    const shortName = (bench.name || '').split('_').pop() || bench.name;
    current[shortName] = current[shortName] || {};
    for (const [metricName, metricData] of Object.entries(bench.metrics || {})) {
      const runs = metricData.runs || [];
      if (!runs.length) continue;
      const sorted = [...runs].sort((a, b) => a - b);
      current[shortName][metricName] = {
        p50: percentile(sorted, 50),
        p90: percentile(sorted, 90),
        p99: percentile(sorted, 99),
      };
    }
  }
}

// ── --update-baseline: write current numbers and exit ──────────────────
if (updateBaseline) {
  const out = {
    '//': 'PERF CI — checked-in jank baseline. Regenerated via --update-baseline; update deliberately, never automatically in CI.',
    capturedAt: new Date().toISOString().slice(0, 10),
    device: 'Pixel 6 API 31 (GMD: pixel6Api31)',
    compilationMode: 'Partial',
    benchmarks: current,
  };
  fs.writeFileSync(baselinePath, JSON.stringify(out, null, 2) + '\n');
  console.log(`Baseline updated: ${baselinePath}`);
  process.exit(0);
}

// ── Compare against baseline ────────────────────────────────────────────
if (!fs.existsSync(baselinePath)) {
  console.error(`Baseline file not found: ${baselinePath}`);
  console.error('Run with --update-baseline once to seed it.');
  process.exit(2);
}
const baseline = JSON.parse(fs.readFileSync(baselinePath, 'utf8')).benchmarks || {};

const lines = [];
lines.push('');
lines.push('╔══════════════════════════════════════════════════════════════╗');
lines.push('║      Jank Regression Check (vs checked-in baseline)          ║');
lines.push(`║      Tolerance: P99 may regress up to ${String(tolerancePct).padStart(2)}%                     ║`);
lines.push('╚══════════════════════════════════════════════════════════════╝');

let anyRegression = false;
let anyCompared = false;

for (const [testName, metrics] of Object.entries(baseline)) {
  const currentMetrics = current[testName];
  lines.push('');
  lines.push(`▶ ${testName}`);
  if (!currentMetrics) {
    lines.push('  ⚠ SKIPPED — no matching result in this run (benchmark renamed/removed/failed?)');
    continue;
  }
  for (const [metricName, baselineStats] of Object.entries(metrics)) {
    const curStats = currentMetrics[metricName];
    if (!curStats) {
      lines.push(`  ⚠ ${metricName.padEnd(22)} SKIPPED — metric missing in this run`);
      continue;
    }
    anyCompared = true;
    const allowedP99 = baselineStats.p99 * (1 + tolerancePct / 100);
    const regressed = curStats.p99 > allowedP99;
    const deltaPct = ((curStats.p99 - baselineStats.p99) / Math.abs(baselineStats.p99 || 1) * 100);
    const flag = regressed ? '✗ REGRESSED' : '✓ OK';
    if (regressed) anyRegression = true;
    lines.push(
      `  ${metricName.padEnd(22)} baseline P99=${fmt(baselineStats.p99)}  current P99=${fmt(curStats.p99)}` +
      `  (${deltaPct >= 0 ? '+' : ''}${deltaPct.toFixed(1)}%)  ${flag}`
    );
  }
}

lines.push('');
if (!anyCompared) {
  lines.push('No metrics could be compared — treating as a failure (likely a broken benchmark run).');
  console.log(lines.join('\n'));
  process.exit(2);
}
lines.push(anyRegression
  ? 'RESULT: jank regression detected — failing the check.'
  : 'RESULT: no jank regression beyond tolerance — OK.');

const output = lines.join('\n');
console.log(output);
fs.writeFileSync(path.join(resultsDir, 'jank_regression_summary.txt'), output);

process.exit(anyRegression ? 1 : 0);

// ── Helpers ───────────────────────────────────────────────────────────
function percentile(sorted, p) {
  const idx = Math.ceil((p / 100) * sorted.length) - 1;
  return sorted[Math.max(0, idx)];
}

function fmt(v) {
  if (v === undefined || v === null) return '-';
  return typeof v === 'number' ? v.toFixed(1) : String(v);
}
