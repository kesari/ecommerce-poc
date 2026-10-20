import { createHash, randomUUID } from "node:crypto";
import { mkdir, open, readFile, rename, unlink } from "node:fs/promises";
import { join } from "node:path";

export const SAFE_CASE = /^[A-Z]+-?[0-9]+$/;
const SAFE_ARM = /^[a-z0-9][a-z0-9-]{0,95}$/i;
const DIGEST = /^[a-f0-9]{64}$/;
export interface CampaignSlot {
 schedule_sha256: string; condition_sha256: string; case_id: string; repeat: number; arm: string;
}
export interface MeasurementIdentity {
 runner: string; contestant: string; run_index: number; model: string; model_digest: string | null;
 pi_version: string; harness_sha256: string; contestant_config_sha256: string; index_sha256: string;
 thinking: string; estate_sha256: string; task_prompt_sha256: string; campaign_slot?: CampaignSlot;
}
export interface FrozenCampaign { schedule: any; conditions: any; scheduleSha256: string; conditionSha256: string; casesManifest?: any }
export function canonical(value: any): string {
 const sort = (item: any): any => Array.isArray(item) ? item.map(sort) : item && typeof item === "object"
  ? Object.fromEntries(Object.keys(item).sort().map(key => [key, sort(item[key])])) : item;
 return JSON.stringify(sort(value));
}
export const digest = (value: string | Buffer) => createHash("sha256").update(value).digest("hex");
function requireThat(value: any, message: string): asserts value { if (!value) throw new Error(`campaign preflight: ${message}`); }
function same(actual: any, expected: any, name: string) { requireThat(canonical(actual) === canonical(expected), `${name} differs from frozen condition`); }

