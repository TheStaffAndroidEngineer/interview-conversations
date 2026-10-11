#!/usr/bin/env python3
"""Freeze reproducible C31 source/evidence identities after execution and service cleanup."""
from pathlib import Path
import datetime,hashlib,json,shutil
R=Path(__file__).resolve().parents[1]
O=R/'evidence/final-runtime'
if O.exists(): raise SystemExit('Refusing to overwrite an existing runtime snapshot')
O.mkdir(parents=True)
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def row(p):return {'path':str(p.relative_to(R)),'sha256':sha(p),'bytes':p.stat().st_size}
excluded={'build','.gradle','.kotlin','__pycache__','.git','evidence'}
sources=[]
for p in sorted(R.rglob('*')):
    parts=p.relative_to(R).parts
    if not p.is_file() or set(parts)&excluded or p.name in {'local.properties','README.md','verification.md'} or p.suffix=='.md':continue
    target=O/'sources'/p.relative_to(R);target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,target)
    entry=row(p);entry['snapshot']=str(target.relative_to(R));assert sha(target)==entry['sha256'];sources.append(entry)
artifacts=[];evidence=[]
for p in sorted((R/'evidence').rglob('*')):
    if not p.is_file() or O in p.parents or '__pycache__' in p.parts:continue
    (artifacts if p.suffix in {'.apk','.jar'} else evidence).append(row(p))
manifest={'status':'FROZEN','utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'sources':sources,'artifacts':artifacts,'evidence_files':evidence,
'scope':'JVM reference tests and checked worked program, deliberately incomplete sequenced-results starter and replay of supplied historical C20 trace, resolved runtime dependencies and restored reference classes. No new Android application, native screen, external service, device capture or timing benchmark was added. Sources frozen after execution; Markdown docs bind separately at chapter completion.'}
(O/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
print(json.dumps({'sources':len(sources),'artifacts':len(artifacts),'evidence_files':len(evidence),'manifest':str(O/'manifest.json')}))
