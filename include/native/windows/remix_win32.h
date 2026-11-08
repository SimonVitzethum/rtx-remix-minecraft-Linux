// Minimal Win32 API surface for the native Linux build of the Remix runtime.
// Implemented in src/util/linux/win32_compat.cpp. Only what the runtime uses is provided.
#pragma once

#include <stdint.h>
#include <stddef.h>
#include <wchar.h>
#include <string.h>
#include <limits.h>
#include <pthread.h>
#include <sched.h>
#include <unistd.h>
#include <time.h>
#include <errno.h>
#include <sys/time.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <dlfcn.h>
#include <alloca.h>
#include <strings.h>
#include <stdlib.h>
#include <stdio.h>

#include "windows_base.h"

#ifndef MAX_PATH
#define MAX_PATH 260
#endif

typedef char CHAR;
typedef CHAR TCHAR;
typedef wchar_t* LPWSTR;
typedef wchar_t* PWSTR;
typedef const wchar_t* PCWSTR;
typedef char* PSTR;
typedef const char* PCSTR;
typedef DWORD* LPDWORD;
typedef DWORD* PDWORD;
typedef BYTE* LPBYTE;
typedef BYTE* PBYTE;
typedef WORD* LPWORD;
typedef BOOL* LPBOOL;
typedef BOOL* PBOOL;
typedef LONG* PLONG;
typedef LONG* LPLONG;
typedef ULONG* PULONG;
typedef UINT* PUINT;
typedef void* PVOID;
typedef HANDLE* PHANDLE;
typedef int64_t INT64;
typedef int64_t LONG64;
typedef int64_t LONG_PTR;
typedef uint64_t ULONG_PTR;
typedef int64_t INT_PTR;
typedef uint64_t UINT_PTR;
typedef uint64_t DWORD_PTR;
typedef uint64_t DWORD64;
typedef uint64_t ULONG64;
typedef ULONG_PTR SIZE_T_PTR;
typedef UINT_PTR WPARAM;
typedef LONG_PTR LPARAM;
typedef LONG_PTR LRESULT;
typedef DWORD NTSTATUS;
typedef LONG NTSTATUS_S;
typedef HANDLE HINSTANCE;
typedef HANDLE HICON;
typedef HANDLE HCURSOR;
typedef HANDLE HBRUSH;
typedef HANDLE HBITMAP;
typedef HANDLE HGDIOBJ;
typedef HANDLE HMENU;
typedef HANDLE HLOCAL;
typedef HANDLE HGLOBAL;
typedef HANDLE HRGN;
typedef HANDLE HKL;
typedef HANDLE HPALETTE;
typedef HANDLE HDESK;
typedef HANDLE HWINSTA;
typedef unsigned short ATOM;
typedef char16_t WCHAR16;
typedef LPVOID FARPROC_V;
typedef int (*FARPROC)();
typedef FARPROC PROC;
typedef LRESULT (*WNDPROC)(HWND, UINT, WPARAM, LPARAM);
typedef DWORD (*LPTHREAD_START_ROUTINE)(LPVOID);
typedef DWORD (*PTHREAD_START_ROUTINE)(LPVOID);
typedef BOOL (*MONITORENUMPROC)(HMONITOR, HDC, RECT*, LPARAM);
typedef void (*PAPCFUNC)(ULONG_PTR);
typedef const RECT* LPCRECT;
typedef RECT* LPRECT;
typedef const wchar_t* LPCTSTR_W;

#define CALLBACK
#define APIENTRY
#define NTAPI
#define FAR
#define NEAR
#define IN
#define OUT
#define OPTIONAL
#define __cdecl
#define __fastcall
#define __declspec(x)
#define __success(x)
#define __success(x)
#define _In_
#define _Out_
#define _Inout_
#define _In_opt_
#define _Out_opt_
#define _Inout_opt_
#define _Out_writes_(x)
#define _In_reads_(x)
#define _In_reads_bytes_(x)
#define _Out_writes_bytes_(x)
#define _Frees_ptr_opt_
#define _Check_return_
#define _Success_(x)
#define _Ret_maybenull_
#define _Null_terminated_
#define UNREFERENCED_PARAMETER(x) ((void)(x))
#define DECLSPEC_ALIGN(x) __attribute__((aligned(x)))
#define DECLSPEC_NORETURN __attribute__((noreturn))
#define __forceinline inline __attribute__((always_inline))
#define __debugbreak() __builtin_trap()
#define _countof(a) (sizeof(a)/sizeof(*(a)))
#define ARRAYSIZE(a) _countof(a)
#define __assume(x) do { if (!(x)) __builtin_unreachable(); } while (0)
#define _ReturnAddress() __builtin_return_address(0)
#define _AddressOfReturnAddress() __builtin_frame_address(0)
#define __stdcall
#define _stricmp strcasecmp
#define _strnicmp strncasecmp
#define _wcsicmp wcscasecmp
#define _wcsnicmp wcsncasecmp
#define stricmp strcasecmp
#define _strdup strdup
#define _wcsdup wcsdup
#define _snprintf snprintf
#define _snwprintf swprintf
#define _vsnprintf vsnprintf
#define _putenv putenv
#define _getpid getpid
#define _unlink unlink
#define _access access
#define _fileno fileno
#define _mkdir(p) mkdir(p, 0777)
#define _MAX_PATH MAX_PATH
#define _alloca alloca
#define _aligned_malloc(sz, al) aligned_alloc(al, ((sz) + (al) - 1) / (al) * (al))
#define _aligned_free free
#define _byteswap_ulong __builtin_bswap32
#define _byteswap_uint64 __builtin_bswap64
#define _byteswap_ushort __builtin_bswap16
#define _rotl(x, n) ((unsigned)(((x) << (n)) | ((x) >> (32 - (n)))))
#define _rotr(x, n) ((unsigned)(((x) >> (n)) | ((x) << (32 - (n)))))
#define _rotl64(x, n) ((uint64_t)(((x) << (n)) | ((x) >> (64 - (n)))))
#define _rotr64(x, n) ((uint64_t)(((x) >> (n)) | ((x) << (64 - (n)))))
#define _stdcall

