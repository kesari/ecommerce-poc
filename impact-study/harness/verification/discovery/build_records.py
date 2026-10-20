#!/usr/bin/env python3
"""Build draft discovery records only after externally pinned proof validation.

No provider calls. Expected facts remain evaluator material outside source estates.
Scale actions use before/after as decimal multipliers of the named received field.
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
STUDY = HERE.parents[2]
HTTP = 'harness/verification/discovery/http'
WEB = 'harness/verification/discovery/web'
INFRA = 'harness/verification/discovery/infra'
INFRA_CASES = {'H05': 'H05-attempt3', 'H06': 'H06-attempt1', 'H09': 'H09-attempt1', 'H10': 'H10-attempt1'}
HTTP_CASES = {'H01': 'H01-attempt-a', 'H02': 'H02-attempt-c', 'H07': 'H07-attempt-b', 'H08': 'H08-attempt-a'}
PROOFS = {case: f'{HTTP}/evidence/{attempt}/manifest.json' for case, attempt in HTTP_CASES.items()}
PROOFS.update({case: f'{INFRA}/evidence/{attempt}/manifest.json' for case, attempt in INFRA_CASES.items()})
PROOFS.update({'web': f'{WEB}/evidence/manifest.json', 'closure': f'{WEB}/closure/evidence/manifest.json', 'audit': f'{WEB}/closure/source-audit/all-ten-repo-consumer-audit.json', 'cache': f'{HTTP}/closure/evidence/manifest.json'})
QUERY = ('Assess this proposed change across the supplied repositories. Identify behavior that breaks, repositories needing production changes, validation-only obligations, directed contract dependencies, and a sufficient repair plan. Preserve the requested change and the stated compatibility requirements. Support findings with source evidence and state unresolved claims.')


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':')).encode()


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def safe_path(root, relative):
    value = PurePosixPath(relative)
    if value.is_absolute() or '..' in value.parts or not value.parts or '\\' in relative:
        raise ValueError(f'unsafe proof path: {relative}')
    path = (root / relative).resolve()
    if not path.is_relative_to(root.resolve()):
        raise ValueError(f'proof path escapes root: {relative}')
    return path


def load(root, relative):
    return json.loads(safe_path(root, relative).read_text())


def require(condition, message):
    if not condition:
        raise ValueError(message)


def validate_manifest(root, relative, trusted):
    path = safe_path(root, relative)
    require(relative in trusted, f'external proof pin missing: {relative}')
    require(digest(path.read_bytes()) == trusted[relative], f'external proof pin mismatch: {relative}')
    manifest = json.loads(path.read_text())
    if relative.endswith('all-ten-repo-consumer-audit.json'):
        return manifest
    artifacts = manifest.get('artifacts') if isinstance(manifest, dict) else None
    if artifacts is None:
        artifacts = [{'path': name, 'sha256': sha} for name, sha in manifest.items()]
    if isinstance(artifacts, dict):
        artifacts = [{'path': name, 'sha256': sha} for name, sha in artifacts.items()]
    require(bool(artifacts), f'empty artifact manifest: {relative}')
    seen = set()
    for item in artifacts:
        name = item['path']
        require(name not in seen, f'duplicate artifact: {name}')
        seen.add(name)
        # Resolve artifact paths relative to their manifest, preserving the same boundary.
        artifact = safe_path(path.parent, name)
        require(digest(artifact.read_bytes()) == item['sha256'], f'artifact hash mismatch: {relative}: {name}')
    return manifest


def protected_equal(root, directory, protected):
    for name, expected in protected.items():
        require(digest(safe_path(root, f'{directory}/{name}').read_bytes()) == expected, f'protected bytes changed: {directory}/{name}')


def source_equal(actual, audit, message):
    require(actual == audit, f'source snapshot mismatch: {message}')


def junit(root, relative, failures, marker=None):
    suite = ET.parse(safe_path(root, relative)).getroot()
    require(int(suite.attrib['tests']) == 1 and int(suite.attrib['errors']) == 0 and int(suite.attrib['failures']) == failures, f'wrong JUnit outcome: {relative}')
    if marker:
        require(marker in suite.find('.//failure').attrib.get('message', ''), f'wrong business failure: {relative}')


def validate_http(root, manifest, case, pins, audit):
    directory = str(PurePosixPath(PROOFS[case]).parent)
    require(manifest['status'] == 'passed' and manifest['case'] == case, f'unpassed HTTP proof {case}')
    expected_snapshot = {repo: {key: pin[key] for key in ('commit', 'tree')} for repo, pin in sorted(pins.items())}
    require(manifest['snapshot'] == expected_snapshot, f'HTTP pins mismatch {case}')
    require(manifest['snapshot_sha256'] == digest(canonical(expected_snapshot)), f'HTTP snapshot digest mismatch {case}')
    protected_equal(root, HTTP, manifest['protected'])
    for repo in (manifest['criteria']['origin'], manifest['criteria']['consumer']):
        source_equal({name.removeprefix(repo + '/'): sha for name, sha in manifest['baseline_source_files'].items() if name.startswith(repo + '/')}, audit[repo]['all_archive_file_hashes'], case + '/' + repo)
    require(set(manifest['phases']) == {'baseline', 'changed', 'repaired', 'faulted-repair'}, f'missing HTTP phase {case}')
    require(len(manifest['commands']) == 8, f'incomplete producer/test commands {case}')
    for row in manifest['commands']:
        expected = 1 if row['log'] == 'faulted-repair-boundary-test.log' else 0
        require(row['exit_code'] == expected, f'wrong HTTP command exit {case}')
        require(digest(safe_path(root, directory + '/' + row['log']).read_bytes()) == row['log_sha256'], f'HTTP command log mismatch {case}')
    for phase, proof in manifest['phases'].items():
        require(proof['passed'] is True, f'incomplete HTTP phase {case}/{phase}')
        require(proof['fault_detected'] == (phase == 'faulted-repair'), f'fault classification {case}/{phase}')
        observed = load(root, f'{directory}/{phase}-observation.json')
        require(observed == proof['observations'], f'observation mismatch {case}/{phase}')
        require(digest(safe_path(root, f'{directory}/{phase}.patch').read_bytes()) == proof['patch_sha256'], f'phase patch mismatch {case}/{phase}')
        junit(root, f'{directory}/{phase}-junit.xml', int(phase == 'faulted-repair'), 'BUSINESS_' if phase == 'faulted-repair' else None)
    if case == 'H01':
        for phase, currency in [('baseline', 'INR'), ('changed', None), ('repaired', 'INR'), ('faulted-repair', None)]:
            require(manifest['phases'][phase]['observations']['bound_currency'] == currency, f'wrong H01 fact {phase}')
    if case in ('H02', 'H07'):
        expected = {'baseline': [0, 10000, 12300], 'repaired': [0, 10000, 12300], 'changed': [0, 0, 0] if case == 'H02' else [0, 100, 123], 'faulted-repair': [0, 0, 0] if case == 'H02' else [0, 1000, 1230]}
        for phase, values in expected.items():
            require([row['quote_shipping_minor'] for row in manifest['phases'][phase]['observations']] == values, f'wrong amount facts {case}/{phase}')
    return directory


def validate_infra(root, manifest, case, pins, audit):
    directory = str(PurePosixPath(PROOFS[case]).parent)
    require(manifest['status'] == 'passed' and manifest['case'] == case, 'unpassed infrastructure proof ' + case)
    for repo, pin in manifest['snapshot'].items():
        require(pin == {key: pins[repo][key] for key in ('commit', 'tree')}, 'infrastructure source pin mismatch ' + repo)
        source_equal({name.removeprefix(repo + '/'): sha for name, sha in manifest['source_files'].items() if name.startswith(repo + '/')}, audit[repo]['all_archive_file_hashes'], case + '/' + repo)
    protected_equal(root, directory, manifest['protected_inputs'])
    test = 'MessagingBoundaryTest.java' if case in ('H05', 'H06') else 'StorageBoundaryTest.java'
    protected_equal(root, INFRA, {name: manifest['protected_inputs'][name] for name in ('criteria.json', test)})
    criteria = load(root, directory + '/criteria.json')['cases'][case]
    require(set(manifest['phases']) == set(criteria['phases']), 'missing infrastructure phase ' + case)
    for row in manifest['commands']:
        is_bad_test = row['log'] in ('changed-test.log', 'faulted-repair-test.log') if case in ('H05', 'H06') else row['log'] == 'faulted-change-test.log'
        require(row['exit_code'] == int(is_bad_test), 'wrong infrastructure command exit ' + case)
        require(digest(safe_path(root, directory + '/' + row['log']).read_bytes()) == row['sha256'], 'infrastructure command log mismatch ' + case)
    for phase, expected_exit in criteria['phases'].items():
        proof = manifest['phases'][phase]
        require(proof['status'] == 'passed' and proof['test_exit'] == expected_exit, 'infrastructure phase incomplete ' + case + '/' + phase)
        require(load(root, f'{directory}/{phase}-observation.json') == proof['observations'], 'infrastructure observation mismatch')
        require(digest(safe_path(root, f'{directory}/{phase}.patch').read_bytes()) == proof['patch_sha256'], 'infrastructure patch mismatch')
        junit(root, f'{directory}/{phase}-junit.xml', expected_exit, criteria['fault_assertion'] if expected_exit else None)
        observed = proof['observations']
        if case in ('H05', 'H06'):
            require(observed['broker_records'] == 1 and observed['consumer_liveness'] is True, 'no independent broker delivery or listener liveness')
        if case == 'H05':
            require(observed['order_status'] == ('PAYMENT_PENDING' if expected_exit else 'INVENTORY_COMMIT_PENDING'), 'wrong payment/Order state evidence')
        if case == 'H06':
            require(observed['reservation_quantity'] == (0 if expected_exit else 3) and observed['remaining_stock'] == (10 if expected_exit else 7), 'wrong reservation state evidence')
        if case == 'H09':
            require(observed['basket_price'] == (65001 if expected_exit else 65000) and observed['read_back_price'] == (72001 if expected_exit else 72000), 'wrong SQL API observation')
        if case == 'H10':
            require(observed['basket_price'] == (65001 if expected_exit else 65000) and observed['legacy_price'] == 65000, 'wrong private-cache API observation')
    require(safe_path(root, directory + '/owned-container-ids.log').read_text().strip() == '', 'owned verification containers remain')


def validate_proofs(root, trusted):
    require(set(trusted) == set(PROOFS.values()), 'trusted registry must pin exactly the required externally reviewed proofs')
    pins = load(root, 'harness/indexes/pins.json')['repositories']
    require(len(pins) == 10, 'public repository universe must have ten pins')
    manifests = {name: validate_manifest(root, relative, trusted) for name, relative in PROOFS.items()}
    audit = manifests['audit']
    require(audit['source_pins'] == pins, 'all-ten source audit pins differ from public source pins')
    require(set(audit['repositories']) == set(pins), 'incomplete source audit')
    for case in HTTP_CASES:
        validate_http(root, manifests[case], case, pins, audit['repositories'])
    for case in INFRA_CASES:
        validate_infra(root, manifests[case], case, pins, audit['repositories'])
    web = f'{WEB}/evidence'
    summary = load(root, web + '/summary.json')
    require(summary['status'] == 'verified', 'web proof incomplete')
    require(summary['source_pins'] == {repo: pins[repo] for repo in summary['source_pins']}, 'web source pins changed')
    protected = load(root, web + '/protected-before.json')
    require(protected == summary['protected'], 'web protected hash declaration mismatch')
    for name, sha in protected.items():
        location = web if name == 'criteria.json' else WEB
        require(digest(safe_path(root, f'{location}/{name}').read_bytes()) == sha, f'web protected bytes changed: {name}')
    for repo, source in load(root, web + '/source-snapshot-hashes.json').items():
        source_equal(source, audit['repositories'][repo]['all_archive_file_hashes'], 'web/' + repo)
    commands = load(root, web + '/commands.json')
    require(len(commands) == 35, 'incomplete web command history')
    for row in commands:
        require(row['exit'] == row['expected_exit'], 'wrong web command exit')
    failures = {'H03-changed': 'BASKET_VISIBLE_TOTAL', 'H03-faulty-repair': 'BASKET_VISIBLE_TOTAL', 'H04-changed': 'PRODUCT_IMAGES', 'H04-faulty-repair': 'PRODUCT_IMAGES', 'H11-incompatible-fault': 'PRODUCT_IMAGES', 'H12-incompatible-fault': 'PRODUCT_PRICES'}
    for phase, marker in failures.items():
        require(('AssertionError: ' + marker) in safe_path(root, f'{web}/{phase}-web.log').read_text(), 'wrong web business failure ' + phase)
    for label in ('baseline', 'H03-repaired', 'H03-fault', 'H04-repaired', 'H04-fault'):
        text = safe_path(root, f'{web}/{label}-web-build.log').read_text()
        require('tsc -b' in text and 'built in' in text, 'missing complete web build ' + label)
    for path in safe_path(root, web).glob('*provider.json'):
        cache = path.with_name(path.name + '.cache.json')
        if cache.exists():
            require(path.read_bytes() == cache.read_bytes(), 'Catalog cache miss/hit mismatch')
    base = load(root, web + '/H04-baseline-bff.json')
    require(load(root, web + '/H12-changed-bff.json') == base, 'H12 changed published values')
    additive = load(root, web + '/H11-changed-bff.json')
    require(all(all(row[key] == base[i][key] for key in base[i]) and row['merchandisingTag'] == 'featured' for i, row in enumerate(additive)), 'H11 additive response incompatible')
    closure = f'{WEB}/closure/evidence'
    summary = load(root, closure + '/summary.json')
    require(summary['status'] == 'verified', 'closure incomplete')
    protected_equal(root, WEB + '/closure', summary['protected'])
    protected_equal(root, closure, summary['protected'])
    for repo, source in load(root, closure + '/source-snapshots.json').items():
        require(source['pin'] == pins[repo], 'closure source pin mismatch')
        source_equal(source['files'], audit['repositories'][repo]['all_archive_file_hashes'], 'closure/' + repo)
    commands = load(root, closure + '/commands.json')
    require(len(commands) == 16, 'incomplete closure commands')
    for row in commands:
        require((row['exit'] == 0) == row['expected_success'], 'wrong closure command exit')
    for phase in ('changed', 'fault'):
        name = 'H01-' + ('faulty-repair' if phase == 'fault' else phase) + '-web.log'
        # Closure output uses explicit assertions even when React catches a render error.
        require('AssertionError: BUSINESS_PRODUCT_CURRENCY_PRICES' in safe_path(root, closure + '/' + name).read_text(), 'missing currency business counterexample')
    for case, suffix in [('H03', 'basket-snapshot'), ('H04', 'product-info'), ('H11', 'product-info')]:
        require(load(root, f'{closure}/{case}-baseline-{suffix}.json') == load(root, f'{closure}/{case}-changed-{suffix}.json'), 'unchanged consumer closure mismatch ' + case)
    for phase in ('baseline', 'changed'):
        provider = safe_path(root, f'{closure}/H01-{phase}-provider.json')
        require(provider.read_bytes() == provider.with_name(provider.name + '.cache.json').read_bytes(), 'H01 provider cache hit differs')
    for phase in ('repaired', 'fault'):
        text = safe_path(root, f'{closure}/H01-{phase}-web-build.log').read_text()
        require('tsc -b' in text and 'built in' in text, 'missing H01 complete web build')
    cache = manifests['cache']
    require(cache['status'] == 'passed' and cache['source_pin'] == pins['shipment-service'], 'cache proof incomplete/pin mismatch')
    source_equal(cache['baseline_source_files'], audit['repositories']['shipment-service']['all_archive_file_hashes'], 'H08 cache')
    protected_equal(root, HTTP + '/closure', cache['protected'])
    require(len(cache['commands']) == 4, 'incomplete cache commands')
    require(set(cache['phases']) == {'baseline', 'changed', 'repaired', 'faulted-repair'}, 'missing cache phase')
    for row in cache['commands']:
        require(row['exit_code'] == int(row['log'] == 'faulted-repair-service-test.log'), 'wrong cache command exit')
    for phase, proof in cache['phases'].items():
        require(proof['passed'] and proof['fault_detected'] == (phase == 'faulted-repair'), 'incomplete cache phase')
        observed = proof['observations']
        require(observed['warm_from'] == '2026-01-05' and observed['warm_to'] == '2026-01-07', 'old TTL allowance not proved')
        require(observed['gets'] == 3 and observed['puts'] == 1 and observed['puts_after_warm'] == 0, 'wrong cache service branches')
        junit(root, f'{HTTP}/closure/evidence/{phase}-junit.xml', int(phase == 'faulted-repair'), 'BUSINESS_NEW_COMPUTATION_FROM' if phase == 'faulted-repair' else None)
    return pins, manifests


def action(repo, target, before, after, operation='read_json_field'):
    return {'repo': repo, 'target': target, 'operation': operation, 'before': before, 'after': after, 'critical': True}


def edge(provider, consumer, route, field):
    return {'provider': provider, 'consumer': consumer, 'type': 'rest', 'identifier': route, 'field': field}


def normalize_upstream(patch, origin):
    # Only strip the archive's repository prefix. Never alter source edits or hunk text.
    return ''.join(line.replace('a/' + origin + '/', 'a/', 1) if line.startswith('--- a/' + origin + '/') else line.replace('b/' + origin + '/', 'b/', 1) if line.startswith('+++ b/' + origin + '/') else line for line in patch.splitlines(True))


def candidates(root, trusted):
    pins, proofs = validate_proofs(root, trusted)
    snapshot = {repo: {key: pin[key] for key in ('commit', 'tree')} for repo, pin in sorted(pins.items())}
    snapshot_sha = digest(canonical(snapshot))
    cat = 'catalog-service'; basket = 'basket-service'; order = 'order-service'; shipment = 'shipment-service'; bff = 'commerce-bff'; web = 'commerce-web'
    product_route = 'GET /api/v1/products'
    shipment_route = 'POST /api/v1/delivery-estimates'
    specs = {
        'H01': (cat, {cat: 'unchanged', basket: 'broken', bff: 'unchanged', web: 'broken'}, [action(basket, 'com.poc.basket.infrastructure.catalog.CatalogProductResponse.currency', 'currency', 'currencyCode'), action(web, 'src/api/products.ts.listProducts', 'currency', 'currencyCode')], [edge(cat, basket, 'GET /api/v1/products/{productId}', 'currency'), edge(cat, bff, product_route, 'currency'), edge(bff, web, product_route, 'currency')]),
        'H02': (shipment, {shipment: 'unchanged', order: 'broken'}, [action(order, 'com.poc.order.infrastructure.client.ShipmentClient.EstimateResponse.shippingChargeMinor', 'shippingChargeMinor', 'feeMinor')], [edge(shipment, order, shipment_route, 'shippingChargeMinor')]),
        'H03': (basket, {basket: 'unchanged', order: 'unchanged', bff: 'unchanged', web: 'broken'}, [action(web, 'src/api/basket.ts.getBasket', 'totalMinor', 'payableMinor')], [edge(basket, bff, 'GET /api/v1/basket', 'totalMinor'), edge(bff, web, 'GET /api/v1/basket', 'totalMinor')]),
        'H04': (cat, {cat: 'unchanged', basket: 'unchanged', bff: 'unchanged', web: 'broken'}, [action(web, 'src/pages/ProductsPage.tsx.ProductsPage', 'imageUrl', 'thumbnailUrl')], [edge(cat, bff, product_route, 'imageUrl'), edge(bff, web, product_route, 'imageUrl')]),
        'H05': ('payment-service', {'payment-service': 'unchanged', order: 'broken'}, [action(order, 'com.poc.order.infrastructure.messaging.SagaResultListener.onResult', 'payment.charged.v1', 'payment.charged.v2', 'subscribe_topic')], [{'provider': 'payment-service', 'consumer': order, 'type': 'kafka', 'identifier': 'payment.charged.v1', 'field': ''}]),
        'H06': (order, {order: 'unchanged', 'inventory-service': 'broken'}, [action('inventory-service', 'com.poc.inventory.infrastructure.messaging.InventoryCommandListener.items', 'quantity', 'units')], [{'provider': order, 'consumer': 'inventory-service', 'type': 'kafka', 'identifier': 'inventory.reserve.requested.v1', 'field': 'items[].quantity'}]),
        'H09': (cat, {cat: 'unchanged', basket: 'unchanged'}, [], []),
        'H10': (cat, {cat: 'unchanged', basket: 'unchanged'}, [], []),
        'H07': (shipment, {shipment: 'unchanged', order: 'broken'}, [action(order, 'com.poc.order.infrastructure.client.ShipmentClient.estimate', '1', '100', 'scale_numeric_field')], [edge(shipment, order, shipment_route, 'shippingChargeMinor')]),
        'H08': (shipment, {shipment: 'unchanged', order: 'unchanged'}, [], []),
        'H11': (cat, {cat: 'unchanged', basket: 'unchanged', bff: 'unchanged', web: 'unchanged'}, [], []),
        'H12': (cat, {cat: 'unchanged', bff: 'unchanged', web: 'unchanged'}, [], []),
    }
    output = {}
    for case, (origin, repos, actions, edges) in specs.items():
        if case in HTTP_CASES:
            patch_path = f'{HTTP}/evidence/{HTTP_CASES[case]}/changed.patch'
        elif case in INFRA_CASES:
            patch_path = f'{INFRA}/evidence/{INFRA_CASES[case]}/changed.patch'
        else:
            patch_path = f'{WEB}/evidence/{case}-upstream.patch'
        patch = normalize_upstream(safe_path(root, patch_path).read_text(), origin)
        require(patch.startswith('--- a/') and '\n+++ b/' in patch and '\n@@ ' in patch, 'missing exact complete upstream diff ' + case)
        query = QUERY
        if case == 'H07':
            query += ' The changed response expresses charges in whole INR. In-scope values are non-negative whole-INR amounts, represented before the change as integer minor units divisible by 100; existing public amount contracts must retain their documented units.'
        if case == 'H08':
            query += ' Preserve the existing configured estimate-cache TTL: entries computed under the previous delivery policy may remain visible until normal expiration. Every new nonmetro computation must use the updated SLA.'
        record = {'protocol_version': 2, 'evaluation_revision': '2.2', 'change_id': case, 'status': 'draft', 'category': 'meaning_change' if case in ('H07', 'H08') else 'private_storage' if case in ('H09', 'H10') else 'messaging' if case in ('H05', 'H06') else 'compatible_control' if case in ('H11', 'H12') else 'rest_contract', 'query': query, 'snapshot_sha256': snapshot_sha, 'repository_universe': sorted(pins), 'origin_repo': origin, 'proposed_change': {'repo': origin, 'diff': patch}, 'repositories': [{'repo': repo, 'runtime_status': status} for repo, status in repos.items()], 'symbols': [{'repo': item['repo'], 'fqn': item['target']} for item in actions], 'edges': edges, 'repair_alternatives': [{'name': 'preserve_requested_change_and_repair_consumers' if actions else 'preserve_requested_change_without_coordinated_edits', 'actions': actions, 'obligations': [{'repo': repo, 'obligation': 'production_edit' if any(item['repo'] == repo for item in actions) else 'validation_only'} for repo in repos]}]}
        output[case] = record
    h01 = normalize_upstream(safe_path(root, f'{HTTP}/evidence/H01-attempt-a/changed.patch').read_text(), cat)
    require(h01 == safe_path(root, f'{WEB}/closure/evidence/H01-upstream.patch').read_text(), 'H01 boundary and web closure use different origin changes')
    for case in output:
        covered = set()
        for name in case_proof_names(case):
            if name == 'audit':
                continue
            manifest = proofs[name]
            artifacts = manifest.get('artifacts', manifest)
            names = artifacts if isinstance(artifacts, dict) else [item['path'] for item in artifacts]
            covered.update(str(PurePosixPath(PROOFS[name]).parent / artifact_name) for artifact_name in names)
        for path in concrete_artifacts(case):
            require(path in covered and safe_path(root, path).is_file(), 'claim artifact is missing or not covered by a trusted manifest: ' + path)
    return output


def concrete_artifacts(case):
    paths = []
    if case in HTTP_CASES:
        directory = f'{HTTP}/evidence/{HTTP_CASES[case]}'
        paths += [f'{directory}/{phase}-observation.json' for phase in ('baseline', 'changed', 'repaired', 'faulted-repair')]
        paths += [f'{directory}/repaired.patch', f'{directory}/faulted-repair-junit.xml']
    if case in INFRA_CASES:
        directory = f'{INFRA}/evidence/{INFRA_CASES[case]}'
        phases = ('baseline', 'changed', 'repaired', 'faulted-repair') if case in ('H05', 'H06') else ('baseline', 'changed', 'faulted-change')
        paths += [f'{directory}/{phase}-observation.json' for phase in phases]
        paths += [f'{directory}/{phases[-1]}-junit.xml', f'{directory}/criteria.json']
    if case in ('H03', 'H04', 'H11', 'H12'):
        directory = f'{WEB}/evidence'
        for phase in ('baseline', 'changed'):
            # Compatible controls explicitly reuse the identical H04 provider baseline.
            provider_case = 'H04' if phase == 'baseline' and case in ('H11', 'H12') else case
            paths += [f'{directory}/{provider_case}-{phase}-provider.json', f'{directory}/{provider_case}-{phase}-bff.json', f'{directory}/{case}-{phase}-web.log']
        fault = 'faulty-repair' if case in ('H03', 'H04') else 'incompatible-fault'
        paths += [f'{directory}/{case}-{fault}-web.log']
        if case in ('H03', 'H04'):
            paths += [f'{directory}/{case}-repair.patch', f'{directory}/{case}-repaired-web.log', f'{directory}/{case}-repaired-web-build.log']
    if case == 'H01':
        directory = f'{WEB}/closure/evidence'
        paths += [f'{directory}/H01-{phase}-web.log' for phase in ('baseline', 'changed', 'repaired', 'faulty-repair')]
        paths += [f'{directory}/H01-web-repair.patch', f'{directory}/H01-repaired-web-build.log']
    if case == 'H03':
        paths += [f'{WEB}/closure/evidence/H03-{phase}-basket-snapshot.json' for phase in ('baseline', 'changed')]
    if case in ('H04', 'H11'):
        paths += [f'{WEB}/closure/evidence/{case}-{phase}-product-info.json' for phase in ('baseline', 'changed')]
    if case == 'H08':
        paths += [f'{HTTP}/closure/evidence/{phase}-observation.json' for phase in ('baseline', 'changed', 'repaired', 'faulted-repair')]
        paths += [f'{HTTP}/closure/evidence/criteria.json', f'{HTTP}/closure/evidence/faulted-repair-junit.xml']
    return paths


def source_references(record):
    paths = [{'repo': record['origin_repo'], 'path': line.removeprefix('+++ b/')} for line in record['proposed_change']['diff'].splitlines() if line.startswith('+++ b/')]
    for item in record['repair_alternatives'][0]['actions']:
        target = item['target']
        if target.startswith('src/'):
            path = target.rsplit('.', 1)[0]
        else:
            parts = target.split('.')
            class_index = next(index for index, part in enumerate(parts) if part[0].isupper())
            path = 'src/main/java/' + '/'.join(parts[:class_index + 1]) + '.java'
        reference = {'repo': item['repo'], 'path': path}
        if reference not in paths:
            paths.append(reference)
    return paths


def case_proof_names(case):
    names = [case] if case in HTTP_CASES or case in INFRA_CASES else ['web']
    if case in ('H01', 'H03', 'H04', 'H11'):
        names.append('closure')
    if case == 'H08':
        names.append('cache')
    return names + ['audit']


def claim_bindings(records, trusted):
    bindings = {'status': 'draft_pending_independent_root_freeze', 'evaluation_revision': '2.2', 'trusted_proofs': dict(trusted), 'records': {}}
    for case, record in records.items():
        artifacts = [PROOFS[name] for name in case_proof_names(case)]
        claims = []
        for section in ('repositories', 'symbols', 'edges'):
            claims.extend({'pointer': f'/{section}/{index}', 'claim': item, 'proof_manifests': artifacts, 'artifact_paths': concrete_artifacts(case), 'source_paths': source_references(record)} for index, item in enumerate(record[section]))
        for section in ('actions', 'obligations'):
            claims.extend({'pointer': f'/repair_alternatives/0/{section}/{index}', 'claim': item, 'proof_manifests': artifacts, 'artifact_paths': concrete_artifacts(case), 'source_paths': source_references(record)} for index, item in enumerate(record['repair_alternatives'][0][section]))
        bindings['records'][case] = {'file': case + '.json', 'record_sha256': digest(canonical(record)), 'record': record, 'claims': claims, 'limits': ['Expected facts cover independently executed boundaries and concrete inputs; source audit identifies additional consumers but is not runtime evidence.', 'Unlisted repositories and relationships remain outside the proved critical set.', 'These draft records are evaluator material and must never enter tool indexes.']}
    return bindings


def verify_record_bindings(records, bindings):
    require(set(records) == set(bindings['records']), 'bound record set changed')
    for case, record in records.items():
        bound = bindings['records'][case]
        require(record == bound['record'] and digest(canonical(record)) == bound['record_sha256'], 'full record binding mismatch: ' + case)
    require(bindings == claim_bindings(records, bindings['trusted_proofs']), 'claim evidence bindings changed')


def checked_output(path):
    output = path.resolve()
    allowed = (HERE / 'record-candidates').resolve()
    temporary = Path('/private/tmp').resolve()
    require(output.is_relative_to(allowed) or output.is_relative_to(temporary), 'candidate output must stay in evaluator record-candidates or private tmp, outside indexed estates')
    require(not output.exists(), 'preserve previous candidate outputs; use a fresh output directory')
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--trusted-pins', required=True, type=Path, help='Externally reviewed proof path-to-SHA registry; no self-generated trust')
    parser.add_argument('--output', required=True, type=Path, help='Fresh evaluator-only draft candidate directory')
    args = parser.parse_args()
    output = checked_output(args.output)
    trusted = json.loads(args.trusted_pins.read_text())
    records = candidates(STUDY, trusted)
    bindings = claim_bindings(records, trusted)
    verify_record_bindings(records, bindings)
    output.mkdir(parents=True)
    for case, record in records.items():
        (output / (case + '.json')).write_text(json.dumps(record, indent=2) + '\n')
    (output / 'claim-proofs.json').write_text(json.dumps(bindings, indent=2) + '\n')
    print(json.dumps({'status': 'draft_pending_independent_root_freeze', 'cases': sorted(records), 'output': str(output)}))


if __name__ == '__main__':
    main()
