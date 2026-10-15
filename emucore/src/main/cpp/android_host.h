// The Android frontend's side of MesenCE: the emulator, the render window, the audio stream, the
// pads and the settings. jni.cpp drives it from Kotlin; android_host.cpp implements it.

#pragma once

#include <jni.h>
#include <cstdint>
#include <optional>
#include <string>
#include <utility>
#include <vector>

struct ANativeWindow;

namespace AndroidHost {

/// Called from JNI_OnLoad. [native_core] is a global reference to the Kotlin NativeCore class.
void SetJavaVM(JavaVM* vm, jclass native_core);

/// Sets up the folders under [data_dir] and the emulator. Once per process. Empty on success,
/// or why the core cannot run.
std::string Initialize(const std::string& data_dir);

/// The MesenCE version, e.g. "2.2.1".
std::string GetVersion();

/// Boots [path] and runs it until RequestShutdown(), blocking the calling thread. Starts from the
/// save state at [save_state] unless it is empty. Empty when it ran, or why it did not boot.
std::string RunSystem(const std::string& path, const std::string& save_state);

/// True from the moment the game has booted until it is stopped.
bool HasActiveSystem();

void RequestShutdown();
void RequestPause(bool paused);

/// Writes the running game's battery save now if it has changed (the core otherwise writes it
/// once it has been unchanged for a second, and when the game stops). True if it wrote anything.
/// Blocks.
bool FlushBatterySave();

/// Writes one value to the settings ("Section/Key"). Not applied to a running game until
/// CommitSettings().
void SetSetting(const std::string& section, const std::string& key, const std::string& value);
void CommitSettings();

/// The surface to render to, or null for none. Takes the reference [window] holds. Blocks
/// until the render thread has let go of the previous window.
void SetWindow(ANativeWindow* window, uint32_t width, uint32_t height);

/// Puts the picture at the top of a portrait surface, [top_margin] pixels down, when
/// [portrait_top]; otherwise in the middle.
void SetDisplayLayout(bool portrait_top, uint32_t top_margin);

/// Presses ([value] > 0.5) or releases button [index] (SnesController::Buttons) of the pad in [port].
void SetPadBind(uint32_t port, uint32_t index, float value);

void SetFastForward(bool enabled);

/// Saves the running game to the state file at [path], or loads it from there. Blocks until done.
/// Empty on success, or why it failed.
std::string SaveState(const std::string& path);
std::string LoadState(const std::string& path);

/// The rom name the state at [path] was saved from and a PNG of its screen; false if it is not
/// a save state.
bool GetSaveStateInfo(const std::string& path, std::string* rom_name, std::vector<uint8_t>* png);

/// A point the running game can rewind to.
struct RewindPoint
{
	uint64_t age_ms;
	std::vector<uint8_t> png;
};

/// The rewind history, newest first.
std::vector<RewindPoint> GetRewindPoints();

/// Goes back to the point at [index] in GetRewindPoints(), and drops the newer ones. Blocks.
bool LoadRewindPoint(uint32_t index);

/// Writes what is on screen to [path] as a PNG, on a thread of its own.
void SaveScreenshot(const std::string& path);

/// The cheats to run: Game Genie ("XXXX-XXXX") and Pro Action Replay ("AAAAAAVV") codes. They
/// go into the running game now, and into every game booted after it.
void SetCheats(const std::vector<std::string>& codes);

/// True when [code] is a code the core takes.
bool IsValidCheat(const std::string& code);

/// [length] bytes of work RAM from [address], or nothing with no game or out of range. Blocks.
std::optional<std::vector<uint8_t>> ReadMemory(uint32_t address, uint32_t length);

/// Writes [value] as [size] (1, 2 or 4) bytes at [address] of work RAM. Blocks.
bool WriteMemory(uint32_t address, uint32_t size, uint32_t value);

float GetFps();
uint64_t GetFrameNumber();

/// "SNES-xxxxxxxx", the CRC32 of the ROM at [path] (without a copier header, out of the archive
/// for a .zip or .7z), or empty if it cannot be read.
std::string GetGameSerial(const std::string& path);

} // namespace AndroidHost
