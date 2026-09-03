// Stage: pull the answer object out of whatever prose the model wrapped it in.

function* jsonObjects(text: string) {
	for (let start = 0; start < text.length; start++) {
		if (text[start] !== "{") continue;
		let depth = 0;
		let inString = false;
		let escaped = false;
		for (let index = start; index < text.length; index++) {
			const current = text[index];
			if (inString) {
				if (escaped) escaped = false;
				else if (current === "\\") escaped = true;
				else if (current === '"') inString = false;
				continue;
			}
			if (current === '"') inString = true;
			else if (current === "{") depth++;
			else if (current === "}" && --depth === 0) {
				yield text.slice(start, index + 1);
				break;
			}
		}
	}
}

/** The last parseable object carrying a `findings` key wins: models often draft before answering. */
export function extractAnswer(raw: string) {
	let best: any;
	for (const candidate of jsonObjects(raw)) {
		try {
			const parsed = JSON.parse(candidate);
			if (parsed && typeof parsed === "object" && "findings" in parsed) best = parsed;
		} catch {
			continue;
		}
	}
	if (!best) throw new Error("no answer object with a 'findings' key in the output");
	return best;
}
