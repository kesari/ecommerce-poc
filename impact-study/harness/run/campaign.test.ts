import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { assertBlindEstate } from "./estate.ts";
import { ModelRuntime } from "@earendil-works/pi-coding-agent";
import { claimCampaignSlot, digest, loadFrozenCampaign, preflightCampaignSlot, reserveCampaignBudget, settleCampaignBudget, validateFrozenCampaign } from "./campaign.ts";
import { buildPrompt, createMeasurementIdentity, parseArgs, persistRunReceipts, runOnce, stampProvenance } from "./run.ts";
const hash = (letter = "a") => letter.repeat(64);
const policy = { timeout_seconds: 600, max_tokens: 2_000_000, max_cost_usd: 5,
 cost_enforcement: "catalog_context_reservation", token_enforcement: "context_window_reservation", retries: false, compaction: false } as const;
function fixture(authorized = true) {
 const cases = Array.from({ length: 12 }, (_, i) => `H${String(i + 1).padStart(2, "0")}`);
 const arms = ["pi-codex", "pi-codex-gortex-first", "pi-codex-repowise-first", "pi-codex-codebase-memory-first"];
 const conditions = { campaign_version: 1, protocol_version: 2, evaluation_revision: "2.2", paid_runs_authorized: authorized,
  campaign_budget: {authorization_id:"test-approval",max_total_usd:authorized?50:0,currency:"USD"},
  shared_identity: { model: "openai-codex/gpt-5.6-terra", model_digest: null, pi_version: "0.84.4", harness_sha256: hash(), estate_sha256: hash("b"),
   thinking: "off", scorer_sha256: hash("c"), answer_schema_sha256: hash("d"), task_template_sha256: hash("e"), scoring_policy: "scoped_reference_v1", budget_policy: policy },
  arms: Object.fromEntries(arms.map((name, i) => [name, { contestant: i ? name.slice(9) : "agent-only", contestant_config_sha256: hash("f"),
   index_sha256: i ? hash("c") : "none", treatment: i ? "product-first" : "baseline", prompt_sha256_by_case: Object.fromEntries(cases.map(id => [id, hash()])) }])) };
 const manifest = { campaign_version: 1, cases: cases.map(case_id => ({ case_id, record_sha256: hash("b"), oracle_bundle_sha256: hash("c") })) };
 const conditionBytes = JSON.stringify(conditions), conditionPin = digest(conditionBytes), manifestBytes = JSON.stringify(manifest);
 const cells = cases.flatMap(case_id => [1, 2, 3].flatMap(repeat => arms.map(arm => ({ slot_id: `${case_id}/r${repeat}/${arm}`, case_id, repeat, arm, ordinal: 0 }))));
 cells.forEach((cell, index) => cell.ordinal = index);
 const schedule = { campaign_version: 1, protocol_version: 2, evaluation_revision: "2.2", condition_sha256: conditionPin,
  cases_manifest_sha256: digest(manifestBytes), seed: 31, repetitions: 3, arms, cells };
 const scheduleBytes = JSON.stringify(schedule), schedulePin = digest(scheduleBytes);
 const campaign = { ...validateFrozenCampaign(schedule, conditions, schedulePin, conditionPin), casesManifest: manifest };
 const identity = { runner: arms[0], contestant: "agent-only", run_index: 1, model: conditions.shared_identity.model, model_digest: null,
  pi_version: "0.84.4", harness_sha256: hash(), estate_sha256: hash("b"), contestant_config_sha256: hash("f"), index_sha256: "none", thinking: "off", task_prompt_sha256: hash() };
 const runtime = { caseId: "H01", estateSha256: hash("b"), budgetPolicy: policy, scorerSha256: hash("c"), answerSchemaSha256: hash("d"),
  taskTemplateSha256: hash("e"), scoringPolicy: "scoped_reference_v1", treatment: "baseline", recordSha256: hash("b"), oracleSha256: hash("c") };
 return { campaign, identity, runtime, conditions, schedule, manifestBytes, conditionBytes, scheduleBytes, schedulePin, conditionPin };
}
test("frozen schedule matches case, arm, repetition and current runtime", () => {
 const f = fixture(); assert.deepEqual(preflightCampaignSlot(f.campaign, f.identity, f.runtime), { schedule_sha256: f.schedulePin,
  condition_sha256: f.conditionPin, case_id: "H01", repeat: 1, arm: "pi-codex" });assert.equal(f.schedule.cells.length, 144);
});
test("identity, prompt, model, policy and oracle tampering fail closed", () => {
 const f = fixture();
 for (const [key, value] of Object.entries({ runner: "../escape", contestant: "wrong", run_index: 4, model: "wrong/model", model_digest: "invented", pi_version: "other",
  harness_sha256: hash("b"), estate_sha256: hash("c"), contestant_config_sha256: hash("a"), index_sha256: hash("d"), thinking: "high", task_prompt_sha256: hash("b") }))
  assert.throws(() => preflightCampaignSlot(f.campaign, { ...f.identity, [key]: value }, f.runtime), /campaign preflight/);
 for (const [key, value] of Object.entries({ caseId: "H99", estateSha256: hash("a"), budgetPolicy: { ...policy, max_cost_usd: 50 }, scorerSha256: hash("a"),
  answerSchemaSha256: hash("a"), taskTemplateSha256: hash("a"), scoringPolicy: "other", treatment: "product-first", recordSha256: hash("a"), oracleSha256: hash("a") }))
  assert.throws(() => preflightCampaignSlot(f.campaign, f.identity, { ...f.runtime, [key]: value }), /campaign preflight/);
 assert.throws(() => preflightCampaignSlot({ ...f.campaign, casesManifest: undefined }, f.identity, f.runtime), /frozen case manifest/);
});
test("duplicate, incomplete, unsafe and mismatched schedule slots are rejected", () => {
 const f = fixture();
 for (const change of [(s: any) => s.cells.push(s.cells[0]), (s: any) => s.cells[0].case_id = "../H01", (s: any) => s.cells[0].slot_id = "H01/r2/pi-codex",
  (s: any) => s.cells.pop(), (s: any) => s.condition_sha256 = hash("c"), (s: any) => s.arms.push(s.arms[0])]) {
  const schedule = structuredClone(f.schedule);change(schedule);assert.throws(() => validateFrozenCampaign(schedule, f.conditions, f.schedulePin, f.conditionPin), /campaign preflight/);
 }
});
test("frozen files and transitive case manifest require trusted digests", async () => {
 const root = await mkdtemp(join(tmpdir(), "campaign-files-"));try {
  const f = fixture();const schedulePath = join(root, "schedule.json"), conditionsPath = join(root, "conditions.json"), casesManifestPath = join(root, "cases.json");
  await writeFile(schedulePath, f.scheduleBytes);await writeFile(conditionsPath, f.conditionBytes);await writeFile(casesManifestPath, f.manifestBytes);
  const options = { schedulePath, conditionsPath, casesManifestPath, trustedScheduleSha256: f.schedulePin, trustedConditionSha256: f.conditionPin };
  assert.equal((await loadFrozenCampaign(options)).casesManifest.cases.length, 12);
  await writeFile(casesManifestPath, f.manifestBytes + " ");await assert.rejects(loadFrozenCampaign(options), /case manifest digest/);
  await writeFile(casesManifestPath, f.manifestBytes);await writeFile(conditionsPath, f.conditionBytes + " ");await assert.rejects(loadFrozenCampaign(options), /frozen file digest/);
 } finally { await rm(root, { recursive: true, force: true }); }
});
test("slot claims are atomic, permanent and path safe", async () => {
 const root = await mkdtemp(join(tmpdir(), "campaign-claims-"));try {
  const f = fixture(), slot = preflightCampaignSlot(f.campaign, f.identity, f.runtime);
  const results = await Promise.allSettled([claimCampaignSlot(root, slot, "2026-10-20T00:00:00Z"), claimCampaignSlot(root, slot, "2026-10-20T00:00:01Z")]);
  assert.equal(results.filter(r => r.status === "fulfilled").length, 1);assert.match(String((results.find(r => r.status === "rejected") as PromiseRejectedResult).reason), /already attempted/);
  await assert.rejects(claimCampaignSlot(root, { ...slot, arm: "../escape" }, "now"), /unsafe campaign slot/);
 } finally { await rm(root, { recursive: true, force: true }); }
});
test("paid authorization false stops before a provider continuation", () => {
 const f = fixture(false);let providerCalls = 0;const launch = () => {preflightCampaignSlot(f.campaign, f.identity, f.runtime);providerCalls++;};
 assert.throws(launch, /paid runs are not authorized/);assert.equal(providerCalls, 0);
});
test("revision 2.2 missing campaign cannot initialize ModelRuntime", async () => {
 const root = await mkdtemp(join(tmpdir(), "campaign-no-provider-")), original = ModelRuntime.create;let initialized = 0;
 ModelRuntime.create = async () => {initialized++;throw new Error("provider initialization forbidden");};
 try {
  await assert.rejects(runOnce({ protocol_version: 2, evaluation_revision: "2.2", change_id: "H01", snapshot_sha256: hash(), query: "Assess change", proposed_change: {} },
   "pi-codex", { label: "agent-only", provider: "openai-codex", model: "gpt-5.6-terra" }, root, 1,
   { outputDir: root, budgetPolicy: policy, estate: { sha256: hash("b"), repositories: [], revisions: [] }, piVersion: "0.84.4", harnessSha256: hash() }), /requires a frozen validated campaign slot/);
  assert.equal(initialized, 0);
 } finally {ModelRuntime.create = original;await rm(root, {recursive:true,force:true});}
});
test("H01 receipts carry independent runtime identity and reject unsafe names", async () => {
 const root = await mkdtemp(join(tmpdir(), "campaign-receipt-"));try {
  const identity = createMeasurementIdentity("pi-codex", { label: "agent-only", provider: "openai-codex", model: "gpt-5.6-terra" }, 2,
   { outputDir: root, budgetPolicy: policy, estate: { sha256: hash("b"), repositories: [], revisions: [] }, piVersion: "0.84.4", harnessSha256: hash() }, "public task");
  const receipt = await persistRunReceipts(root, { change_id: "H01", snapshot_sha256: hash() }, "pi-codex", "2026-10-20T00:00:00Z", { prompt: "effective task", estateSha256: hash("b"), budgetPolicy: policy,
   usage: { tokens_consumed: 0, cost_usd: 0, elapsed_seconds: 0 }, measurementIdentity: identity });
  const bytes = await readFile(receipt.path);assert.equal(digest(bytes), receipt.sha256);assert.deepEqual(JSON.parse(bytes.toString()).measurement_identity, identity);
  const checked=spawnSync("python3",["-c","import json,sys;sys.path.insert(0,sys.argv[1]);import score_v2;errors=score_v2.validate(score_v2.read_json(sys.argv[2]),score_v2.schema('run-receipts'),'receipts');assert not errors,errors",new URL("../scoring",import.meta.url).pathname,receipt.path],{encoding:"utf8"});
  assert.equal(checked.status,0,checked.stderr);

  await assert.rejects(persistRunReceipts(root, { change_id: "../H01" }, "pi-codex", "now", {} as any), /unsafe run identity/);
 } finally {await rm(root, {recursive:true,force:true});}
});
test("campaign CLI selects one H case/repetition and public universe is sorted", () => {
 const args = ["--record", "H01", "--protocol", "2", "--contestant", "pi-codex", "--oracle-bundle", "oracle.json", "--trusted-oracle-sha256", hash(), "--artifact-root", "evidence", "--timeout", "600",
  "--max-tokens", "2000000", "--max-cost-usd", "5", "--campaign-schedule", "schedule.json", "--trusted-campaign-schedule-sha256", hash(), "--campaign-conditions", "conditions.json",
  "--trusted-campaign-conditions-sha256", hash(), "--campaign-cases-manifest", "cases.json", "--campaign-repeat", "2"];
 assert.equal(parseArgs(args).runs, 1);assert.equal(parseArgs(args).campaignRepeat, 2);assert.throws(() => parseArgs([...args, "--runs", "3"]), /exactly one/);
 assert.throws(() => parseArgs([...args, "--campaign-repeat", "4"]), /exactly one/);
 assert.equal(buildPrompt({ query: "Q", change_id: "H01", repository_universe: ["z-repo", "a-repo"] }, "{{REPOSITORY_UNIVERSE}}"), "a-repo, z-repo");
 assert.match(buildPrompt({ query: "Q", change_id: "OLD-1" }, "{{REPOSITORY_UNIVERSE}}"), /repository names available in source/);
});