#define INFINITE 0xFFFFFFFFu
#define INVALID_HANDLE_VALUE ((HANDLE)(LONG_PTR)-1)
#define INVALID_FILE_ATTRIBUTES ((DWORD)-1)
#define INVALID_SET_FILE_POINTER ((DWORD)-1)
#define FILE_ATTRIBUTE_DIRECTORY 0x10
#define FILE_ATTRIBUTE_NORMAL 0x80
#define GENERIC_READ 0x80000000u
#define GENERIC_WRITE 0x40000000u
#define FILE_SHARE_READ 1
#define FILE_SHARE_WRITE 2
#define OPEN_EXISTING 3
#define CREATE_ALWAYS 2
#define OPEN_ALWAYS 4
#define CREATE_NEW 1
#define WAIT_OBJECT_0 0
#define WAIT_TIMEOUT 258
#define WAIT_FAILED 0xFFFFFFFFu
#define STILL_ACTIVE 259
#define ERROR_SUCCESS 0
#define ERROR_FILE_NOT_FOUND 2
#define ERROR_PATH_NOT_FOUND 3
#define ERROR_ACCESS_DENIED 5
#define ERROR_INVALID_HANDLE 6
#define ERROR_NOT_ENOUGH_MEMORY 8
#define ERROR_INSUFFICIENT_BUFFER 122
#define ERROR_ALREADY_EXISTS 183
#define ERROR_MORE_DATA 234
#define ERROR_ENVVAR_NOT_FOUND 203
#define NO_ERROR 0
#define MB_ERR_INVALID_CHARS 8
#define CP_ACP 0
#define CP_UTF8 65001
#define MB_ICONERROR 0x10
#define MB_OK 0
#define MB_ICONWARNING 0x30
#define MB_ICONINFORMATION 0x40
#define MAXIMUM_WAIT_OBJECTS 64
#define THREAD_PRIORITY_NORMAL 0
#define THREAD_PRIORITY_LOWEST (-2)
#define THREAD_PRIORITY_BELOW_NORMAL (-1)
#define THREAD_PRIORITY_ABOVE_NORMAL 1
#define THREAD_PRIORITY_HIGHEST 2
#define PAGE_READWRITE 4
#define MEM_COMMIT 0x1000
#define MEM_RESERVE 0x2000
#define MEM_RELEASE 0x8000
#define DLL_PROCESS_ATTACH 1
#define DLL_PROCESS_DETACH 0
#define DLL_THREAD_ATTACH 2
#define DLL_THREAD_DETACH 3
#define LOAD_LIBRARY_SEARCH_DEFAULT_DIRS 0x1000
#define LOAD_LIBRARY_SEARCH_USER_DIRS 0x400
#define LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR 0x100
#define LOAD_WITH_ALTERED_SEARCH_PATH 8
#define GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS 4
#define GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT 2
#define DISPLAY_DEVICE_ATTACHED_TO_DESKTOP 1
#define DISPLAY_DEVICE_PRIMARY_DEVICE 4
#define CCHDEVICENAME 32
#define CCHFORMNAME 32
#define MONITOR_DEFAULTTONULL 0
#define MONITOR_DEFAULTTOPRIMARY 1
#define MONITOR_DEFAULTTONEAREST 2
#define SM_CXSCREEN 0
#define SM_CYSCREEN 1
#define SW_SHOW 5
#define SW_HIDE 0
#define GWL_STYLE (-16)
#define GWL_EXSTYLE (-20)
#define WS_OVERLAPPEDWINDOW 0x00CF0000
#define WS_POPUP 0x80000000
#define WS_VISIBLE 0x10000000
#define WS_CHILD 0x40000000
#define WS_EX_TOPMOST 8
#define WM_CLOSE 0x10
#define WM_QUIT 0x12
#define WM_KEYDOWN 0x100
#define WM_KEYUP 0x101
#define WM_SIZE 0x5
#define WM_DESTROY 0x2
#define WM_USER 0x400
#define WM_COPYDATA 0x4A
#define WM_ACTIVATEAPP 0x1C
#define WM_NCCALCSIZE 0x83
#define WM_SYSCOMMAND 0x112
#define PM_REMOVE 1
#define STD_INPUT_HANDLE ((DWORD)-10)
#define STD_OUTPUT_HANDLE ((DWORD)-11)
#define STD_ERROR_HANDLE ((DWORD)-12)
#define TIME_ZONE_ID_INVALID ((DWORD)0xFFFFFFFF)
#define PROCESSOR_ARCHITECTURE_AMD64 9
#define VER_NT_WORKSTATION 1
#define EXCEPTION_EXECUTE_HANDLER 1
#define SEM_FAILCRITICALERRORS 1
#define SEM_NOGPFAULTERRORBOX 2
#define SYNCHRONIZE 0x100000
#define EVENT_ALL_ACCESS 0x1F0003
#define FILE_MAP_READ 4
#define FILE_MAP_WRITE 2
#define FILE_MAP_ALL_ACCESS 0xF001F
#define PAGE_READONLY 2
#define MOVEFILE_REPLACE_EXISTING 1
#define HKEY_CURRENT_USER ((HKEY)(ULONG_PTR)0x80000001)
#define HKEY_LOCAL_MACHINE ((HKEY)(ULONG_PTR)0x80000002)
#define KEY_READ 0x20019
#define ERROR_NO_MORE_ITEMS 259
#define CREATE_SUSPENDED 4
#define NORMAL_PRIORITY_CLASS 0x20
#define HIGH_PRIORITY_CLASS 0x80
#define IDLE_PRIORITY_CLASS 0x40
#define PROCESS_QUERY_INFORMATION 0x400
#define THREAD_ALL_ACCESS 0x1FFFFF
#define DUPLICATE_SAME_ACCESS 2
#define TLS_OUT_OF_INDEXES 0xFFFFFFFF
#define LANG_NEUTRAL 0
#define SUBLANG_DEFAULT 1
#define MAKELANGID(p, s) ((((WORD)(s)) << 10) | (WORD)(p))
#define LOWORD(l) ((WORD)(((DWORD_PTR)(l)) & 0xffff))
#define HIWORD(l) ((WORD)((((DWORD_PTR)(l)) >> 16) & 0xffff))
#define LOBYTE(w) ((BYTE)(((DWORD_PTR)(w)) & 0xff))
#define HIBYTE(w) ((BYTE)((((DWORD_PTR)(w)) >> 8) & 0xff))
#define MAKELONG(a, b) ((LONG)(((WORD)(((DWORD_PTR)(a)) & 0xffff)) | ((DWORD)((WORD)(((DWORD_PTR)(b)) & 0xffff))) << 16))
#define MAKEWORD(a, b) ((WORD)(((BYTE)(((DWORD_PTR)(a)) & 0xff)) | ((WORD)((BYTE)(((DWORD_PTR)(b)) & 0xff))) << 8))
#define GET_X_LPARAM(lp) ((int)(short)LOWORD(lp))
#define GET_Y_LPARAM(lp) ((int)(short)HIWORD(lp))
#define MAKEINTRESOURCE(i) ((wchar_t*)((ULONG_PTR)((WORD)(i))))

