// The JNI functions of com.kira3.ametrine.emucore.NativeCore.

#include "android_host.h"

#include <android/native_window_jni.h>
#include <jni.h>

#include <algorithm>
#include <sstream>
#include <string>
#include <vector>

namespace {
std::string ToString(JNIEnv* env, jstring str)
{
	if(!str) {
		return {};
	}
	const char* chars = env->GetStringUTFChars(str, nullptr);
	std::string ret(chars);
	env->ReleaseStringUTFChars(str, chars);
	return ret;
}

jbyteArray ToByteArray(JNIEnv* env, const std::vector<uint8_t>& data)
{
	jbyteArray array = env->NewByteArray(static_cast<jsize>(data.size()));
	env->SetByteArrayRegion(array, 0, static_cast<jsize>(data.size()), reinterpret_cast<const jbyte*>(data.data()));
	return array;
}

/// Null for no error.
jstring ErrorToString(JNIEnv* env, const std::string& error)
{
	return error.empty() ? nullptr : env->NewStringUTF(error.c_str());
}
} // namespace

#define NATIVE_CORE(name) Java_com_kira3_ametrine_emucore_NativeCore_##name

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*)
{
	JNIEnv* env;
	if(vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
		return JNI_ERR;
	}

	jclass native_core = env->FindClass("com/kira3/ametrine/emucore/NativeCore");
	if(!native_core) {
		return JNI_ERR;
	}

	AndroidHost::SetJavaVM(vm, static_cast<jclass>(env->NewGlobalRef(native_core)));
	env->DeleteLocalRef(native_core);
	return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jstring JNICALL NATIVE_CORE(nativeInitialize)(JNIEnv* env, jclass, jstring data_dir)
{
	return ErrorToString(env, AndroidHost::Initialize(ToString(env, data_dir)));
}

extern "C" JNIEXPORT jstring JNICALL NATIVE_CORE(nativeGetBuildVersion)(JNIEnv* env, jclass)
{
	return env->NewStringUTF(AndroidHost::GetVersion().c_str());
}

extern "C" JNIEXPORT void JNICALL NATIVE_CORE(nativeSetSetting)(JNIEnv* env, jclass, jstring section, jstring key, jstring value)
{
	AndroidHost::SetSetting(ToString(env, section), ToString(env, key), ToString(env, value));
}

extern "C" JNIEXPORT void JNICALL NATIVE_CORE(nativeCommitSettings)(JNIEnv*, jclass)
{
	AndroidHost::CommitSettings();
}

extern "C" JNIEXPORT jstring JNICALL NATIVE_CORE(nativeRunSystem)(JNIEnv* env, jclass, jstring path, jstring save_state)
{
	return ErrorToString(env, AndroidHost::RunSystem(ToString(env, path), ToString(env, save_state)));
}

extern "C" JNIEXPORT jboolean JNICALL NATIVE_CORE(nativeHasActiveSystem)(JNIEnv*, jclass)
{
	return AndroidHost::HasActiveSystem();
}

extern "C" JNIEXPORT void JNICALL NATIVE_CORE(nativePause)(JNIEnv*, jclass, jboolean paused)
{
	AndroidHost::RequestPause(paused);
}

extern "C" JNIEXPORT void JNICALL NATIVE_CORE(nativeShutdown)(JNIEnv*, jclass)
{
	AndroidHost::RequestShutdown();
}

extern "C" JNIEXPORT jboolean JNICALL NATIVE_CORE(nativeFlushBatterySave)(JNIEnv*, jclass)
{
	return AndroidHost::FlushBatterySave();
}

extern "C" JNIEXPORT void JNICALL NATIVE_CORE(nativeSetSurface)(JNIEnv* env, jclass, jobject surface, jint width, jint height)
{
	ANativeWindow* window = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
	AndroidHost::SetWindow(window, window ? static_cast<uint32_t>(width) : 0, window ? static_cast<uint32_t>(height) : 0);
}

extern "C" JNIEXPORT void JNICALL NATIVE_CORE(nativeSetDisplayLayout)(JNIEnv*, jclass, jboolean portrait_top, jint top_margin)
{
	AndroidHost::SetDisplayLayout(portrait_top, static_cast<uint32_t>(std::max(top_margin, 0)));
}

extern "C" JNIEXPORT void JNICALL NATIVE_CORE(nativeSetPadBind)(JNIEnv*, jclass, jint port, jint index, jfloat value)
{
	if(port >= 0 && index >= 0) {
		AndroidHost::SetPadBind(static_cast<uint32_t>(port), static_cast<uint32_t>(index), value);
	}
}

extern "C" JNIEXPORT void JNICALL NATIVE_CORE(nativeSetFastForward)(JNIEnv*, jclass, jboolean enabled)
{
	AndroidHost::SetFastForward(enabled);
}

extern "C" JNIEXPORT jfloat JNICALL NATIVE_CORE(nativeGetFps)(JNIEnv*, jclass)
{
	return AndroidHost::GetFps();
}

extern "C" JNIEXPORT jlong JNICALL NATIVE_CORE(nativeGetFrameNumber)(JNIEnv*, jclass)
{
	return static_cast<jlong>(AndroidHost::GetFrameNumber());
}

extern "C" JNIEXPORT jstring JNICALL NATIVE_CORE(nativeGetGameSerial)(JNIEnv* env, jclass, jstring path)
{
	std::string serial = AndroidHost::GetGameSerial(ToString(env, path));
	return serial.empty() ? nullptr : env->NewStringUTF(serial.c_str());
}

extern "C" JNIEXPORT jstring JNICALL NATIVE_CORE(nativeSaveState)(JNIEnv* env, jclass, jstring path)
{
	return ErrorToString(env, AndroidHost::SaveState(ToString(env, path)));
}

extern "C" JNIEXPORT jstring JNICALL NATIVE_CORE(nativeLoadState)(JNIEnv* env, jclass, jstring path)
{
	return ErrorToString(env, AndroidHost::LoadState(ToString(env, path)));
}

/// [rom name, PNG screenshot or null] from a save state's header, or null if [path] is not a save state.
extern "C" JNIEXPORT jobjectArray JNICALL NATIVE_CORE(nativeGetSaveStateInfo)(JNIEnv* env, jclass, jstring jpath)
{
	std::string name;
	std::vector<uint8_t> png;
	if(!AndroidHost::GetSaveStateInfo(ToString(env, jpath), &name, &png)) {
		return nullptr;
	}

	jclass object_class = env->FindClass("java/lang/Object");
	jobjectArray ret = env->NewObjectArray(2, object_class, nullptr);
	env->DeleteLocalRef(object_class);

	jstring jname = env->NewStringUTF(name.c_str());
	env->SetObjectArrayElement(ret, 0, jname);
	env->DeleteLocalRef(jname);
	if(!png.empty()) {
		jbyteArray array = ToByteArray(env, png);
		env->SetObjectArrayElement(ret, 1, array);
		env->DeleteLocalRef(array);
	}
	return ret;
}

/// [ages in ms (long[]), PNG thumbnails (byte[][], an entry null when there is none)], newest first.
extern "C" JNIEXPORT jobjectArray JNICALL NATIVE_CORE(nativeGetRewindPoints)(JNIEnv* env, jclass)
{
	const std::vector<AndroidHost::RewindPoint> points = AndroidHost::GetRewindPoints();
	const jsize count = static_cast<jsize>(points.size());

	jlongArray ages = env->NewLongArray(count);
	jclass byte_array_class = env->FindClass("[B");
	jobjectArray thumbnails = env->NewObjectArray(count, byte_array_class, nullptr);
	env->DeleteLocalRef(byte_array_class);
	for(jsize i = 0; i < count; i++) {
		const jlong age = static_cast<jlong>(points[i].age_ms);
		env->SetLongArrayRegion(ages, i, 1, &age);
		if(!points[i].png.empty()) {
			jbyteArray png = ToByteArray(env, points[i].png);
			env->SetObjectArrayElement(thumbnails, i, png);
			env->DeleteLocalRef(png);
		}
	}

	jclass object_class = env->FindClass("java/lang/Object");
	jobjectArray ret = env->NewObjectArray(2, object_class, nullptr);
	env->DeleteLocalRef(object_class);
	env->SetObjectArrayElement(ret, 0, ages);
	env->SetObjectArrayElement(ret, 1, thumbnails);
	env->DeleteLocalRef(ages);
	env->DeleteLocalRef(thumbnails);
	return ret;
}

extern "C" JNIEXPORT jboolean JNICALL NATIVE_CORE(nativeLoadRewindPoint)(JNIEnv*, jclass, jint index)
{
	return index >= 0 && AndroidHost::LoadRewindPoint(static_cast<uint32_t>(index));
}

extern "C" JNIEXPORT void JNICALL NATIVE_CORE(nativeSaveScreenshot)(JNIEnv* env, jclass, jstring path)
{
	AndroidHost::SaveScreenshot(ToString(env, path));
}

/// [codes]: one cheat code per line, of every enabled cheat.
extern "C" JNIEXPORT void JNICALL NATIVE_CORE(nativeSetCheats)(JNIEnv* env, jclass, jobjectArray codes)
{
	std::vector<std::string> lines;
	const jsize count = env->GetArrayLength(codes);
	for(jsize i = 0; i < count; i++) {
		jstring code = static_cast<jstring>(env->GetObjectArrayElement(codes, i));
		std::istringstream in(ToString(env, code));
		env->DeleteLocalRef(code);
		std::string line;
		while(std::getline(in, line)) {
			if(!line.empty()) {
				lines.push_back(line);
			}
		}
	}
	AndroidHost::SetCheats(lines);
}

extern "C" JNIEXPORT jboolean JNICALL NATIVE_CORE(nativeIsValidCheat)(JNIEnv* env, jclass, jstring code)
{
	return AndroidHost::IsValidCheat(ToString(env, code));
}

extern "C" JNIEXPORT jbyteArray JNICALL NATIVE_CORE(nativeReadMemory)(JNIEnv* env, jclass, jint address, jint length)
{
	if(address < 0 || length < 0) {
		return nullptr;
	}
	const auto data = AndroidHost::ReadMemory(static_cast<uint32_t>(address), static_cast<uint32_t>(length));
	return data.has_value() ? ToByteArray(env, *data) : nullptr;
}

extern "C" JNIEXPORT jboolean JNICALL NATIVE_CORE(nativeWriteMemory)(JNIEnv*, jclass, jint address, jint size, jint value)
{
	return address >= 0 && AndroidHost::WriteMemory(static_cast<uint32_t>(address), static_cast<uint32_t>(size), static_cast<uint32_t>(value));
}
