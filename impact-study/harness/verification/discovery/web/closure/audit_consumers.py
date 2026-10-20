#!/usr/bin/env python3
"""Inventory contract consumer references from all ten exact pinned git archives.

This is static source evidence, not a claim of full dynamic estate coverage.
"""
import hashlib, io, json, pathlib, re, shutil, subprocess, tarfile, tempfile
HERE=pathlib.Path(__file__).resolve().parent
STUDY=HERE.parents[4]
ESTATE=pathlib.Path('/Users/rajmohan/Projects/POC/POC-order-microservices')
PATTERN=re.compile(r'api/v1/products|api/v1/basket|delivery-estimates|CatalogClient|CatalogProductResponse|ProductResponse|ShipmentClient|DeliveryEstimateResponse|listProducts|product\.currency|product\.imageUrl|basket\.totalMinor|QuoteResponse|estimatedDelivery|promisedFrom|promisedTo|shipment\.created|class CatalogService|listActive|RestClient|WebClient|RestTemplate|HttpClient|Feign|WebTarget|fetch\(')

def main():
 pins=json.loads((STUDY/'harness/indexes/pins.json').read_text())['repositories']
 out=pathlib.Path(tempfile.mkdtemp(prefix='discovery-source-audit-',dir='/private/tmp'))
 evidence={'kind':'static_contract_consumer_closure_audit','source_pins':pins,'repositories':{}}
 for repo,pin in pins.items():
  archive=subprocess.check_output(['git','archive',pin['commit']],cwd=ESTATE/repo)
  files={};matches=[]
  with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
   for member in tar:
    if not member.isfile():continue
    data=tar.extractfile(member).read();files[member.name]=hashlib.sha256(data).hexdigest()
    production=('src/main/' in member.name or (repo=='commerce-web' and member.name.startswith('src/') and not member.name.startswith('src/test/')))
    if not production or not member.name.endswith(('.java','.ts','.tsx','.properties','.yaml','.yml','.xml')):continue
    for line,text in enumerate(data.decode(errors='replace').splitlines(),1):
     if PATTERN.search(text):matches.append({'file':member.name,'line':line,'text':text.strip()})
  evidence['repositories'][repo]={'all_archive_file_hashes':files,'production_contract_matches':matches}
 (out/'all-ten-repo-consumer-audit.json').write_text(json.dumps(evidence,indent=2)+'\n')
 target=HERE/'source-audit';assert not target.exists(),'Preserve existing audit; choose a new output for a new snapshot'
 shutil.copytree(out,target)
 print(json.dumps({r:len(v['production_contract_matches']) for r,v in evidence['repositories'].items()},indent=2))

if __name__=='__main__':main()
