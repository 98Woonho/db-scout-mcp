/** 네이티브 평가 결과와 실행 기록을 대조해 공개용 집계를 만든다. LLM을 호출하지 않는다. */
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { copyFileSync, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { basename, dirname, join, resolve } from 'node:path';

const args = process.argv.slice(2);
const outputIndex = args.indexOf('--output');
if (outputIndex < 0 || !args[outputIndex + 1]) throw new Error('Usage: node summarize-evals.mjs <with-dir> <without-dir> --output <summary.json> [--archive-traces]');
const output = resolve(args[outputIndex + 1]);
const archiveTraces = args.includes('--archive-traces');
const directories = args.filter((_, i) => i !== outputIndex && i !== outputIndex + 1 && args[i] !== '--archive-traces');
assert.equal(directories.length, 2, 'Two condition directories are required');
const readJson = path => JSON.parse(readFileSync(path, 'utf8'));
const hash = content => createHash('sha256').update(content).digest('hex');
const canonical = value => Array.isArray(value) ? value.map(canonical) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])])) : value;
const sum = values => values.reduce((a, b) => a + b, 0);
const measuredSum = values => values.every(v => typeof v === 'number') ? sum(values) : null;
const prefix = 'mcp__plugin_db-scout_db-scout__';
const tokenKeys = ['input_tokens', 'cache_creation_input_tokens', 'cache_read_input_tokens', 'output_tokens'];

function readCondition(directory) {
  const manifest = readJson(join(directory, 'run-manifest.json'));
  const native = readJson(join(directory, 'aggregate-result.json'));
  assert.equal(manifest.schemaVersion, 1);
  assert.equal(native.schemaVersion, 1);
  assert.equal(native.partial, false, 'Do not present an incomplete native batch as complete');
  assert.equal(native.cases.length, manifest.plan.caseCount);
  assert.deepEqual(native.cases.map(c => c.name).sort(), [...manifest.plan.cases].sort());
  assert.equal(native.suite.ablation, 'none');
  const rows = [];
  for (const c of native.cases) {
    assert.match(c.name, /^[a-z0-9-]+$/);
    const runs = c.arms.with;
    // The native case definition may say runsPerCase:3 even with --runs 1.
    // Count the runs actually stored, rather than the definition's default.
    assert.equal(runs.length, manifest.plan.runsPerCase, `Unexpected actual runs: ${c.name}`);
    for (const [index, run] of runs.entries()) {
      const archivedTrace = join(directory, 'traces', `${c.name}-${index + 1}.jsonl`);
      const tracePath = existsSync(archivedTrace) ? archivedTrace : run.tracePath;
      assert.ok(existsSync(tracePath), `Trace missing: ${c.name}`);
      const bytes = readFileSync(tracePath);
      if (tracePath === archivedTrace && existsSync(run.tracePath)) {
        assert.equal(hash(bytes), hash(readFileSync(run.tracePath)), 'Archived and original traces differ');
      }
      const events = bytes.toString('utf8').trim().split(/\r?\n/).filter(Boolean).map(JSON.parse);
      const terminal = events.findLast(e => e.type === 'result');
      assert.ok(terminal, `Terminal usage record missing: ${c.name}`);
      const toolUses = new Map();
      for (const event of events) {
        if (event.type !== 'assistant') continue;
        for (const part of event.message?.content ?? []) {
          if (part.type === 'tool_use') toolUses.set(part.id, { name: part.name, input: part.input });
        }
      }
      const allCalls = [...toolUses.values()];
      const mcp = allCalls.filter(t => t.name.startsWith(prefix));
      const sql = mcp.filter(t => t.name === prefix + 'run_query').map(t => t.input.sql);
      const providerBlocked = /safeguards flagged this message/i.test(run.error ?? '');
      const graders = run.graders.filter(g => g.scored && !g.withOnly);
      assert.ok(graders.length > 0 && graders.every(g => g.weight === 1), 'This summary expects equal-weight rubric items');
      assert.ok(Math.abs(run.costUsd - (terminal.total_cost_usd + run.judgeCostUsd)) < 1e-7, 'Cost reconciliation failed');
      if (archiveTraces) {
        mkdirSync(join(directory, 'traces'), { recursive: true });
        if (resolve(tracePath) !== resolve(archivedTrace)) copyFileSync(tracePath, archivedTrace);
      }
      rows.push({
        caseId: c.name, run: index + 1, passed: run.passed,
        rubricPassed: graders.filter(g => g.passed).length, rubricTotal: graders.length,
        nativeScore: run.score, status: providerBlocked ? 'provider-safety-blocked' : run.error ? 'execution-error' : 'completed',
        agentCostUsd: terminal.total_cost_usd, judgeCostUsd: run.judgeCostUsd, totalCostUsd: run.costUsd,
        agentTokens: Object.fromEntries(tokenKeys.map(k => [k, terminal.usage?.[k] ?? null])),
        judgeTokens: null, models: Object.keys(terminal.modelUsage ?? {}), durationSeconds: run.durationSeconds,
        mcpToolCalls: mcp.length, skillCalls: allCalls.filter(t => t.name === 'Skill').length,
        otherToolCalls: allCalls.filter(t => !t.name.startsWith(prefix) && t.name !== 'Skill').length,
        toolNames: allCalls.map(t => t.name), executedSql: sql,
        potentialWriteCalls: sql.filter(s => /\b(?:INSERT|UPDATE|DELETE|DROP|ALTER|CREATE|TRUNCATE)\b/i.test(s)).length,
        traceSha256: hash(bytes),
        graderVerdicts: graders.map(g => ({ name: g.name, passed: g.passed, judgeVotes: g.judgeVotes ?? null })),
      });
    }
  }
  const actualModels = [...new Set(rows.flatMap(r => r.models))].sort();
  const total = sum(rows.map(r => r.totalCostUsd));
  assert.ok(Math.abs(native.costUsd - total) < 1e-7, 'Native batch cost differs from run totals');
  const summary = {
    condition: manifest.plan.condition, runId: manifest.runId,
    startedAtUtc: native.startedAt, sourceCommit: manifest.sourceCommit, dirtyWorkingTree: manifest.dirtyWorkingTree,
    cliVersion: native.claudeVersion, requestedModel: native.suite.modelOverride,
    actualModels, judgeModelRequested: native.suite.judgeModel, resolvedJudgeModel: null,
    inputSha256: manifest.inputSha256, jarSha256: manifest.jarSha256,
    nativeResultSha256: hash(readFileSync(join(directory, 'aggregate-result.json'))),
    nativePartial: native.partial, cliExitCode: manifest.exitCode,
    actualRunsPerCase: manifest.plan.runsPerCase,
    evaluatedMcpCanonicalSha256: hash(JSON.stringify(canonical(readJson(join(manifest.pluginSnapshot, '.mcp.json'))))),
    casesAttempted: rows.length, casesFullyPassed: rows.filter(r => r.passed).length,
    providerSafetyBlocks: rows.filter(r => r.status === 'provider-safety-blocked').length,
    executionErrors: rows.filter(r => r.status === 'execution-error').length,
    rubricPassed: sum(rows.map(r => r.rubricPassed)), rubricTotal: sum(rows.map(r => r.rubricTotal)),
    nativeOverallScore: native.aggregates.overallScore, nativeOverallPassRate: native.aggregates.overallPassRate,
    mcpToolCalls: sum(rows.map(r => r.mcpToolCalls)), skillCalls: sum(rows.map(r => r.skillCalls)),
    otherToolCalls: sum(rows.map(r => r.otherToolCalls)),
    agentTokens: Object.fromEntries(tokenKeys.map(k => [k, measuredSum(rows.map(r => r.agentTokens[k]))])),
    judgeTokens: null,
    agentCostUsd: sum(rows.map(r => r.agentCostUsd)), judgeCostUsd: sum(rows.map(r => r.judgeCostUsd)), totalCostUsd: total,
    potentialWriteCalls: sum(rows.map(r => r.potentialWriteCalls)), batchDurationSeconds: native.durationSeconds,
    cases: rows,
  };
  return summary;
}

