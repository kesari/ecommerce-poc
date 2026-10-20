import test from 'node:test';
import assert from 'node:assert/strict';
import { gortexRouteQuery } from './products.ts';

test('Gortex API-impact receives its documented path substring and preserves template identity', () => {
  assert.equal(gortexRouteQuery('GET /api/v1/widgets/{widgetId}'), '/api/v1/widgets/{widgetId}');
  assert.equal(gortexRouteQuery('POST /api/v1/widgets?preview=true'), '/api/v1/widgets');
  assert.throws(() => gortexRouteQuery('WidgetResponse'), /route must read/);
  assert.throws(() => gortexRouteQuery('GET https://example.com/widgets'), /route must read/);
});