test("holdout record filenames cannot enter a searchable source repository", async () => {
 const root = await mkdtemp(join(tmpdir(), "campaign-blind-estate-"));
 try {
  await mkdir(join(root, "sample-service"));
  for (const id of ["H01", "H09", "H12"]) {
   const path = join(root, "sample-service", `${id}.json`);await writeFile(path, "{}");
   await assert.rejects(assertBlindEstate(root, ["sample-service"]), /ground-truth leak/);await rm(path);
  }
  await writeFile(join(root, "sample-service", "ordinary-product.json"), "{}");await assertBlindEstate(root, ["sample-service"]);
 } finally {await rm(root, {recursive:true,force:true});}
});


test("authorization false reaches the actual runner but no provider initializes", async () => {
 const root = await mkdtemp(join(tmpdir(), "campaign-denied-provider-")), original = ModelRuntime.create;let initialized = 0;
 ModelRuntime.create = async () => {initialized++;throw new Error("provider initialization forbidden");};
 try {
  const f = fixture(false), contestant = {label:"agent-only",provider:"openai-codex",model:"gpt-5.6-terra"};
  const context = {outputDir:root,campaignClaimDir:root,budgetPolicy:policy,estate:{sha256:hash("b"),repositories:[],revisions:[]},piVersion:"0.84.4",harnessSha256:hash()};
  const record = {protocol_version:2,evaluation_revision:"2.2",change_id:"H01",snapshot_sha256:hash(),query:"Assess change",proposed_change:{}};
  const prompt = buildPrompt(record, await readFile(new URL("./prompt-template-v2.md", import.meta.url),"utf8"));
  const identity = createMeasurementIdentity("pi-codex",contestant,1,context,prompt);
  f.conditions.arms["pi-codex"].contestant_config_sha256=identity.contestant_config_sha256;
  for (const arm of Object.values(f.conditions.arms)) arm.prompt_sha256_by_case.H01=identity.task_prompt_sha256;
  const conditionPin=digest(JSON.stringify(f.conditions));f.schedule.condition_sha256=conditionPin;
  const campaign={...validateFrozenCampaign(f.schedule,f.conditions,digest(JSON.stringify(f.schedule)),conditionPin),casesManifest:f.campaign.casesManifest};
  let failure:any;
  try {await runOnce(record,"pi-codex",contestant,root,1,{...context,campaign,campaignRuntime:{...f.runtime}});}catch(error){failure=error;}
  assert.match(failure.message,/paid runs are not authorized/);assert.equal(initialized,0);assert.equal(failure.evidence.unrun,true);
  assert.equal(failure.evidence.attempt_evidence,undefined);
  const diagnostic=JSON.parse(await readFile(failure.evidence.campaign_preflight.path,"utf8"));
  assert.equal(diagnostic.slot_claimed,false);assert.equal(diagnostic.campaign_budget_reservation,null);
 } finally {ModelRuntime.create=original;await rm(root,{recursive:true,force:true});}
});

