/*
    Clamshell DS - JNI bindings for com.clamshellds.emu.NativeBridge.
    Licensed under the GNU GPL v3 or later.
*/

#include <jni.h>
#include <android/native_window_jni.h>

#include <string>

#include "EmuCore.h"

using clamshell::EmuConfig;
using clamshell::EmuCore;
using melonDS::u32;

static std::string JStr(JNIEnv* env, jstring s)
{
    if (!s) return "";
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

static jstring ErrOrNull(JNIEnv* env, const std::string& err)
{
    return err.empty() ? nullptr : env->NewStringUTF(err.c_str());
}

#define JNI_FN(name) Java_com_clamshellds_emu_NativeBridge_##name

extern "C" {

JNIEXPORT void JNICALL JNI_FN(setBasePath)(JNIEnv* env, jobject, jstring path)
{
    EmuCore::Get().SetBasePath(JStr(env, path));
}

JNIEXPORT void JNICALL JNI_FN(configure)(JNIEnv* env, jobject,
    jint consoleType, jboolean jit, jboolean fastMem, jboolean threaded3D, jboolean directBoot,
    jboolean audioSync, jboolean audioEnabled, jint volume, jint scale,
    jstring username, jint language, jint favoriteColor, jint birthMonth, jint birthDay,
    jboolean useExternalDSBios, jstring biosDir)
{
    EmuConfig c;
    c.consoleType = consoleType;
    c.jit = jit;
    c.fastMem = fastMem;
    c.threaded3D = threaded3D;
    c.directBoot = directBoot;
    c.audioSync = audioSync;
    c.audioEnabled = audioEnabled;
    c.volume = volume;
    c.scale = scale;
    c.username = JStr(env, username);
    c.language = language;
    c.favoriteColor = favoriteColor;
    c.birthMonth = birthMonth;
    c.birthDay = birthDay;
    c.useExternalDSBios = useExternalDSBios;

    std::string dir = JStr(env, biosDir);
    c.bios9Path = dir + "/bios9.bin";
    c.bios7Path = dir + "/bios7.bin";
    c.firmwarePath = dir + "/firmware.bin";
    c.dsiBios9Path = dir + "/dsi_bios9.bin";
    c.dsiBios7Path = dir + "/dsi_bios7.bin";
    c.dsiFirmwarePath = dir + "/dsi_firmware.bin";
    c.dsiNandPath = dir + "/dsi_nand.bin";
    EmuCore::Get().Configure(c);
}

JNIEXPORT jstring JNICALL JNI_FN(loadGame)(JNIEnv* env, jobject, jstring rom, jstring save)
{
    return ErrOrNull(env, EmuCore::Get().LoadGame(JStr(env, rom), JStr(env, save)));
}

JNIEXPORT jstring JNICALL JNI_FN(bootFirmware)(JNIEnv* env, jobject)
{
    return ErrOrNull(env, EmuCore::Get().BootFirmware());
}

JNIEXPORT void JNICALL JNI_FN(start)(JNIEnv*, jobject) { EmuCore::Get().Start(); }
JNIEXPORT void JNICALL JNI_FN(pause)(JNIEnv*, jobject) { EmuCore::Get().Pause(); }
JNIEXPORT void JNICALL JNI_FN(resume)(JNIEnv*, jobject) { EmuCore::Get().Resume(); }
JNIEXPORT void JNICALL JNI_FN(shutdown)(JNIEnv*, jobject) { EmuCore::Get().Shutdown(); }
JNIEXPORT void JNICALL JNI_FN(reset)(JNIEnv*, jobject) { EmuCore::Get().Reset(); }

JNIEXPORT jboolean JNICALL JNI_FN(isRunning)(JNIEnv*, jobject) { return EmuCore::Get().IsRunning(); }
JNIEXPORT jint JNICALL JNI_FN(getStopReason)(JNIEnv*, jobject) { return EmuCore::Get().StopReason(); }
JNIEXPORT jboolean JNICALL JNI_FN(isJitActive)(JNIEnv*, jobject) { return EmuCore::Get().JitActive(); }
JNIEXPORT jint JNICALL JNI_FN(getFps)(JNIEnv*, jobject) { return EmuCore::Get().Fps(); }

JNIEXPORT void JNICALL JNI_FN(setSurface)(JNIEnv* env, jobject, jint slot, jobject surface, jint screen)
{
    ANativeWindow* win = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    EmuCore::Get().SetSurface(slot, win, screen);
}

JNIEXPORT void JNICALL JNI_FN(setKeys)(JNIEnv*, jobject, jint mask) { EmuCore::Get().SetKeys((u32)mask); }

JNIEXPORT void JNICALL JNI_FN(setTouch)(JNIEnv*, jobject, jboolean down, jint x, jint y)
{
    EmuCore::Get().SetTouch(down, x, y);
}

JNIEXPORT void JNICALL JNI_FN(setFastForward)(JNIEnv*, jobject, jboolean ff) { EmuCore::Get().SetFastForward(ff); }
JNIEXPORT void JNICALL JNI_FN(setLidClosed)(JNIEnv*, jobject, jboolean c) { EmuCore::Get().SetLidClosed(c); }
JNIEXPORT void JNICALL JNI_FN(setBattery)(JNIEnv*, jobject, jint pct, jboolean chg) { EmuCore::Get().SetBattery(pct, chg); }
JNIEXPORT void JNICALL JNI_FN(setScale)(JNIEnv*, jobject, jint s) { EmuCore::Get().SetScale(s); }

JNIEXPORT jboolean JNICALL JNI_FN(saveState)(JNIEnv* env, jobject, jstring path)
{
    return EmuCore::Get().SaveState(JStr(env, path));
}

JNIEXPORT jboolean JNICALL JNI_FN(loadState)(JNIEnv* env, jobject, jstring path)
{
    return EmuCore::Get().LoadState(JStr(env, path));
}

JNIEXPORT jboolean JNICALL JNI_FN(copyScreen)(JNIEnv* env, jobject, jint screen, jintArray out)
{
    if (!out || env->GetArrayLength(out) < 256 * 192) return JNI_FALSE;
    jint* dst = env->GetIntArrayElements(out, nullptr);
    bool ok = EmuCore::Get().CopyScreen(screen, reinterpret_cast<u32*>(dst));
    env->ReleaseIntArrayElements(out, dst, 0);
    return ok;
}

}
