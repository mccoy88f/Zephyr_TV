"""Plan and audit Nello updates. Never silently replace the custom APK with upstream's binary."""
import argparse,json,re,sys,xml.etree.ElementTree as ET
from pathlib import Path
TAG=re.compile(r'^v\d+\.\d+\.\d+(?:-(?:beta|alpha|rc)\.\d+)?$')
ROOT=Path(__file__).resolve().parents[1]
def read(p):return json.loads(Path(p).read_text(encoding='utf-8-sig'))
def plan(releases,state,published=False,force=False):
 candidates=[r for r in releases if not r.get('draft') and TAG.fullmatch(r.get('tag_name','')) and any(a.get('name','').endswith('.apk') for a in r.get('assets',[]))]
 if not candidates:return dict(needed=False)
 latest=max(candidates,key=lambda r:r.get('published_at') or '')
 if latest['tag_name']==state['upstreamTag'] and not force:
  return dict(state,needed=not published,recover=not published,prerelease=bool(latest.get('prerelease')))
 build=state['build']+1
 return dict(state,needed=True,recover=False,upstreamTag=latest['tag_name'],customTag=latest['tag_name']+'-nello.'+str(build),build=build,prerelease=bool(latest.get('prerelease')))
def prepare(next_state):
 p=ROOT/'app/build.gradle.kts';s=p.read_text(encoding='utf-8-sig')
 name=re.search(r'versionName = "([^"]+)"',s).group(1)
 if name!=next_state['upstreamTag'].removeprefix('v'):raise ValueError('upstream version and merged sources differ')
 current=int(re.search(r'versionCode = (\d+)',s).group(1))
 code=max(current,next_state['versionCode']+1)
 s=re.sub(r'versionCode = \d+',f'versionCode = {code}',s,count=1)
 p.write_text(s,encoding='utf-8');next_state['versionCode']=code
 state={k:v for k,v in next_state.items() if k not in ('needed','recover')}
 (ROOT/'automation/nello-state.json').write_text(json.dumps(state,indent=2)+'\n',encoding='utf-8')
def audit():
 contract=read(ROOT/'automation/nello-contract.json')
 gradle=(ROOT/'app/build.gradle.kts').read_text()
 assert int(re.search(r'minSdk = (\d+)',gradle).group(1))<=24,'Android 7 compatibility lost'
 assert 'applicationIdSuffix = ".debug"' in gradle,'custom package identity lost'
 assert 'desugar_jdk_libs_nio' in gradle,'Android 7 NIO support lost'
 for name in contract['requiredSources']:assert (ROOT/'app/src/main/java/link/mccoy88f/zephyr'/name).is_file(),name+' lost'
 for name in contract['requiredTests']:
  report=ROOT/f'app/build/test-results/testDebugUnitTest/TEST-link.mccoy88f.zephyr.{name}.xml'
  s=ET.parse(report).getroot()
  assert int(s.get('tests','0'))>0 and not any(int(s.get(k,'0')) for k in ('failures','errors','skipped')),name+' did not pass'
 print('Nello compatibility and customization contract verified')
def main():
 ap=argparse.ArgumentParser();ap.add_argument('command',choices=['plan','prepare','audit']);ap.add_argument('--releases');ap.add_argument('--published',default='false');ap.add_argument('--force',default='false');ap.add_argument('--next');args=ap.parse_args()
 if args.command=='plan':
  p=plan(read(args.releases),read(ROOT/'automation/nello-state.json'),args.published=='true',args.force=='true')
  Path(args.next).write_text(json.dumps(p),encoding='utf-8')
  for key in ('needed','recover','upstreamTag','customTag','prerelease'):
   if key in p:print(key+'='+str(p[key]).lower() if isinstance(p[key],bool) else key+'='+str(p[key]))
 elif args.command=='prepare':prepare(read(args.next))
 else:audit()
if __name__=='__main__':main()
