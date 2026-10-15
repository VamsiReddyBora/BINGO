#!/usr/bin/env python3
"""Opt-in production-client MQTT verification in unique rooms; no real users/invitations.
Run after testDebugUnitTest and exportCodexVerificationClasspath. Preserve prior logs first.
"""
from pathlib import Path
import json
import subprocess
import uuid

root = Path(__file__).resolve().parent
repo = root.parent.parent
evidence = root / 'evidence'
cp = (evidence / 'test.classpath').read_text()
room = 'CODEX_' + uuid.uuid4().hex[:16].upper()
(evidence / 'live-test-room.txt').write_text(room)
results = []
try:
    for mode in ('writer', 'reader'):
        result = subprocess.run([
            'java', '-cp', cp, 'com.bingo.multiplayer.domain.network.LiveMqttRecoveryProbe',
            mode, room, str(evidence / mode)
        ], cwd=repo, capture_output=True, text=True, timeout=90)
        output = result.stdout + result.stderr
        (evidence / f'mqtt-{mode}.log').write_text(output)
        print(mode, 'exit', result.returncode, flush=True)
        print(output, end='', flush=True)
        results.append({'mode': mode, 'exit': result.returncode})
        if result.returncode:
            break
finally:
    cleanup = subprocess.run([
        'java', '-cp', cp, 'com.bingo.multiplayer.domain.network.LiveMqttRecoveryProbe',
        'cleanup', room, str(evidence / 'cleanup')
    ], cwd=repo, capture_output=True, text=True, timeout=30)
    (evidence / 'mqtt-cleanup.log').write_text(cleanup.stdout + cleanup.stderr)
    results.append({'mode': 'cleanup', 'exit': cleanup.returncode})
    print('cleanup exit', cleanup.returncode, flush=True)
    (evidence / 'mqtt-results.json').write_text(json.dumps(results, indent=2) + '\n')
raise SystemExit(1 if any(item['exit'] for item in results) else 0)
