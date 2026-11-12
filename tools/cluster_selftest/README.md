# Cluster GPU regression harness

This preserves the S17 CLAS/cluster BLAS/PTLAS ray verification, bounds grid size
to 8–128, queries features before device creation and exercises tagged geometry
indices used by the production hit mapping. It also executes the production
gather shader on reverse-indexed/interleaved uint16 and uint32 geometry with
65 triangles, a partial final CLAS and an inactive degenerate primitive. The
production committed-hit macro also preserves custom index, surface ID, material
type and view-model flags under explicit GPU checks. Generated shader headers and the
executable stay in scratch. This isolated harness does not exercise the retained
renderer cache or demonstrate a game performance improvement. Its unconditional
KHR rebuild timing is a synthetic build measurement, not a retained-cache baseline.

From the repository root (adjust scratch destination for your assigned run):

```
/home/simon/Videos/RTX/port-artifacts/resource-run.sh compile python3 tools/cluster_selftest/build.py /home/simon/Videos/RTX/port-artifacts/scratch/cluster-next/harness
/home/simon/Videos/RTX/port-artifacts/resource-run.sh game env REMIX_CLUSTER_AS_GRID=8 /home/simon/Videos/RTX/port-artifacts/scratch/cluster-next/harness/cluster_selftest
```

Exit 77 means unsupported device/features. Select the same Vulkan device as the
runtime and record source HEAD, executable hash, driver and feature output.

## Serial Linux game sessions

`session_processes.py` records PID/start-time identities for a launcher and an
exclusive resource cgroup. It excludes the complete ancestor chain. A child
session must not terminate a controller that holds the resource lock while
running several sessions in sequence. Proven launcher descendants are recorded
before a scope-escape error is raised, so the caller can still clean them up.
The helper never signals processes itself. Callers must compare start times
again before signaling, reap their launcher, and verify that no owned child
survives before returning from the admitted scope.

The bounded regression creates a real controller/session/launcher chain and
checks ancestor exclusion and child retirement:

```
/home/simon/Videos/RTX/port-artifacts/resource-run.sh build python3 tools/cluster_selftest/test_session_processes.py
```