// Virtual-key codes.
#define VK_LBUTTON 0x01
#define VK_RBUTTON 0x02
#define VK_CANCEL 0x03
#define VK_MBUTTON 0x04
#define VK_XBUTTON1 0x05
#define VK_XBUTTON2 0x06
#define VK_BACK 0x08
#define VK_TAB 0x09
#define VK_CLEAR 0x0C
#define VK_RETURN 0x0D
#define VK_SHIFT 0x10
#define VK_CONTROL 0x11
#define VK_MENU 0x12
#define VK_PAUSE 0x13
#define VK_CAPITAL 0x14
#define VK_KANA 0x15
#define VK_HANGUL 0x15
#define VK_JUNJA 0x17
#define VK_FINAL 0x18
#define VK_HANJA 0x19
#define VK_KANJI 0x19
#define VK_ESCAPE 0x1B
#define VK_CONVERT 0x1C
#define VK_NONCONVERT 0x1D
#define VK_ACCEPT 0x1E
#define VK_MODECHANGE 0x1F
#define VK_SPACE 0x20
#define VK_PRIOR 0x21
#define VK_NEXT 0x22
#define VK_END 0x23
#define VK_HOME 0x24
#define VK_LEFT 0x25
#define VK_UP 0x26
#define VK_RIGHT 0x27
#define VK_DOWN 0x28
#define VK_SELECT 0x29
#define VK_PRINT 0x2A
#define VK_EXECUTE 0x2B
#define VK_SNAPSHOT 0x2C
#define VK_INSERT 0x2D
#define VK_DELETE 0x2E
#define VK_HELP 0x2F
#define VK_LWIN 0x5B
#define VK_RWIN 0x5C
#define VK_APPS 0x5D
#define VK_SLEEP 0x5F
#define VK_NUMPAD0 0x60
#define VK_NUMPAD1 0x61
#define VK_NUMPAD2 0x62
#define VK_NUMPAD3 0x63
#define VK_NUMPAD4 0x64
#define VK_NUMPAD5 0x65
#define VK_NUMPAD6 0x66
#define VK_NUMPAD7 0x67
#define VK_NUMPAD8 0x68
#define VK_NUMPAD9 0x69
#define VK_MULTIPLY 0x6A
#define VK_ADD 0x6B
#define VK_SEPARATOR 0x6C
#define VK_SUBTRACT 0x6D
#define VK_DECIMAL 0x6E
#define VK_DIVIDE 0x6F
#define VK_F1 0x70
#define VK_F2 0x71
#define VK_F3 0x72
#define VK_F4 0x73
#define VK_F5 0x74
#define VK_F6 0x75
#define VK_F7 0x76
#define VK_F8 0x77
#define VK_F9 0x78
#define VK_F10 0x79
#define VK_F11 0x7A
#define VK_F12 0x7B
#define VK_F13 0x7C
#define VK_F14 0x7D
#define VK_F15 0x7E
#define VK_F16 0x7F
#define VK_F17 0x80
#define VK_F18 0x81
#define VK_F19 0x82
#define VK_F20 0x83
#define VK_F21 0x84
#define VK_F22 0x85
#define VK_F23 0x86
#define VK_F24 0x87
#define VK_NUMLOCK 0x90
#define VK_SCROLL 0x91
#define VK_LSHIFT 0xA0
#define VK_RSHIFT 0xA1
#define VK_LCONTROL 0xA2
#define VK_RCONTROL 0xA3
#define VK_LMENU 0xA4
#define VK_RMENU 0xA5
#define VK_BROWSER_BACK 0xA6
#define VK_BROWSER_FORWARD 0xA7
#define VK_BROWSER_REFRESH 0xA8
#define VK_BROWSER_STOP 0xA9
#define VK_BROWSER_SEARCH 0xAA
#define VK_BROWSER_FAVORITES 0xAB
#define VK_BROWSER_HOME 0xAC
#define VK_VOLUME_MUTE 0xAD
#define VK_VOLUME_DOWN 0xAE
#define VK_VOLUME_UP 0xAF
#define VK_MEDIA_NEXT_TRACK 0xB0
#define VK_MEDIA_PREV_TRACK 0xB1
#define VK_MEDIA_STOP 0xB2
#define VK_MEDIA_PLAY_PAUSE 0xB3
#define VK_LAUNCH_MAIL 0xB4
#define VK_LAUNCH_MEDIA_SELECT 0xB5
#define VK_LAUNCH_APP1 0xB6
#define VK_LAUNCH_APP2 0xB7
#define VK_OEM_1 0xBA
#define VK_OEM_PLUS 0xBB
#define VK_OEM_COMMA 0xBC
#define VK_OEM_MINUS 0xBD
#define VK_OEM_PERIOD 0xBE
#define VK_OEM_2 0xBF
#define VK_OEM_3 0xC0
#define VK_OEM_4 0xDB
#define VK_OEM_5 0xDC
#define VK_OEM_6 0xDD
#define VK_OEM_7 0xDE
#define VK_OEM_8 0xDF
#define VK_OEM_102 0xE2
#define VK_PROCESSKEY 0xE5
#define VK_PACKET 0xE7
#define VK_ATTN 0xF6
#define VK_CRSEL 0xF7
#define VK_EXSEL 0xF8
#define VK_EREOF 0xF9
#define VK_PLAY 0xFA
#define VK_ZOOM 0xFB
#define VK_NONAME 0xFC
#define VK_PA1 0xFD
#define VK_OEM_CLEAR 0xFE