export function validateFrozenCampaign(schedule: any, conditions: any, scheduleSha256: string, conditionSha256: string): FrozenCampaign {
 requireThat(DIGEST.test(scheduleSha256) && DIGEST.test(conditionSha256), "external trusted SHA-256 digests are required");
 for (const [name, doc] of [["schedule", schedule], ["conditions", conditions]] as const) {
  requireThat(doc?.campaign_version === 1 && doc.protocol_version === 2 && doc.evaluation_revision === "2.2", `${name} revision is unsupported`);
 }
 same(schedule.condition_sha256, conditionSha256, "schedule condition SHA");
 requireThat(DIGEST.test(schedule.cases_manifest_sha256), "case manifest digest is required");
 requireThat(typeof conditions.paid_runs_authorized === "boolean", "explicit paid_runs_authorized is required");
 requireThat(schedule.repetitions === 3 && Array.isArray(schedule.arms) && schedule.arms.length === 4
  && new Set(schedule.arms).size === 4 && schedule.arms.every((arm: any) => typeof arm === "string" && SAFE_ARM.test(arm)), "four safe unique arms and three repetitions are required");
 same([...Object.keys(conditions.arms ?? {})].sort(), [...schedule.arms].sort(), "scheduled arms");
 validateCampaignBudget(conditions);
 const shared = conditions.shared_identity;
 requireThat(shared && typeof shared.model === "string" && typeof shared.pi_version === "string"
  && typeof shared.thinking === "string" && shared.budget_policy, "shared runtime identity is incomplete");
 for (const key of ["harness_sha256", "estate_sha256", "scorer_sha256", "answer_schema_sha256", "task_template_sha256"]) requireThat(DIGEST.test(shared[key]), `shared ${key} digest is required`);
 requireThat(shared.model_digest === null || typeof shared.model_digest === "string", "model_digest must be explicit");
 requireThat(Array.isArray(schedule.cells) && schedule.cells.length > 0, "schedule cells are required");
 const slots = new Set<string>(), ordinals = new Set<number>(), cases = new Set<string>();
 for (const cell of schedule.cells) {
  requireThat(SAFE_CASE.test(cell?.case_id) && schedule.arms.includes(cell.arm) && Number.isInteger(cell.repeat)
   && cell.repeat >= 1 && cell.repeat <= 3 && Number.isInteger(cell.ordinal) && cell.ordinal >= 0, "unsafe or malformed scheduled cell");
  const id = `${cell.case_id}/r${cell.repeat}/${cell.arm}`;
  requireThat(cell.slot_id === id && !slots.has(id) && !ordinals.has(cell.ordinal), "duplicate or mismatched schedule slot");
  slots.add(id); ordinals.add(cell.ordinal); cases.add(cell.case_id);
 }
 requireThat([...ordinals].every(n => n < schedule.cells.length), "schedule ordinals must be contiguous");
 for (const caseId of cases) for (let repeat = 1; repeat <= 3; repeat++) for (const arm of schedule.arms) {
  requireThat(slots.has(`${caseId}/r${repeat}/${arm}`), "incomplete paired schedule");
 }
 for (const arm of schedule.arms) {
  const profile = conditions.arms[arm];
  requireThat(typeof profile.contestant === "string" && profile.contestant.length > 0 && DIGEST.test(profile.contestant_config_sha256)
   && (profile.index_sha256 === "none" || DIGEST.test(profile.index_sha256)), "arm identity is incomplete");
  requireThat(["baseline", "product-first"].includes(profile.treatment), "unsupported arm treatment");
  same(Object.keys(profile.prompt_sha256_by_case ?? {}).sort(), [...cases].sort(), "arm prompt case set");
  for (const caseId of cases) requireThat(DIGEST.test(profile.prompt_sha256_by_case[caseId]), "case task prompt digest is required");
  same(profile.prompt_sha256_by_case, conditions.arms[schedule.arms[0]].prompt_sha256_by_case, "public task prompts across arms");
 }
 requireThat(schedule.arms.filter((arm: string) => conditions.arms[arm].treatment === "baseline").length === 1, "one baseline and three product-first treatments are required");
 return { schedule, conditions, scheduleSha256, conditionSha256 };
}
export async function loadFrozenCampaign(options: { schedulePath: string; conditionsPath: string; trustedScheduleSha256: string; trustedConditionSha256: string; casesManifestPath: string }) {
 const [scheduleBytes, conditionBytes] = await Promise.all([readFile(options.schedulePath), readFile(options.conditionsPath)]);
 requireThat(digest(scheduleBytes) === options.trustedScheduleSha256 && digest(conditionBytes) === options.trustedConditionSha256, "frozen file digest mismatch");
 const campaign = validateFrozenCampaign(JSON.parse(scheduleBytes.toString()), JSON.parse(conditionBytes.toString()), options.trustedScheduleSha256, options.trustedConditionSha256);
 const casesBytes = await readFile(options.casesManifestPath);
 requireThat(digest(casesBytes) === campaign.schedule.cases_manifest_sha256, "case manifest digest mismatch");
 const casesManifest = JSON.parse(casesBytes.toString());
 const rows = casesManifest?.cases;
 requireThat(casesManifest?.campaign_version === 1 && Array.isArray(rows) && rows.length === 12
  && new Set(rows.map((row: any) => row.case_id)).size === 12, "twelve frozen cases required");
 same(rows.map((row: any) => row.case_id).sort(), [...new Set(campaign.schedule.cells.map((cell: any) => cell.case_id))].sort(), "frozen case set");
 for (const row of rows) requireThat(SAFE_CASE.test(row.case_id) && DIGEST.test(row.record_sha256) && DIGEST.test(row.oracle_bundle_sha256), "case record/oracle digests are required");
 return { ...campaign, casesManifest };
}
/** Validated before the supervised worker (and again there before provider initialization). */
export function preflightCampaignSlot(campaign: FrozenCampaign, identity: Omit<MeasurementIdentity, "index_sha256" | "campaign_slot"> & { index_sha256?: string }, runtime: {
 caseId: string; estateSha256: string; budgetPolicy: any; scorerSha256: string; answerSchemaSha256: string; taskTemplateSha256: string; scoringPolicy: string; recordSha256?: string; oracleSha256?: string; treatment: string;
}) {
 const { conditions, schedule } = campaign, shared = conditions.shared_identity, arm = conditions.arms[identity.runner];
 requireThat(campaign.casesManifest, "frozen case manifest must be validated before execution");
 if (campaign.casesManifest) {
  const row = campaign.casesManifest.cases.find((item: any) => item.case_id === runtime.caseId);
  requireThat(row, "case is absent from frozen manifest");
  same(runtime.recordSha256, row.record_sha256, "record SHA");
  same(runtime.oracleSha256, row.oracle_bundle_sha256, "oracle SHA");
 }
 requireThat(arm && SAFE_CASE.test(runtime.caseId) && SAFE_ARM.test(identity.runner), "unknown arm or unsafe case");
 const cell = schedule.cells.find((cell: any) => cell.case_id === runtime.caseId && cell.repeat === identity.run_index && cell.arm === identity.runner);
 requireThat(cell, "requested case/arm/repetition is not scheduled");
 for (const key of ["model", "model_digest", "pi_version", "harness_sha256", "thinking", "estate_sha256"] as const) same(identity[key], shared[key], key);
 same(runtime.treatment, arm.treatment, "treatment");
 for (const key of ["contestant", "contestant_config_sha256"] as const) same(identity[key], arm[key], key);
 if (identity.index_sha256 !== undefined) same(identity.index_sha256, arm.index_sha256, "index_sha256");
 same(identity.task_prompt_sha256, arm.prompt_sha256_by_case[runtime.caseId], "task prompt SHA");
 for (const [key, value] of Object.entries({ estate_sha256: runtime.estateSha256, budget_policy: runtime.budgetPolicy,
  scorer_sha256: runtime.scorerSha256, answer_schema_sha256: runtime.answerSchemaSha256, task_template_sha256: runtime.taskTemplateSha256, scoring_policy: runtime.scoringPolicy })) same(value, shared[key], key);
 requireThat(conditions.paid_runs_authorized === true, "paid runs are not authorized; no provider may initialize");
 return { schedule_sha256: campaign.scheduleSha256, condition_sha256: campaign.conditionSha256,
  case_id: runtime.caseId, repeat: identity.run_index, arm: identity.runner } satisfies CampaignSlot;
}
/** Atomic and never removed: every scheduled attempt, including failures, consumes its slot. */
export async function claimCampaignSlot(outputDir: string, slot: CampaignSlot, runStartedAt: string) {
 requireThat(DIGEST.test(slot.schedule_sha256) && DIGEST.test(slot.condition_sha256) && SAFE_CASE.test(slot.case_id)
  && SAFE_ARM.test(slot.arm) && Number.isInteger(slot.repeat) && slot.repeat >= 1 && slot.repeat <= 3, "unsafe campaign slot claim");
 const directory = join(outputDir, ".campaign-slots", slot.schedule_sha256); await mkdir(directory, { recursive: true });
 const path = join(directory, `${slot.case_id}-r${slot.repeat}-${slot.arm}.json`);
 let file: Awaited<ReturnType<typeof open>>;
 try { file = await open(path, "wx"); }
 catch (error: any) { if (error.code === "EEXIST") throw new Error("campaign preflight: scheduled slot already attempted"); throw error; }
 try { await file.writeFile(JSON.stringify({ campaign_slot: slot, run_started_at: runStartedAt }) + "\n");await file.sync(); }
 finally { await file.close(); }
 for (const parent of [directory, join(outputDir, ".campaign-slots"), outputDir]) {
  const handle = await open(parent,"r");try {await handle.sync();}finally {await handle.close();}
 }
 return path;
}


