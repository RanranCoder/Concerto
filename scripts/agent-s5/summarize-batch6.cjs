const fs = require('node:fs');
const path = require('node:path');
const directory = path.resolve(process.argv[2] || '');
const log = path.join(directory, 'batch6-mysql.log');
if (!fs.existsSync(log)) throw new Error('Pass the evidence directory returned by BatchSixAcceptance.');
const start = fs.statSync(log).birthtimeMs - 2000;
const reports = [];
const reportRoot = path.resolve('pams-backend/target/surefire-reports');
for (const file of fs.readdirSync(reportRoot)) {
  const filename = path.join(reportRoot, file);
  if (!/^TEST-.*\.xml$/.test(file) || fs.statSync(filename).mtimeMs < start) continue;
  const header = fs.readFileSync(filename, 'utf8').match(/<testsuite\b[^>]*>/)?.[0];
  if (!header) continue;
  const read = key => header.match(new RegExp(`\\b${key}="([^"]*)"`))?.[1];
  reports.push({ report: file, tests: Number(read('tests')), failures: Number(read('failures')), errors: Number(read('errors')), skipped: Number(read('skipped')) });
}
const totals = reports.reduce((sum, r) => { for (const key of Object.keys(sum)) sum[key] += r[key]; return sum; }, { tests: 0, failures: 0, errors: 0, skipped: 0 });
totals.passed = totals.tests - totals.failures - totals.errors - totals.skipped;
const isolation = JSON.parse(fs.readFileSync(path.join(directory, 'batch6-isolation.json'), 'utf8'));
const expectedNames = ['AgentEditorialIntegrationTest', 'ControlledExecutionIntegrationTest', 'BatchSixMySqlAcceptanceTest', 'BatchSixProcessRecoveryTest', 'AgentTaskPlanIntegrationTest', 'AgentNotificationIntegrationTest', 'ConversationRecallIntegrationTest'];
const suitesMatch = expectedNames.every(name => reports.some(r => r.report.endsWith('.' + name + '.xml')));
const result = { generatedAt: new Date().toISOString(), totals, expectedSuites: expectedNames.length, reports, isolation, passed: isolation.exitCode === 0 && suitesMatch && reports.length === expectedNames.length && totals.tests > 0 && totals.failures === 0 && totals.errors === 0 && totals.skipped === 0 };
fs.writeFileSync(path.join(directory, 'test-summary.json'), JSON.stringify(result, null, 2) + '\n');
console.log(JSON.stringify({ ...totals, passedAcceptance: result.passed }));
if (!result.passed) process.exitCode = 1;
