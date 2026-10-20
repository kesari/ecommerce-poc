// Native adapter coverage using development identifiers only; no model runtime.
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { HARNESS } from '../run/paths.ts';
import { captureEstateSnapshot, DEFAULT_ESTATE } from '../run/estate.ts';
import { createRealProduct } from '../run/products.ts';

const output = resolve(process.argv[2] ?? '');
if (!process.argv[2] || !output.startsWith(resolve(HARNESS, 'indexes/smokes') + '/')) throw new Error('supply a fresh smokes output directory');
await mkdir(output, { recursive: false });
const snapshot = await captureEstateSnapshot(DEFAULT_ESTATE);
const reports: any[] = [];
for (const kind of ['gortex', 'repowise', 'codebase-memory'] as const) {
  if (process.argv[3] && process.argv[3] !== kind) continue;
  const report: any = { kind: 'native_operation_coverage', provider_calls: 0, estate: snapshot, product: kind, checks: [] };
  try {
    const integration = await createRealProduct({ kind }, DEFAULT_ESTATE, snapshot);
    report.product_provenance = integration.receipt;
    const calls: [string, any][] = [];
    if (kind === 'gortex') {
      for (const operation of ['symbol','usages','callers','calls','dependents','deps','implementations'])
        calls.push(['gortex_query', { operation, query: operation === 'symbol' ? 'AddressDtoMapper' : 'account-service/src/main/java/com/poc/account/infrastructure/mapping/AddressDtoMapper.java::AddressDtoMapper.toResponse', repo: 'account-service' }]);
      for (const action of ['list','check','validate','bridge_rank','symbol_impact','route_impact'])
        calls.push(['gortex_contracts', { action, repo: 'account-service', ...(action === 'route_impact' ? { query: 'GET /api/v1/addresses/{addressId}' } : action === 'symbol_impact' ? { query: 'account-service/src/main/java/com/poc/account/infrastructure/mapping/AddressDtoMapper.java::AddressDtoMapper.toResponse' } : action === 'bridge_rank' ? { query: 'postalCode' } : {}) }]);
      calls.push(['gortex_query', { operation: 'symbol', query: 'BenchmarkMissingSymbol987654', repo: 'account-service' }]);
    } else if (kind === 'repowise') {
      calls.push(['repowise_search', {query:'AddressResponse', mode:'symbol', repo:'all'}],
        ['repowise_search', {query:'AddressResponse.java', mode:'path', repo:'all'}],
        ['repowise_context', {query:'AddressResponse', repo:'account-service'}],
        ['repowise_context', {query:'BenchmarkMissingSymbol987654', repo:'account-service'}],
        ['repowise_search', {query:'get', mode:'symbol', repo:'all'}],
        ['repowise_blast_radius', {target:'account-service', max_depth:5, include_behavioral:true}]);
    } else {
      for (const operation of ['search','references','cross_repo','tests','schema'])
        calls.push(['codebase_memory_query', {operation, query:'AddressResponse', repo:'account-service'}]);
      // This unique qualified function is returned by the pinned development index.
      calls.push(['codebase_memory_query', {operation:'trace', query:'order-service.src.main.java.com.poc.order.infrastructure.client.AccountClient.address', repo:'order-service'}],
        ['codebase_memory_query', {operation:'search', query:'BenchmarkMissingSymbol987654', repo:'account-service'}],
        ['codebase_memory_query', {operation:'search', query:'get', repo:'account-service'}],
        ['codebase_memory_query', {operation:'trace', query:'getAddress', repo:'account-service'}]);
    }
    for (const [name, params] of calls) {
      const tool = integration.tools.find((t:any) => t.name === name);
      try { await tool.execute('native-smoke', params); } catch (error) { /* failure is retained in the invocation receipt */ }
      const receipt = integration.receipts.at(-1);
      report.checks.push({ tool:name, parameters:params, receipt });
      console.log(kind, name, params.operation ?? params.action ?? params.mode ?? params.query ?? 'blast_radius', receipt?.success);
    }
    for (const check of report.checks) check.expected = check.parameters.operation === 'trace' && check.parameters.query === 'getAddress' ? 'unresolved_name' : check.parameters.action === 'route_impact' ? 'route_lookup_may_be_empty' : 'successful_invocation';
    report.status = report.checks.every((c:any) => c.receipt?.success || c.expected === 'unresolved_name' && /function not found/.test(c.receipt?.error ?? '') || c.expected === 'route_lookup_may_be_empty' && /no route matching/.test(c.receipt?.error ?? '')) ? 'expected_outcomes' : 'unexpected_invocation_failures';
  } catch(error) { report.status = 'preflight_failed'; report.error = String(error); }
  await writeFile(resolve(output, kind + '.json'), JSON.stringify(report,null,2)+'\n');
  reports.push({product:kind,status:report.status,error:report.error});
}
await writeFile(resolve(output,'summary.json'),JSON.stringify({provider_calls:0, reports},null,2)+'\n');
console.log(JSON.stringify(reports));
