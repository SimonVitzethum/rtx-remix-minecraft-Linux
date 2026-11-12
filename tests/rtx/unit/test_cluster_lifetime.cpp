/* SPDX-License-Identifier: MIT */
#include <cstdio>
#include "../../../src/dxvk/rtx_render/rtx_cluster_blas.h"
#include "../../../src/dxvk/dxvk_lifetime.h"

int main() {
  using namespace dxvk;
  auto budget = std::make_shared<std::atomic<uint64_t>>(0);
  DxvkLifetimeTracker submitted, previousTlas, cancelled;
  Rc<ClusterBlasResources> retained = new ClusterBlasResources(budget, 4096);
  Rc<ClusterBlasResources> scratch = new ClusterBlasResources(budget, 8192);
  submitted.trackResource<DxvkAccess::Read>(retained);
  previousTlas.trackResource<DxvkAccess::Read>(retained);
  submitted.trackResource<DxvkAccess::Write>(scratch);
  // Scene unload drops all cache references while two submissions still refer to the hierarchy.
  retained = nullptr;
  scratch = nullptr;
  if (budget->load() != 12288) { return 1; }
  submitted.notify();
  if (budget->load() != 12288) { return 2; }
  submitted.reset();
  if (budget->load() != 4096) { return 3; }
  previousTlas.reset();
  if (budget->load() != 0) { return 4; }
  // An unsubmitted command list releases cancellation reservations through the real tracker.
  Rc<ClusterBlasResources> pending = new ClusterBlasResources(budget, 16384);
  cancelled.trackResource<DxvkAccess::Write>(pending);
  pending = nullptr;
  if (budget->load() != 16384) { return 5; }
  cancelled.reset();
  if (budget->load() != 0) { return 6; }
  std::puts("cluster lifetime PASS: submission, unload, previous TLAS and cancellation retain reservations until retirement");
}