#ifndef VK_OEM_WSCTRL
#define VK_OEM_WSCTRL 0xEE
#endif
#ifndef VK_OEM_RESET
#define VK_OEM_RESET 0xE9
#endif
#ifndef VK_OEM_PA3
#define VK_OEM_PA3 0xED
#endif
#ifndef VK_OEM_PA1
#define VK_OEM_PA1 0xEB
#endif
#ifndef VK_OEM_PA2
#define VK_OEM_PA2 0xEC
#endif
#ifndef VK_OEM_NEC_EQUAL
#define VK_OEM_NEC_EQUAL 0x92
#endif
#ifndef VK_OEM_FJ_JISHO
#define VK_OEM_FJ_JISHO 0x92
#endif
#ifndef VK_OEM_FJ_MASSHOU
#define VK_OEM_FJ_MASSHOU 0x93
#endif
#ifndef VK_OEM_FJ_TOUROKU
#define VK_OEM_FJ_TOUROKU 0x94
#endif
#ifndef VK_OEM_FJ_LOYA
#define VK_OEM_FJ_LOYA 0x95
#endif
#ifndef VK_OEM_FJ_ROYA
#define VK_OEM_FJ_ROYA 0x96
#endif
#ifndef VK_OEM_FINISH
#define VK_OEM_FINISH 0xF1
#endif
#ifndef VK_OEM_ENLW
#define VK_OEM_ENLW 0xF4
#endif
#ifndef VK_OEM_CUSEL
#define VK_OEM_CUSEL 0xEF
#endif
#ifndef VK_OEM_COPY
#define VK_OEM_COPY 0xF2
#endif
#ifndef VK_OEM_BACKTAB
#define VK_OEM_BACKTAB 0xF5
#endif
#ifndef VK_OEM_AUTO
#define VK_OEM_AUTO 0xF3
#endif
#ifndef VK_OEM_ATTN
#define VK_OEM_ATTN 0xF0
#endif
#ifndef VK_OEM_AX
#define VK_OEM_AX 0xE1
#endif
#ifndef VK_NAVIGATION_VIEW
#define VK_NAVIGATION_VIEW 0x88
#endif
#ifndef VK_NAVIGATION_MENU
#define VK_NAVIGATION_MENU 0x89
#endif
#ifndef VK_NAVIGATION_UP
#define VK_NAVIGATION_UP 0x8A
#endif
#ifndef VK_NAVIGATION_DOWN
#define VK_NAVIGATION_DOWN 0x8B
#endif
#ifndef VK_NAVIGATION_LEFT
#define VK_NAVIGATION_LEFT 0x8C
#endif
#ifndef VK_NAVIGATION_RIGHT
#define VK_NAVIGATION_RIGHT 0x8D
#endif
#ifndef VK_NAVIGATION_ACCEPT
#define VK_NAVIGATION_ACCEPT 0x8E
#endif
#ifndef VK_NAVIGATION_CANCEL
#define VK_NAVIGATION_CANCEL 0x8F
#endif
#ifndef VK_IME_ON
#define VK_IME_ON 0x16
#endif
#ifndef VK_IME_OFF
#define VK_IME_OFF 0x1A
#endif
#ifndef VK_ICO_HELP
#define VK_ICO_HELP 0xE3
#endif
#ifndef VK_ICO_00
#define VK_ICO_00 0xE4
#endif
#ifndef VK_ICO_CLEAR
#define VK_ICO_CLEAR 0xE6
#endif
#ifndef VK_GAMEPAD_A
#define VK_GAMEPAD_A 0xC3
#endif
#ifndef VK_GAMEPAD_B
#define VK_GAMEPAD_B 0xC4
#endif
#ifndef VK_GAMEPAD_X
#define VK_GAMEPAD_X 0xC5
#endif
#ifndef VK_GAMEPAD_Y
#define VK_GAMEPAD_Y 0xC6
#endif
#ifndef VK_GAMEPAD_RIGHT_SHOULDER
#define VK_GAMEPAD_RIGHT_SHOULDER 0xC7
#endif
#ifndef VK_GAMEPAD_LEFT_SHOULDER
#define VK_GAMEPAD_LEFT_SHOULDER 0xC8
#endif
#ifndef VK_GAMEPAD_LEFT_TRIGGER
#define VK_GAMEPAD_LEFT_TRIGGER 0xC9
#endif
#ifndef VK_GAMEPAD_RIGHT_TRIGGER
#define VK_GAMEPAD_RIGHT_TRIGGER 0xCA
#endif
#ifndef VK_GAMEPAD_DPAD_UP
#define VK_GAMEPAD_DPAD_UP 0xCB
#endif
#ifndef VK_GAMEPAD_DPAD_DOWN
#define VK_GAMEPAD_DPAD_DOWN 0xCC
#endif
#ifndef VK_GAMEPAD_DPAD_LEFT
#define VK_GAMEPAD_DPAD_LEFT 0xCD
#endif
#ifndef VK_GAMEPAD_DPAD_RIGHT
#define VK_GAMEPAD_DPAD_RIGHT 0xCE
#endif
#ifndef VK_GAMEPAD_MENU
#define VK_GAMEPAD_MENU 0xCF
#endif
#ifndef VK_GAMEPAD_VIEW
#define VK_GAMEPAD_VIEW 0xD0
#endif
#ifndef VK_GAMEPAD_LEFT_THUMBSTICK_BUTTON
#define VK_GAMEPAD_LEFT_THUMBSTICK_BUTTON 0xD1
#endif
#ifndef VK_GAMEPAD_RIGHT_THUMBSTICK_BUTTON
#define VK_GAMEPAD_RIGHT_THUMBSTICK_BUTTON 0xD2
#endif
#ifndef VK_GAMEPAD_LEFT_THUMBSTICK_UP
#define VK_GAMEPAD_LEFT_THUMBSTICK_UP 0xD3
#endif
#ifndef VK_GAMEPAD_LEFT_THUMBSTICK_DOWN
#define VK_GAMEPAD_LEFT_THUMBSTICK_DOWN 0xD4
#endif
#ifndef VK_GAMEPAD_LEFT_THUMBSTICK_RIGHT
#define VK_GAMEPAD_LEFT_THUMBSTICK_RIGHT 0xD5
#endif
#ifndef VK_GAMEPAD_LEFT_THUMBSTICK_LEFT
#define VK_GAMEPAD_LEFT_THUMBSTICK_LEFT 0xD6
#endif
#ifndef VK_GAMEPAD_RIGHT_THUMBSTICK_UP
#define VK_GAMEPAD_RIGHT_THUMBSTICK_UP 0xD7
#endif
#ifndef VK_GAMEPAD_RIGHT_THUMBSTICK_DOWN
#define VK_GAMEPAD_RIGHT_THUMBSTICK_DOWN 0xD8
#endif
#ifndef VK_GAMEPAD_RIGHT_THUMBSTICK_RIGHT
#define VK_GAMEPAD_RIGHT_THUMBSTICK_RIGHT 0xD9
#endif
#ifndef VK_GAMEPAD_RIGHT_THUMBSTICK_LEFT
#define VK_GAMEPAD_RIGHT_THUMBSTICK_LEFT 0xDA
#endif
typedef struct _FILETIME { DWORD dwLowDateTime; DWORD dwHighDateTime; } FILETIME, *LPFILETIME;
typedef struct _SYSTEMTIME { WORD wYear, wMonth, wDayOfWeek, wDay, wHour, wMinute, wSecond, wMilliseconds; } SYSTEMTIME, *LPSYSTEMTIME;
typedef struct _OVERLAPPED { ULONG_PTR Internal, InternalHigh; DWORD Offset, OffsetHigh; HANDLE hEvent; } OVERLAPPED, *LPOVERLAPPED;
typedef struct _CRITICAL_SECTION { pthread_mutex_t m; } CRITICAL_SECTION, *LPCRITICAL_SECTION;
typedef struct _SRWLOCK { pthread_rwlock_t* p; pthread_mutex_t init; } SRWLOCK, *PSRWLOCK;
typedef struct _CONDITION_VARIABLE { pthread_cond_t c; } CONDITION_VARIABLE, *PCONDITION_VARIABLE;
typedef struct _SYSTEM_INFO {
  WORD wProcessorArchitecture, wReserved; DWORD dwPageSize; LPVOID lpMinimumApplicationAddress, lpMaximumApplicationAddress;
  DWORD_PTR dwActiveProcessorMask; DWORD dwNumberOfProcessors, dwProcessorType, dwAllocationGranularity; WORD wProcessorLevel, wProcessorRevision;
} SYSTEM_INFO, *LPSYSTEM_INFO;
typedef struct _MEMORYSTATUSEX {
  DWORD dwLength; DWORD dwMemoryLoad; ULONGLONG ullTotalPhys, ullAvailPhys, ullTotalPageFile, ullAvailPageFile, ullTotalVirtual, ullAvailVirtual, ullAvailExtendedVirtual;
} MEMORYSTATUSEX, *LPMEMORYSTATUSEX;
typedef struct _OSVERSIONINFOEXW {
  DWORD dwOSVersionInfoSize, dwMajorVersion, dwMinorVersion, dwBuildNumber, dwPlatformId; WCHAR szCSDVersion[128];
  WORD wServicePackMajor, wServicePackMinor, wSuiteMask; BYTE wProductType, wReserved;
} OSVERSIONINFOEXW, *POSVERSIONINFOEXW, RTL_OSVERSIONINFOEXW, *PRTL_OSVERSIONINFOEXW;
typedef struct _DEVMODEW {
  WCHAR dmDeviceName[CCHDEVICENAME]; WORD dmSpecVersion, dmDriverVersion, dmSize, dmDriverExtra; DWORD dmFields;
  POINT dmPosition; DWORD dmDisplayOrientation, dmDisplayFixedOutput;
  short dmColor, dmDuplex, dmYResolution, dmTTOption, dmCollate; WCHAR dmFormName[CCHFORMNAME];
  WORD dmLogPixels; DWORD dmBitsPerPel, dmPelsWidth, dmPelsHeight, dmDisplayFlags, dmDisplayFrequency;
} DEVMODEW, *LPDEVMODEW;
typedef struct tagMONITORINFO { DWORD cbSize; RECT rcMonitor; RECT rcWork; DWORD dwFlags; } MONITORINFO, *LPMONITORINFO;
typedef struct tagMONITORINFOEXW : public tagMONITORINFO { WCHAR szDevice[CCHDEVICENAME]; } MONITORINFOEXW, *LPMONITORINFOEXW;
typedef struct tagMSG { HWND hwnd; UINT message; WPARAM wParam; LPARAM lParam; DWORD time; POINT pt; } MSG, *LPMSG;
typedef struct tagWNDCLASSEXW {
  UINT cbSize, style; WNDPROC lpfnWndProc; int cbClsExtra, cbWndExtra; HINSTANCE hInstance; HICON hIcon; HCURSOR hCursor; HBRUSH hbrBackground;
  LPCWSTR lpszMenuName, lpszClassName; HICON hIconSm;
} WNDCLASSEXW;
typedef struct tagCOPYDATASTRUCT { ULONG_PTR dwData; DWORD cbData; PVOID lpData; } COPYDATASTRUCT, *PCOPYDATASTRUCT;
typedef struct _PROCESS_INFORMATION { HANDLE hProcess, hThread; DWORD dwProcessId, dwThreadId; } PROCESS_INFORMATION;
typedef struct _EXCEPTION_POINTERS EXCEPTION_POINTERS, *PEXCEPTION_POINTERS;
typedef struct _RTL_SRWLOCK_UNUSED RTL_SRWLOCK_UNUSED;
typedef struct _MODULEINFO { LPVOID lpBaseOfDll; DWORD SizeOfImage; LPVOID EntryPoint; } MODULEINFO;
typedef union _TLS_UNUSED { int x; } TLS_UNUSED;
#define SRWLOCK_INIT { nullptr, PTHREAD_MUTEX_INITIALIZER }
#define CONDITION_VARIABLE_INIT { PTHREAD_COND_INITIALIZER }

