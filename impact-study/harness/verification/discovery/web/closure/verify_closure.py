#!/usr/bin/env python3
"""Execute independently reviewed cross-family consumer closure checks."""
import argparse, hashlib, importlib.util, json, os, pathlib, shutil, subprocess, tempfile, time

HERE=pathlib.Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('web_verifier',HERE.parent/'verify.py')
base=importlib.util.module_from_spec(spec);spec.loader.exec_module(base)
PINS={
 'criteria.json':'f88b7dac3391639453fbf1ab9908f98446d1642d3ae4fd86086ccd75ba00ccaa',
 'CurrencyClosure.test.tsx':'43fc628f05ccf8f5f47386d6f5e38534d32ed925d22a92510f2947fef93d98e0',
 'ProductClosureTest.java':'847a1b77ebe6f39413e2b346bcaaafcbc8c1ba0bc1f61a1980912d002aec4279',
 'BasketClosureTest.java':'9d69eda798563f8c28b1ab01633d4d3ab43d94213850e8c6c08dcd24fd8f0ef0'}

def main():
 for name,pin in PINS.items(): assert base.sha(HERE/name)==pin,('protected bytes changed',name)
 portable=HERE/'evidence';assert not portable.exists(),'Fresh evidence directory required'
 runtime=pathlib.Path(tempfile.mkdtemp(prefix='discovery-closure-',dir='/private/tmp'))
 evidence=runtime/'portable-evidence';evidence.mkdir()
 commands=[];summary={'status':'running','runtime':str(runtime),'protected':PINS,'commands':commands}
 def command(label,args,cwd,env=None,success=True):
  now=time.monotonic();env={**os.environ,'JAVA_HOME':base.JAVA,'PATH':base.JAVA+'/bin:'+os.environ['PATH'],**(env or {})}
  for k in ('MAVEN_ARGS','MAVEN_OPTS','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS'):env.pop(k,None)
  r=subprocess.run(args,cwd=cwd,env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=180)
  (evidence/(label+'.log')).write_bytes(r.stdout)
  commands.append({'label':label,'argv':args,'repo':cwd.name,'exit':r.returncode,'expected_success':success,'elapsed_seconds':round(time.monotonic()-now,3)})
  (evidence/'commands.json').write_text(json.dumps(commands,indent=2)+'\n')
  assert (r.returncode==0)==success,(label,r.stdout.decode(errors='replace')[-5000:])
  return r
 try:
  roots={};snapshots={}
  for repo in ['catalog-service','basket-service','order-service','commerce-bff','commerce-web']:
   root=runtime/repo;root.mkdir();base.archive(repo,root);roots[repo]=root
   snapshots[repo]={'pin':base.PINS[repo],'files':{str(p.relative_to(root)):base.sha(p) for p in sorted(root.rglob('*')) if p.is_file()}}
  (evidence/'source-snapshots.json').write_text(json.dumps(snapshots,indent=2)+'\n')
  for name in PINS:shutil.copy(HERE/name,evidence/name)
  web=roots['commerce-web'];shutil.copytree(base.ESTATE/'commerce-web/node_modules',web/'node_modules',symlinks=True)
  shutil.copy(HERE/'CurrencyClosure.test.tsx',web/'src/test/CurrencyClosure.test.tsx')
  for repo,test,package in [('catalog-service','CatalogWireTest','catalog'),('commerce-bff','ForwardWireTest','bff/api'),('basket-service','ProductClosureTest','basket/verification'),('order-service','BasketClosureTest','order/verification')]:
   source=(HERE.parent if test in ['CatalogWireTest','ForwardWireTest'] else HERE)/(test+'.java')
   target=roots[repo]/'src/test/java/com/poc'/package;target.mkdir(parents=True,exist_ok=True);shutil.copy(source,target/source.name)
   summary.setdefault('additional_protected',{})[source.name]=base.sha(source)
  def java(label,repo,test,env):
   r=command(label,['mvn','-o','-q','-Dtest='+test,'test'],roots[repo],env)
   for p in (roots[repo]/'target/surefire-reports').glob('*'+test+'*'):shutil.copy(p,evidence/(label+'-'+p.name))
   return r
  def produce(label):
   wire=evidence/(label+'-provider.json');java(label+'-origin','catalog-service','CatalogWireTest',{'WIRE_OUTPUT':str(wire)});return wire
  def forward(label,wire):
   output=evidence/(label+'-bff.json');java(label+'-forward','commerce-bff','ForwardWireTest',{'WIRE_INPUT':str(wire),'WIRE_OUTPUT':str(output),'WIRE_KIND':'catalog'});return output
  def browser(label,wire,success):
   r=command(label+'-web',['npm','test','--','src/test/CurrencyClosure.test.tsx','--reporter=verbose'],web,{'WIRE_INPUT':str(wire)},success)
   if not success:
    assert b'BUSINESS_PRODUCT_CURRENCY_PRICES' in r.stdout,'Expected exact protected price failure'
    assert b'Unhandled Errors' not in r.stdout,'Unhandled runtime/transport errors do not count'
  baseline=produce('H01-baseline');baseline_bff=forward('H01-baseline',baseline);browser('H01-baseline',baseline_bff,True)
  base.replace(roots['catalog-service'],base.PRODUCT,'String currency,','@com.fasterxml.jackson.annotation.JsonProperty("currencyCode") String currency,')
  base.patch(roots['catalog-service'],'catalog-service',[base.PRODUCT],evidence/'H01-upstream.patch')
  expected=(HERE.parents[1]/'http/evidence/H01-attempt-a/changed.patch').read_text().replace('a/catalog-service/','a/').replace('b/catalog-service/','b/')
  assert (evidence/'H01-upstream.patch').read_text()==expected,'Cross-family H01 public source mutation mismatch'
  changed=produce('H01-changed');changed_bff=forward('H01-changed',changed);browser('H01-changed',changed_bff,False)
  api=web/'src/api/products.ts';original=api.read_text()
  repaired=original.replace('export function listProducts','export async function listProducts').replace("return apiRequest<Product[]>(`/products?page=${page}&size=${size}`, {}, false)","const products = await apiRequest<Array<Product & { currencyCode: string }>>(`/products?page=${page}&size=${size}`, {}, false)\n  return products.map((product) => ({ ...product, currency: product.currencyCode }))")
  assert repaired!=original;api.write_text(repaired);base.patch(web,'commerce-web',['src/api/products.ts'],evidence/'H01-web-repair.patch')
  command('H01-repaired-web-build',['npm','run','build'],web);browser('H01-repaired',changed_bff,True)
  api.write_text(repaired.replace('currency: product.currencyCode','currency: (product as Product & { currencyWrong?: string }).currencyWrong ?? ""'));base.patch(web,'commerce-web',['src/api/products.ts'],evidence/'H01-web-fault.patch')
  command('H01-fault-web-build',['npm','run','build'],web);browser('H01-faulty-repair',changed_bff,False)
  # Independently unchanged direct consumers use original case provider bytes.
  old_evidence=HERE.parent/'evidence'
  for case in ['H04','H11']:
   for phase in ['baseline','changed']:
    wire=old_evidence/('H04-baseline-provider.json' if phase=='baseline' else case+'-changed-provider.json')
    shutil.copy(wire,evidence/(case+'-'+phase+'-input.json'))
    java(case+'-'+phase+'-basket','basket-service','ProductClosureTest',{'WIRE_INPUT':str(wire),'WIRE_OUTPUT':str(evidence/(case+'-'+phase+'-product-info.json'))})
   assert (evidence/(case+'-baseline-product-info.json')).read_bytes()==(evidence/(case+'-changed-product-info.json')).read_bytes()
  for phase in ['baseline','changed']:
   wire=old_evidence/('H03-'+phase+'-provider.json');shutil.copy(wire,evidence/('H03-'+phase+'-input.json'))
   java('H03-'+phase+'-order','order-service','BasketClosureTest',{'WIRE_INPUT':str(wire),'WIRE_OUTPUT':str(evidence/('H03-'+phase+'-basket-snapshot.json'))})
  assert (evidence/'H03-baseline-basket-snapshot.json').read_bytes()==(evidence/'H03-changed-basket-snapshot.json').read_bytes()
  for name,pin in PINS.items():assert base.sha(HERE/name)==pin
  summary['status']='verified'
 except Exception as e:
  summary['status']='failed';summary['error']=str(e);raise
 finally:
  summary['evidence_limits']=['Exact H01 producer patch matches HTTP family H01. Concrete serialized values differ across independently chosen business fixtures.','GET forwarding executes localhost HTTP and actual BFF controller; global servlet security and query propagation semantics not claimed.','H04/H11 Basket and H03 Order tests execute actual unedited HTTP clients and compare output snapshots; no unrelated persistence/Kafka journey.']
  (evidence/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
  artifacts={str(p.relative_to(evidence)):base.sha(p) for p in sorted(evidence.rglob('*')) if p.is_file() and p.name!='manifest.json'}
  (evidence/'manifest.json').write_text(json.dumps(artifacts,indent=2)+'\n')
  shutil.copytree(evidence,portable)
 print(json.dumps({'status':summary['status'],'commands':len(commands)}))

if __name__=='__main__':main()
