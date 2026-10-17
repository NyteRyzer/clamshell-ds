/*
    Clamshell DS - emulation core wrapper around melonDS.
    Licensed under the GNU GPL v3 or later.
*/

#pragma once

#include <android/native_window.h>
#include <aaudio/AAudio.h>

#include <atomic>
#include <condition_variable>
#include <deque>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "types.h"
#include "BigThread.h"

namespace melonDS { class NDS; }

namespace clamshell
{

using melonDS::u8;
using melonDS::u16;
using melonDS::u32;
using melonDS::u64;
using melonDS::s16;

struct EmuConfig
{
    int consoleType = 0;        // 0 = DS, 1 = DSi
    bool jit = true;
    bool fastMem = true;        // JIT fast memory (SIGSEGV-based)
    bool threaded3D = true;
    bool directBoot = true;     // skip the console boot menu when launching a game
    bool audioSync = true;
    bool audioEnabled = true;
    int volume = 256;           // 0..256
    int scale = 2;              // nearest-neighbour prescale factor (1 = smooth)
    int jitBlockSize = 32;

    std::string username = "Clamshell";
    int language = 1;           // firmware language (1 = English)
    int favoriteColor = 11;     // DS/DSi favourite colour index (11 = blue)
    int birthMonth = 1;
    int birthDay = 1;

    // External BIOS / firmware / NAND paths. DS mode can run without them
    // (melonDS ships FreeBIOS); DSi mode needs real dumps.
    std::string bios9Path, bios7Path, firmwarePath;
    std::string dsiBios9Path, dsiBios7Path, dsiFirmwarePath, dsiNandPath;
    bool useExternalDSBios = false;
};

class EmuCore
{
public:
    static EmuCore& Get();

    void SetBasePath(const std::string& path) { basePath = path; }
    const std::string& BasePath() const { return basePath; }

    void Configure(const EmuConfig& cfg);

    // Boot: returns empty string on success, error text otherwise.
    std::string LoadGame(const std::string& romPath, const std::string& savePath);
    std::string BootFirmware();

    void Start();
    void Pause();
    void Resume();
    void Shutdown();
    void Reset();

    bool IsRunning() const { return running.load(); }
    bool IsPaused() const { return paused.load(); }
    int StopReason() const { return stopReason.load(); }
    bool JitActive() const { return jitActive.load(); }
    int Fps() const { return fps.load(); }

    void SetSurface(int slot, ANativeWindow* window, int screen);
    void SetKeys(u32 pressedMask) { keysPressed.store(pressedMask); }
    void SetTouch(bool down, int x, int y);
    void SetFastForward(bool ff) { fastForward.store(ff); }
    void SetLidClosed(bool closed) { lidClosed.store(closed); }
    void SetBattery(int percent, bool charging);
    void SetScale(int scale);

    bool SaveState(const std::string& path);
    bool LoadState(const std::string& path);
    // Copies the last frame of a screen (0 = top, 1 = bottom) as 0xAARRGGBB.
    bool CopyScreen(int screen, u32* out);

    // Called from the melonDS Platform layer.
    void OnNDSSaveWritten(const u8* data, u32 len);
    void OnFirmwareWritten(const u8* data, u32 len);
    void OnCoreStop(int reason);

    // Audio callback
    static aaudio_data_callback_result_t AudioCallback(AAudioStream* stream, void* user, void* audioData, int32_t numFrames);
    static void AudioErrorCallback(AAudioStream* stream, void* user, aaudio_result_t error);

private:
    EmuCore() = default;

    bool CreateConsole(std::string& err);
    void DestroyConsole();
    void EmuThreadMain();
    void RenderThreadMain();
    void RunOnEmuThread(const std::function<void()>& fn);
    void FlushSave(bool force);
    void SyncClock();
    void OpenAudio();
    void CloseAudio();
    void PresentToWindow(int slot);

    std::string basePath;
    EmuConfig config;
    std::string romPath, savePath, activeFirmwarePath;

    melonDS::NDS* nds = nullptr;
    std::mutex ndsLock;

    BigThread emuThread, renderThread;
    std::atomic<bool> running {false};
    std::atomic<bool> paused {false};
    std::atomic<bool> quit {false};
    std::atomic<int> stopReason {-1};
    std::atomic<int> fps {0};
    std::atomic<bool> jitActive {false};

    std::mutex cmdLock;
    std::condition_variable cmdCv;
    std::deque<std::function<void()>> commands;

    std::atomic<u32> keysPressed {0};
    std::atomic<bool> touching {false};
    std::atomic<int> touchX {0}, touchY {0};
    std::atomic<bool> fastForward {false};
    std::atomic<bool> lidClosed {false};
    std::atomic<int> batteryPercent {100};
    std::atomic<bool> batteryCharging {false};
    std::atomic<bool> batteryDirty {true};

    // video: two 256x192 screens in BGRA (melonDS native output)
    std::mutex frameLock;
    std::condition_variable frameCv;
    std::vector<u32> frame[2];
    u64 frameSerial = 0;
    bool haveFrame = false;

    struct Slot
    {
        ANativeWindow* window = nullptr;
        int screen = 0;
        int geomScale = 0;
    };
    std::mutex surfaceLock;
    Slot slots[2];
    std::atomic<int> scale {2};

    // saves
    std::mutex saveLock;
    std::vector<u8> pendingSave;
    bool saveDirty = false;
    u64 saveDirtyTime = 0;

    // audio
    AAudioStream* audioStream = nullptr;
    std::mutex audioLock;
    std::condition_variable audioCv;
    std::atomic<bool> audioRestart {false};
};

}