#define DM_DISPLAYFREQUENCY 0x400000
#define DM_PELSWIDTH 0x80000
#define DM_PELSHEIGHT 0x100000
#define DM_BITSPERPEL 0x40000
#define CDS_FULLSCREEN 4
#define CDS_TEST 2
#define DISP_CHANGE_SUCCESSFUL 0
#define DISP_CHANGE_FAILED (-1)
#define SMTO_BLOCK 1
#define SMTO_ABORTIFHUNG 2
#define HWND_BROADCAST ((HWND)(ULONG_PTR)0xffff)
#define FILE_DEVICE_VIDEO 0x23
#define METHOD_BUFFERED 0
#define FILE_READ_ACCESS 1
#define FILE_WRITE_ACCESS 2
#define CTL_CODE(t, f, m, a) (((t) << 16) | ((a) << 14) | ((f) << 2) | (m))
#define FILE_ATTRIBUTE_READONLY 1
#define ULW_ALPHA 2
#define LWA_ALPHA 2
#define ERROR_TIMEOUT 1460
#define SW_SHOWNORMAL 1
#define TH32CS_SNAPPROCESS 2
#define PROCESS_VM_READ 0x10
#define VK_OEM_JUMP 0xEA

typedef struct tagPROCESSENTRY32W { DWORD dwSize, cntUsage, th32ProcessID; ULONG_PTR th32DefaultHeapID; DWORD th32ModuleID, cntThreads, th32ParentProcessID; LONG pcPriClassBase; DWORD dwFlags; WCHAR szExeFile[MAX_PATH]; } PROCESSENTRY32W, PROCESSENTRY32, *PPROCESSENTRY32;

