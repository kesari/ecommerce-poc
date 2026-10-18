import { fork } from "node:child_process";

export interface BudgetPolicy {
	timeout_seconds: number;
	max_tokens: number | null;
	max_cost_usd: number | null;
	cost_enforcement: "catalog_context_reservation";
	token_enforcement: "context_window_reservation";
	retries: false;
	compaction: false;
}

export class AttemptError extends Error {
	status: string;
	evidence: any;
	constructor(status: string, message: string, evidence?: any) {
		super(message);
		this.status = status;
		this.evidence = evidence;
	}
}

// Audited in the installed 0.84.4 adapters. Codex Responses ignores maxTokens;
// passing it an option would not establish a provider-side output bound.
const OUTPUT_LIMIT_APIS = new Set(["anthropic-messages", "openai-responses", "openai-completions"]);

export class AttemptBudget {
	policy: BudgetPolicy;
	started: number;
	stats = { tokens: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 }, cost: 0 };
	failure: AttemptError | undefined;
	reservations: any[] = [];
	pending: any;
	deadlineEpochMs: number;
	constructor(policy: BudgetPolicy, deadlineEpochMs = Date.now() + policy.timeout_seconds * 1000) {
		this.policy = policy;
		this.started = performance.now();
		this.deadlineEpochMs = deadlineEpochMs;
	}
	stop(error: AttemptError) { this.failure ??= error; }
	check() {
		if (this.failure) throw this.failure;
		if (Date.now() >= this.deadlineEpochMs) {
			this.stop(new AttemptError("budget_exhausted", "total attempt deadline exhausted"));
			throw this.failure;
		}
	}
	reserve(model: any) {
		this.check();
		if (this.policy.max_tokens === null && this.policy.max_cost_usd === null) return {};
		if (!OUTPUT_LIMIT_APIS.has(model.api) && model.api !== "openai-codex-responses") {
			throw new AttemptError("setup_failed", `capped requests unsupported for ${model.api}: adapter output limit is not verified`);
		}
		const tables = [model.cost, ...(model.cost?.tiers ?? [])];
		const priceKeys = ["input", "cacheRead", "cacheWrite", "output"];
		if (!Number.isInteger(model.contextWindow) || model.contextWindow <= 0 || !Number.isInteger(model.maxTokens)
			|| model.maxTokens <= 0 || tables.some((table) => priceKeys.some((key) => typeof table?.[key] !== "number"
				|| !Number.isFinite(table[key]) || table[key] < 0))) {
			throw new AttemptError("setup_failed", "model context, output limit and catalog USD rates are required for reservation");
		}
		const inputRate = Math.max(...tables.flatMap((table) => priceKeys.slice(0, 3).map((key) => table[key])));
		const outputRate = Math.max(...tables.map((table) => table.output));
		const inputCost = model.contextWindow * inputRate / 1_000_000;
		const fullOutput = model.api === "openai-codex-responses";
		const remainingTokens = this.policy.max_tokens === null ? Infinity : this.policy.max_tokens - this.stats.tokens.total;
		const remainingCost = this.policy.max_cost_usd === null ? Infinity : this.policy.max_cost_usd - this.stats.cost;
		let outputTokens = model.maxTokens;
		if (!fullOutput) {
			outputTokens = Math.min(outputTokens, remainingTokens - model.contextWindow);
			outputTokens = remainingCost < inputCost ? 0 : Math.min(outputTokens,
				outputRate > 0 ? Math.floor((remainingCost - inputCost) * 1_000_000 / outputRate) : Infinity);
		} else if (remainingTokens < model.contextWindow + model.maxTokens
			|| remainingCost < inputCost + model.maxTokens * outputRate / 1_000_000) outputTokens = 0;
		outputTokens = Math.floor(outputTokens);
		const reservation = {
			api: model.api, model: model.id, context_window: model.contextWindow,
			max_output_tokens: Math.max(0, outputTokens), catalog_rates_usd_per_million: model.cost,
			output_limit: fullOutput ? "model_catalog_maximum" : "provider_max_tokens",
			maximum_request_cost_usd: inputCost + Math.max(0, outputTokens) * outputRate / 1_000_000,
			prior_tokens: this.stats.tokens.total, prior_cost_usd: this.stats.cost,
		};
		this.reservations.push(reservation);
		if (outputTokens < (model.api === "openai-responses" ? 16 : 1)) {
			this.stop(new AttemptError("budget_exhausted", "remaining budget cannot reserve full model context and one output token", reservation));
			throw this.failure;
		}
		// Bound assumes the provider honors its context/output limits and the
		// catalog USD rates. It is not an invoice guarantee. A provider violating
		// those limits can overshoot for one in-flight response; then we stop.
		this.pending = reservation;
		return { maxTokens: outputTokens, maxRetries: 0 };
	}
	exposure() {
		return { unknown_usage: Boolean(this.pending), pending_request: this.pending ?? null,
			exposure_upper_bound_usd: this.pending ? Math.max(this.stats.cost, this.pending.prior_cost_usd + this.pending.maximum_request_cost_usd) : this.stats.cost };
	}
	observe(event: any) {
		if (event.type !== "message_end" || event.message?.role !== "assistant") return;
		const message = event.message;
		const usage = message.usage;
		if (usage) {
			for (const key of ["input", "output", "cacheRead", "cacheWrite"] as const) {
				if (typeof usage[key] !== "number" || !Number.isFinite(usage[key]) || usage[key] < 0) {
					this.stop(new AttemptError("provider_failed", "provider reported invalid usage"));
					return;
				}
			}
			if (typeof usage.cost?.total !== "number" || !Number.isFinite(usage.cost.total) || usage.cost.total < 0) {
				this.stop(new AttemptError("provider_failed", "provider reported invalid cost"));
				return;
			}
			for (const key of ["input", "output", "cacheRead", "cacheWrite"] as const) this.stats.tokens[key] += usage[key];
			this.stats.tokens.total = this.stats.tokens.input + this.stats.tokens.output + this.stats.tokens.cacheRead + this.stats.tokens.cacheWrite;
			this.stats.cost += usage.cost.total;
		}
		if (usage && message.stopReason !== "error" && message.stopReason !== "aborted") this.pending = undefined;
		if (message.stopReason === "error" || message.stopReason === "aborted") {
			this.stop(new AttemptError("provider_failed", message.errorMessage || `provider stopped: ${message.stopReason}`));
		} else if (!usage && (this.policy.max_tokens !== null || this.policy.max_cost_usd !== null)) {
			this.stop(new AttemptError("provider_failed", "provider completed without usage required for budget accounting"));
		} else if ((this.policy.max_tokens !== null && this.stats.tokens.total > this.policy.max_tokens)
			|| (this.policy.max_cost_usd !== null && this.stats.cost > this.policy.max_cost_usd)) {
			this.stop(new AttemptError("budget_exhausted", "provider usage exceeded reserved attempt budget"));
		}
	}
}

