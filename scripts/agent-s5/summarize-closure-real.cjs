const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve('docs/audits/agent-closure-20261103');
const quality = JSON.parse(fs.readFileSync(path.join(root, 'quality-hardened/report.json'), 'utf8'));
const comparison = JSON.parse(fs.readFileSync(path.join(root, 'execution-comparison/report.json'), 'utf8'));
const metrics = rows => rows.reduce((sum, row) => {
  sum.samples++;
  sum.elapsedMs += row.durationMs ?? row.elapsedMs ?? 0;
  for (const usage of row.usage ?? []) {
    sum.attempts += usage.attempts;
    sum.reportedCalls += usage.reportedCalls;
    sum.inputTokens += usage.reportedInputTokens;
    sum.outputTokens += usage.reportedOutputTokens;
    sum.failures += usage.failures;
    sum.degraded += usage.degradedResponses;
  }
  return sum;
}, { samples: 0, elapsedMs: 0, attempts: 0, reportedCalls: 0, inputTokens: 0, outputTokens: 0, failures: 0, degraded: 0 });
const pairs = [...new Set(comparison.samples.map(row => row.case))].map(code => {
  const samples = comparison.samples.filter(row => row.case === code);
  return { case: code, valid: samples.length === 2 && samples.every(row => row.completed && row.usage.some(usage => usage.usageComplete)),
    paths: Object.fromEntries(samples.map(row => [row.path, { completed: row.completed, tools: row.successfulCapabilities ?? [], ...metrics([row]) }])) };
});
const summary = {
  quality: { model: 'deepseek-flash', scenarioCount: 28, sampleCount: quality.sampleCount,
    automatedPassed: quality.automatedPassed, metrics: metrics(quality.samples),
    semanticReview: 'KNOWN_ISSUES_SEPARATELY_RECORDED_NOT_FULL_QUALITY_PASS',
    byPath: Object.fromEntries(['ordinary', 'specialist'].map(p => [p, metrics(quality.samples.filter(row => row.path === p))])) },
  execution: { plannedPairs: pairs.length, validPairs: pairs.filter(row => row.valid).length, pairs,
    interruption: 'PROVIDER_HTTP_402_DURING_E04; LATER_PAIRS_NOT_VALID_QUALITY_COMPARISONS',
    benefitVerdict: 'NOT_ESTABLISHED', releaseEligible: false },
  priceOrInvoiceCost: null,
};
fs.writeFileSync(path.join(root, 'real-model-summary.json'), JSON.stringify(summary, null, 2) + '\n');
console.log(JSON.stringify({ qualitySamples: quality.sampleCount, automatedPassed: quality.automatedPassed, validExecutionPairs: summary.execution.validPairs }));
