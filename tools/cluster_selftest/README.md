# Cluster GPU regression harness

This harness supports the native Linux Vulkan runtime. See the
[runtime README](../../README.md) and [Linux build guide](../../documentation/LinuxBuild.md).
The [Minecraft performance report](https://github.com/SimonVitzethum/rtx-remix-minecraft-Linux/blob/main/docs/LINUX_PERFORMANCE.md)
does not establish a CLAS speedup or substitute for these correctness checks.

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

From the repository root, choose a scratch directory and use your environment's
resource guard for compilation and exclusive GPU execution. For example, set
`RESOURCE_RUN` to the guard executable, then run:

```
"$RESOURCE_RUN" compile python3 tools/cluster_selftest/build.py /tmp/remix-cluster-selftest
"$RESOURCE_RUN" game env REMIX_CLUSTER_AS_GRID=8 /tmp/remix-cluster-selftest/cluster_selftest
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
"$RESOURCE_RUN" build python3 tools/cluster_selftest/test_session_processes.py
```
