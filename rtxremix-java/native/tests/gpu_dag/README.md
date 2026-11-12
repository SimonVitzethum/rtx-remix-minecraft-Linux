# GPU DAG key interning test

This compute-kernel regression runs natively on Linux. It is a development
component of the [Linux / Minecraft 26.3 port](../../../../README.md), not a
standalone game benchmark. Dated game measurements and their coverage limits
are in [Linux Performance](../../../../docs/LINUX_PERFORMANCE.md).

`test_intern.cpp` runs `dag_intern.comp` on an NVIDIA Vulkan compute queue with
Khronos synchronization validation. It checks empty/uniform collapse, random
full-key equality and repeated batches, forced hash collisions, bounded probe
failure and node-capacity failure. The capacity values are deliberate unit-test
bounds, not production storage limits. GPU timestamp queries measure all three
passes. The CPU checks completed output only; it does not construct the GPU graph.

The shader has three passes. The first publishes immutable candidate addresses
into the hash table and compares all eight children. Winners write output nodes;
duplicates retain the winning candidate address. The second resolves these
addresses after a compute memory barrier. The third replaces candidate addresses
in the table with completed node indices before another batch can use the table.
No shader invocation waits for a different invocation to finish. Probe exhaustion
sets an error flag; the caller must discard the partial result. Output node zero
is reserved. Leaves carry bit31 and RGB565; tagged candidate addresses are local
to the hash table and never become DAG references.

The standalone test needs Vulkan headers, loader, an NVIDIA device and the
Khronos validation layer. Compile the shader with `glslangValidator -V
--target-env vulkan1.2`, validate it with `spirv-val --target-env vulkan1.2`,
and compile the test with C++20 and `-lvulkan`. Pass the SPIR-V path as its only
argument. ASan/UBSan builds can use `-fno-pie -no-pie` on hardened Linux systems
whose PIE placement intersects ASan's shadow address range. Run compilation and
GPU validation through the environment's resource/exclusivity guard.

This kernel test does not establish terrain-source integration, a production GPU
DAG builder, GPU mesh construction, full-distance coverage or frame-rate gains.
The current FarField production path still invokes the CPU native builder.