/** A separate process enforces the deadline even while an adapter blocks in
 * spawnSync. Only this detached worker's process group is terminated. */
export function superviseAttempt(entry: string, payload: any, policy: BudgetPolicy,
	onProgress: (progress: any) => void = () => undefined): Promise<any> {
	return new Promise((resolvePromise, rejectPromise) => {
		let settled = false;
		let expired = false;
		const deadlineEpochMs = Date.now() + policy.timeout_seconds * 1000;
		const worker = fork(entry, ["--attempt-worker"], { detached: true, stdio: ["ignore", "ignore", "ignore", "ipc"] });
		const terminate = () => {
			if (!worker.pid) return;
			try { process.kill(-worker.pid, "SIGKILL"); } catch (error: any) { if (error.code !== "ESRCH") throw error; }
		};
		const finish = (error?: Error, value?: any) => {
			if (settled) return;
			settled = true;
			clearTimeout(timer);
			terminate();
			if (error) rejectPromise(error); else resolvePromise(value);
		};
		const timer = setTimeout(() => {
			expired = true;
			// Ask the SDK to abort for best-effort provider cancellation, then
			// kill the group after a bounded 100ms evidence/cleanup grace.
			worker.send({ type: "abort" }, () => undefined);
			setTimeout(() => finish(new AttemptError("budget_exhausted", "total attempt deadline exhausted")), 100);
		}, policy.timeout_seconds * 1000);
		worker.on("message", (message: any) => {
			if (settled) return;
			if (message.type === "progress" || message.type === "reservation") {
				try { onProgress(message.value); }
				catch (error: any) { finish(new AttemptError("setup_failed", `cannot retain worker progress: ${error.message}`)); return; }
				if (message.type === "reservation" && !expired && Date.now() < deadlineEpochMs) worker.send({ type: "reservation_ack", id: message.id }, () => undefined);
			}
			else if (message.type === "result") finish(expired || Date.now() >= deadlineEpochMs ? new AttemptError("budget_exhausted", "total attempt deadline exhausted") : undefined, message.value);
			else if (message.type === "failure") finish(new AttemptError(message.status, message.reason, message.evidence));
		});
		worker.once("error", (error) => finish(new AttemptError("setup_failed", error.message)));
		worker.once("exit", (code, signal) => {
			if (!settled) finish(new AttemptError(expired ? "budget_exhausted" : "setup_failed", `attempt worker exited without result (${code ?? signal})`));
		});
		worker.send({ type: "start", payload: { ...payload, deadlineEpochMs } }, (error) => { if (error) finish(new AttemptError("setup_failed", error.message)); });
	});
}
