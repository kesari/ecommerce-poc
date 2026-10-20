#!/usr/bin/env python3
"""Independent Java DTO/application -> real BFF relay HTTP -> actual React consumption.

No provider/model calls. All mutations occur in pinned git archives in /private/tmp.
"""
import atexit, difflib, hashlib, io, json, os, pathlib, shutil, subprocess, tarfile, tempfile, time

HERE=pathlib.Path(__file__).resolve().parent
STUDY=HERE.parents[3]
ESTATE=pathlib.Path('/Users/rajmohan/Projects/POC/POC-order-microservices')
PINS=json.loads((STUDY/'harness/indexes/pins.json').read_text())['repositories']
JAVA='/Users/rajmohan/.sdkman/candidates/java/21.0.10-tem'
BASKET='src/main/java/com/poc/basket/api/dto/BasketResponse.java'
PRODUCT='src/main/java/com/poc/catalog/api/dto/ProductResponse.java'
SERVICE='src/main/java/com/poc/catalog/application/CatalogService.java'

def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def replace(root,path,old,new):
 p=root/path; text=p.read_text(); assert text.count(old)==1,(path,old,text.count(old));p.write_text(text.replace(old,new))
def archive(repo,root):
 data=subprocess.check_output(['git','archive',PINS[repo]['commit']],cwd=ESTATE/repo)
 with tarfile.open(fileobj=io.BytesIO(data)) as tar: tar.extractall(root,filter='data')
def patch(root,repo,files,out):
 lines=[]
 for rel in files:
  before=subprocess.check_output(['git','show',PINS[repo]['commit']+':'+rel],cwd=ESTATE/repo).decode()
  lines.extend(difflib.unified_diff(before.splitlines(True),(root/rel).read_text().splitlines(True),fromfile='a/'+rel,tofile='b/'+rel))
 out.write_text(''.join(lines))