static inline BOOL InvalidateRect(HWND, const RECT*, BOOL) { return TRUE; }
static inline BOOL SetLayeredWindowAttributes(HWND, COLORREF, BYTE, DWORD) { return TRUE; }

#define FILE_SHARE_DELETE 4
#define FILE_FLAG_OVERLAPPED 0x40000000
#define FILE_FLAG_BACKUP_SEMANTICS 0x02000000
#define FILE_LIST_DIRECTORY 1
#define FILE_BEGIN 0
#define FILE_CURRENT 1
#define FILE_END 2
#define MB_ICONSTOP 0x10
#define PAGE_WRITECOPY 8
#define SECTION_MAP_READ 4
#define HANDLE_FLAG_INHERIT 1
#define STARTF_USESTDHANDLES 0x100
#define FILE_NOTIFY_CHANGE_FILE_NAME 1
#define FILE_NOTIFY_CHANGE_SIZE 8
#define FILE_NOTIFY_CHANGE_LAST_WRITE 0x10
#define FILE_NOTIFY_CHANGE_CREATION 0x40
#define ZeroMemory(p, n) memset((p), 0, (n))
#define RtlZeroMemory(p, n) memset((p), 0, (n))
#define CopyMemory(d, s, n) memcpy((d), (s), (n))
#define SecureZeroMemory(p, n) memset((p), 0, (n))
#define NT_SUCCESS(s) (((NTSTATUS_S)(s)) >= 0)
#define STATUS_SUCCESS ((NTSTATUS_S)0)
typedef struct _SECURITY_ATTRIBUTES_UNUSED { int x; } SECURITY_ATTRIBUTES_UNUSED;
typedef struct _POINT_UNUSED { int x; } POINT_UNUSED;
typedef int errno_t;

#define DM_INTERLACED 2
#define DISPLAY_DEVICE_MIRRORING_DRIVER 8
#define CURSOR_SHOWING 1
#define GWLP_WNDPROC (-4)
#define GWLP_HINSTANCE (-6)
#define WM_NULL 0
#define WS_EX_OVERLAPPEDWINDOW 0x300
#define HWND_TOPMOST ((HWND)(LONG_PTR)-1)
#define HWND_NOTOPMOST ((HWND)(LONG_PTR)-2)
#define SWP_NOSIZE 1
#define SWP_NOMOVE 2
#define SWP_NOZORDER 4
#define SWP_NOACTIVATE 0x10
#define SWP_FRAMECHANGED 0x20
#define SWP_SHOWWINDOW 0x40
#define SWP_ASYNCWINDOWPOS 0x4000
#define REG_DWORD 4
#define REG_SZ 1
#define REG_NOTIFY_CHANGE_LAST_SET 4
typedef LONG LSTATUS;
typedef struct _DISPLAY_DEVICEA { DWORD cb; CHAR DeviceName[32]; CHAR DeviceString[128]; DWORD StateFlags; CHAR DeviceID[128]; CHAR DeviceKey[128]; } DISPLAY_DEVICEA, *PDISPLAY_DEVICEA;
typedef struct _ICONINFO { BOOL fIcon; DWORD xHotspot, yHotspot; HBITMAP hbmMask, hbmColor; } ICONINFO;
typedef struct tagCURSORINFO { DWORD cbSize, flags; HCURSOR hCursor; POINT ptScreenPos; } CURSORINFO, *PCURSORINFO;
#define WM_ACTIVATE 6
#define WM_ENDSESSION 0x16
#define WM_MOUSEACTIVATE 0x21
#define WM_NCACTIVATE 0x86
#define WM_SETCURSOR 0x20
#define WM_SHOWWINDOW 0x18
#define WA_INACTIVE 0
#define WA_ACTIVE 1
#define SIZE_RESTORED 0
#define SIZE_MINIMIZED 1
#define SIZE_MAXIMIZED 2

#define WM_KILLFOCUS 8
#define WM_SETFOCUS 7
#define WM_NCDESTROY 0x82
#define WM_ENTERMENULOOP 0x211
#define WM_EXITMENULOOP 0x212
#define WM_WINDOWPOSCHANGING 0x46
#define WM_WINDOWPOSCHANGED 0x47
#define GW_HWNDPREV 3
#define HWND_DESKTOP ((HWND)0)
#define SW_MINIMIZE 6
#define SW_RESTORE 9
#define SW_SHOWMINNOACTIVE 7
#define SW_SHOWNOACTIVATE 4
typedef struct tagWINDOWPOS { HWND hwnd, hwndInsertAfter; int x, y, cx, cy; UINT flags; } WINDOWPOS, *LPWINDOWPOS;

#ifdef __cplusplus
// Bounded "secure" CRT variants, with and without the array-size deduction form.
template<size_t N, typename... Args> inline int sprintf_s(char (&buffer)[N], const char* format, Args... args) { return snprintf(buffer, N, format, args...); }
template<typename... Args> inline int sprintf_s(char* buffer, size_t size, const char* format, Args... args) { return snprintf(buffer, size, format, args...); }
template<size_t N> inline int strcpy_s(char (&dst)[N], const char* src) { strncpy(dst, src, N); dst[N - 1] = 0; return 0; }
inline int strcpy_s(char* dst, size_t size, const char* src) { strncpy(dst, src, size); if (size) dst[size - 1] = 0; return 0; }
inline int strncpy_s(char* dst, size_t size, const char* src, size_t count) { strncpy(dst, src, count < size ? count : size); if (size) dst[size - 1] = 0; return 0; }
template<size_t N> inline int wcscpy_s(wchar_t (&dst)[N], const wchar_t* src) { wcsncpy(dst, src, N); dst[N - 1] = 0; return 0; }
inline int wcscpy_s(wchar_t* dst, size_t size, const wchar_t* src) { wcsncpy(dst, src, size); if (size) dst[size - 1] = 0; return 0; }
#endif

