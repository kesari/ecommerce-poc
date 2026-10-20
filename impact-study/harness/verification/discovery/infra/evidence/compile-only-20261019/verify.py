#!/usr/bin/env python3
"""Execute protected discovery infrastructure cases in pinned, isolated source archives."""
import argparse, difflib, hashlib, io, json, os, shutil, subprocess, tarfile, uuid
from pathlib import Path
import xml.etree.ElementTree as ET
HERE=Path(__file__).resolve().parent
STUDY=HERE.parents[3]
def sha(data):return hashlib.sha256(data).hexdigest()
def inventory(path):return {str(p.relative_to(path)):sha(p.read_bytes()) for p in sorted(path.rglob('*')) if p.is_file() and 'target' not in p.parts}
def replace(path,before,after,count=1):
 s=path.read_text()
 if s.count(before)!=count:raise ValueError(f'{path}: expected {count} anchors, got {s.count(before)}')
 path.write_text(s.replace(before,after))
def mutate(root,case,phase):
 if phase=='baseline':return
 if case=='H05':
  replace(root/'payment-service/src/main/java/com/poc/payment/application/PaymentCommandService.java','CHARGED_TOPIC = "payment.charged.v1"','CHARGED_TOPIC = "payment.charged.v2"')
  if phase=='repaired':replace(root/'order-service/src/main/java/com/poc/order/infrastructure/messaging/SagaResultListener.java','            "payment.charged.v1",','            "payment.charged.v2",')
 elif case=='H06':
  replace(root/'order-service/src/main/java/com/poc/order/application/OrderService.java','"quantity", line.quantity()','"units", line.quantity()')
  if phase=='repaired':replace(root/'inventory-service/src/main/java/com/poc/inventory/infrastructure/messaging/InventoryCommandListener.java','item.path("quantity")','item.path("units")')
 elif case=='H09':
  (root/'catalog-service/src/main/resources/db/migration/V3__private_price_column.sql').write_text('ALTER TABLE products RENAME COLUMN price_minor TO unit_price_minor;\n')
  mapper=root/'catalog-service/src/main/resources/mybatis/mapper/ProductMapper.xml';replace(mapper,'price_minor','unit_price_minor',2)
  if phase=='faulted-change':replace(mapper,'unit_price_minor AS priceMinor','(unit_price_minor + 1) AS priceMinor')
 elif case=='H10':
  cache=root/'catalog-service/src/main/java/com/poc/catalog/infrastructure/cache/ProductCache.java'
  replace(cache,'objectMapper.writeValueAsString(product), DETAIL_TTL','objectMapper.writeValueAsString(java.util.Map.of("format", 1, "product", product)), DETAIL_TTL')
  replace(cache,'return objectMapper.readValue(raw, Product.class);','var node = objectMapper.readTree(raw);\n            return objectMapper.treeToValue(node.has("format") ? node.path("product") : node, Product.class);')
  if phase=='faulted-change':replace(cache,'java.util.Map.of("format", 1, "product", product)','java.util.Map.of("format", 1, "product", product.withPrice(product.priceMinor() + 1))')