def main():
 runtime=pathlib.Path(tempfile.mkdtemp(prefix='discovery-web-',dir='/private/tmp'))
 portable=HERE/'evidence'; assert not portable.exists(),'Preserve previous evidence; use a new directory for a new execution'
 evidence=runtime/'portable-evidence'; evidence.mkdir()
 atexit.register(lambda: shutil.copytree(evidence,portable))
 results=[]
 def command(label,args,cwd,env=None,expected=0):
  started=time.time(); merged={**os.environ,'JAVA_HOME':JAVA,'PATH':JAVA+'/bin:'+os.environ['PATH'],**(env or {})}
  r=subprocess.run(args,cwd=cwd,env=merged,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=180)
  (evidence/(label+'.log')).write_bytes(r.stdout)
  row={'label':label,'command':args,'cwd_repo':cwd.name,'env':env or {},'exit':r.returncode,'expected_exit':expected,'elapsed_seconds':round(time.time()-started,3)}
  results.append(row);(evidence/'commands.json').write_text(json.dumps(results,indent=2)+'\n')
  if expected==0: assert r.returncode==0,(label,r.stdout.decode(errors='replace')[-5000:])
  else: assert r.returncode!=0,(label,'fault/change unexpectedly passed')
  return r
 roots={}; source_hashes={}
 for repo in ['basket-service','catalog-service','commerce-bff','commerce-web']:
  roots[repo]=runtime/repo;roots[repo].mkdir();archive(repo,roots[repo])
  source_hashes[repo]={str(p.relative_to(roots[repo])):sha(p) for p in sorted(roots[repo].rglob('*')) if p.is_file()}
 (evidence/'source-snapshot-hashes.json').write_text(json.dumps(source_hashes,indent=2)+'\n')
 web=roots['commerce-web']; shutil.copytree(ESTATE/'commerce-web/node_modules',web/'node_modules',symlinks=True)
 shutil.copy(HERE/'discovery-wire.test.tsx',web/'src/test/discovery-wire.test.tsx')
 for repo,test in [('basket-service','BasketWireTest'),('catalog-service','CatalogWireTest'),('commerce-bff','ForwardWireTest')]:
  target=roots[repo]/'src/test/java'/('com/poc/'+{'basket-service':'basket','catalog-service':'catalog','commerce-bff':'bff/api'}[repo]);target.mkdir(parents=True,exist_ok=True)
  shutil.copy(HERE/(test+'.java'),target/(test+'.java'))
 criteria={'H03':{'change':'BasketResponse wire totalMinor -> payableMinor','assertion':'BASKET_VISIBLE_TOTAL','baseline':True,'changed':False,'repaired':True,'faulty_repair':False},'H04':{'change':'ProductResponse wire imageUrl -> thumbnailUrl','assertion':'PRODUCT_IMAGES','baseline':True,'changed':False,'repaired':True,'faulty_repair':False},'H11':{'change':'ProductResponse optional merchandisingTag additive field','assertion':'PRODUCT_IMAGES','baseline':True,'changed':True,'incompatible_fault':False},'H12':{'change':'CatalogService listActive ordered mapping loop','assertion':'PRODUCT_PRICES','baseline':True,'changed':True,'incompatible_fault':False}}
 (evidence/'criteria.json').write_text(json.dumps(criteria,indent=2)+'\n')
 protected={p.name:sha(p) for p in [HERE/'BasketWireTest.java',HERE/'CatalogWireTest.java',HERE/'ForwardWireTest.java',HERE/'discovery-wire.test.tsx',evidence/'criteria.json']}
 (evidence/'protected-before.json').write_text(json.dumps(protected,indent=2)+'\n')
 def origin(case,phase,repo):
  wire=evidence/(case+'-'+phase+'-provider.json')
  test='BasketWireTest' if repo=='basket-service' else 'CatalogWireTest'
  command(case+'-'+phase+'-origin',['mvn','-o','-q','-Dtest='+test,'test'],roots[repo],{'WIRE_OUTPUT':str(wire)})
  for f in (roots[repo]/'target/surefire-reports').glob('*'+test+'*'):
   shutil.copy(f,evidence/(case+'-'+phase+'-'+f.name))
  return wire
 def forward(case,phase,wire,kind):
  out=evidence/(case+'-'+phase+'-bff.json')
  command(case+'-'+phase+'-bff',['mvn','-o','-q','-Dtest=ForwardWireTest','test'],roots['commerce-bff'],{'WIRE_INPUT':str(wire),'WIRE_OUTPUT':str(out),'WIRE_KIND':kind})
  return out
 def browser(case,phase,wire,kind,success=True):
  r=command(case+'-'+phase+'-web',['npm','test','--','src/test/discovery-wire.test.tsx','--reporter=verbose'],web,{'WIRE_INPUT':str(wire),'WIRE_KIND':kind},0 if success else 1)
  if not success: assert criteria[case]['assertion'].encode() in r.stdout, 'Must fail protected behavioral assertion'
 def build(label): command(label,['npm','run','build'],web)
 basket=roots['basket-service']; catalog=roots['catalog-service']
 original_basket=(basket/BASKET).read_text(); original_product=(catalog/PRODUCT).read_text(); original_service=(catalog/SERVICE).read_text()
 api=(web/'src/api/basket.ts').read_text(); products=(web/'src/pages/ProductsPage.tsx').read_text(); types=(web/'src/api/types.ts').read_text()
 # H03: the exact same protected component assertion across all four phases.
 base=forward('H03','baseline',origin('H03','baseline','basket-service'),'basket');browser('H03','baseline',base,'basket');build('baseline-web-build')
 replace(basket,BASKET,'long discountMinor, long totalMinor','long discountMinor, @com.fasterxml.jackson.annotation.JsonProperty("payableMinor") long totalMinor')
 patch(basket,'basket-service',[BASKET],evidence/'H03-upstream.patch')
 changed=forward('H03','changed',origin('H03','changed','basket-service'),'basket');browser('H03','changed',changed,'basket',False)
 wrapped=api.replace('apiRequest<Basket>(', 'basketRequest(')
 wrapped+='\nasync function basketRequest(path: string, init: RequestInit = {}): Promise<Basket> {\n  const response = await apiRequest<Basket & { payableMinor: number }>(path, init)\n  return { ...response, totalMinor: response.payableMinor }\n}\n'
 (web/'src/api/basket.ts').write_text(wrapped);patch(web,'commerce-web',['src/api/basket.ts'],evidence/'H03-repair.patch');build('H03-repaired-web-build');browser('H03','repaired',changed,'basket')
 (web/'src/api/basket.ts').write_text(wrapped.replace('totalMinor: response.payableMinor','totalMinor: response.subtotalMinor'));patch(web,'commerce-web',['src/api/basket.ts'],evidence/'H03-fault.patch');build('H03-fault-web-build');browser('H03','faulty-repair',changed,'basket',False)
 (web/'src/api/basket.ts').write_text(api)
 # H04: new Catalog wire member crossing passthrough proxy to image rendering.
 base=forward('H04','baseline',origin('H04','baseline','catalog-service'),'catalog');browser('H04','baseline',base,'catalog')
 replace(catalog,PRODUCT,'String imageUrl,','@com.fasterxml.jackson.annotation.JsonProperty("thumbnailUrl") String imageUrl,');patch(catalog,'catalog-service',[PRODUCT],evidence/'H04-upstream.patch')
 changed=forward('H04','changed',origin('H04','changed','catalog-service'),'catalog');browser('H04','changed',changed,'catalog',False)
 (web/'src/pages/ProductsPage.tsx').write_text(products.replace('product.imageUrl','product.thumbnailUrl'));(web/'src/api/types.ts').write_text(types.replace('imageUrl: string | null','thumbnailUrl: string | null'))
 patch(web,'commerce-web',['src/pages/ProductsPage.tsx','src/api/types.ts'],evidence/'H04-repair.patch');build('H04-repaired-web-build');browser('H04','repaired',changed,'catalog')
 (web/'src/pages/ProductsPage.tsx').write_text(products.replace('product.imageUrl','product.description'));patch(web,'commerce-web',['src/pages/ProductsPage.tsx','src/api/types.ts'],evidence/'H04-fault.patch');build('H04-fault-web-build');browser('H04','faulty-repair',changed,'catalog',False)
 (web/'src/pages/ProductsPage.tsx').write_text(products);(web/'src/api/types.ts').write_text(types);(catalog/PRODUCT).write_text(original_product)
 # H11 additive optional field, no consumer repair. Compile actual origin and retain new field through BFF.
 browser('H11','baseline',base,'catalog')
 additive=original_product.replace(') {',') {\n    @com.fasterxml.jackson.annotation.JsonProperty(value = "merchandisingTag", access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)\n    public String merchandisingTag() { return active ? "featured" : null; }')
 (catalog/PRODUCT).write_text(additive);patch(catalog,'catalog-service',[PRODUCT],evidence/'H11-upstream.patch')
 changed=forward('H11','changed',origin('H11','changed','catalog-service'),'catalog');browser('H11','changed',changed,'catalog')
 assert all(p['merchandisingTag']=='featured' for p in json.loads(changed.read_text()))
 (catalog/PRODUCT).write_text(additive.replace('String imageUrl,','@com.fasterxml.jackson.annotation.JsonIgnore String imageUrl,'));patch(catalog,'catalog-service',[PRODUCT],evidence/'H11-fault.patch')
 fault=forward('H11','fault',origin('H11','fault','catalog-service'),'catalog');browser('H11','incompatible-fault',fault,'catalog',False);(catalog/PRODUCT).write_text(original_product)
 # H12 private map implementation, identical serialised values and real service cache-hit path.
 browser('H12','baseline',base,'catalog')
 before='List<ProductResponse> items = products.findActivePage(page, size).stream()\n                            .map(CatalogService::toResponse)\n                            .toList();'
 after='var mapped = new java.util.ArrayList<ProductResponse>();\n                    for (Product product : products.findActivePage(page, size)) {\n                        mapped.add(toResponse(product));\n                    }\n                    List<ProductResponse> items = List.copyOf(mapped);'
 replace(catalog,SERVICE,before,after);patch(catalog,'catalog-service',[SERVICE],evidence/'H12-upstream.patch')
 changed=forward('H12','changed',origin('H12','changed','catalog-service'),'catalog');browser('H12','changed',changed,'catalog');assert json.loads(changed.read_text())==json.loads(base.read_text())
 replace(catalog,SERVICE,'mapped.add(toResponse(product));','mapped.add(toResponse(product.withPrice(product.priceMinor() + 1)));');patch(catalog,'catalog-service',[SERVICE],evidence/'H12-fault.patch')
 fault=forward('H12','fault',origin('H12','fault','catalog-service'),'catalog');browser('H12','incompatible-fault',fault,'catalog',False)
 assert protected=={p.name:sha(p) for p in [HERE/'BasketWireTest.java',HERE/'CatalogWireTest.java',HERE/'ForwardWireTest.java',HERE/'discovery-wire.test.tsx',evidence/'criteria.json']}
 summary={'status':'verified','runtime':str(runtime),'source_pins':{r:PINS[r] for r in roots},'protected':protected,'criteria':criteria,'limits':['DTO/application serialization; controlled repository and cache collaborators, no deployed DB/Valkey','Real localhost HTTP provider body into actual BFF controller+relay using MockMvc; no deployed security chain','Actual API wrappers/components in jsdom consume exact captured BFF body; no browser/login/checkout journey','H03 all basket response wrappers repaired; only GET display executed','No exhaustive estate causal claims; production Java compiled, web production build exercised']}
 (evidence/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
 manifest={str(p.relative_to(evidence)):sha(p) for p in sorted(evidence.rglob('*')) if p.is_file() and p.name!='manifest.json'}
 (evidence/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
 print(json.dumps(summary,indent=2))

if __name__=='__main__': main()
