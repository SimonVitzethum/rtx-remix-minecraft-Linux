#pragma once
#include <x86intrin.h>
#include <immintrin.h>
#include <cpuid.h>
#define __cpuid(info, leaf) __cpuid_count(leaf, 0, (info)[0], (info)[1], (info)[2], (info)[3])
#define __cpuidex(info, leaf, sub) __cpuid_count(leaf, sub, (info)[0], (info)[1], (info)[2], (info)[3])
#define _mm_pause __builtin_ia32_pause

// MSVC intrinsics used by the runtime.
static inline unsigned char _BitScanForward(unsigned long* i, unsigned long m) { if (!m) return 0; *i = __builtin_ctzl(m); return 1; }
static inline unsigned char _BitScanReverse(unsigned long* i, unsigned long m) { if (!m) return 0; *i = 63 - __builtin_clzl(m); return 1; }
static inline unsigned char _BitScanForward64(unsigned long* i, unsigned long long m) { if (!m) return 0; *i = __builtin_ctzll(m); return 1; }
static inline unsigned char _BitScanReverse64(unsigned long* i, unsigned long long m) { if (!m) return 0; *i = 63 - __builtin_clzll(m); return 1; }
#define __popcnt __builtin_popcount
#define __popcnt64 __builtin_popcountll
#define _InterlockedIncrement(p) (__atomic_add_fetch((p), 1, __ATOMIC_SEQ_CST))
#define _InterlockedDecrement(p) (__atomic_sub_fetch((p), 1, __ATOMIC_SEQ_CST))
#define _InterlockedIncrement64(p) (__atomic_add_fetch((p), 1, __ATOMIC_SEQ_CST))
#define _InterlockedDecrement64(p) (__atomic_sub_fetch((p), 1, __ATOMIC_SEQ_CST))
#define _InterlockedExchange(p, v) (__atomic_exchange_n((p), (v), __ATOMIC_SEQ_CST))
#define _InterlockedExchange64(p, v) (__atomic_exchange_n((p), (v), __ATOMIC_SEQ_CST))
#define _InterlockedExchangeAdd(p, v) (__atomic_fetch_add((p), (v), __ATOMIC_SEQ_CST))
#define _InterlockedExchangeAdd64(p, v) (__atomic_fetch_add((p), (v), __ATOMIC_SEQ_CST))
#define _InterlockedOr(p, v) (__atomic_fetch_or((p), (v), __ATOMIC_SEQ_CST))
#define _InterlockedAnd(p, v) (__atomic_fetch_and((p), (v), __ATOMIC_SEQ_CST))
static inline long _InterlockedCompareExchange(volatile long* p, long x, long c) { __atomic_compare_exchange_n(p, &c, x, false, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST); return c; }
static inline long long _InterlockedCompareExchange64(volatile long long* p, long long x, long long c) { __atomic_compare_exchange_n(p, &c, x, false, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST); return c; }
#define InterlockedIncrement _InterlockedIncrement
#define InterlockedDecrement _InterlockedDecrement
#define InterlockedExchange _InterlockedExchange
#define InterlockedExchangeAdd _InterlockedExchangeAdd
#define InterlockedCompareExchange _InterlockedCompareExchange
#define InterlockedIncrement64 _InterlockedIncrement64
#define InterlockedDecrement64 _InterlockedDecrement64
#define InterlockedCompareExchange64 _InterlockedCompareExchange64
#define _ReadWriteBarrier() __asm__ __volatile__("" ::: "memory")
#define _mm_mfence __builtin_ia32_mfence
#define __umulh(a, b) ((unsigned long long)(((unsigned __int128)(a) * (b)) >> 64))
#define _umul128(a, b, hi) ({ unsigned __int128 r_ = (unsigned __int128)(a) * (b); *(hi) = (unsigned long long)(r_ >> 64); (unsigned long long)r_; })