test("runner stamps sealed identity over model-authored provenance", () => {
 const f=fixture(),context={outputDir:"/unused",budgetPolicy:policy,estate:{sha256:hash("b"),repositories:[],revisions:[]},piVersion:"0.84.4",harnessSha256:hash()};
 const identity={...f.identity,campaign_slot:preflightCampaignSlot(f.campaign,f.identity,f.runtime)};
 const forged={runner:"forged",model:"forged",measurement_identity:{runner:"forged"},thinking:"high",task_prompt_sha256:hash("f"),campaign_slot:{arm:"forged"},findings:{}};
 const answer=stampProvenance(forged,{record:{protocol_version:2,change_id:"H01",snapshot_sha256:hash()},name:"pi-codex",contestant:{label:"agent-only",provider:"openai-codex",model:"gpt-5.6-terra"},index:1,
  prompt:"effective",elapsed:0,stats:{tokens:{total:0},cost:0},context,runStartedAt:"2026-10-20T00:00:00Z",measurementIdentity:identity});
 assert.deepEqual(answer.measurement_identity,identity);for(const [key,value]of Object.entries(f.identity))assert.deepEqual(answer[key],value);
 assert.equal("campaign_slot"in answer,false);assert.notEqual(answer.prompt_sha256,answer.task_prompt_sha256);
});


