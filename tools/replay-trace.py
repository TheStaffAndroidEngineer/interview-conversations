#!/usr/bin/env python3
"""Verify the supplied packet and replay its exact clipped-state SQL. No device access."""
from pathlib import Path
import csv, datetime, hashlib, json, subprocess, sys

ROOT = Path(__file__).resolve().parents[1]
PACKET = ROOT / 'supplied/blocked-1'
OUT = ROOT / 'evidence' / ('trace-replay-' + datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ'))
OUT.mkdir(parents=True)
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
provenance = json.loads((PACKET / 'provenance.json').read_text())
for row in provenance['files']:
    assert sha(ROOT / row['path']) == row['sha256'], row['path']
assert sha(PACKET / 'observations.json') == provenance['observations_sha256']
tool = ROOT / 'tools/trace_processor'
version = subprocess.run([sys.executable, str(tool), '--version'], capture_output=True, text=True, check=True)
(OUT / 'processor-version.txt').write_text(version.stdout + version.stderr)
command = [sys.executable, str(tool), str(PACKET / 'trace.pftrace'), '-q', str(PACKET / 'analysis/blocked-1-4444-states.sql')]
with (OUT / 'states.csv').open('w') as out, (OUT / 'processor.stderr').open('w') as err:
    subprocess.run(command, stdout=out, stderr=err, check=True)
with (OUT / 'states.csv').open() as stream: actual = list(csv.DictReader(stream))
with (PACKET / 'analysis/blocked-1-4444-states.csv').open() as stream: expected = list(csv.DictReader(stream))
assert actual == expected, (actual, expected)
observation = json.loads((PACKET / 'observations.json').read_text())
snapshot = next(s for s in observation['slices'] if s['name'] == 'CG/blocked/snapshot')
assert sum(snapshot['states_ns'].values()) == snapshot['duration_ns']
assert snapshot['duration_ns'] == 767491417
assert snapshot['states_ns'] == {'R': 389333, 'Running': 567417, 'S': 766534667}
record = {'status': 'PASS_REPLAY_OF_SUPPLIED_TRACE', 'command': command,
          'trace_sha256': sha(PACKET / 'trace.pftrace'), 'processor_launcher_sha256': sha(tool),
          'rows': actual, 'snapshot': snapshot,
          'scope': 'Reanalysis of Chapter 20 API 36 emulator capture. No new device measurement or ANR determination.',
          'files': {p.name: sha(p) for p in OUT.iterdir() if p.is_file()}}
(OUT / 'result.json').write_text(json.dumps(record, indent=2) + '\n')
print(json.dumps({'status': record['status'], 'path': str(OUT)}))
