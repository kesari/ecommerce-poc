#!/usr/bin/env python3
"""Retain offline compilation evidence while the external container runtime is unavailable."""
import argparse,json,os,shutil,subprocess
from pathlib import Path
from verify import HERE,inventory,mutate,sha

def run(args):
 source=args.prepared_source.resolve();runtime=args.runtime.resolve();out=args.evidence.resolve()
 if out.exists() or runtime.exists():raise ValueError('new runtime/evidence required')
 if not str(runtime).startswith('/private/tmp/'):raise ValueError('runtime must be /private/tmp')
 expected=json.loads(args.trusted_inputs)
 if expected!={n:sha((HERE/n).read_bytes()) for n in expected}:raise ValueError('protected digest mismatch')
 out.mkdir(parents=True);runtime.mkdir(parents=True);env=os.environ.copy();env['JAVA_HOME']='/Users/rajmohan/.sdkman/candidates/java/21.0.10-tem';env['PATH']=env['JAVA_HOME']+'/bin:'+env['PATH']
 for key in ('MAVEN_ARGS','MAVEN_OPTS','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS'):env.pop(key,None)
 manifest={'kind':'compile_only_not_runtime_proof','protected_inputs':expected,'cases':{},'commands':[],'status':'running'}
 for case,criteria in json.loads((HERE/'criteria.json').read_text())['cases'].items():
  manifest['cases'][case]={}
  for phase in criteria['phases']:
   work=runtime/f'{case}-{phase}';shutil.copytree(source,work,ignore=shutil.ignore_patterns('target','*.log'));mutate(work,case,phase)
   if case=='H05':shutil.copyfile(work/'payment-service/src/main/java/com/poc/payment/application/PaymentCommandService.java',work/'order-service/src/test/java/com/poc/payment/application/PaymentCommandService.java')
   if case=='H06':shutil.copyfile(work/'inventory-service/src/main/java/com/poc/inventory/infrastructure/messaging/InventoryCommandListener.java',work/'order-service/src/test/java/com/poc/inventory/infrastructure/messaging/InventoryCommandListener.java')
   origin='payment-service' if case=='H05' else 'order-service' if case=='H06' else 'catalog-service';host='order-service' if case in ('H05','H06') else 'catalog-service'
   codes=[]
   for label,module,goal in [('origin',origin,'compile'),('protected-test',host,'test-compile')]:
    argv=['mvn','-o','-B','-Dstyle.color=never','-DskipTests',goal];result=subprocess.run(argv,cwd=work/module,env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=120);name=f'{case}-{phase}-{label}.log';(out/name).write_bytes(result.stdout);manifest['commands'].append({'argv':argv,'cwd':str(work/module),'exit_code':result.returncode,'log':name});codes.append(result.returncode)
   manifest['cases'][case][phase]={'compiles':not any(codes),'source_files':inventory(work)}
 manifest['status']='passed' if all(p['compiles'] for c in manifest['cases'].values() for p in c.values()) else 'failed';manifest['artifacts']=inventory(out);(out/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n');print(json.dumps({'status':manifest['status'],'kind':manifest['kind'],'evidence':str(out)}));return int(manifest['status']!='passed')
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--prepared-source',type=Path,required=True);p.add_argument('--runtime',type=Path,required=True);p.add_argument('--evidence',type=Path,required=True);p.add_argument('--trusted-inputs',required=True);raise SystemExit(run(p.parse_args()))
