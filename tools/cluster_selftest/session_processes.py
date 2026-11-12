#!/usr/bin/env python3
"""Linux test-session ownership, including reparented children in an exclusive cgroup."""
import os
from pathlib import Path


def start_ticks(pid):
    """Identify a process without confusing a reused PID with the original child."""
    try:
        return Path(f'/proc/{pid}/stat').read_text().rsplit(')', 1)[1].split()[19]
    except (FileNotFoundError, ProcessLookupError):
        return None


def capture_session_processes(launcher_pid, cgroup, owned):
    """Extend PID/start-time ownership without including the session's controllers.

    The caller owns the exclusive resource scope and its launcher. Record proven
    descendants before rejecting a scope escape, so cleanup can still reap them.
    The caller seeds owned[launcher_pid] immediately after starting its launcher.
    """
    if launcher_pid not in owned:
        raise ValueError('The launcher start time must be recorded before capture')
    launcher_ticks = start_ticks(launcher_pid)
    if launcher_ticks is not None and launcher_ticks != owned[launcher_pid]:
        raise RuntimeError('The launcher PID no longer has its recorded identity')
    expected = '0::/' + str(cgroup.relative_to('/sys/fs/cgroup'))
    ancestors = {os.getpid()}
    parent = os.getppid()
    while parent > 1 and parent not in ancestors:
        ancestors.add(parent)
        try:
            parent = int(Path(f'/proc/{parent}/stat').read_text().rsplit(')', 1)[1].split()[1])
        except (FileNotFoundError, ProcessLookupError):
            break

    for raw in (cgroup / 'cgroup.procs').read_text().splitlines():
        pid = int(raw)
        ticks = start_ticks(pid)
        try:
            actual = Path(f'/proc/{pid}/cgroup').read_text().strip()
        except (FileNotFoundError, ProcessLookupError):
            continue
        if pid not in ancestors and ticks and actual == expected and start_ticks(pid) == ticks:
            owned.setdefault(pid, ticks)

    parents = {}
    identities = {}
    for proc in Path('/proc').glob('[0-9]*'):
        try:
            fields = proc.joinpath('stat').read_text().rsplit(')', 1)[1].split()
            parents[int(proc.name)] = int(fields[1])
            identities[int(proc.name)] = fields[19]
        except (FileNotFoundError, PermissionError, ProcessLookupError):
            pass
    descendants = {launcher_pid} if identities.get(launcher_pid) == owned[launcher_pid] else set()
    while True:
        children = {pid for pid, parent in parents.items() if parent in descendants
                    and start_ticks(parent) == identities.get(parent)
                    and start_ticks(pid) == identities[pid]}
        if children <= descendants:
            break
        descendants.update(children)

    for pid in descendants:
        ticks = start_ticks(pid)
        if not ticks or ticks != identities[pid]:
            continue
        owned.setdefault(pid, ticks)
        try:
            actual = Path(f'/proc/{pid}/cgroup').read_text().strip()
        except (FileNotFoundError, ProcessLookupError):
            continue
        if actual != expected:
            raise RuntimeError(f'Test descendant {pid} escaped the resource scope')