test("failed product setup retains frozen target index with separate unknown observed index", async () => {
 const root=await mkdtemp(join(tmpdir(),"campaign-failed-product-"));
 try {
  const f=fixture(),runner="pi-codex-codebase-memory-first",contestant={label:"codebase-memory-first",provider:"openai-codex",model:"gpt-5.6-terra",product:{kind:"codebase-memory" as const},product_first:true};
  const context={outputDir:root,campaignClaimDir:root,budgetPolicy:policy,estate:{sha256:hash("b"),repositories:[],revisions:[]},piVersion:"0.84.4",harnessSha256:hash()};
  const record={protocol_version:2,evaluation_revision:"2.2",change_id:"H01",snapshot_sha256:hash(),query:"Assess change",proposed_change:{repo:"sample",diff:"-a\n+b"}};
  const prompt=buildPrompt(record,await readFile(new URL("./prompt-template-v2.md",import.meta.url),"utf8"));
  const identity=createMeasurementIdentity(runner,contestant,1,context,prompt,hash("c"));
  f.conditions.arms[runner].contestant_config_sha256=identity.contestant_config_sha256;for (const arm of Object.values(f.conditions.arms)) arm.prompt_sha256_by_case.H01=identity.task_prompt_sha256;
  const conditionPin=digest(JSON.stringify(f.conditions));f.schedule.condition_sha256=conditionPin;
  const campaign={...validateFrozenCampaign(f.schedule,f.conditions,digest(JSON.stringify(f.schedule)),conditionPin),casesManifest:f.campaign.casesManifest};
  let failure:any;try{await runOnce(record,runner,contestant,root,1,{...context,campaign,campaignRuntime:{...f.runtime,treatment:"product-first"}});}catch(error){failure=error;}
  assert.equal(failure.status,"setup_failed");assert.match(failure.message,/estate changed after snapshot/);
  const attempt=JSON.parse(await readFile(failure.evidence.attempt_evidence.path,"utf8"));
  assert.equal(attempt.measurement_identity.index_sha256,hash("c"));assert.equal(attempt.index_observed_sha256,null);
  assert.equal(attempt.measurement_identity.campaign_slot.arm,runner);assert.equal(attempt.reservations.length,0);assert.equal(attempt.productReceipt,undefined);
  await assert.rejects(claimCampaignSlot(root,attempt.measurement_identity.campaign_slot,"retry"),/already attempted/);
 }finally{await rm(root,{recursive:true,force:true});}
});


