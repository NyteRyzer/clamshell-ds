/*
    Clamshell DS - Android platform layer for the melonDS core.

    Clamshell DS is free software: you can redistribute it and/or modify it
    under the terms of the GNU General Public License as published by the Free
    Software Foundation, either version 3 of the License, or (at your option)
    any later version.

    File helpers follow melonDS' own Qt frontend (src/frontend/qt_sdl/Platform.cpp).
*/

#include <android/log.h>
#include <dlfcn.h>
#include <sys/stat.h>
#include <unistd.h>

#include <chrono>
#include <condition_variable>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <thread>

#include "Platform.h"
#include "SPI_Firmware.h"

#include "EmuCore.h"

namespace melonDS::Platform
{

static constexpr const char* kTag = "ClamshellDS";

void SignalStop(StopReason reason, void* userdata)
{
    clamshell::EmuCore::Get().OnCoreStop((int)reason);
}

static bool PathExists(const std::string& path)
{
    struct stat st;
    return stat(path.c_str(), &st) == 0;
}

static std::string GetModeString(FileMode mode, bool fileExists)
{
    std::string m;
    if (mode & FileMode::Append)
        m += 'a';
    else if (!(mode & FileMode::Write))
        m += 'r';
    else if (mode & (FileMode::NoCreate))
        m += 'r'; // must exist; opened "r+"
    else if ((mode & FileMode::Preserve) && fileExists)
        m += 'r'; // keep contents; opened "r+"
    else
        m += 'w';

    if ((mode & FileMode::ReadWrite) == FileMode::ReadWrite || ((mode & FileMode::Write) && m[0] == 'r'))
        m += '+';

    if (!(mode & FileMode::Text))
        m += 'b';
    return m;
}

FileHandle* OpenFile(const std::string& path, FileMode mode)
{
    if ((mode & (FileMode::ReadWrite | FileMode::Append)) == FileMode::None)
        return nullptr;

    bool exists = PathExists(path);
    if ((mode & FileMode::NoCreate) && !exists)
        return nullptr;
    if (!(mode & (FileMode::Write | FileMode::Append)) && !exists)
        return nullptr;

    std::string modeStr = GetModeString(mode, exists);
    FILE* f = fopen(path.c_str(), modeStr.c_str());
    if (!f)
    {
        Log(LogLevel::Debug, "Failed to open \"%s\" (mode %s)\n", path.c_str(), modeStr.c_str());
        return nullptr;
    }
    return reinterpret_cast<FileHandle*>(f);
}

std::string GetLocalFilePath(const std::string& filename)
{
    if (!filename.empty() && filename[0] == '/')
        return filename;
    return clamshell::EmuCore::Get().BasePath() + "/" + filename;
}

FileHandle* OpenLocalFile(const std::string& path, FileMode mode)
{
    return OpenFile(GetLocalFilePath(path), mode);
}

bool FileExists(const std::string& name)
{
    return PathExists(name);
}

bool LocalFileExists(const std::string& name)
{
    return PathExists(GetLocalFilePath(name));
}

bool CheckFileWritable(const std::string& filepath)
{
    if (PathExists(filepath))
        return access(filepath.c_str(), W_OK) == 0;

    std::string dir = filepath.substr(0, filepath.find_last_of('/'));
    return access(dir.c_str(), W_OK) == 0;
}

bool CheckLocalFileWritable(const std::string& filepath)
{
    return CheckFileWritable(GetLocalFilePath(filepath));
}

bool CloseFile(FileHandle* file)
{
    return fclose(reinterpret_cast<FILE*>(file)) == 0;
}

bool IsEndOfFile(FileHandle* file)
{
    return feof(reinterpret_cast<FILE*>(file)) != 0;
}

bool FileReadLine(char* str, int count, FileHandle* file)
{
    return fgets(str, count, reinterpret_cast<FILE*>(file)) != nullptr;
}

u64 FilePosition(FileHandle* file)
{
    return ftell(reinterpret_cast<FILE*>(file));
}

bool FileSeek(FileHandle* file, s64 offset, FileSeekOrigin origin)
{
    int o = SEEK_SET;
    switch (origin)
    {
        case FileSeekOrigin::Start: o = SEEK_SET; break;
        case FileSeekOrigin::Current: o = SEEK_CUR; break;
        case FileSeekOrigin::End: o = SEEK_END; break;
    }
    return fseeko(reinterpret_cast<FILE*>(file), offset, o) == 0;
}

void FileRewind(FileHandle* file)
{
    rewind(reinterpret_cast<FILE*>(file));
}

u64 FileRead(void* data, u64 size, u64 count, FileHandle* file)
{
    return fread(data, size, count, reinterpret_cast<FILE*>(file));
}

bool FileFlush(FileHandle* file)
{
    return fflush(reinterpret_cast<FILE*>(file)) == 0;
}

u64 FileWrite(const void* data, u64 size, u64 count, FileHandle* file)
{
    return fwrite(data, size, count, reinterpret_cast<FILE*>(file));
}

u64 FileWriteFormatted(FileHandle* file, const char* fmt, ...)
{
    if (!fmt) return 0;
    va_list args;
    va_start(args, fmt);
    u64 ret = vfprintf(reinterpret_cast<FILE*>(file), fmt, args);
    va_end(args);
    return ret;
}

u64 FileLength(FileHandle* file)
{
    FILE* f = reinterpret_cast<FILE*>(file);
    off_t pos = ftello(f);
    fseeko(f, 0, SEEK_END);
    off_t len = ftello(f);
    fseeko(f, pos, SEEK_SET);
    return len < 0 ? 0 : (u64)len;
}

void Log(LogLevel level, const char* fmt, ...)
{
    if (!fmt) return;
    int prio = ANDROID_LOG_DEBUG;
    switch (level)
    {
        case LogLevel::Debug: prio = ANDROID_LOG_DEBUG; break;
        case LogLevel::Info: prio = ANDROID_LOG_INFO; break;
        case LogLevel::Warn: prio = ANDROID_LOG_WARN; break;
        case LogLevel::Error: prio = ANDROID_LOG_ERROR; break;
    }
    va_list args;
    va_start(args, fmt);
    __android_log_vprint(prio, kTag, fmt, args);
    va_end(args);
}

// --- Threads & sync -------------------------------------------------------

struct Thread
{
    std::thread t;
};

Thread* Thread_Create(std::function<void()> func)
{
    auto* t = new Thread();
    t->t = std::thread(std::move(func));
    return t;
}

void Thread_Free(Thread* thread)
{
    if (!thread) return;
    if (thread->t.joinable())
        thread->t.detach();
    delete thread;
}

void Thread_Wait(Thread* thread)
{
    if (thread && thread->t.joinable())
        thread->t.join();
}

struct Semaphore
{
    std::mutex m;
    std::condition_variable cv;
    int count = 0;
};

Semaphore* Semaphore_Create() { return new Semaphore(); }
void Semaphore_Free(Semaphore* sema) { delete sema; }

void Semaphore_Reset(Semaphore* sema)
{
    std::lock_guard<std::mutex> lk(sema->m);
    sema->count = 0;
}

void Semaphore_Wait(Semaphore* sema)
{
    std::unique_lock<std::mutex> lk(sema->m);
    sema->cv.wait(lk, [sema] { return sema->count > 0; });
    sema->count--;
}

bool Semaphore_TryWait(Semaphore* sema, int timeout_ms)
{
    std::unique_lock<std::mutex> lk(sema->m);
    if (timeout_ms == 0)
    {
        if (sema->count <= 0) return false;
        sema->count--;
        return true;
    }
    if (!sema->cv.wait_for(lk, std::chrono::milliseconds(timeout_ms), [sema] { return sema->count > 0; }))
        return false;
    sema->count--;
    return true;
}

void Semaphore_Post(Semaphore* sema, int count)
{
    {
        std::lock_guard<std::mutex> lk(sema->m);
        sema->count += count;
    }
    if (count == 1) sema->cv.notify_one();
    else sema->cv.notify_all();
}

struct Mutex
{
    std::mutex m;
};

Mutex* Mutex_Create() { return new Mutex(); }
void Mutex_Free(Mutex* mutex) { delete mutex; }
void Mutex_Lock(Mutex* mutex) { mutex->m.lock(); }
void Mutex_Unlock(Mutex* mutex) { mutex->m.unlock(); }
bool Mutex_TryLock(Mutex* mutex) { return mutex->m.try_lock(); }

void Sleep(u64 usecs)
{
    std::this_thread::sleep_for(std::chrono::microseconds(usecs));
}

u64 GetMSCount()
{
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

u64 GetUSCount()
{
    using namespace std::chrono;
    return duration_cast<microseconds>(steady_clock::now().time_since_epoch()).count();
}

// --- Saves ----------------------------------------------------------------

void WriteNDSSave(const u8* savedata, u32 savelen, u32 writeoffset, u32 writelen, void* userdata)
{
    clamshell::EmuCore::Get().OnNDSSaveWritten(savedata, savelen);
}

void WriteGBASave(const u8* savedata, u32 savelen, u32 writeoffset, u32 writelen, void* userdata)
{
}

void WriteFirmware(const Firmware& firmware, u32 writeoffset, u32 writelen, void* userdata)
{
    clamshell::EmuCore::Get().OnFirmwareWritten(firmware.Buffer(), firmware.Length());
}

void WriteDateTime(int year, int month, int day, int hour, int minute, int second, void* userdata)
{
}

// --- Local wireless / network (not supported) -----------------------------

void MP_Begin(void*) {}
void MP_End(void*) {}
int MP_SendPacket(u8*, int, u64, void*) { return 0; }
int MP_RecvPacket(u8*, u64*, void*) { return 0; }
int MP_SendCmd(u8*, int, u64, void*) { return 0; }
int MP_SendReply(u8*, int, u64, u16, void*) { return 0; }
int MP_SendAck(u8*, int, u64, void*) { return 0; }
int MP_RecvHostPacket(u8*, u64*, void*) { return 0; }
u16 MP_RecvReplies(u8*, u64, u16, void*) { return 0; }

int Net_SendPacket(u8*, int, void*) { return 0; }
int Net_RecvPacket(u8*, void*) { return 0; }

// --- Camera / mic (DSi camera shows a neutral grey frame) -----------------

void Camera_Start(int, void*) {}
void Camera_Stop(int, void*) {}

void Camera_CaptureFrame(int num, u32* frame, int width, int height, bool yuv, void* userdata)
{
    // YUV422 grey (Y=0x80, U/V=0x80) or RGB grey
    u32 value = yuv ? 0x80808080 : 0xFF808080;
    int count = yuv ? (width * height) / 2 : width * height;
    for (int i = 0; i < count; i++) frame[i] = value;
}

void Mic_Start(void*) {}
void Mic_Stop(void*) {}
int Mic_ReadInput(s16* data, int maxlength, void* userdata)
{
    return 0;
}

// --- AAC (DSi DSP HLE) not available --------------------------------------

AACDecoder* AAC_Init() { return nullptr; }
void AAC_DeInit(AACDecoder*) {}
bool AAC_Configure(AACDecoder*, int, int) { return false; }
bool AAC_DecodeFrame(AACDecoder*, const void*, int, void*, int) { return false; }

// --- Addons ----------------------------------------------------------------

bool Addon_KeyDown(KeyType, void*) { return false; }
void Addon_RumbleStart(u32, void*) {}
void Addon_RumbleStop(void*) {}
float Addon_MotionQuery(MotionQueryType type, void*)
{
    // Report a console lying still, screen up.
    return type == MotionAccelerationZ ? 9.80665f : 0.0f;
}

// --- Dynamic libraries (used by the JIT's fastmem on Android) --------------

DynamicLibrary* DynamicLibrary_Load(const char* lib)
{
    return reinterpret_cast<DynamicLibrary*>(dlopen(lib, RTLD_NOW | RTLD_LOCAL));
}

void DynamicLibrary_Unload(DynamicLibrary* lib)
{
    if (lib) dlclose(lib);
}

void* DynamicLibrary_LoadFunction(DynamicLibrary* lib, const char* name)
{
    return lib ? dlsym(lib, name) : nullptr;
}

}