// ---- Function declarations (implemented in src/util/linux/win32_compat.cpp) ----
#ifdef __cplusplus
HMODULE LoadLibraryA(const char*);
HMODULE LoadLibraryW(const wchar_t*);
HMODULE LoadLibraryExW(const wchar_t*, HANDLE, DWORD);
HMODULE LoadLibraryExA(const char*, HANDLE, DWORD);
HMODULE GetModuleHandleA(const char*);
HMODULE GetModuleHandleW(const wchar_t*);
BOOL GetModuleHandleExW(DWORD, const wchar_t*, HMODULE*);
BOOL GetModuleHandleExA(DWORD, const char*, HMODULE*);
FARPROC GetProcAddress(HMODULE, const char*);
BOOL FreeLibrary(HMODULE);
DWORD GetModuleFileNameA(HMODULE, char*, DWORD);
DWORD GetModuleFileNameW(HMODULE, wchar_t*, DWORD);
DWORD GetLastError();
void SetLastError(DWORD);
DWORD GetCurrentThreadId();
DWORD GetCurrentProcessId();
HANDLE GetCurrentProcess();
HANDLE GetCurrentThread();
void YieldProcessor();
void Sleep(DWORD);
BOOL SwitchToThread();
void DebugBreak();
BOOL IsDebuggerPresent();
void OutputDebugStringA(const char*);
void OutputDebugStringW(const wchar_t*);
int MultiByteToWideChar(UINT, DWORD, const char*, int, wchar_t*, int);
int WideCharToMultiByte(UINT, DWORD, const wchar_t*, int, char*, int, const char*, BOOL*);
DWORD GetEnvironmentVariableA(const char*, char*, DWORD);
DWORD GetEnvironmentVariableW(const wchar_t*, wchar_t*, DWORD);
BOOL SetEnvironmentVariableA(const char*, const char*);
BOOL SetEnvironmentVariableW(const wchar_t*, const wchar_t*);
DWORD GetCurrentDirectoryA(DWORD, char*);
DWORD GetTempPathA(DWORD, char*);
DWORD GetTempPathW(DWORD, wchar_t*);
DWORD GetFileAttributesA(const char*);
DWORD GetFileAttributesW(const wchar_t*);
BOOL CreateDirectoryA(const char*, void*);
BOOL CreateDirectoryW(const wchar_t*, void*);
BOOL DeleteFileA(const char*);
BOOL DeleteFileW(const wchar_t*);
BOOL CloseHandle(HANDLE);
BOOL QueryPerformanceCounter(LARGE_INTEGER*);
BOOL QueryPerformanceFrequency(LARGE_INTEGER*);
DWORD GetTickCount();
ULONGLONG GetTickCount64();
void GetSystemTimeAsFileTime(FILETIME*);
void GetLocalTime(SYSTEMTIME*);
void GetSystemTime(SYSTEMTIME*);
void GetSystemInfo(SYSTEM_INFO*);
void GetNativeSystemInfo(SYSTEM_INFO*);
BOOL GlobalMemoryStatusEx(MEMORYSTATUSEX*);
BOOL GetMonitorInfoW(HMONITOR, MONITORINFO*);
BOOL EnumDisplaySettingsW(const wchar_t*, DWORD, DEVMODEW*);
HMONITOR MonitorFromWindow(HWND, DWORD);
HMONITOR MonitorFromPoint(POINT, DWORD);
BOOL EnumDisplayMonitors(HDC, const RECT*, MONITORENUMPROC, LPARAM);
BOOL GetClientRect(HWND, RECT*);
BOOL GetWindowRect(HWND, RECT*);
BOOL IsWindow(HWND);
int GetSystemMetrics(int);
HWND GetDesktopWindow();
BOOL AllocateLocallyUniqueId(LUID*);
HANDLE CreateFileA(const char*, DWORD, DWORD, void*, DWORD, DWORD, HANDLE);
HANDLE CreateFileW(const wchar_t*, DWORD, DWORD, void*, DWORD, DWORD, HANDLE);
BOOL DeviceIoControl(HANDLE, DWORD, void*, DWORD, void*, DWORD, DWORD*, void*);
LONG ChangeDisplaySettingsExW(const wchar_t*, DEVMODEW*, HWND, DWORD, void*);
UINT RegisterWindowMessageA(const char*);
BOOL PostThreadMessageA(DWORD, UINT, WPARAM, LPARAM);
BOOL PostMessageA(HWND, UINT, WPARAM, LPARAM);
LRESULT SendMessageTimeoutA(HWND, UINT, WPARAM, LPARAM, UINT, UINT, DWORD_PTR*);
BOOL GetMessageA(MSG*, HWND, UINT, UINT);
BOOL PeekMessageA(MSG*, HWND, UINT, UINT, UINT);
BOOL TranslateMessage(const MSG*);
LRESULT DispatchMessageA(const MSG*);
DWORD GetThreadId(HANDLE);
HANDLE CreateToolhelp32Snapshot(DWORD, DWORD);
BOOL Process32FirstW(HANDLE, PROCESSENTRY32W*);
BOOL Process32NextW(HANDLE, PROCESSENTRY32W*);
#define Process32First Process32FirstW
#define Process32Next Process32NextW
HANDLE OpenProcess(DWORD, BOOL, DWORD);
DWORD GetModuleFileNameExW(HANDLE, HMODULE, wchar_t*, DWORD);
BOOL PathRemoveFileSpecW(wchar_t*);
BOOL TerminateProcess(HANDLE, UINT);
void ExitProcess(UINT);
HINSTANCE ShellExecuteA(HWND, const char*, const char*, const char*, const char*, int);