test("total campaign budget atomically reserves full attempts across parallel invocations", async () => {
 const root=await mkdtemp(join(tmpdir(),"campaign-total-budget-"));
 try {
  const f=fixture();f.conditions.campaign_budget.max_total_usd=10;const slot=preflightCampaignSlot(f.campaign,f.identity,f.runtime);
  const results=await Promise.allSettled([1,2,3].map(i=>reserveCampaignBudget(root,f.conditions,{...slot,repeat:i},`start${i}`,5)));
  assert.equal(results.filter(r=>r.status==="fulfilled").length,2);assert.match(String((results.find(r=>r.status==="rejected")as PromiseRejectedResult).reason),/insufficient campaign total budget/);
  const receipts=results.filter((r):r is PromiseFulfilledResult<any>=>r.status==="fulfilled").map(r=>r.value);
  let ledger=JSON.parse(await readFile(receipts[0].ledger_path,"utf8"));assert.equal(ledger.totals.debit_upper_bound_usd,10);assert.equal(ledger.totals.pending_reserved_usd,10);
  await settleCampaignBudget(root,f.conditions,receipts[0],{cost_usd:.2},{exposure_upper_bound_usd:.2,unknown_usage:false});
  await settleCampaignBudget(root,f.conditions,receipts[1],{cost_usd:.1},{exposure_upper_bound_usd:2,unknown_usage:true});
  ledger=JSON.parse(await readFile(receipts[0].ledger_path,"utf8"));assert.equal(ledger.totals.debit_upper_bound_usd,2.2);assert.equal(ledger.totals.pending_reserved_usd,0);
  await reserveCampaignBudget(root,f.conditions,slot,"next",5);await assert.rejects(reserveCampaignBudget(root,f.conditions,slot,"too much",5),/insufficient/);
  await assert.rejects(reserveCampaignBudget(root,{...f.conditions,campaign_budget:{...f.conditions.campaign_budget,max_total_usd:100}},slot,"reset",5),/cannot reset/);
  await assert.rejects(settleCampaignBudget(root,f.conditions,receipts[1],{cost_usd:.1},{exposure_upper_bound_usd:.1,unknown_usage:false}),/existing campaign settlement/);
 }finally{await rm(root,{recursive:true,force:true});}
});

