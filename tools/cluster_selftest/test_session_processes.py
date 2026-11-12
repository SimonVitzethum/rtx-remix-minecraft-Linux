#!/usr/bin/env python3
"""Exercise the controller -> session -> launcher topology inside resource-run."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

from session_processes import capture_session_processes, start_ticks


def scope():
    return Path('/sys/fs/cgroup') / Path('/proc/self/cgroup').read_text().split('::', 1)[1].strip().lstrip('/')


if len(sys.argv) > 1 and sys.argv[1] == '--session':
    launcher = subprocess.Popen(['sleep', '5'])
    try:
        owned = {launcher.pid: start_ticks(launcher.pid)}
        capture_session_processes(launcher.pid, scope(), owned)
        assert launcher.pid in owned and owned[launcher.pid] == start_ticks(launcher.pid)
        assert os.getpid() not in owned
        assert os.getppid() not in owned, 'The controller must survive child-session cleanup'
        assert start_ticks(2**31 - 1) is None
        stale = {launcher.pid: 'reused-pid'}
        try:
            capture_session_processes(launcher.pid, scope(), stale)
        except RuntimeError:
            pass
        else:
            raise AssertionError('A stale launcher identity must be rejected')
        assert stale == {launcher.pid: 'reused-pid'}
        print(json.dumps(dict(controller=os.getppid(), session=os.getpid(), session_ticks=start_ticks(os.getpid()), launcher=launcher.pid, owned=owned)))
    finally:
        launcher.terminate()
        launcher.wait(timeout=3)
else:
    assert scope().name.endswith('.scope'), 'Run the complete regression through resource-run.sh'
    result = subprocess.run([sys.executable, __file__, '--session'], check=True, text=True, capture_output=True, timeout=10)
    evidence = json.loads(result.stdout)
    assert evidence['controller'] == os.getpid()
    assert str(os.getpid()) not in evidence['owned']
    assert start_ticks(evidence['session']) != evidence['session_ticks']
    assert start_ticks(evidence['launcher']) != evidence['owned'][str(evidence['launcher'])]
    evidence.update(helper_sha256=hashlib.sha256(Path(__file__).with_name('session_processes.py').read_bytes()).hexdigest(),
                    test_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), scope=str(scope()),
                    memory_peak_bytes=int((scope() / 'memory.peak').read_text()),
                    memory_events=(scope() / 'memory.events').read_text())
    print(json.dumps(evidence))
    print('Session ownership PASS: controller excluded, stale PID rejected, launcher identity retained and children reaped')
