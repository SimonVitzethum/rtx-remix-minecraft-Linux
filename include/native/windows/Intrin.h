#pragma once
#include <x86intrin.h>
#include <immintrin.h>
#include <cpuid.h>
#define __cpuid(info, leaf) __cpuid_count(leaf, 0, (info)[0], (info)[1], (info)[2], (info)[3])
#define __cpuidex(info, leaf, sub) __cpuid_count(leaf, sub, (info)[0], (info)[1], (info)[2], (info)[3])
#define _mm_pause __builtin_ia32_pause