test("parent crash leaves a durable full-cap reservation that later conditions cannot erase", async () => {
 const root=await mkdtemp(join(tmpdir(),"campaign-crash-budget-"));
 try {
  const f=fixture();f.conditions.campaign_budget.max_total_usd=5;const slot=preflightCampaignSlot(f.campaign,f.identity,f.runtime);
  const source=`import {reserveCampaignBudget} from ${JSON.stringify(new URL("./campaign.ts",import.meta.url).href)};await reserveCampaignBudget(${JSON.stringify(root)},${JSON.stringify(f.conditions)},${JSON.stringify(slot)},"crashed",5,"crash-reservation");process.kill(process.pid,"SIGKILL");`;
  const child=spawnSync(process.execPath,["--input-type=module","-e",source],{encoding:"utf8"});assert.equal(child.signal,"SIGKILL",child.stderr);
  const ledger=JSON.parse(await readFile(join(root,".campaign-budgets/test-approval.json"),"utf8"));
  assert.equal(ledger.reservations[0].status,"reserved");assert.equal(ledger.totals.pending_reserved_usd,5);
  await assert.rejects(reserveCampaignBudget(root,f.conditions,{...slot,condition_sha256:hash("e")},"later condition",5),/insufficient/);
 }finally{await rm(root,{recursive:true,force:true});}
});

test("insufficient total authorization leaves the actual runner slot unrun", async () => {
 const root=await mkdtemp(join(tmpdir(),"campaign-unfunded-slot-"));
 try {
  const f=fixture(),contestant={label:"agent-only",provider:"openai-codex",model:"gpt-5.6-terra"};f.conditions.campaign_budget.max_total_usd=1;
  const context={outputDir:root,campaignClaimDir:root,budgetPolicy:policy,estate:{sha256:hash("b"),repositories:[],revisions:[]},piVersion:"0.84.4",harnessSha256:hash()};
  const record={protocol_version:2,evaluation_revision:"2.2",change_id:"H01",snapshot_sha256:hash(),query:"Assess",proposed_change:{}};
  const identity=createMeasurementIdentity("pi-codex",contestant,1,context,buildPrompt(record,await readFile(new URL("./prompt-template-v2.md",import.meta.url),"utf8")));
  f.conditions.arms["pi-codex"].contestant_config_sha256=identity.contestant_config_sha256;for (const arm of Object.values(f.conditions.arms)) arm.prompt_sha256_by_case.H01=identity.task_prompt_sha256;
  const pin=digest(JSON.stringify(f.conditions));f.schedule.condition_sha256=pin;
  const campaign={...validateFrozenCampaign(f.schedule,f.conditions,digest(JSON.stringify(f.schedule)),pin),casesManifest:f.campaign.casesManifest};
  let failure:any;try{await runOnce(record,"pi-codex",contestant,root,1,{...context,campaign,campaignRuntime:f.runtime});}catch(error){failure=error;}
  assert.equal(failure.evidence.unrun,true);assert.match(failure.message,/insufficient campaign total budget/);assert.equal(failure.evidence.attempt_evidence,undefined);
  const slot=preflightCampaignSlot(campaign,identity,f.runtime);await claimCampaignSlot(root,slot,"still unrun");
 }finally{await rm(root,{recursive:true,force:true});}
});