HANDLE CreateEventA(void*, BOOL, BOOL, const char*);
HANDLE CreateEventW(void*, BOOL, BOOL, const wchar_t*);
BOOL SetEvent(HANDLE);
BOOL ResetEvent(HANDLE);
DWORD WaitForSingleObject(HANDLE, DWORD);
DWORD WaitForSingleObjectEx(HANDLE, DWORD, BOOL);
DWORD WaitForMultipleObjects(DWORD, const HANDLE*, BOOL, DWORD);
BOOL ReadFile(HANDLE, void*, DWORD, DWORD*, void*);
BOOL WriteFile(HANDLE, const void*, DWORD, DWORD*, void*);
BOOL GetFileSizeEx(HANDLE, LARGE_INTEGER*);
DWORD GetFileSize(HANDLE, DWORD*);
BOOL SetFilePointerEx(HANDLE, LARGE_INTEGER, LARGE_INTEGER*, DWORD);
BOOL FlushFileBuffers(HANDLE);
HANDLE CreateFileMappingA(HANDLE, void*, DWORD, DWORD, DWORD, const char*);
HANDLE CreateFileMappingW(HANDLE, void*, DWORD, DWORD, DWORD, const wchar_t*);
void* MapViewOfFile(HANDLE, DWORD, DWORD, DWORD, SIZE_T);
BOOL UnmapViewOfFile(const void*);
int MessageBoxA(HWND, const char*, const char*, UINT);
errno_t localtime_s(struct tm*, const time_t*);
errno_t gmtime_s(struct tm*, const time_t*);
const char* GetCommandLineA();
int ShowCursor(BOOL);
BOOL GetCursorPos(POINT*);
BOOL GetPhysicalCursorPos(POINT*);
BOOL SetCursorPos(int, int);
BOOL ClientToScreen(HWND, POINT*);
BOOL ScreenToClient(HWND, POINT*);
HWND GetForegroundWindow();
DWORD GetWindowThreadProcessId(HWND, DWORD*);
SHORT GetAsyncKeyState(int);
SHORT GetKeyState(int);
#define CreateEvent CreateEventA
#define CreateFileMapping CreateFileMappingA
#define MessageBox MessageBoxA

BOOL EnumDisplayDevicesA(const char*, DWORD, DISPLAY_DEVICEA*, DWORD);
HCURSOR SetCursor(HCURSOR);
HBITMAP CreateBitmap(int, int, UINT, UINT, const void*);
BOOL DestroyCursor(HCURSOR);
HICON CreateIconIndirect(ICONINFO*);
BOOL DeleteObject(HGDIOBJ);
BOOL GetCursorInfo(CURSORINFO*);
BOOL SetPhysicalCursorPos(int, int);
HDC CreateCompatibleDC(HDC);
BOOL DeleteDC(HDC);
LONG_PTR GetWindowLongPtrA(HWND, int);
LONG_PTR GetWindowLongPtrW(HWND, int);
LONG_PTR SetWindowLongPtrA(HWND, int, LONG_PTR);
LONG_PTR SetWindowLongPtrW(HWND, int, LONG_PTR);
LONG GetWindowLongA(HWND, int);
LONG SetWindowLongA(HWND, int, LONG);
BOOL PostMessageW(HWND, UINT, WPARAM, LPARAM);
BOOL SetWindowPos(HWND, HWND, int, int, int, int, UINT);
LSTATUS RegOpenKeyExA(HKEY, const char*, DWORD, DWORD, HKEY*);
LSTATUS RegQueryValueExA(HKEY, const char*, DWORD*, DWORD*, BYTE*, DWORD*);
LSTATUS RegCloseKey(HKEY);
LSTATUS RegNotifyChangeKeyValue(HKEY, BOOL, DWORD, HANDLE, BOOL);
BOOL IsWindowUnicode(HWND);
// Returns the process wide XCB connection used to query windows and to present into them, or null when unavailable.
void* RemixGetXcbConnection();
// The Wayland display the client's window belongs to, set through remixapi_linux_SetWaylandDisplay, or null for X11.
void* RemixGetWaylandDisplay();
// The Vulkan presenter draws into a subsurface of the client's wl_surface, since that surface already belongs to an EGL window.
void* RemixGetWaylandChildSurface(void* parentSurface);
BOOL IsIconic(HWND);
BOOL IsWindowVisible(HWND);
BOOL ShowWindow(HWND, int);
HWND GetWindow(HWND, UINT);
HWND GetParent(HWND);
BOOL SetRect(RECT*, int, int, int, int);
BOOL OffsetRect(RECT*, int, int);
BOOL AdjustWindowRectEx(RECT*, DWORD, BOOL, DWORD);
int MapWindowPoints(HWND, HWND, POINT*, UINT);
LONG GetWindowLongW(HWND, int);
LONG SetWindowLongW(HWND, int, LONG);
LRESULT CallWindowProcA(WNDPROC, HWND, UINT, WPARAM, LPARAM);
LRESULT CallWindowProcW(WNDPROC, HWND, UINT, WPARAM, LPARAM);
LRESULT DefWindowProcA(HWND, UINT, WPARAM, LPARAM);
LRESULT DefWindowProcW(HWND, UINT, WPARAM, LPARAM);
#define GetWindowLongPtr GetWindowLongPtrA
#define SetWindowLongPtr SetWindowLongPtrA
#define GetWindowLong GetWindowLongA
#define SetWindowLong SetWindowLongA
#define GetModuleHandleEx GetModuleHandleExA
#define PostThreadMessage PostThreadMessageA
#define PostMessage PostMessageA
#define SendMessageTimeout SendMessageTimeoutA
#define GetMessage GetMessageA
#define PeekMessage PeekMessageA
#define DispatchMessage DispatchMessageA
#define CreateFile CreateFileA
#endif

#ifdef __cplusplus
#define OutputDebugString OutputDebugStringA
#define LoadLibrary LoadLibraryA
#define GetModuleHandle GetModuleHandleA
#define GetModuleFileName GetModuleFileNameA
#define GetEnvironmentVariable GetEnvironmentVariableA
#define SetEnvironmentVariable SetEnvironmentVariableA
#define GetTempPath GetTempPathA
#define CreateDirectory CreateDirectoryA
#define DeleteFile DeleteFileA
#define GetFileAttributes GetFileAttributesA
#define GetCurrentDirectory GetCurrentDirectoryA
#endif
