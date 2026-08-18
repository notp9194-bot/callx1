#!/usr/bin/env node
/**
 * ══════════════════════════════════════════════════════════════════════
 *  compare_results.js — Benchmark JSON result parser + comparator
 *  ─────────────────────────────────────────────────────────────────────
 *  Macrobenchmark JSON files parse karta hai aur:
 *  1. Per-metric P50/P90/P99 table print karta hai
 *  2. "No Profile" vs "Baseline Profile" comparison dikhata hai
 *  3. summary.txt generate karta hai (CI Slack notification ke liye)
 *
 *  USAGE:
 *    node macrobenchmark/scripts/compare_results.js <results-dir>
 *    node macrobenchmark/scripts/compare_results.js benchmark-results/
 * ══════════════════════════════════════════════════════════════════════
 */

const fs   = require('fs');
const path = require('path');

const dir = process.argv[2] || '.';

// Collect all JSON result files
const jsonFiles = fs.readdirSync(dir)
  .filter(f => f.endsWith('.json'))
  .map(f => {
    try { return { file: f, data: JSON.parse(fs.readFileSync(path.join(dir, f), 'utf8')) }; }
    catch { return null; }
  })
  .filter(Boolean);

if (!jsonFiles.length) {
  console.log('No JSON result files found in:', dir);
  process.exit(0);
}

// ── Parse benchmark results ────────────────────────────────────────────
const results = {};

for (const { file, data } of jsonFiles) {
  const benchmarks = data.benchmarks || [];
  for (const bench of benchmarks) {
    const name   = bench.name || file;
    const params = bench.params || {};
    const key    = `${name}[${Object.values(params).join(',')}]`;

    results[key] = results[key] || { metrics: {}, compilationMode: params.compilationMode || 'unknown' };

    for (const [metricName, metricData] of Object.entries(bench.metrics || {})) {
      const runs = metricData.runs || [];
      if (!runs.length) continue;
      const sorted = [...runs].sort((a, b) => a - b);
      results[key].metrics[metricName] = {
        p50: percentile(sorted, 50),
        p90: percentile(sorted, 90),
        p99: percentile(sorted, 99),
        min: sorted[0],
        max: sorted[sorted.length - 1],
        unit: metricData.unit || 'ms',
      };
    }
  }
}

// ── Print table ───────────────────────────────────────────────────────
const lines = [];
lines.push('');
lines.push('╔══════════════════════════════════════════════════════════════╗');
lines.push('║           CallX Macrobenchmark Results                      ║');
lines.push('╚══════════════════════════════════════════════════════════════╝');

for (const [testKey, { metrics, compilationMode }] of Object.entries(results)) {
  lines.push('');
  lines.push(`▶ ${testKey}`);
  lines.push(`  Compilation: ${compilationMode}`);
  lines.push(`  ${'Metric'.padEnd(35)} ${'P50'.padStart(10)} ${'P90'.padStart(10)} ${'P99'.padStart(10)} ${'Unit'.padStart(6)}`);
  lines.push(`  ${'-'.repeat(75)}`);

  for (const [metric, { p50, p90, p99, unit }] of Object.entries(metrics)) {
    const flag = p99 > thresholdFor(metric) ? ' ⚠' : ' ✓';
    lines.push(
      `  ${metric.padEnd(35)} ${fmt(p50).padStart(10)} ${fmt(p90).padStart(10)} ${fmt(p99).padStart(10)} ${unit.padStart(6)}${flag}`
    );
  }
}

// ── Comparison: None vs Partial (with profile) ────────────────────────
const noProfile  = Object.entries(results).filter(([k]) => k.includes('None'));
const withProfile = Object.entries(results).filter(([k]) => k.includes('Partial') || k.includes('BaselineProfile'));

if (noProfile.length && withProfile.length) {
  lines.push('');
  lines.push('── Before vs After (Profile Impact) ─────────────────────────────');
  for (const [nk, nd] of noProfile) {
    const baseName = nk.replace(/\[.*\]/, '');
    const match = withProfile.find(([wk]) => wk.startsWith(baseName));
    if (!match) continue;
    const [, wd] = match;

    for (const metric of ['timeToInitialDisplayMs', 'timeToFullDisplayMs', 'frameDurationCpuMs']) {
      const before = nd.metrics[metric];
      const after  = wd.metrics[metric];
      if (!before || !after) continue;

      const improvePct = ((before.p50 - after.p50) / before.p50 * 100).toFixed(1);
      const arrow = improvePct > 0 ? '▼' : '▲';
      lines.push(`  ${metric.padEnd(30)} ${fmt(before.p50).padStart(8)} → ${fmt(after.p50).padStart(8)} ms  ${arrow} ${Math.abs(improvePct)}% ${improvePct > 0 ? 'FASTER' : 'SLOWER'}`);
    }
  }
}

lines.push('');
const output = lines.join('\n');
console.log(output);

// Write summary for CI
const summaryPath = path.join(dir, 'summary.txt');
fs.writeFileSync(summaryPath, output);
console.log('\nSummary written to:', summaryPath);

// ── Helpers ───────────────────────────────────────────────────────────
function percentile(sorted, p) {
  const idx = Math.ceil((p / 100) * sorted.length) - 1;
  return sorted[Math.max(0, idx)];
}

function fmt(v) {
  if (v === undefined || v === null) return '-';
  return typeof v === 'number' ? v.toFixed(1) : String(v);
}

function thresholdFor(metric) {
  if (metric.includes('timeToInitial')) return 600;
  if (metric.includes('timeToFull'))    return 1200;
  if (metric.includes('frameDuration')) return 16;
  if (metric.includes('frameOverrun'))  return 5;
  if (metric.includes('Memory'))        return 150;
  return Infinity;
}
