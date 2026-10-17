/*
    Clamshell DS - emulation core wrapper around melonDS.
    Licensed under the GNU GPL v3 or later.
*/

#include "EmuCore.h"

#include <sys/resource.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <future>

#include "Args.h"
#include "DSi.h"
#include "DSi_I2C.h"
#include "DSi_NAND.h"
#include "FreeBIOS.h"
#include "GPU.h"
#include "GPU3D_Soft.h"
#include "NDS.h"
#include "NDSCart.h"
#include "Platform.h"
#include "SPI.h"
#include "SPI_Firmware.h"
#include "Savestate.h"

using namespace melonDS;
using Platform::Log;
using Platform::LogLevel;

namespace clamshell
{

static constexpr int kAudioRate = 48000;
static constexpr double kNdsFps = 59.8260982880808;

static u64 NowMs()
{
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

static bool ReadWholeFile(const std::string& path, std::unique_ptr<u8[]>& out, u32& len, u32 maxLen = 0x40000000)
{
    FILE* f = fopen(path.c_str(), "rb");
    if (!f) return false;
    fseeko(f, 0, SEEK_END);
    off_t size = ftello(f);
    fseeko(f, 0, SEEK_SET);
    if (size <= 0 || (u64)size > maxLen)
    {
        fclose(f);
        return false;
    }
    out = std::make_unique<u8[]>(size);
    bool ok = fread(out.get(), size, 1, f) == 1;
    fclose(f);
    len = (u32)size;
    return ok;
}

template <size_t N>
static std::unique_ptr<std::array<u8, N>> LoadBiosFile(const std::string& path)
{
    std::unique_ptr<u8[]> data;
    u32 len = 0;
    if (path.empty() || !ReadWholeFile(path, data, len, 16 * 1024 * 1024) || len < N)
        return nullptr;
    auto bios = std::make_unique<std::array<u8, N>>();
    memcpy(bios->data(), data.get(), N);
    return bios;
}

static std::string FileName(const std::string& path)
{
    size_t p = path.find_last_of('/');
    return p == std::string::npos ? path : path.substr(p + 1);
}

EmuCore& EmuCore::Get()
{
    static EmuCore instance;
    return instance;
}

void EmuCore::Configure(const EmuConfig& cfg)
{
    config = cfg;
    scale.store(std::clamp(cfg.scale, 1, 4));
}

void EmuCore::SetScale(int s)
{
    scale.store(std::clamp(s, 1, 4));
    std::lock_guard<std::mutex> lk(frameLock);
    frameSerial++;
    frameCv.notify_all();
}

// ---------------------------------------------------------------------------
// Console creation
// ---------------------------------------------------------------------------

bool EmuCore::CreateConsole(std::string& err)
{
    DestroyConsole();

    const bool dsi = config.consoleType == 1;
    const bool externalBios = dsi || config.useExternalDSBios;
    activeFirmwarePath.clear();

    std::unique_ptr<ARM9BIOSImage> arm9;
    std::unique_ptr<ARM7BIOSImage> arm7;
    if (externalBios)
    {
        arm9 = LoadBiosFile<ARM9BIOSSize>(config.bios9Path);
        arm7 = LoadBiosFile<ARM7BIOSSize>(config.bios7Path);
        if (!arm9 || !arm7)
        {
            if (dsi)
            {
                err = "DSi mode needs the DS BIOS files (bios9.bin and bios7.bin) in the ClamshellDS/bios folder.";
                return false;
            }
            Log(LogLevel::Warn, "External DS BIOS missing, falling back to FreeBIOS\n");
            arm9 = std::make_unique<ARM9BIOSImage>(bios_arm9_bin);
            arm7 = std::make_unique<ARM7BIOSImage>(bios_arm7_bin);
        }
    }
    else
    {
        arm9 = std::make_unique<ARM9BIOSImage>(bios_arm9_bin);
        arm7 = std::make_unique<ARM7BIOSImage>(bios_arm7_bin);
    }

    // Firmware
    std::optional<Firmware> firmware;
    std::string fwPath = dsi ? config.dsiFirmwarePath : (config.useExternalDSBios ? config.firmwarePath : "");
    if (!fwPath.empty())
    {
        if (Platform::FileHandle* f = Platform::OpenFile(fwPath, Platform::FileMode::Read))
        {
            firmware.emplace(f);
            Platform::CloseFile(f);
            if (!firmware->Buffer())
                firmware.reset();
            else
                activeFirmwarePath = fwPath;
        }
    }
    if (!firmware)
    {
        if (dsi)
        {
            err = "DSi mode needs your DSi firmware dump (dsi_firmware.bin) in the ClamshellDS/bios folder.";
            return false;
        }
        firmware.emplace(0);

        auto& user = firmware->GetEffectiveUserData();
        std::u16string name;
        for (char c : config.username)
        {
            if (name.size() >= 10) break;
            name.push_back((char16_t)(unsigned char)c);
        }
        if (!name.empty())
        {
            memset(user.Nickname, 0, sizeof(user.Nickname));
            memcpy(user.Nickname, name.data(), name.size() * sizeof(char16_t));
            user.NameLength = (u16)name.size();
        }
        user.Settings &= ~Firmware::Language::Reserved;
        user.Settings |= (Firmware::Language)std::clamp(config.language, 0, 5);
        user.FavoriteColor = (u8)std::clamp(config.favoriteColor, 0, 15);
        user.BirthdayMonth = (u8)std::clamp(config.birthMonth, 1, 12);
        user.BirthdayDay = (u8)std::clamp(config.birthDay, 1, 31);
        firmware->UpdateChecksums();
    }

    std::optional<JITArgs> jit = std::nullopt;
#ifdef JIT_ENABLED
    if (config.jit)
    {
        JITArgs j;
        j.MaxBlockSize = (unsigned)std::clamp(config.jitBlockSize, 1, 32);
        j.LiteralOptimizations = true;
        j.BranchOptimizations = true;
        j.FastMemory = true;
        jit = j;
    }
#endif

    NDSArgs ndsargs {
        std::move(arm9),
        std::move(arm7),
        std::move(*firmware),
        jit,
        AudioBitDepth::Auto,
        AudioInterpolation::None,
        (double)kAudioRate,
        std::nullopt,
    };

    if (dsi)
    {
        auto arm9i = LoadBiosFile<DSiBIOSSize>(config.dsiBios9Path);
        auto arm7i = LoadBiosFile<DSiBIOSSize>(config.dsiBios7Path);
        if (!arm9i || !arm7i)
        {
            err = "DSi mode needs the DSi BIOS files (dsi_bios9.bin and dsi_bios7.bin) in the ClamshellDS/bios folder.";
            return false;
        }

        Platform::FileHandle* nandFile = Platform::OpenFile(config.dsiNandPath, Platform::FileMode::ReadWriteExisting);
        if (!nandFile)
        {
            err = "DSi mode needs your DSi NAND dump (dsi_nand.bin) in the ClamshellDS/bios folder.";
            return false;
        }

        DSi_NAND::NANDImage nand(nandFile, &(*arm7i)[0x8308]);
        if (!nand)
        {
            err = "The DSi NAND dump could not be read. Make sure it matches your DSi BIOS dump.";
            return false;
        }

        {
            auto mount = DSi_NAND::NANDMount(nand);
            if (!mount)
            {
                err = "The DSi NAND dump could not be mounted.";
                return false;
            }
            DSi_NAND::DSiFirmwareSystemSettings settings {};
            if (mount.ReadUserData(settings))
            {
                // Touchscreen calibration matching our touch input mapping.
                settings.TouchCalibrationADC1 = {0, 0};
                settings.TouchCalibrationPixel1 = {0, 0};
                settings.TouchCalibrationADC2 = {255 << 4, 191 << 4};
                settings.TouchCalibrationPixel2 = {255, 191};
                settings.UpdateHash();
                mount.ApplyUserData(settings);
            }
        }

        DSiArgs dsiargs {
            std::move(ndsargs),
            std::move(arm9i),
            std::move(arm7i),
            std::move(nand),
            std::nullopt,
            false,
            false,
        };
        nds = new DSi(std::move(dsiargs), this);
    }
    else
    {
        nds = new NDS(std::move(ndsargs), this);
    }

    nds->GPU.SetRenderer3D(std::make_unique<SoftRenderer>());
    static_cast<SoftRenderer&>(nds->GPU.GetRenderer3D()).SetThreaded(config.threaded3D, nds->GPU);
    nds->Reset();
    return true;
}

void EmuCore::DestroyConsole()
{
    if (nds)
    {
        delete nds;
        nds = nullptr;
    }
}

void EmuCore::SyncClock()
{
    time_t t = time(nullptr);
    struct tm lt;
    localtime_r(&t, &lt);
    nds->RTC.SetDateTime(lt.tm_year + 1900, lt.tm_mon + 1, lt.tm_mday, lt.tm_hour, lt.tm_min, lt.tm_sec);
}

std::string EmuCore::LoadGame(const std::string& rom, const std::string& save)
{
    Shutdown();

    std::unique_ptr<u8[]> romData;
    u32 romLen = 0;
    if (!ReadWholeFile(rom, romData, romLen))
        return "Could not read the ROM file:\n" + rom;

    std::unique_ptr<u8[]> saveData;
    u32 saveLen = 0;
    if (!save.empty())
        ReadWholeFile(save, saveData, saveLen, 64 * 1024 * 1024);

    std::string err;
    if (!CreateConsole(err))
        return err;

    NDSCart::NDSCartArgs cartargs {
        std::nullopt,
        std::move(saveData),
        saveLen,
    };
    auto cart = NDSCart::ParseROM(std::move(romData), romLen, this, std::move(cartargs));
    if (!cart)
    {
        DestroyConsole();
        return "This file is not a valid DS ROM:\n" + rom;
    }

    romPath = rom;
    savePath = save;
    {
        std::lock_guard<std::mutex> lk(saveLock);
        pendingSave.clear();
        saveDirty = false;
    }

    nds->SetNDSCart(std::move(cart));
    nds->Reset();
    if (config.directBoot || nds->NeedsDirectBoot())
        nds->SetupDirectBoot(FileName(rom));

    return "";
}

std::string EmuCore::BootFirmware()
{
    Shutdown();

    std::string err;
    if (!CreateConsole(err))
        return err;

    romPath.clear();
    savePath.clear();

    if (nds->NeedsDirectBoot())
    {
        DestroyConsole();
        if (config.consoleType == 1)
            return "The DSi firmware could not be booted. Check your DSi BIOS, firmware and NAND dumps.";
        return "Booting the DS menu needs real DS BIOS and firmware dumps (bios9.bin, bios7.bin, firmware.bin) and 'Use DS BIOS files' enabled.";
    }
    nds->Reset();
    return "";
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

void EmuCore::Start()
{
    if (!nds || running.load()) return;

    SyncClock();
    batteryDirty = true;
    nds->Start();

    quit = false;
    paused = false;
    stopReason = -1;
    running = true;
    {
        std::lock_guard<std::mutex> lk(frameLock);
        for (auto& f : frame) f.assign(256 * 192, 0xFF000000);
        haveFrame = false;
    }

    OpenAudio();
    emuThread = std::thread(&EmuCore::EmuThreadMain, this);
    renderThread = std::thread(&EmuCore::RenderThreadMain, this);
}

void EmuCore::Pause()
{
    if (!running) return;
    paused = true;
    RunOnEmuThread([this] { FlushSave(true); });
    if (audioStream) AAudioStream_requestPause(audioStream);
}

void EmuCore::Resume()
{
    if (!running) return;
    if (audioStream)
    {
        AAudioStream_requestFlush(audioStream);
        AAudioStream_requestStart(audioStream);
    }
    {
        std::lock_guard<std::mutex> lk(cmdLock);
        paused = false;
    }
    cmdCv.notify_all();
}

void EmuCore::Shutdown()
{
    if (running.load())
    {
        {
            std::lock_guard<std::mutex> lk(cmdLock);
            quit = true;
        }
        cmdCv.notify_all();
        audioCv.notify_all();
        {
            std::lock_guard<std::mutex> lk(frameLock);
            frameCv.notify_all();
        }
        if (emuThread.joinable()) emuThread.join();
        if (renderThread.joinable()) renderThread.join();
        running = false;
    }

    CloseAudio();
    if (nds) FlushSave(true);
    DestroyConsole();
}

void EmuCore::Reset()
{
    RunOnEmuThread([this] {
        if (!nds) return;
        FlushSave(true);
        nds->Reset();
        if (!romPath.empty() && (config.directBoot || nds->NeedsDirectBoot()))
            nds->SetupDirectBoot(FileName(romPath));
        SyncClock();
        batteryDirty = true;
        nds->Start();
    });
}

void EmuCore::RunOnEmuThread(const std::function<void()>& fn)
{
    if (!running.load() || std::this_thread::get_id() == emuThread.get_id())
    {
        fn();
        return;
    }
    auto task = std::make_shared<std::packaged_task<void()>>(fn);
    auto fut = task->get_future();
    {
        std::lock_guard<std::mutex> lk(cmdLock);
        commands.emplace_back([task] { (*task)(); });
    }
    cmdCv.notify_all();
    audioCv.notify_all();
    fut.wait();
}

void EmuCore::OnCoreStop(int reason)
{
    stopReason = reason;
}

// ---------------------------------------------------------------------------
// Input
// ---------------------------------------------------------------------------

void EmuCore::SetTouch(bool down, int x, int y)
{
    touchX = std::clamp(x, 0, 255);
    touchY = std::clamp(y, 0, 191);
    touching = down;
}

void EmuCore::SetBattery(int percent, bool charging)
{
    batteryPercent = std::clamp(percent, 0, 100);
    batteryCharging = charging;
    batteryDirty = true;
}

// ---------------------------------------------------------------------------
// Emulation thread
// ---------------------------------------------------------------------------

void EmuCore::EmuThreadMain()
{
    setpriority(PRIO_PROCESS, gettid(), -10);

    using clock = std::chrono::steady_clock;
    const auto frameDuration = std::chrono::duration_cast<clock::duration>(std::chrono::duration<double>(1.0 / kNdsFps));
    auto nextFrame = clock::now();
    auto fpsStart = clock::now();
    int fpsFrames = 0;
    bool lastLid = false;

    while (true)
    {
        std::deque<std::function<void()>> pending;
        {
            std::unique_lock<std::mutex> lk(cmdLock);
            if (quit) break;
            if (paused || !nds->IsRunning())
            {
                cmdCv.wait_for(lk, std::chrono::milliseconds(100), [this] {
                    return quit || !commands.empty() || !paused;
                });
                nextFrame = clock::now();
            }
            pending.swap(commands);
        }
        for (auto& c : pending) c();
        if (quit) break;
        if (paused || !nds->IsRunning())
        {
            FlushSave(false);
            continue;
        }

        // input
        nds->SetKeyMask(~keysPressed.load() & 0xFFF);
        if (touching)
            nds->TouchScreen((u16)touchX.load(), (u16)touchY.load());
        else
            nds->ReleaseScreen();

        bool lid = lidClosed.load();
        if (lid != lastLid)
        {
            nds->SetLidClosed(lid);
            lastLid = lid;
        }

        if (batteryDirty.exchange(false))
        {
            int pct = batteryPercent.load();
            if (nds->ConsoleType == 1)
            {
                u8 level = pct > 87 ? DSi_BPTWL::batteryLevel_Full
                         : pct > 62 ? DSi_BPTWL::batteryLevel_ThreeQuarters
                         : pct > 37 ? DSi_BPTWL::batteryLevel_Half
                         : pct > 12 ? DSi_BPTWL::batteryLevel_Low
                         : pct > 4 ? DSi_BPTWL::batteryLevel_AlmostEmpty
                         : DSi_BPTWL::batteryLevel_Critical;
                auto* bptwl = static_cast<DSi*>(nds)->I2C.GetBPTWL();
                bptwl->SetBatteryLevel(level);
                bptwl->SetBatteryCharging(batteryCharging.load());
            }
            else
            {
                nds->SPI.GetPowerMan()->SetBatteryLevelOkay(pct > 12);
            }
        }

        nds->RunFrame();

        {
            std::lock_guard<std::mutex> lk(frameLock);
            int fb = nds->GPU.FrontBuffer;
            for (int s = 0; s < 2; s++)
            {
                const u32* src = nds->GPU.Framebuffer[fb][s].get();
                if (src) memcpy(frame[s].data(), src, 256 * 192 * 4);
            }
            haveFrame = true;
            frameSerial++;
        }
        frameCv.notify_one();

        FlushSave(false);

        if (audioRestart.exchange(false))
        {
            CloseAudio();
            OpenAudio();
        }

        fpsFrames++;
        auto now = clock::now();
        if (now - fpsStart >= std::chrono::seconds(1))
        {
            double secs = std::chrono::duration<double>(now - fpsStart).count();
            fps = (int)(fpsFrames / secs + 0.5);
            fpsFrames = 0;
            fpsStart = now;
        }

        if (fastForward)
        {
            nextFrame = clock::now();
            continue;
        }

        // Audio sync: don't run more than ~3 frames ahead of the audio device.
        if (config.audioSync && audioStream)
        {
            std::unique_lock<std::mutex> lk(audioLock);
            audioCv.wait_for(lk, std::chrono::milliseconds(40), [this] {
                return quit || nds->SPU.GetOutputSize() < (kAudioRate / 60) * 3;
            });
        }

        // Frame limiter
        nextFrame += frameDuration;
        now = clock::now();
        if (nextFrame > now)
            std::this_thread::sleep_until(nextFrame);
        else if (now - nextFrame > frameDuration * 4)
            nextFrame = now; // fell behind: don't try to catch up in a burst
    }

    FlushSave(true);
}

// ---------------------------------------------------------------------------
// Video
// ---------------------------------------------------------------------------

void EmuCore::SetSurface(int slot, ANativeWindow* window, int screen)
{
    if (slot < 0 || slot > 1)
    {
        if (window) ANativeWindow_release(window);
        return;
    }
    {
        std::lock_guard<std::mutex> lk(surfaceLock);
        Slot& s = slots[slot];
        if (s.window && s.window != window)
            ANativeWindow_release(s.window);
        s.window = window;
        s.screen = screen & 1;
        s.geomScale = 0;
    }
    std::lock_guard<std::mutex> lk(frameLock);
    frameSerial++;
    frameCv.notify_all();
}

static inline u32 BgraToRgba(u32 c)
{
    return 0xFF000000 | ((c & 0xFF) << 16) | (c & 0xFF00) | ((c >> 16) & 0xFF);
}

void EmuCore::RenderThreadMain()
{
    setpriority(PRIO_PROCESS, gettid(), -4);

    std::vector<u32> local[2];
    local[0].resize(256 * 192);
    local[1].resize(256 * 192);
    u64 lastSerial = 0;

    while (true)
    {
        {
            std::unique_lock<std::mutex> lk(frameLock);
            frameCv.wait(lk, [&] { return quit.load() || frameSerial != lastSerial; });
            if (quit) break;
            lastSerial = frameSerial;
            if (!haveFrame) continue;
            memcpy(local[0].data(), frame[0].data(), 256 * 192 * 4);
            memcpy(local[1].data(), frame[1].data(), 256 * 192 * 4);
        }

        std::lock_guard<std::mutex> lk(surfaceLock);
        const int sc = scale.load();
        for (auto& s : slots)
        {
            if (!s.window) continue;
            if (s.geomScale != sc)
            {
                ANativeWindow_setBuffersGeometry(s.window, 256 * sc, 192 * sc, WINDOW_FORMAT_RGBX_8888);
                s.geomScale = sc;
            }

            ANativeWindow_Buffer buf;
            if (ANativeWindow_lock(s.window, &buf, nullptr) != 0)
                continue;

            const u32* src = local[s.screen].data();
            const int w = std::min(buf.width, 256 * sc);
            const int h = std::min(buf.height, 192 * sc);
            u32* bits = static_cast<u32*>(buf.bits);

            for (int y = 0; y < h; y++)
            {
                u32* dst = bits + (size_t)y * buf.stride;
                if (y % sc != 0)
                {
                    memcpy(dst, dst - buf.stride, w * 4);
                    continue;
                }
                const u32* row = src + (y / sc) * 256;
                if (sc == 1)
                {
                    for (int x = 0; x < w; x++) dst[x] = BgraToRgba(row[x]);
                }
                else
                {
                    for (int x = 0; x < w; x++) dst[x] = BgraToRgba(row[x / sc]);
                }
            }
            ANativeWindow_unlockAndPost(s.window);
        }
    }
}

bool EmuCore::CopyScreen(int screen, u32* out)
{
    std::lock_guard<std::mutex> lk(frameLock);
    if (!haveFrame || frame[screen & 1].size() < 256 * 192) return false;
    memcpy(out, frame[screen & 1].data(), 256 * 192 * 4);
    return true;
}

// ---------------------------------------------------------------------------
// Save data
// ---------------------------------------------------------------------------

void EmuCore::OnNDSSaveWritten(const u8* data, u32 len)
{
    std::lock_guard<std::mutex> lk(saveLock);
    pendingSave.assign(data, data + len);
    saveDirty = true;
    saveDirtyTime = NowMs();
}

void EmuCore::FlushSave(bool force)
{
    std::vector<u8> toWrite;
    {
        std::lock_guard<std::mutex> lk(saveLock);
        if (!saveDirty || savePath.empty()) return;
        if (!force && NowMs() - saveDirtyTime < 1500) return;
        toWrite = pendingSave;
        saveDirty = false;
    }

    std::string tmp = savePath + ".tmp";
    FILE* f = fopen(tmp.c_str(), "wb");
    if (!f)
    {
        Log(LogLevel::Error, "Could not write save file %s\n", savePath.c_str());
        return;
    }
    bool ok = fwrite(toWrite.data(), toWrite.size(), 1, f) == 1;
    ok &= fflush(f) == 0;
    fsync(fileno(f));
    fclose(f);
    if (ok)
        rename(tmp.c_str(), savePath.c_str());
    else
        unlink(tmp.c_str());
}

void EmuCore::OnFirmwareWritten(const u8* data, u32 len)
{
    if (activeFirmwarePath.empty()) return;
    FILE* f = fopen(activeFirmwarePath.c_str(), "r+b");
    if (!f) return;
    fwrite(data, len, 1, f);
    fclose(f);
}

// ---------------------------------------------------------------------------
// Save states
// ---------------------------------------------------------------------------

bool EmuCore::SaveState(const std::string& path)
{
    bool ok = false;
    RunOnEmuThread([&] {
        if (!nds) return;
        Savestate state;
        if (state.Error) return;
        if (!nds->DoSavestate(&state) || state.Error) return;
        FILE* f = fopen(path.c_str(), "wb");
        if (!f) return;
        ok = fwrite(state.Buffer(), state.Length(), 1, f) == 1;
        fclose(f);
    });
    return ok;
}

bool EmuCore::LoadState(const std::string& path)
{
    bool ok = false;
    RunOnEmuThread([&] {
        if (!nds) return;
        std::unique_ptr<u8[]> data;
        u32 len = 0;
        if (!ReadWholeFile(path, data, len, 256 * 1024 * 1024)) return;

        Savestate backup;
        bool haveBackup = !backup.Error && nds->DoSavestate(&backup) && !backup.Error;

        Savestate state(data.get(), len, false);
        if (nds->DoSavestate(&state) && !state.Error)
        {
            ok = true;
        }
        else if (haveBackup)
        {
            Savestate restore(backup.Buffer(), backup.Length(), false);
            nds->DoSavestate(&restore);
        }
    });
    return ok;
}

// ---------------------------------------------------------------------------
// Audio (AAudio)
// ---------------------------------------------------------------------------

aaudio_data_callback_result_t EmuCore::AudioCallback(AAudioStream*, void* user, void* audioData, int32_t numFrames)
{
    auto* self = static_cast<EmuCore*>(user);
    auto* out = static_cast<s16*>(audioData);
    int got = 0;

    if (self->nds && !self->paused.load() && self->config.audioEnabled)
    {
        got = self->nds->SPU.ReadOutput(out, numFrames);
        const int vol = self->config.volume;
        if (vol < 256)
        {
            for (int i = 0; i < got * 2; i++)
                out[i] = (s16)((out[i] * vol) >> 8);
        }
    }

    if (got < numFrames)
    {
        // Underrun: hold the last sample to avoid clicks.
        s16 l = got > 0 ? out[(got - 1) * 2] : 0;
        s16 r = got > 0 ? out[(got - 1) * 2 + 1] : 0;
        for (int i = got; i < numFrames; i++)
        {
            out[i * 2] = l;
            out[i * 2 + 1] = r;
        }
    }

    self->audioCv.notify_one();
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void EmuCore::AudioErrorCallback(AAudioStream*, void* user, aaudio_result_t error)
{
    // Typically a device change (headphones plugged in). Reopen from the emu thread.
    if (error == AAUDIO_ERROR_DISCONNECTED)
        static_cast<EmuCore*>(user)->audioRestart = true;
}

void EmuCore::OpenAudio()
{
    if (audioStream) return;

    AAudioStreamBuilder* builder = nullptr;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK)
        return;

    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSampleRate(builder, kAudioRate);
    AAudioStreamBuilder_setChannelCount(builder, 2);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
#if defined(__ANDROID__)
    if (__builtin_available(android 28, *))
    {
        AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_GAME);
        AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_SONIFICATION);
    }
#endif
    AAudioStreamBuilder_setDataCallback(builder, &EmuCore::AudioCallback, this);
    AAudioStreamBuilder_setErrorCallback(builder, &EmuCore::AudioErrorCallback, this);

    AAudioStream* stream = nullptr;
    aaudio_result_t res = AAudioStreamBuilder_openStream(builder, &stream);
    AAudioStreamBuilder_delete(builder);
    if (res != AAUDIO_OK || !stream)
    {
        Log(LogLevel::Error, "AAudio open failed: %s\n", AAudio_convertResultToText(res));
        return;
    }

    int32_t burst = AAudioStream_getFramesPerBurst(stream);
    if (burst > 0)
        AAudioStream_setBufferSizeInFrames(stream, std::max(burst * 4, 1024));

    if (AAudioStream_requestStart(stream) != AAUDIO_OK)
    {
        AAudioStream_close(stream);
        return;
    }
    audioStream = stream;
}

void EmuCore::CloseAudio()
{
    if (!audioStream) return;
    AAudioStream_requestStop(audioStream);
    AAudioStream_close(audioStream);
    audioStream = nullptr;
}

}
