# Cluster GPU regression harness

This preserves the S17 CLAS/cluster BLAS/PTLAS ray verification, bounds grid size
to 8–128, queries features before device creation and exercises tagged geometry
indices used by the production hit mapping. It also executes the production
gather shader on reverse-indexed/interleaved uint16 and uint32 geometry with
65 triangles, a partial final CLAS and an inactive degenerate primitive. Generated shader headers and the
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