test("parallel processes cannot oversubscribe the shared authorization", async () => {
 const root=await mkdtemp(join(tmpdir(),"campaign-process-budget-"));
 try {
  const f=fixture();f.conditions.campaign_budget.max_total_usd=5;const slot=preflightCampaignSlot(f.campaign,f.identity,f.runtime);
  const source=`import {reserveCampaignBudget} from ${JSON.stringify(new URL("./campaign.ts",import.meta.url).href)};try{await reserveCampaignBudget(${JSON.stringify(root)},${JSON.stringify(f.conditions)},${JSON.stringify(slot)},"parallel",5);process.exitCode=0;}catch(e){process.exitCode=1;}`;
  const execute=()=>new Promise<number|null>((resolve,reject)=>{const child=spawn(process.execPath,["--input-type=module","-e",source],{stdio:"ignore"});child.on("error",reject);child.on("exit",resolve);});
  const codes=await Promise.all([execute(),execute(),execute()]);assert.deepEqual(codes.sort(),[0,1,1]);
  const ledger=JSON.parse(await readFile(join(root,".campaign-budgets/test-approval.json"),"utf8"));assert.equal(ledger.reservations.length,1);assert.equal(ledger.totals.debit_upper_bound_usd,5);
 }finally{await rm(root,{recursive:true,force:true});}
});

test("authorization cap and currency fail closed; observed overspend is retained", async () => {
 const root=await mkdtemp(join(tmpdir(),"campaign-cap-budget-"));
 try {
  const f=fixture();f.conditions.campaign_budget.max_total_usd=5;const slot=preflightCampaignSlot(f.campaign,f.identity,f.runtime);
  for(const budget of [{max_total_usd:5,currency:"USD"},{authorization_id:"../escape",max_total_usd:5,currency:"USD"},{authorization_id:"id",max_total_usd:0,currency:"USD"},{authorization_id:"id",max_total_usd:5,currency:"EUR"},{authorization_id:"id",max_total_usd:Infinity,currency:"USD"}])
   await assert.rejects(reserveCampaignBudget(root,{...f.conditions,campaign_budget:budget},slot,"invalid",5),/campaign/);
  const receipt=await reserveCampaignBudget(root,f.conditions,slot,"started",5);
  await settleCampaignBudget(root,f.conditions,receipt,{cost_usd:6},{exposure_upper_bound_usd:6,unknown_usage:false});
  const ledger=JSON.parse(await readFile(receipt.ledger_path,"utf8"));assert.equal(ledger.totals.debit_upper_bound_usd,6);assert.equal(ledger.totals.over_budget,true);assert.equal(ledger.totals.remaining_usd,0);
  await assert.rejects(reserveCampaignBudget(root,f.conditions,slot,"blocked",1),/insufficient/);
 }finally{await rm(root,{recursive:true,force:true});}
});


test("abrupt parent exit preserves a fully written permanent slot claim", async () => {
 const root=await mkdtemp(join(tmpdir(),"campaign-crash-slot-"));
 try {
  const f=fixture(),slot=preflightCampaignSlot(f.campaign,f.identity,f.runtime);
  const source=`import {claimCampaignSlot} from ${JSON.stringify(new URL("./campaign.ts",import.meta.url).href)};await claimCampaignSlot(${JSON.stringify(root)},${JSON.stringify(slot)},"claimed-before-crash");process.kill(process.pid,"SIGKILL");`;
  const child=spawnSync(process.execPath,["--input-type=module","-e",source],{encoding:"utf8"});assert.equal(child.signal,"SIGKILL",child.stderr);
  const marker=JSON.parse(await readFile(join(root,".campaign-slots",slot.schedule_sha256,"H01-r1-pi-codex.json"),"utf8"));
  assert.deepEqual(marker,{campaign_slot:slot,run_started_at:"claimed-before-crash"});
  await assert.rejects(claimCampaignSlot(root,slot,"retry-after-crash"),/already attempted/);
 }finally{await rm(root,{recursive:true,force:true});}
});

test('frozen treatments cannot change the public task prompt', () => {
 const f = fixture();
 f.conditions.arms['pi-codex-gortex-first'].prompt_sha256_by_case.H01 = hash('b');
 const pin = digest(JSON.stringify(f.conditions));
 assert.throws(() => validateFrozenCampaign({...f.schedule,condition_sha256:pin},f.conditions,f.schedulePin,pin), /public task prompts across arms/);
});
