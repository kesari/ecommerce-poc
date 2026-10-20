#!/usr/bin/env python3
"""H08 actual service/cache boundary with controlled previous-policy cache state."""
import argparse, difflib, hashlib, io, json, os, shutil, subprocess, sys, tarfile, time
from pathlib import Path
import xml.etree.ElementTree as ET
HERE=Path(__file__).resolve().parent
STUDY=HERE.parents[4]
sys.path.insert(0,str(HERE.parent))
from verify_http import canonical, files, replace, sha

def main(args):
 output=args.output.resolve()
 if output.exists() or output.is_relative_to(STUDY) or output.is_relative_to(args.estate.resolve()):
  raise ValueError('fresh output outside study and estate required')
 protected={'criteria.json':args.trusted_criteria_sha256,'H08CacheClosureTest.java':args.trusted_test_sha256}
 for name,digest in protected.items():
  if sha((HERE/name).read_bytes())!=digest:raise ValueError('reviewed protected bytes mismatch')
 output.mkdir(parents=True)
 for name in protected:shutil.copyfile(HERE/name,output/name)
 commands=[]
 manifest={'case':'H08-cache-closure','status':'running','protected':protected,'verifier_sha256':sha(Path(__file__).read_bytes()),'commands':commands,'phases':{}}
 env=os.environ.copy();env['JAVA_HOME']=str(args.java_home);env['PATH']=str(args.java_home/'bin')+os.pathsep+env['PATH']
 for name in ('MAVEN_ARGS','MAVEN_OPTS','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS'):env.pop(name,None)
 try:
  pin=json.loads((STUDY/'harness/indexes/pins.json').read_text())['repositories']['shipment-service'];manifest['source_pin']=pin
  repo=args.estate/'shipment-service'
  actual_tree=subprocess.check_output(['git','-C',str(repo),'rev-parse',pin['commit']+'^{tree}']).decode().strip()
  if actual_tree!=pin['tree']:raise ValueError('pinned tree mismatch')
  data=subprocess.check_output(['git','-C',str(repo),'archive',pin['commit']]);source=output/'baseline-source';source.mkdir()
  with tarfile.open(fileobj=io.BytesIO(data)) as archive:archive.extractall(source,filter='data')
  baseline=files(source);manifest['baseline_source_files']=baseline;manifest['baseline_source_sha256']=sha(canonical(baseline))
  estimator=Path('src/main/java/com/poc/shipment/application/DeliveryEstimator.java')
  for phase in ('baseline','changed','repaired','faulted-repair'):
   work=output/phase;shutil.copytree(source,work)
   if phase!='baseline':replace(work/estimator,'today.plusDays(4), today.plusDays(6)','today.plusDays(5), today.plusDays(7)')
   if phase=='faulted-repair':replace(work/estimator,'today.plusDays(5), today.plusDays(7)','today.plusDays(5).plusDays(1), today.plusDays(7).plusDays(1)')
   production=files(work);paths=[p for p,h in production.items() if baseline.get(p)!=h]
   patch=''.join(''.join(difflib.unified_diff((source/p).read_text().splitlines(True),(work/p).read_text().splitlines(True),fromfile='a/'+p,tofile='b/'+p))for p in paths)
   (output/f'{phase}.patch').write_text(patch)
   manifest['phases'][phase]={'production_files':production,'patch_sha256':sha(patch.encode())}
   target=work/'src/test/java/com/poc/shipment/verification/H08CacheClosureTest.java';target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(HERE/'H08CacheClosureTest.java',target)
   observation=output/f'{phase}-observation.json'
   argv=['mvn','-o','-B','-Dstyle.color=never','-Dtest=H08CacheClosureTest',f'-Dverification.phase={phase}',f'-Dverification.observation={observation}','test']
   started=time.monotonic();result=subprocess.run(argv,cwd=work,env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=180)
   log=f'{phase}-service-test.log';(output/log).write_bytes(result.stdout);commands.append({'argv':argv,'cwd':str(work),'exit_code':result.returncode,'log':log,'log_sha256':sha(result.stdout),'seconds':round(time.monotonic()-started,3)})
   expected=1 if phase=='faulted-repair' else 0
   if result.returncode!=expected:raise RuntimeError(f'{phase}: unexpected Maven exit{result.returncode}')
   report=work/'target/surefire-reports/TEST-com.poc.shipment.verification.H08CacheClosureTest.xml';suite=ET.parse(report).getroot()
   if int(suite.attrib['tests'])!=1 or int(suite.attrib['errors']) or int(suite.attrib['failures'])!=expected:raise RuntimeError('wrong protected JUnit suite outcome')
   if expected and 'BUSINESS_NEW_COMPUTATION_FROM' not in suite.find('.//failure').attrib.get('message',''):raise RuntimeError('fault failed unrelated assertion')
   shutil.copyfile(report,output/f'{phase}-junit.xml');manifest['phases'][phase].update({'passed':True,'fault_detected':bool(expected),'observations':json.loads(observation.read_text())})
  if files(source)!=baseline:raise RuntimeError('baseline source changed')
  for name,digest in protected.items():
   if sha((HERE/name).read_bytes())!=digest:raise RuntimeError('protected bytes changed')
  manifest['status']='passed'
 except Exception as error:manifest['status']='failed';manifest['error']=str(error)
 finally:
  manifest['artifacts']=[{'path':p.name,'sha256':sha(p.read_bytes())}for p in sorted(output.iterdir())if p.is_file()and p.name!='manifest.json'];(output/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
 print(json.dumps({'status':manifest['status'],'manifest':str(output/'manifest.json'),'error':manifest.get('error')}));return int(manifest['status']!='passed')
if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('--estate',required=True,type=Path);parser.add_argument('--output',required=True,type=Path);parser.add_argument('--java-home',required=True,type=Path);parser.add_argument('--trusted-criteria-sha256',required=True);parser.add_argument('--trusted-test-sha256',required=True);raise SystemExit(main(parser.parse_args()))
