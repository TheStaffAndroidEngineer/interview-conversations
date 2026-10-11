#!/usr/bin/env python3
"""Run the reference and deliberately incomplete transfer starter into fresh evidence."""
from pathlib import Path
import datetime, hashlib, json, os, shutil, subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'evidence' / datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
OUT.mkdir(parents=True)
commands = []

def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()

def run(label, args, expected=0):
    log = OUT / (label + '.log')
    with log.open('wb') as stream:
        result = subprocess.run(args, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT)
    commands.append({'label': label, 'args': args, 'exit': result.returncode, 'log': log.name})
    if expected == 0 and result.returncode != 0:
        raise RuntimeError('See ' + str(log))
    if expected == 'failure' and result.returncode == 0:
        raise RuntimeError('Deliberately incomplete starter unexpectedly passed')

def results(label):
    target = OUT / label
    shutil.copytree(ROOT / 'build/test-results/test', target)
    cases = []
    for report in sorted(target.glob('TEST-*.xml')):
        root = ET.parse(report).getroot()
        for case in root.iter('testcase'):
            cases.append({'class': case.attrib['classname'], 'name': case.attrib['name'],
                          'failure': case.find('failure') is not None,
                          'error': case.find('error') is not None,
                          'skipped': case.find('skipped') is not None,
                          'failure_type': case.find('failure').get('type') if case.find('failure') is not None else None})
    assert cases
    return cases

record = {'status': 'RUNNING', 'utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
          'commands': commands, 'java_home': os.environ.get('JAVA_HOME'),
          'scope': 'JVM correctness and controlled callback and streaming histories; no Android or benchmark claim.'}
try:
    run('java', [str(Path(os.environ['JAVA_HOME']) / 'bin/java'), '-version'])
    base = ['./gradlew', '--no-daemon', '--console=plain']
    run('reference', base + ['-Pimplementation=reference', 'clean', 'test', 'run', 'jar'])
    record['reference_cases'] = results('reference-tests')
    assert not any(c['failure'] or c['error'] or c['skipped'] for c in record['reference_cases'])
    jars = list((ROOT / 'build/libs').glob('*.jar'))
    assert len(jars) == 1
    shutil.copy2(jars[0], OUT / 'reference.jar')
    run('dependencies', base + ['-Pimplementation=reference', 'dependencies', '--configuration', 'runtimeClasspath'])
    run('learner', base + ['-Pimplementation=learner', 'test', '--tests', '*SequencedResultsTest', '--rerun-tasks'], 'failure')
    record['learner_cases'] = results('learner-tests')
    assert all(c['class'].endswith('.SequencedResultsTest') for c in record['learner_cases'])
    assert any(c['failure'] for c in record['learner_cases'])
    assert not any(c['error'] or c['skipped'] for c in record['learner_cases'])
    assert all('Assertion' in c['failure_type'] for c in record['learner_cases'] if c['failure'])
    run('restore-reference', base + ['-Pimplementation=reference', 'classes', 'run'])
    record['status'] = 'PASS_REFERENCE_AND_EXPECTED_LEARNER_FAILURES'
finally:
    record['files'] = {str(p.relative_to(OUT)): sha(p) for p in OUT.rglob('*') if p.is_file()}
    (OUT / 'result.json').write_text(json.dumps(record, indent=2) + '\n')
    print(json.dumps({'status': record['status'], 'path': str(OUT)}), flush=True)