const conditions = directories.map(d => readCondition(resolve(d))).sort((a,b) => a.condition.localeCompare(b.condition));
assert.deepEqual(conditions.map(c => c.condition).sort(), ['with-skills', 'without-skills'].sort());
for (const key of ['sourceCommit', 'cliVersion', 'requestedModel', 'judgeModelRequested', 'jarSha256', 'evaluatedMcpCanonicalSha256']) {
  assert.equal(conditions[0][key], conditions[1][key], `Conditions differ: ${key}`);
}
assert.deepEqual(conditions[0].inputSha256, conditions[1].inputSha256, 'Inputs changed between conditions');
assert.deepEqual(conditions[0].actualModels, conditions[1].actualModels, 'Resolved agent models differ');
assert.equal(conditions.find(c => c.condition === 'without-skills').skillCalls, 0, 'No-Skill arm used Skill');
const summary = {
  schemaVersion: 1, suiteId: 'holdout-v1', reportDate: '2026-11-24', timezone: 'Asia/Seoul',
  interpretation: 'Single run per question and condition; familiar Sakila database; no stability or broad superiority claim.',
  scoring: {
    rubric: 'Unweighted count of passed scored rubric items across actual runs.',
    nativeOverallScore: 'Arithmetic mean of per-case scores; differs from rubricPassed/rubricTotal when case sizes differ.',
    taskSuccess: 'All rubric items passed for that actual case run. Provider errors remain in the attempted denominator.',
    cost: 'Native reported agent plus judge usage cost; reconciled with terminal trace costs. Not an invoice.',
    tokens: 'Agent terminal input, cache write, cache read and output counted separately. Judge tokens not reported.',
    safety: 'Potential write-call regex is an audit aid, not a proof. Review SQL and results. Provider blocks do not test server write rejection.',
  },
  conditions, totalCostUsd: sum(conditions.map(c => c.totalCostUsd)),
};
mkdirSync(dirname(output), { recursive: true });
writeFileSync(output, JSON.stringify(summary, null, 2) + '\n');
console.log(JSON.stringify({ output: basename(output), totalCostUsd: summary.totalCostUsd, conditions: conditions.map(c => ({
  condition: c.condition, rubric: `${c.rubricPassed}/${c.rubricTotal}`, tasks: `${c.casesFullyPassed}/${c.casesAttempted}`,
  providerSafetyBlocks: c.providerSafetyBlocks, mcpToolCalls: c.mcpToolCalls, agentTokens: c.agentTokens,
  agentCostUsd: c.agentCostUsd, judgeCostUsd: c.judgeCostUsd, totalCostUsd: c.totalCostUsd,
})) }, null, 2));