export interface CampaignBudgetAuthorization { authorization_id: string; max_total_usd: number; currency: "USD" }
export interface CampaignBudgetReservation {
 ledger_path: string; authorization_id: string; reservation_id: string; reserved_usd: number;
 campaign_slot: CampaignSlot; run_started_at: string;
}
const finiteUsd = (value: any) => typeof value === "number" && Number.isFinite(value) && value >= 0 && Number.isSafeInteger(Math.ceil(value * 1e9));
const nanos = (value: number) => Math.ceil(value * 1e9);
export function validateCampaignBudget(conditions: any): CampaignBudgetAuthorization {
 const budget = conditions?.campaign_budget;
 requireThat(budget && typeof budget.authorization_id === "string" && /^[a-zA-Z0-9][a-zA-Z0-9_-]{0,95}$/.test(budget.authorization_id)
  && budget.currency === "USD" && finiteUsd(budget.max_total_usd), "explicit campaign USD authorization and finite total cap are required");
 requireThat(conditions.paid_runs_authorized !== true || budget.max_total_usd > 0, "paid authorization requires a positive campaign total cap");
 return budget;
}
function budgetTotals(ledger: any) {
 let debit = 0, pending = 0, observed = 0;
 requireThat(Array.isArray(ledger.reservations), "campaign budget ledger reservations are invalid");
 const ids = new Set();
 for (const row of ledger.reservations) {
  requireThat(typeof row.reservation_id === "string" && !ids.has(row.reservation_id) && finiteUsd(row.reserved_usd) && row.reserved_usd > 0, "invalid or duplicate campaign budget reservation");ids.add(row.reservation_id);
  if (row.status === "reserved") {debit += nanos(row.reserved_usd);pending += nanos(row.reserved_usd);}
  else {
   requireThat(row.status === "settled" && finiteUsd(row.observed_cost_usd) && finiteUsd(row.exposure_upper_bound_usd)
    && row.exposure_upper_bound_usd >= row.observed_cost_usd && typeof row.unknown_usage === "boolean", "invalid campaign budget settlement");
   debit += nanos(Math.max(row.observed_cost_usd, row.exposure_upper_bound_usd));observed += nanos(row.observed_cost_usd);
  }
 }
 requireThat(Number.isSafeInteger(debit), "campaign budget total exceeds supported precision");
 const cap = Math.floor(ledger.max_total_usd * 1e9);
 return { observed_cost_usd: observed / 1e9, pending_reserved_usd: pending / 1e9, debit_upper_bound_usd: debit / 1e9,
  remaining_usd: Math.max(0, cap - debit) / 1e9, over_budget: debit > cap };
}
async function durableBudgetWrite(path: string, value: any, directory: string) {
 const temporary = `${path}.${randomUUID()}.tmp`, file = await open(temporary, "wx");
 try {await file.writeFile(JSON.stringify(value, null, 2) + "\n");await file.sync();}finally {await file.close();}
 await rename(temporary, path);
 const parent = await open(directory, "r");try {await parent.sync();}finally {await parent.close();}
}
async function lockedBudget<T>(root: string, authorization: CampaignBudgetAuthorization, update: (ledger: any, path: string) => T): Promise<T> {
 const directory = join(root, ".campaign-budgets");await mkdir(directory, {recursive:true});
 const rootDirectory = await open(root,"r");try {await rootDirectory.sync();}finally {await rootDirectory.close();}
 const path = join(directory, `${authorization.authorization_id}.json`), lockPath = `${path}.lock`;
 let lock: Awaited<ReturnType<typeof open>> | undefined;
 for (let attempt = 0; attempt < 500; attempt++) {
  try {lock = await open(lockPath, "wx");break;}
  catch (error: any) {if (error.code !== "EEXIST") throw error;await new Promise(resolve => setTimeout(resolve, 10));}
 }
 requireThat(lock, "campaign budget ledger is locked; audit interrupted writer before removing its lock");
 try {
  await lock.writeFile(JSON.stringify({pid:process.pid,started_at:new Date().toISOString()}));await lock.sync();
  let ledger: any;
  try {ledger = JSON.parse(await readFile(path, "utf8"));}
  catch (error: any) {if (error.code !== "ENOENT") throw error;ledger = {ledger_version:1,...authorization,reservations:[]};}
  requireThat(ledger.ledger_version === 1 && ledger.authorization_id === authorization.authorization_id
   && ledger.currency === authorization.currency && ledger.max_total_usd === authorization.max_total_usd, "campaign authorization ID cannot reset its cap/currency or prior exposure");
  budgetTotals(ledger);
  const result = update(ledger, path);
  ledger.totals = budgetTotals(ledger);ledger.updated_at = new Date().toISOString();
  await durableBudgetWrite(path, ledger, directory);
  return result;
 } finally {await lock.close();await unlink(lockPath);}
}
/** Reserve the whole attempt limit durably before any supervised worker starts. */
export async function reserveCampaignBudget(root: string, conditions: any, slot: CampaignSlot, runStartedAt: string, amount: number, reservationId = randomUUID()): Promise<CampaignBudgetReservation> {
 const authorization = validateCampaignBudget(conditions);
 requireThat(conditions.paid_runs_authorized === true, "paid runs are not authorized");
 requireThat(finiteUsd(amount) && amount > 0, "a positive full-attempt cost cap is required");
 return lockedBudget(root, authorization, (ledger, path) => {
  const cap = Math.floor(authorization.max_total_usd * 1e9), totals = budgetTotals(ledger);
  requireThat(nanos(totals.debit_upper_bound_usd) + nanos(amount) <= cap, "insufficient campaign total budget; scheduled slot remains unrun");
  requireThat(!ledger.reservations.some((row: any) => row.reservation_id === reservationId), "duplicate campaign reservation ID");
  const receipt = {ledger_path:path,authorization_id:authorization.authorization_id,reservation_id:reservationId,reserved_usd:amount,campaign_slot:slot,run_started_at:runStartedAt};
  ledger.reservations.push({...receipt,status:"reserved"});return receipt;
 });
}
/** Unknown provider exposure stays debited; missing settlement retains the original full cap. */
export async function settleCampaignBudget(root: string, conditions: any, receipt: CampaignBudgetReservation, usage: {cost_usd:number}, exposure: {exposure_upper_bound_usd:number;unknown_usage:boolean}) {
 const authorization = validateCampaignBudget(conditions);
 requireThat(receipt.authorization_id === authorization.authorization_id && finiteUsd(usage.cost_usd)
  && finiteUsd(exposure.exposure_upper_bound_usd) && exposure.exposure_upper_bound_usd >= usage.cost_usd
  && typeof exposure.unknown_usage === "boolean", "invalid observed campaign settlement");
 return lockedBudget(root, authorization, (ledger, path) => {
  const row = ledger.reservations.find((item: any) => item.reservation_id === receipt.reservation_id);
  requireThat(row && path === receipt.ledger_path && canonical(row.campaign_slot) === canonical(receipt.campaign_slot)
   && row.reserved_usd === receipt.reserved_usd && row.run_started_at === receipt.run_started_at, "campaign reservation receipt does not match protected ledger");
  if (row.status === "settled") {
   same([row.observed_cost_usd,row.exposure_upper_bound_usd,row.unknown_usage], [usage.cost_usd,exposure.exposure_upper_bound_usd,exposure.unknown_usage], "existing campaign settlement");return;
  }
  Object.assign(row,{status:"settled",observed_cost_usd:usage.cost_usd,exposure_upper_bound_usd:exposure.exposure_upper_bound_usd,
   unknown_usage:exposure.unknown_usage,settled_at:new Date().toISOString()});
 });
}