def run(args):
 runtime=args.runtime.resolve();evidence=args.evidence.resolve()
 if runtime.exists() or evidence.exists():raise ValueError('runtime and evidence must be new paths')
 if not str(runtime).startswith('/private/tmp/'):raise ValueError('runtime must be fresh /private/tmp directory')
 checks={name:sha((HERE/name).read_bytes()) for name in ('criteria.json','MessagingBoundaryTest.java','StorageBoundaryTest.java')}
 if checks!=json.loads(args.trusted_inputs):raise ValueError('protected input pin mismatch')
 runtime.mkdir(parents=True);evidence.mkdir(parents=True)
 for name in checks:shutil.copyfile(HERE/name,evidence/name)
 criteria=json.loads((HERE/'criteria.json').read_text())['cases'][args.case]
 run_id='discovery-'+str(uuid.uuid4());env=os.environ.copy();env.update(JAVA_HOME=args.java_home,DOCKER_HOST='unix:///Users/rajmohan/.docker/run/docker.sock',TESTCONTAINERS_RYUK_DISABLED='true');env['PATH']=args.java_home+'/bin:'+env['PATH']
 for name in ('MAVEN_ARGS','MAVEN_OPTS','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS'):env.pop(name,None)
 manifest={'case':args.case,'status':'running','protected_inputs':checks,'run_id':run_id,'commands':[],'phases':{},'limits':criteria['limits'],'container_policy':{'cached_images_only':True,'loopback_ports_only':True,'ryuk_disabled':True,'privileged':False}}
 def command(argv,cwd,name,timeout=240):
  try:r=subprocess.run(argv,cwd=cwd,env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=timeout);code,body=r.returncode,r.stdout
  except subprocess.TimeoutExpired as e:code,body=124,(e.stdout or b'')+b'\nVERIFIER TIMEOUT\n'
  (evidence/name).write_bytes(body);manifest['commands'].append({'argv':argv,'cwd':str(cwd),'exit_code':code,'log':name,'sha256':sha(body)});return code
 try:
  images=['apache/kafka:4.0.0'] if args.case in ('H05','H06') else ['postgres:17'] if args.case=='H09' else ['valkey/valkey:8']
  for i,image in enumerate(images):
   if command(['docker','image','inspect',image,'--format','{{.Id}}'],runtime,f'image-{i}.log'):raise RuntimeError('cached image unavailable')
  pins=json.loads((STUDY/'harness/indexes/pins.json').read_text())['repositories'];repos=['order-service','payment-service','inventory-service'] if args.case in ('H05','H06') else ['catalog-service','basket-service']
  source=runtime/'source';source.mkdir();manifest['snapshot']={repo:{k:pins[repo][k] for k in ('commit','tree')} for repo in repos}
  for repo in repos:
   target=source/repo;target.mkdir();archive=subprocess.check_output(['git','-C',str(Path(args.estate)/repo),'archive','--format=tar',pins[repo]['commit']]);
   with tarfile.open(fileobj=io.BytesIO(archive)) as bundle:bundle.extractall(target,filter='data')
   for rel in ('pom.xml','src/main'):
    p=target/rel;q=evidence/'source-snapshot'/repo/rel;q.parent.mkdir(parents=True,exist_ok=True)
    shutil.copytree(p,q) if p.is_dir() else shutil.copyfile(p,q)
  manifest['source_files']=inventory(source)
  for phase,expected in criteria['phases'].items():
   work=runtime/phase;shutil.copytree(source,work);mutate(work,args.case,phase);changed=inventory(work)
   patch=''
   for path in sorted(changed):
    if changed[path]!=manifest['source_files'].get(path):
     before=(source/path).read_text().splitlines(True) if (source/path).exists() else []
     patch+=''.join(difflib.unified_diff(before,(work/path).read_text().splitlines(True),fromfile='a/'+path,tofile='b/'+path))
   (evidence/f'{phase}.patch').write_text(patch)
   origin='payment-service' if args.case=='H05' else 'order-service' if args.case=='H06' else 'catalog-service'
   if command(['mvn','-o','-B','-Dstyle.color=never','-DskipTests','compile'],work/origin,f'{phase}-origin-compile.log'):raise RuntimeError(f'{phase}: full producer compile failed')
   host='order-service' if args.case in ('H05','H06') else 'catalog-service';module=work/host
   if host=='order-service':
    for repo,parts in [('payment-service',['application','domain','infrastructure/persistence/row','infrastructure/kafka/OutboxPublisher.java']),('inventory-service',['application','domain','infrastructure/messaging'])]:
     for part in parts:
      p=work/repo/'src/main/java/com/poc'/repo.split('-')[0]/part
      for java in ([p] if p.is_file() else p.rglob('*.java')):
       dest=module/'src/test/java'/java.relative_to(work/repo/'src/main/java');dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(java,dest)
    test='MessagingBoundaryTest.java';package='order'
   else:
    for rel in ('infrastructure/catalog','application/port/CatalogPort.java','application/DownstreamUnavailableException.java'):
     p=work/'basket-service/src/main/java/com/poc/basket'/rel
     for java in ([p] if p.is_file() else p.rglob('*.java')):
      dest=module/'src/test/java'/java.relative_to(work/'basket-service/src/main/java');dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(java,dest)
    pom=module/'pom.xml';replace(pom,'  <dependencies>','  <dependencies>\n    <dependency><groupId>io.github.resilience4j</groupId><artifactId>resilience4j-circuitbreaker</artifactId><version>2.2.0</version><scope>test</scope></dependency>\n    <dependency><groupId>io.github.resilience4j</groupId><artifactId>resilience4j-retry</artifactId><version>2.2.0</version><scope>test</scope></dependency>')
    test='StorageBoundaryTest.java';package='catalog'
   dest=module/f'src/test/java/com/poc/{package}/verification'/test;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(HERE/test,dest)
   obs=evidence/f'{phase}-observation.json';name=test.removesuffix('.java')
   code=command(['mvn','-o','-B','-Dstyle.color=never',f'-Dtest={name}',f'-Dverification.case={args.case}',f'-Dverification.phase={phase}',f'-Dverification.run={run_id}',f'-Dverification.observation={obs}','test'],module,f'{phase}-test.log')
   report=module/f'target/surefire-reports/TEST-com.poc.{package}.verification.{name}.xml'
   if report.exists():shutil.copyfile(report,evidence/f'{phase}-junit.xml')
   if code!=expected or not obs.exists() or not report.exists():raise RuntimeError(f'{phase}: exit {code} expected {expected}; missing observations/report means infrastructure failure')
   suite=ET.parse(report).getroot();failure=suite.find('.//failure');errors=int(suite.attrib['errors']);failed=int(suite.attrib['failures'])
   if errors or failed!=int(expected!=0):raise RuntimeError(f'{phase}: unrelated JUnit error/failure')
   if expected and criteria['fault_assertion'] not in failure.attrib.get('message',''):raise RuntimeError(f'{phase}: failed unexpected assertion')
   observed=json.loads(obs.read_text())
   if args.case in ('H05','H06') and (observed['broker_records']!=1 or not observed['consumer_liveness']):raise RuntimeError('broker/listener control failed')
   manifest['phases'][phase]={'status':'passed','test_exit':code,'observations':observed,'patch_sha256':sha(patch.encode()),'source_files':changed}
  if checks!={name:sha((HERE/name).read_bytes()) for name in checks}:raise RuntimeError('protected inputs changed during execution')
  manifest['status']='passed'
 except Exception as e:manifest.update(status='failed',error=str(e))
 finally:
  # Only containers created by this verifier carry the unpredictable owned label.
  command(['docker','ps','-aq','--filter',f'label=discovery.verification={run_id}'],runtime,'owned-container-ids.log',30)
  owned=(evidence/'owned-container-ids.log').read_text().split()
  if owned and all(len(i)==12 and all(c in '0123456789abcdef' for c in i) for i in owned):command(['docker','rm','-f',*owned],runtime,'owned-container-cleanup.log',30)
  manifest['artifacts']=inventory(evidence);(evidence/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
 print(json.dumps({'case':args.case,'status':manifest['status'],'error':manifest.get('error'),'evidence':str(evidence)}));return int(manifest['status']!='passed')
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--case',choices=['H05','H06','H09','H10'],required=True);p.add_argument('--estate',required=True);p.add_argument('--runtime',type=Path,required=True);p.add_argument('--evidence',type=Path,required=True);p.add_argument('--java-home',default='/Users/rajmohan/.sdkman/candidates/java/21.0.10-tem');p.add_argument('--trusted-inputs',required=True);a=p.parse_args();raise SystemExit(run(a))
