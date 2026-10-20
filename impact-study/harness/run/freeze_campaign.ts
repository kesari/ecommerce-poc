#!/usr/bin/env node
// Offline preparation only. This tool cannot authorize or launch paid requests.
import { createHash } from 'node:crypto';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildPrompt, createMeasurementIdentity, FIXED_THINKING, harnessHash, verifyPinnedSnapshot } from './run.ts';
import { captureEstateSnapshot, DEFAULT_ESTATE } from './estate.ts';
import { HARNESS, RUN_DIR } from './paths.ts';

const output = resolve(process.argv[2] ?? '');
if (!process.argv[2] || !output.startsWith(resolve(HARNESS, 'verification/discovery') + '/'))
  throw new Error('output must be a fresh evaluator discovery directory');
const readJson = async (path:string) => JSON.parse(await readFile(path,'utf8'));
const hash = (data:string|Buffer) => createHash('sha256').update(data).digest('hex');
const fileHash = async (path:string) => hash(await readFile(path));
const contestants = await readJson(join(RUN_DIR,'contestants.json'));
const estate = await captureEstateSnapshot(DEFAULT_ESTATE);
verifyPinnedSnapshot(DEFAULT_ESTATE,estate,await readJson(join(HARNESS,'indexes/pins.json')));
const piEntry = fileURLToPath(import.meta.resolve('@earendil-works/pi-coding-agent'));
const piVersion = (await readJson(resolve(dirname(piEntry),'../package.json'))).version;
const templatePath = join(RUN_DIR,'prompt-template-v2.md');
const template = await readFile(templatePath,'utf8');
const budgetPolicy = { timeout_seconds:300, max_tokens:1000000, max_cost_usd:10,
  cost_enforcement:'catalog_context_reservation', token_enforcement:'context_window_reservation', retries:false, compaction:false } as const;
const context:any = {estate, piVersion, harnessSha256:await harnessHash(), budgetPolicy};
const caseManifest = await readJson(join(HARNESS,'verification/discovery/frozen/cases.json'));
const arms:any = {};
const nativeChecks:any = {};
for (const name of ['pi-codex','pi-codex-gortex-first','pi-codex-repowise-first','pi-codex-codebase-memory-first']) {
  const contestant = contestants[name];
  if (contestant.provider !== contestants['pi-codex'].provider || contestant.model !== contestants['pi-codex'].model) throw new Error('all arms require the same provider/model');
  let indexSha = 'none';
  if (contestant.product) {
    const path = join(HARNESS, contestant.product.kind === 'gortex' ? 'indexes/smokes/2026-10-20-operations-gortex-v1.4.2-verified' : 'indexes/smokes/2026-10-20-operations-resolved',contestant.product.kind+'.json');
    const smoke = await readJson(path);
    if (smoke.estate.sha256 !== estate.sha256 || smoke.product_provenance?.freshness !== 'verified') throw new Error('native source/provenance mismatch');
    indexSha = smoke.product_provenance.artifact_sha256;
    nativeChecks[name] = {path, sha256:await fileHash(path), status:smoke.status};
  }
  const promptHashes:any = {};
  for (const row of caseManifest.cases) {
    const record = await readJson(resolve(HARNESS,'..',row.record_path));
    promptHashes[row.case_id] = hash(buildPrompt(record,template));
  }
  const identity = createMeasurementIdentity(name,contestant,1,context,'unused',indexSha);
  arms[name] = {contestant:contestant.label, contestant_config_sha256:identity.contestant_config_sha256,
    index_sha256:indexSha, treatment:contestant.product_first?'product-first':'baseline', prompt_sha256_by_case:promptHashes};
}
const conditions = {campaign_version:1,protocol_version:2,evaluation_revision:'2.2',paid_runs_authorized:false,
  campaign_budget:{authorization_id:'pending-discovery-approval',max_total_usd:0,currency:'USD'},
  shared_identity:{model:`${contestants['pi-codex'].provider}/${contestants['pi-codex'].model}`,model_digest:null,pi_version:piVersion,
    harness_sha256:context.harnessSha256, estate_sha256:estate.sha256, thinking:FIXED_THINKING,
    scorer_sha256:await fileHash(join(HARNESS,'scoring/score_v2.py')),
    answer_schema_sha256:await fileHash(join(HARNESS,'schema/contestant-answer-v2.schema.json')),
    task_template_sha256:hash(template),scoring_policy:'scoped_reference_v1',budget_policy:budgetPolicy},
  arms, analysis:{bootstrap_seed:20261020,bootstrap_samples:10000,decision_gain:0.10}};
await mkdir(output,{recursive:false});
await writeFile(join(output,'conditions.json'),JSON.stringify(conditions,null,2)+'\n');
await writeFile(join(output,'preparation.json'),JSON.stringify({provider_calls:0,estate,native_checks:nativeChecks,
  limits:['No paid development or comparison runs were authorized or executed.',
    'Remote model weights have no independently available digest; provider/model identity is frozen.',
    'Native empty/ambiguous results are retrieval outcomes, not evidence of an absent real dependency.']},null,2)+'\n');
console.log(JSON.stringify({status:'prepared_provider_disabled',output,provider_calls:0}));
