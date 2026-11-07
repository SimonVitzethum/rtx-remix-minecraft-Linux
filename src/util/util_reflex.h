#pragma once

// Includes the Reflex headers. On native Linux there is no NvLowLatencyVk binary, the entry points are stubbed in
// src/util/linux/reflex_stub.cpp and the PCL stats macros expand to nothing.
#ifdef REMIX_NO_REFLEX
#include <windows.h>
#ifndef _WIN64
#define _WIN64 1
#define REMIX_REFLEX_UNDEF_WIN64
#endif
#include "NvLowLatencyVk.h"
#ifdef REMIX_REFLEX_UNDEF_WIN64
#undef _WIN64
#endif
#define PCLSTATS_DEFINE()
#define PCLSTATS_INIT(flags)
#define PCLSTATS_SHUTDOWN()
#define PCLSTATS_SET_ID_THREAD(id)
#define PCLSTATS_MARKER(marker, frame)
#define PCLSTATS_IS_ON() false
#define PCLSTATS_PING(...)
#define PCLSTATS_IS_PING_MSG_ID(x) false
#define PCLSTATS_SET_ID_THREAD(id)
static unsigned g_PCLStatsWindowMessage = 0;
static unsigned long g_PCLStatsIdThread = 0;
#else
#include "NvLowLatencyVk.h"
#include "pclstats.h"
#endif
