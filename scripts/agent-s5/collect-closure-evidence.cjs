const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const root = path.resolve('docs/audits/agent-closure-20261103');
const read = p => JSON.parse(fs.readFileSync(p, 'utf8'));
const browserDir = path.resolve(process.argv[2]);
const browser = read(path.join(browserDir, 'browser-results.json'));
const isolation = read(path.join(browserDir, 'batch6-isolation.json'));
const units = ['PromptBundleTest', 'PromptRendererTest', 'ToolResultTextTest', 'PromptConfigurationTest'].map(name => {
  const filename = fs.readdirSync('pams-backend/target/surefire-reports').find(file => file.startsWith('TEST-') && file.endsWith('.' + name + '.xml'));
  const header = fs.readFileSync(path.join('pams-backend/target/surefire-reports', filename), 'utf8').match(/<testsuite\b[^>]*>/)[0];
  const fields = Object.fromEntries(['tests', 'failures', 'errors', 'skipped'].map(key =>
    [key, Number(header.match(new RegExp('(?:\\s|<)' + key + '="([0-9]+)"'))?.[1] ?? -1)]));
  return { name, ...fields };
});
const promptDir = 'pams-backend/src/main/resources/agent/prompts/v1';
const manifest = fs.readdirSync(promptDir).filter(name => name.endsWith('.txt')).map(name => ({ resource: name,
  sha256: crypto.createHash('sha256').update(fs.readFileSync(path.join(promptDir, name), 'utf8').replace(/\r\n/g, '\n').trim()).digest('hex') }));
const tests = fs.readFileSync('pams-backend/target/closure-web-tests-final.log', 'utf8');
const build = fs.readFileSync('pams-backend/target/closure-web-build-final.log', 'utf8');
const evidence = {
  date: '2026-11-03', currentResourceManifest: manifest,
  currentManifestScope: 'POST_FIX_RESOURCES; NOT_A_CLAIM_THAT_EARLIER_MODEL_SAMPLES_USED_THESE_HASHES',
  browser: { directory: path.relative(root, browserDir), stats: browser.stats, isolation },
  finalPromptChecks: units,
  frontend: { passed: Number(tests.match(/Tests\s+(\d+) passed/)?.[1] ?? 0), buildPassed: /built in/.test(build) },
  fullBackendBeforeFinalRevocationGuard: read(path.join(root, 'backend-regression-summary.json')).totals,
  mysqlFinalGuardAndRecovery: read('docs/audits/agent-acceptance-20261102/batch6-mysql-e415f8a2ceca/test-summary.json').totals,
  modelQuality: read(path.join(root, 'real-model-summary.json')),
  productionReleased: false, allSixPackagesComplete: false,
};
fs.writeFileSync(path.join(root, 'delivery-evidence.json'), JSON.stringify(evidence, null, 2) + '\n');
const good = browser.stats.expected === 10 && browser.stats.unexpected === 0 && browser.stats.skipped === 0 && isolation.exitCode === 0 && isolation.remainingSchemas === 0 && isolation.remainingAccounts === 0 && units.every(u => u.tests > 0 && u.failures === 0 && u.errors === 0 && u.skipped === 0) && evidence.frontend.passed === 227 && evidence.frontend.buildPassed;
console.log(JSON.stringify({ finalLocalChecksPassed: good, browser: browser.stats.expected, frontend: evidence.frontend.passed }));
if (!good) process.exitCode = 1;
