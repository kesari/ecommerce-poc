#!/usr/bin/env python3
"""Execute protected HTTP discovery boundaries against pinned source archives."""
import argparse,difflib,hashlib,io,json,os,shutil,subprocess,tarfile,time,xml.etree.ElementTree as ET
from pathlib import Path
HERE=Path(__file__).resolve().parent
STUDY=HERE.parents[3]
def sha(b):return hashlib.sha256(b).hexdigest()
def canonical(v):return json.dumps(v,sort_keys=True,separators=(',',':')).encode()
def files(p):return {str(f.relative_to(p)):sha(f.read_bytes()) for f in sorted(p.rglob('*')) if f.is_file() and 'target' not in f.relative_to(p).parts}
def replace(p,a,b):
 s=p.read_text()
 if s.count(a)!=1:raise ValueError(f'nonunique patch anchor {p}: {a!r}')
 p.write_text(s.replace(a,b))
def main(a):
 out=a.output.resolve();estate=a.estate.resolve()
 if out.exists() or out.is_relative_to(STUDY) or out.is_relative_to(estate):raise ValueError('fresh output outside study and estate required')
 test='CatalogDiscoveryTest.java' if a.case=='H01' else 'ShipmentDiscoveryTest.java'
 protected={p:sha((HERE/p).read_bytes()) for p in ('criteria.json',test)}
 if a.trusted_criteria_sha256!=protected['criteria.json'] or a.trusted_test_sha256!=protected[test]:raise ValueError('reviewed oracle bytes mismatch')
 out.mkdir(parents=True); commands=[];m={'case':a.case,'status':'running','protected':protected,'commands':commands,'phases':{}}
 for p in protected:shutil.copyfile(HERE/p,out/p)
 env=os.environ.copy();env['JAVA_HOME']=str(a.java_home);env['PATH']=str(a.java_home/'bin')+os.pathsep+env['PATH']
 for k in ('MAVEN_ARGS','MAVEN_OPTS','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS'):env.pop(k,None)
 def cmd(argv,cwd,log):
  start=time.monotonic()
  try:r=subprocess.run(argv,cwd=cwd,env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=240);code,raw=r.returncode,r.stdout
  except subprocess.TimeoutExpired as e:code,raw=124,(e.stdout or b'')+b'\nTIMEOUT\n'
  (out/log).write_bytes(raw);commands.append({'argv':argv,'cwd':str(cwd),'exit_code':code,'log':log,'log_sha256':sha(raw),'seconds':round(time.monotonic()-start,3)});return code
 try:
  criteria=json.loads((HERE/'criteria.json').read_text())['cases'][a.case];m['criteria']=criteria
  origin,consumer=criteria['origin'],criteria['consumer'];pins=json.loads((STUDY/'harness/indexes/pins.json').read_text())['repositories'];m['snapshot']={r:{k:pins[r][k] for k in ('commit','tree')} for r in sorted(pins)};m['snapshot_sha256']=sha(canonical(m['snapshot']))
  source=out/'baseline-source';source.mkdir()
  for repo in (origin,consumer):
   p=pins[repo];tree=subprocess.check_output(['git','-C',str(estate/repo),'rev-parse',p['commit']+'^{tree}']).decode().strip()
   if tree!=p['tree']:raise ValueError('source tree mismatch')
   archive=subprocess.check_output(['git','-C',str(estate/repo),'archive','--format=tar',p['commit']]);target=source/repo;target.mkdir()
   with tarfile.open(fileobj=io.BytesIO(archive)) as t:t.extractall(target,filter='data')
  m['baseline_source_files']=files(source);m['baseline_source_sha256']=sha(canonical(m['baseline_source_files']))
  for phase in ('baseline','changed','repaired','faulted-repair'):
   work=out/phase;shutil.copytree(source,work);provider=work/origin;client=work/consumer
   dto=provider/'src/main/java/com/poc'/('catalog/api/dto/ProductResponse.java' if a.case=='H01' else 'shipment/api/dto/DeliveryEstimateResponse.java')
   consumer_path=client/'src/main/java/com/poc'/('basket/infrastructure/catalog/CatalogProductResponse.java' if a.case=='H01' else 'order/infrastructure/client/ShipmentClient.java')
   if phase!='baseline':
    if a.case=='H01':replace(dto,'String currency,','@com.fasterxml.jackson.annotation.JsonProperty("currencyCode") String currency,')
    elif a.case=='H02':replace(dto,'long shippingChargeMinor,','@com.fasterxml.jackson.annotation.JsonProperty("feeMinor") long shippingChargeMinor,')
    elif a.case=='H07':replace(dto,'estimate.shippingMinor(), estimate.currency()','estimate.shippingMinor() / 100, estimate.currency()')
    elif a.case=='H08':
     estimator=provider/'src/main/java/com/poc/shipment/application/DeliveryEstimator.java';replace(estimator,'today.plusDays(4), today.plusDays(6)','today.plusDays(5), today.plusDays(7)')
   if phase in ('repaired','faulted-repair'):
    fault=phase=='faulted-repair'
    if a.case=='H01':replace(consumer_path,'String currency,','@com.fasterxml.jackson.annotation.JsonAlias("'+('wrongCurrency' if fault else 'currencyCode')+'") String currency,')
    elif a.case=='H02':replace(consumer_path,'long shippingChargeMinor,','@com.fasterxml.jackson.annotation.JsonAlias("'+('wrongFee' if fault else 'feeMinor')+'") long shippingChargeMinor,')
    elif a.case=='H07':replace(consumer_path,'response.shippingChargeMinor(), response.currency()','Math.multiplyExact(response.shippingChargeMinor(), '+('10' if fault else '100')+'), response.currency()')
    elif a.case=='H08' and fault:replace(consumer_path,'new DeliveryWindow(response.fromDate(), response.toDate())','new DeliveryWindow(response.toDate(), response.fromDate())')
   changed=files(work);paths=[p for p,h in changed.items() if m['baseline_source_files'].get(p)!=h]
   patch=''.join(''.join(difflib.unified_diff((source/p).read_text().splitlines(True),(work/p).read_text().splitlines(True),fromfile='a/'+p,tofile='b/'+p))for p in paths);(out/f'{phase}.patch').write_text(patch)
   m['phases'][phase]={'source_files':changed,'source_sha256':sha(canonical(changed)),'patch_sha256':sha(patch.encode())}
   code=cmd(['mvn','-o','-B','-Dstyle.color=never','-Dmaven.test.skip=true','compile'],provider,f'{phase}-producer-compile.log')
   if code:raise RuntimeError(f'{phase} producer compile exit{code}')
   if a.case=='H01':paths=['catalog/api/dto/ProductResponse.java']
   else:paths=['shipment/api/dto/DeliveryEstimateResponse.java','shipment/api/dto/DeliveryEstimateRequest.java','shipment/domain/model/DeliveryEstimate.java','shipment/domain/model/DeliveryWindow.java','shipment/application/DeliveryEstimator.java','shipment/domain/exception/UnsupportedDestinationException.java']
   for p in paths:
    dest=client/'src/test/java/com/poc'/p;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(provider/'src/main/java/com/poc'/p,dest)
   package='basket' if a.case=='H01' else 'order';dest=client/f'src/test/java/com/poc/{package}/verification'/test;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(HERE/test,dest)
   obs=out/f'{phase}-observation.json';cls=test.removesuffix('.java');code=cmd(['mvn','-o','-B','-Dstyle.color=never',f'-Dtest={cls}',f'-Dverification.phase={phase}',f'-Dverification.scenario={a.case}',f'-Dverification.observation={obs}','test'],client,f'{phase}-boundary-test.log')
   if code!=(1 if phase=='faulted-repair' else 0):raise RuntimeError(f'{phase} unexpected testexit{code}')
   if not obs.exists():raise RuntimeError(f'{phase} missing actual observations')
   report=client/f'target/surefire-reports/TEST-com.poc.{package}.verification.{cls}.xml';suite=ET.parse(report).getroot();expected=1 if phase=='faulted-repair' else 0
   if int(suite.attrib['tests'])!=1 or int(suite.attrib['errors']) or int(suite.attrib['failures'])!=expected:raise RuntimeError(f'{phase} wrong JUnit result')
   if expected and not any('BUSINESS_' in f.attrib.get('message','') for f in suite.findall('.//failure')):raise RuntimeError('fault did not fail protected business assertion')
   shutil.copyfile(report,out/f'{phase}-junit.xml');m['phases'][phase].update({'passed':True,'fault_detected':bool(expected),'observations':json.loads(obs.read_text())})
  if files(source)!=m['baseline_source_files']:raise RuntimeError('protected baseline mutated')
  if protected!={p:sha((HERE/p).read_bytes())for p in protected}:raise RuntimeError('protected oracle mutated')
  m['status']='passed'
 except Exception as e:m['status']='failed';m['error']=str(e)
 finally:
  m['artifacts']=[{'path':str(p.relative_to(out)),'sha256':sha(p.read_bytes())}for p in sorted(out.iterdir())if p.is_file() and p.name!='manifest.json'];(out/'manifest.json').write_text(json.dumps(m,indent=2)+'\n')
 print(json.dumps({'case':a.case,'status':m['status'],'manifest':str(out/'manifest.json'),'error':m.get('error')}));return int(m['status']!='passed')
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--case',choices=['H01','H02','H07','H08'],required=True);p.add_argument('--estate',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--java-home',type=Path,required=True);p.add_argument('--trusted-criteria-sha256',required=True);p.add_argument('--trusted-test-sha256',required=True);raise SystemExit(main(p.parse_args()))
