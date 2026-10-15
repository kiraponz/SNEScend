// MesenCE on Android: the emulator, a GLES renderer on a thread of its own, an AAudio stream, the
// pads and the settings. See android_host.h.

#include "android_host.h"

#include "Core/Shared/Emulator.h"
#include "Core/Shared/EmuSettings.h"
#include "Core/Shared/SettingTypes.h"
#include "Core/Shared/KeyManager.h"
#include "Core/Shared/MessageManager.h"
#include "Core/Shared/NotificationManager.h"
#include "Core/Shared/SaveStateManager.h"
#include "Core/Shared/CheatManager.h"
#include "Core/Shared/FirmwareHelper.h"
#include "Core/Shared/RenderedFrame.h"
#include "Core/Shared/MemoryType.h"
#include "Core/Shared/Audio/SoundMixer.h"
#include "Core/Shared/Audio/BaseSoundManager.h"
#include "Core/Shared/Video/VideoRenderer.h"
#include "Core/Shared/Video/VideoDecoder.h"
#include "Core/Shared/Interfaces/IRenderingDevice.h"
#include "Core/Shared/Interfaces/IKeyManager.h"
#include "Core/Shared/Interfaces/IMessageManager.h"
#include "Core/Shared/Interfaces/INotificationListener.h"
#include "Core/SNES/Input/SnesController.h"
#include "Utilities/ArchiveReader.h"
#include "Utilities/CRC32.h"
#include "Utilities/FolderUtilities.h"
#include "Utilities/PNGHelper.h"
#include "Utilities/VirtualFile.h"

#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <android/native_window.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <deque>
#include <map>
#include <mutex>
#include <sstream>
#include <thread>

#define LOG_TAG "AndroidHost"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

using Clock = std::chrono::steady_clock;

// ---- Java ----

JavaVM* s_vm = nullptr;
jclass s_native_core = nullptr;
jmethodID s_on_vm_paused_changed = nullptr;
jmethodID s_saf_open = nullptr;

/// The calling thread's JNIEnv, attaching it to the VM if it is not yet (core threads are not).
JNIEnv* GetEnv()
{
	JNIEnv* env = nullptr;
	if(s_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
		return env;
	}

	// Detached again when the thread exits.
	static thread_local struct Detacher
	{
		bool attached = false;
		~Detacher()
		{
			if(attached) {
				s_vm->DetachCurrentThread();
			}
		}
	} detacher;

	if(s_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
		return nullptr;
	}
	detacher.attached = true;
	return env;
}

void NotifyPaused(bool paused)
{
	JNIEnv* env = GetEnv();
	if(env) {
		env->CallStaticVoidMethod(s_native_core, s_on_vm_paused_changed, static_cast<jboolean>(paused));
		if(env->ExceptionCheck()) {
			env->ExceptionClear();
		}
	}
}

/// Reads the whole file at [path]: a /saf/ path through the app's SafFiles, else a plain file.
bool ReadWholeFile(const std::string& path, std::vector<uint8_t>& out)
{
	int fd = -1;
	if(path.rfind("/saf/", 0) == 0) {
		JNIEnv* env = GetEnv();
		if(!env) {
			return false;
		}
		jstring jpath = env->NewStringUTF(path.c_str());
		fd = env->CallStaticIntMethod(s_native_core, s_saf_open, jpath);
		env->DeleteLocalRef(jpath);
		if(env->ExceptionCheck()) {
			env->ExceptionClear();
			fd = -1;
		}
	} else {
		fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
	}
	if(fd < 0) {
		return false;
	}

	out.clear();
	uint8_t chunk[64 * 1024];
	while(true) {
		ssize_t n = read(fd, chunk, sizeof(chunk));
		if(n < 0) {
			close(fd);
			return false;
		}
		if(n == 0) {
			break;
		}
		out.insert(out.end(), chunk, chunk + n);
	}
	close(fd);
	return !out.empty();
}

std::string Lower(std::string s)
{
	std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) { return (char)std::tolower(c); });
	return s;
}

std::string ExtensionOf(const std::string& name)
{
	size_t slash = name.find_last_of('/');
	size_t dot = name.find_last_of('.');
	if(dot == std::string::npos || (slash != std::string::npos && dot < slash)) {
		return "";
	}
	return Lower(name.substr(dot));
}

const std::initializer_list<std::string> kSnesExtensions = { ".sfc", ".smc", ".swc", ".fig", ".bs", ".st" };

/// The ROM at [path] in [data], with its extension in [ext]; out of the archive for a .zip or .7z.
bool ReadRom(const std::string& path, std::vector<uint8_t>& data, std::string& ext, std::string& error)
{
	std::vector<uint8_t> file;
	if(!ReadWholeFile(path, file)) {
		error = "Could not read the game file";
		return false;
	}

	if(file.size() >= 2 && (memcmp(file.data(), "PK", 2) == 0 || memcmp(file.data(), "7z", 2) == 0)) {
		std::stringstream in(std::string(file.begin(), file.end()));
		unique_ptr<ArchiveReader> reader = ArchiveReader::GetReader(in);
		vector<string> roms = reader ? reader->GetFileList(kSnesExtensions) : vector<string>();
		if(roms.empty()) {
			error = "The archive holds no SNES game";
			return false;
		}
		if(!reader->ExtractFile(roms[0], data) || data.empty()) {
			error = "Could not extract " + roms[0];
			return false;
		}
		ext = ExtensionOf(roms[0]);
		return true;
	}

	data = std::move(file);
	ext = ExtensionOf(path);
	if(ext.empty()) {
		ext = ".sfc";
	}
	return true;
}

std::string SerialOf(const std::vector<uint8_t>& rom)
{
	// A copier header is the 512 bytes over a multiple of 1 KiB.
	size_t skip = (rom.size() % 1024 == 512) ? 512 : 0;
	vector<uint8_t> body(rom.begin() + skip, rom.end());
	char serial[16];
	snprintf(serial, sizeof(serial), "SNES-%08X", CRC32::GetCRC(body));
	return serial;
}

// ---- Settings ----

std::mutex s_settings_mutex;
std::map<std::string, std::string> s_settings;

std::string Setting(const std::string& id, const std::string& fallback)
{
	auto it = s_settings.find(id);
	return it == s_settings.end() ? fallback : it->second;
}

int SettingInt(const std::string& id, int fallback)
{
	auto it = s_settings.find(id);
	if(it == s_settings.end()) {
		return fallback;
	}
	try {
		return std::stoi(it->second);
	} catch(...) {
		return fallback;
	}
}

bool SettingBool(const std::string& id, bool fallback)
{
	auto it = s_settings.find(id);
	return it == s_settings.end() ? fallback : it->second == "true";
}

std::string s_data_dir;
unique_ptr<Emulator> s_emu;

// ---- Input ----

/// Key codes for the core's key mappings: 1 + port * 16 + SnesController::Buttons.
constexpr uint16_t KeyCode(uint32_t port, uint32_t button) { return (uint16_t)(1 + port * 16 + button); }
constexpr uint32_t kPorts = 2;
constexpr uint32_t kButtons = 12;

class AndroidKeyManager : public IKeyManager
{
public:
	std::atomic<bool> keys[1 + kPorts * 16] = {};

	void RefreshState() override {}
	void UpdateDevices() override {}
	bool IsMouseButtonPressed(MouseButton) override { return false; }
	bool IsKeyPressed(uint16_t keyCode) override { return keyCode < std::size(keys) && keys[keyCode]; }
	vector<uint16_t> GetPressedKeys() override { return {}; }
	string GetKeyName(uint16_t) override { return ""; }
	uint16_t GetKeyCode(string) override { return 0; }
	bool SetKeyState(uint16_t, bool) override { return false; }
	void ResetKeyState() override
	{
		for(auto& key : keys) {
			key = false;
		}
	}
	void SetDisabled(bool) override {}
};

AndroidKeyManager s_keys;

KeyMappingSet PadMapping(uint32_t port)
{
	KeyMappingSet set;
	KeyMapping& m = set.Mapping1;
	m.A = KeyCode(port, SnesController::A);
	m.B = KeyCode(port, SnesController::B);
	m.X = KeyCode(port, SnesController::X);
	m.Y = KeyCode(port, SnesController::Y);
	m.L = KeyCode(port, SnesController::L);
	m.R = KeyCode(port, SnesController::R);
	m.Select = KeyCode(port, SnesController::Select);
	m.Start = KeyCode(port, SnesController::Start);
	m.Up = KeyCode(port, SnesController::Up);
	m.Down = KeyCode(port, SnesController::Down);
	m.Left = KeyCode(port, SnesController::Left);
	m.Right = KeyCode(port, SnesController::Right);
	return set;
}

// ---- Messages ----

class LogMessageManager : public IMessageManager
{
public:
	void DisplayMessage(string title, string message) override
	{
		LOGI("%s: %s", title.c_str(), message.c_str());
	}
};

LogMessageManager s_messages;

/// Hears the firmware a game asks for and does not find.
class HostNotificationListener final : public INotificationListener
{
public:
	std::mutex mutex;
	std::string missing_firmware;

	void ProcessNotification(ConsoleNotificationType type, void* parameter) override
	{
		if(type == ConsoleNotificationType::MissingFirmware && parameter) {
			auto* msg = static_cast<MissingFirmwareMessage*>(parameter);
			std::lock_guard<std::mutex> lock(mutex);
			missing_firmware = msg->Filename ? msg->Filename : "";
			LOGW("Missing firmware: %s", missing_firmware.c_str());
		}
	}
};

shared_ptr<HostNotificationListener> s_listener;

// ---- Video ----

const char* kVertexShader = R"(
attribute vec2 aPos;
attribute vec2 aTex;
varying vec2 vTex;
void main() {
	gl_Position = vec4(aPos, 0.0, 1.0);
	vTex = aTex;
}
)";

// Sharp bilinear: each texel is a flat square, blended only across the border between two, so the
// picture stays crisp at any scale. A large uScale gives nearest, 1.0 plain bilinear.
const char* kFragmentShader = R"(
precision mediump float;
uniform sampler2D uTex;
uniform vec2 uTexSize;
uniform vec2 uScale;
varying vec2 vTex;
void main() {
	vec2 texel = vTex * uTexSize;
	vec2 base = floor(texel);
	vec2 dist = fract(texel) - 0.5;
	vec2 range = 0.5 - 0.5 / uScale;
	vec2 f = (dist - clamp(dist, -range, range)) * uScale + 0.5;
	gl_FragColor = vec4(texture2D(uTex, (base + f) / uTexSize).bgr, 1.0);
}
)";

/// Takes the frames the core makes and draws them to the window, on a thread of its own so
/// the window can come and go whether or not a game runs.
class GlesPresenter : public IRenderingDevice
{
public:
	void Start()
	{
		_thread = std::thread(&GlesPresenter::Loop, this);
	}

	// Core side (the video decoder thread).
	void UpdateFrame(RenderedFrame& frame) override
	{
		double aspect = (double)frame.Width / frame.Height;
		if(s_emu) {
			FrameInfo base = s_emu->GetVideoDecoder()->GetBaseFrameInfo(true);
			if(base.Width && base.Height) {
				aspect = s_emu->GetSettings()->GetAspectRatio(s_emu->GetRegion(), base);
			}
		}
		auto now = Clock::now();
		_fpsFrames++;
		double elapsed = std::chrono::duration<double>(now - _fpsSince).count();
		if(elapsed >= 1.0) {
			_fps = (float)(_fpsFrames / elapsed);
			_fpsFrames = 0;
			_fpsSince = now;
		}

		std::lock_guard<std::mutex> lock(_mutex);
		_frame.assign((uint32_t*)frame.FrameBuffer, (uint32_t*)frame.FrameBuffer + frame.Width * frame.Height);
		_frameWidth = frame.Width;
		_frameHeight = frame.Height;
		_aspect = aspect;
		_frameDirty = true;
		_cv.notify_all();
	}

	void ClearFrame() override
	{
		std::lock_guard<std::mutex> lock(_mutex);
		std::fill(_frame.begin(), _frame.end(), 0);
		_frameDirty = true;
		_cv.notify_all();
	}

	/// Frames the core made in the last second.
	float Fps() { return _fps; }

	void Render(RenderSurfaceInfo&, RenderSurfaceInfo&) override {}
	void Reset() override {}
	void SetExclusiveFullscreenMode(bool, void*) override {}

	// App side.
	void SetWindow(ANativeWindow* window)
	{
		std::unique_lock<std::mutex> lock(_mutex);
		_pendingWindow = window;
		_windowChanged = true;
		_cv.notify_all();
		_cv.wait(lock, [this] { return !_windowChanged; });
	}

	void SetLayout(bool portraitTop, uint32_t topMargin)
	{
		std::lock_guard<std::mutex> lock(_mutex);
		_portraitTop = portraitTop;
		_topMargin = topMargin;
		_frameDirty = true;
		_cv.notify_all();
	}

	void SetScaling(const std::string& scaling)
	{
		std::lock_guard<std::mutex> lock(_mutex);
		_scaling = scaling;
		_frameDirty = true;
		_cv.notify_all();
	}

	/// A copy of the last frame, as ARGB pixels.
	bool CopyFrame(vector<uint32_t>& pixels, uint32_t& width, uint32_t& height, double& aspect)
	{
		std::lock_guard<std::mutex> lock(_mutex);
		if(_frame.empty()) {
			return false;
		}
		pixels = _frame;
		width = _frameWidth;
		height = _frameHeight;
		aspect = _aspect;
		return true;
	}

private:
	std::thread _thread;
	std::mutex _mutex;
	std::condition_variable _cv;

	std::atomic<float> _fps { 0 };
	uint32_t _fpsFrames = 0;
	Clock::time_point _fpsSince = Clock::now();

	vector<uint32_t> _frame;
	uint32_t _frameWidth = 0;
	uint32_t _frameHeight = 0;
	double _aspect = 4.0 / 3.0;
	bool _frameDirty = false;

	ANativeWindow* _pendingWindow = nullptr;
	bool _windowChanged = false;

	bool _portraitTop = true;
	uint32_t _topMargin = 0;
	std::string _scaling = "Sharp";

	// Render thread only.
	ANativeWindow* _window = nullptr;
	EGLDisplay _display = EGL_NO_DISPLAY;
	EGLConfig _config = nullptr;
	EGLContext _context = EGL_NO_CONTEXT;
	EGLSurface _surface = EGL_NO_SURFACE;
	GLuint _program = 0;
	GLuint _texture = 0;
	GLint _uTexSize = -1;
	GLint _uScale = -1;
	uint32_t _texWidth = 0;
	uint32_t _texHeight = 0;
	vector<uint32_t> _drawFrame;

	bool InitEgl()
	{
		_display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
		if(_display == EGL_NO_DISPLAY || !eglInitialize(_display, nullptr, nullptr)) {
			LOGE("eglInitialize failed");
			return false;
		}
		const EGLint configAttribs[] = {
			EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
			EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
			EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8,
			EGL_NONE
		};
		EGLint count = 0;
		if(!eglChooseConfig(_display, configAttribs, &_config, 1, &count) || count == 0) {
			LOGE("eglChooseConfig failed");
			return false;
		}
		const EGLint contextAttribs[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
		_context = eglCreateContext(_display, _config, EGL_NO_CONTEXT, contextAttribs);
		return _context != EGL_NO_CONTEXT;
	}

	static GLuint Compile(GLenum type, const char* source)
	{
		GLuint shader = glCreateShader(type);
		glShaderSource(shader, 1, &source, nullptr);
		glCompileShader(shader);
		GLint ok = 0;
		glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
		if(!ok) {
			char log[512];
			glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
			LOGE("Shader: %s", log);
		}
		return shader;
	}

	void InitGl()
	{
		_program = glCreateProgram();
		glAttachShader(_program, Compile(GL_VERTEX_SHADER, kVertexShader));
		glAttachShader(_program, Compile(GL_FRAGMENT_SHADER, kFragmentShader));
		glBindAttribLocation(_program, 0, "aPos");
		glBindAttribLocation(_program, 1, "aTex");
		glLinkProgram(_program);
		glUseProgram(_program);
		glUniform1i(glGetUniformLocation(_program, "uTex"), 0);
		_uTexSize = glGetUniformLocation(_program, "uTexSize");
		_uScale = glGetUniformLocation(_program, "uScale");

		glGenTextures(1, &_texture);
		glBindTexture(GL_TEXTURE_2D, _texture);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		_texWidth = _texHeight = 0;
	}

	void ReleaseSurface()
	{
		if(_surface != EGL_NO_SURFACE) {
			eglMakeCurrent(_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
			eglDestroySurface(_display, _surface);
			_surface = EGL_NO_SURFACE;
		}
		if(_window) {
			ANativeWindow_release(_window);
			_window = nullptr;
		}
	}

	void SwitchWindow(ANativeWindow* window)
	{
		ReleaseSurface();
		_window = window;
		if(!_window) {
			return;
		}
		if(_context == EGL_NO_CONTEXT && !InitEgl()) {
			return;
		}
		_surface = eglCreateWindowSurface(_display, _config, _window, nullptr);
		if(_surface == EGL_NO_SURFACE) {
			LOGE("eglCreateWindowSurface failed: 0x%x", eglGetError());
			return;
		}
		eglMakeCurrent(_display, _surface, _surface, _context);
		eglSwapInterval(_display, 1);
		if(!_program) {
			InitGl();
		}
	}

	void Draw(uint32_t width, uint32_t height, double aspect, bool portraitTop, uint32_t topMargin, const std::string& scaling, bool upload)
	{
		EGLint sw = 0, sh = 0;
		eglQuerySurface(_display, _surface, EGL_WIDTH, &sw);
		eglQuerySurface(_display, _surface, EGL_HEIGHT, &sh);

		glViewport(0, 0, sw, sh);
		glClearColor(0, 0, 0, 1);
		glClear(GL_COLOR_BUFFER_BIT);

		if(width && height && !_drawFrame.empty()) {
			glBindTexture(GL_TEXTURE_2D, _texture);
			if(width != _texWidth || height != _texHeight) {
				glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, _drawFrame.data());
				_texWidth = width;
				_texHeight = height;
			} else if(upload) {
				glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, _drawFrame.data());
			}

			// The largest rectangle of the game's aspect ratio that fits.
			double w = sw;
			double h = w / aspect;
			if(h > sh) {
				h = sh;
				w = h * aspect;
			}
			if(scaling == "Integer") {
				// Whole multiples of the game's lines, the width following the aspect ratio.
				double lines = (double)height;
				double scale = std::floor(h / lines);
				if(scale >= 1) {
					h = lines * scale;
					w = h * aspect;
				}
			}
			double x = (sw - w) / 2;
			double y = (portraitTop && sh > sw) ? std::min<double>(topMargin, sh - h) : (sh - h) / 2;

			int vx = (int)std::lround(x);
			int vw = (int)std::lround(w);
			int vh = (int)std::lround(h);
			int vy = sh - (int)std::lround(y) - vh;
			glViewport(vx, vy, vw, vh);

			glUseProgram(_program);
			glUniform2f(_uTexSize, (float)width, (float)height);
			float sx, sy;
			if(scaling == "Smooth") {
				sx = sy = 1.0f;
			} else if(scaling == "Nearest") {
				sx = sy = 1000.0f;
			} else {
				sx = std::max(1.0f, (float)(vw / (double)width));
				sy = std::max(1.0f, (float)(vh / (double)height));
			}
			glUniform2f(_uScale, sx, sy);

			static const GLfloat quad[] = {
				-1, -1, 0, 1,
				1, -1, 1, 1,
				-1, 1, 0, 0,
				1, 1, 1, 0,
			};
			glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), quad);
			glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), quad + 2);
			glEnableVertexAttribArray(0);
			glEnableVertexAttribArray(1);
			glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
		}

		if(!eglSwapBuffers(_display, _surface)) {
			LOGW("eglSwapBuffers failed: 0x%x", eglGetError());
		}
	}

	void Loop()
	{
		bool redraw = false;
		while(true) {
			uint32_t width, height, topMargin;
			double aspect;
			bool portraitTop, upload = false;
			std::string scaling;
			{
				std::unique_lock<std::mutex> lock(_mutex);
				_cv.wait_for(lock, std::chrono::milliseconds(250), [this] { return _frameDirty || _windowChanged; });
				if(_windowChanged) {
					SwitchWindow(_pendingWindow);
					_pendingWindow = nullptr;
					_windowChanged = false;
					_cv.notify_all();
					redraw = true;
				}
				if(_frameDirty) {
					_frameDirty = false;
					_drawFrame = _frame;
					upload = true;
				}
				width = _frameWidth;
				height = _frameHeight;
				aspect = _aspect;
				portraitTop = _portraitTop;
				topMargin = _topMargin;
				scaling = _scaling;
			}
			if(_surface != EGL_NO_SURFACE && (upload || redraw)) {
				Draw(width, height, aspect, portraitTop, topMargin, scaling, upload);
				redraw = false;
			}
		}
	}
};

GlesPresenter s_presenter;

// ---- Audio ----

/// Plays the core's samples through AAudio. The core writes a ring buffer, the stream's callback
/// reads it; the core speeds its output up or down a little to keep the buffer at the latency
/// the settings ask (BaseSoundManager::ProcessLatency).
class AAudioSoundManager : public BaseSoundManager
{
public:
	AAudioSoundManager()
	{
		_bufferSize = 0x40000;
		_buffer.resize(_bufferSize);
	}

	~AAudioSoundManager() override { CloseStream(); }

	void PlayBuffer(int16_t* soundBuffer, uint32_t sampleCount, uint32_t sampleRate, bool isStereo) override
	{
		if(!_stream || _sampleRate != sampleRate || _isStereo != isStereo || _needReset) {
			OpenStream(sampleRate, isStereo);
		}
		if(!_stream) {
			return;
		}

		uint32_t bytesPerSample = isStereo ? 4 : 2;
		Write((uint8_t*)soundBuffer, sampleCount * bytesPerSample);

		uint32_t latency = s_emu->GetSettings()->GetAudioConfig().AudioLatency;
		int32_t byteLatency = (int32_t)((float)(sampleRate * latency) / 1000.0f * bytesPerSample);
		if(!_playing && (int32_t)Available() > byteLatency) {
			_playing = AAudioStream_requestStart(_stream) == AAUDIO_OK;
		}
	}

	void Pause() override
	{
		if(_stream && _playing) {
			AAudioStream_requestPause(_stream);
		}
		_playing = false;
	}

	void Stop() override
	{
		Pause();
		if(_stream) {
			AAudioStream_requestFlush(_stream);
		}
		_readPosition = 0;
		_writePosition = 0;
		ResetStats();
	}

	void ProcessEndOfFrame() override
	{
		ProcessLatency(_readPosition, _writePosition);

		uint32_t speed = s_emu->GetSettings()->GetEmulationSpeed();
		if(_averageLatency > 0 && speed <= 100 && speed > 0 && std::abs(_averageLatency - s_emu->GetSettings()->GetAudioConfig().AudioLatency) > 50) {
			// Far off (over 50 ms): start again from an empty buffer.
			Stop();
		}
	}

	string GetAvailableDevices() override { return ""; }
	void SetAudioDevice(string) override {}

private:
	AAudioStream* _stream = nullptr;
	vector<uint8_t> _buffer;
	std::atomic<uint32_t> _readPosition { 0 };
	std::atomic<uint32_t> _writePosition { 0 };
	std::atomic<bool> _playing { false };
	std::atomic<bool> _needReset { false };

	uint32_t Available()
	{
		uint32_t r = _readPosition, w = _writePosition;
		return w >= r ? w - r : _bufferSize - r + w;
	}

	void Write(const uint8_t* data, uint32_t len)
	{
		uint32_t w = _writePosition;
		if(w + len < _bufferSize) {
			memcpy(_buffer.data() + w, data, len);
			w += len;
		} else {
			uint32_t first = _bufferSize - w;
			memcpy(_buffer.data() + w, data, first);
			memcpy(_buffer.data(), data + first, len - first);
			w = len - first;
		}
		_writePosition = w;
	}

	void Read(uint8_t* out, uint32_t len)
	{
		uint32_t available = Available();
		uint32_t take = std::min(len, available);
		uint32_t r = _readPosition;
		if(r + take < _bufferSize) {
			memcpy(out, _buffer.data() + r, take);
			r += take;
		} else {
			uint32_t first = _bufferSize - r;
			memcpy(out, _buffer.data() + r, first);
			memcpy(out + first, _buffer.data(), take - first);
			r = take - first;
		}
		_readPosition = r;
		if(take < len) {
			memset(out + take, 0, len - take);
			_bufferUnderrunEventCount++;
		}
	}

	static aaudio_data_callback_result_t DataCallback(AAudioStream*, void* userData, void* audioData, int32_t numFrames)
	{
		auto* self = static_cast<AAudioSoundManager*>(userData);
		self->Read((uint8_t*)audioData, numFrames * (self->_isStereo ? 4 : 2));
		return AAUDIO_CALLBACK_RESULT_CONTINUE;
	}

	static void ErrorCallback(AAudioStream*, void* userData, aaudio_result_t error)
	{
		// The device went away (headphones unplugged): reopen on the next write.
		LOGW("AAudio error %d", error);
		static_cast<AAudioSoundManager*>(userData)->_needReset = true;
	}

	void CloseStream()
	{
		if(_stream) {
			AAudioStream_requestStop(_stream);
			AAudioStream_close(_stream);
			_stream = nullptr;
		}
		_playing = false;
	}

	void OpenStream(uint32_t sampleRate, bool isStereo)
	{
		CloseStream();
		_needReset = false;
		_sampleRate = sampleRate;
		_isStereo = isStereo;
		_readPosition = 0;
		_writePosition = 0;
		ResetStats();

		AAudioStreamBuilder* builder = nullptr;
		if(AAudio_createStreamBuilder(&builder) != AAUDIO_OK) {
			return;
		}
		AAudioStreamBuilder_setSampleRate(builder, (int32_t)sampleRate);
		AAudioStreamBuilder_setChannelCount(builder, isStereo ? 2 : 1);
		AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
		AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
		AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_GAME);
		AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_MUSIC);
		AAudioStreamBuilder_setDataCallback(builder, &DataCallback, this);
		AAudioStreamBuilder_setErrorCallback(builder, &ErrorCallback, this);
		if(AAudioStreamBuilder_openStream(builder, &_stream) != AAUDIO_OK) {
			LOGE("Could not open the audio stream");
			_stream = nullptr;
		}
		AAudioStreamBuilder_delete(builder);
	}
};

unique_ptr<AAudioSoundManager> s_audio;

// ---- The running game ----

std::mutex s_run_mutex;
std::condition_variable s_run_cv;
std::atomic<bool> s_active { false };
std::atomic<bool> s_paused { false };
bool s_shutdown_requested = false;
std::atomic<bool> s_fast_forward { false };

std::mutex s_cheat_mutex;
vector<CheatCode> s_cheats;

// ---- Rewind ----

struct StoredRewindPoint
{
	uint64_t run_ms;
	std::string state;
	std::vector<uint8_t> png;
};

std::mutex s_rewind_mutex;
std::deque<StoredRewindPoint> s_rewind;
/// Milliseconds the game has run (not counting pauses), kept by the rewind thread.
std::atomic<uint64_t> s_run_ms { 0 };

std::vector<uint8_t> FramePng(uint32_t maxWidth)
{
	vector<uint32_t> pixels;
	uint32_t width, height;
	double aspect;
	if(!s_presenter.CopyFrame(pixels, width, height, aspect)) {
		return {};
	}
	// Halve a high-res frame for a thumbnail.
	if(width > maxWidth) {
		uint32_t w2 = width / 2, h2 = height / 2;
		vector<uint32_t> half(w2 * h2);
		for(uint32_t y = 0; y < h2; y++) {
			for(uint32_t x = 0; x < w2; x++) {
				half[y * w2 + x] = pixels[(y * 2) * width + x * 2];
			}
		}
		pixels.swap(half);
		width = w2;
		height = h2;
	}
	std::stringstream png;
	if(!PNGHelper::WritePNG(png, pixels.data(), width, height)) {
		return {};
	}
	std::string s = png.str();
	return std::vector<uint8_t>(s.begin(), s.end());
}

void RewindLoop()
{
	auto last = Clock::now();
	uint64_t nextPoint = 0;
	while(true) {
		{
			std::unique_lock<std::mutex> lock(s_run_mutex);
			s_run_cv.wait_for(lock, std::chrono::milliseconds(100));
			if(!s_active) {
				last = Clock::now();
				nextPoint = 0;
				continue;
			}
		}

		auto now = Clock::now();
		if(!s_paused) {
			s_run_ms += std::chrono::duration_cast<std::chrono::milliseconds>(now - last).count();
		}
		last = now;

		bool enabled;
		int interval, keep;
		{
			std::lock_guard<std::mutex> lock(s_settings_mutex);
			enabled = SettingBool("Rewind/Enable", false);
			interval = std::clamp(SettingInt("Rewind/IntervalSeconds", 10), 1, 60);
			keep = std::clamp(SettingInt("Rewind/Keep", 6), 1, 20);
		}
		if(!enabled || s_paused) {
			continue;
		}
		if(nextPoint == 0) {
			nextPoint = s_run_ms + interval * 1000ull;
			continue;
		}
		if(s_run_ms < nextPoint) {
			continue;
		}
		nextPoint = s_run_ms + interval * 1000ull;

		StoredRewindPoint point;
		point.run_ms = s_run_ms;
		{
			std::stringstream state;
			auto emuLock = s_emu->AcquireLock();
			if(!s_active) {
				continue;
			}
			s_emu->GetSaveStateManager()->SaveState(state);
			point.state = state.str();
		}
		point.png = FramePng(256);

		std::lock_guard<std::mutex> lock(s_rewind_mutex);
		s_rewind.push_front(std::move(point));
		while((int)s_rewind.size() > keep) {
			s_rewind.pop_back();
		}
	}
}

// ---- Settings into the core ----

void ApplySettings()
{
	std::lock_guard<std::mutex> lock(s_settings_mutex);
	EmuSettings* settings = s_emu->GetSettings();

	SnesConfig snes = settings->GetSnesConfig();
	snes.Port1.Type = ControllerType::SnesController;
	snes.Port1.Keys = PadMapping(0);
	snes.Port2.Type = ControllerType::SnesController;
	snes.Port2.Keys = PadMapping(1);
	std::string region = Setting("Snes/Region", "Auto");
	snes.Region = region == "NTSC" ? ConsoleRegion::Ntsc : region == "PAL" ? ConsoleRegion::Pal : ConsoleRegion::Auto;
	std::string ram = Setting("Snes/RamPowerOnState", "Random");
	snes.RamPowerOnState = ram == "AllZeros" ? RamState::AllZeros : ram == "AllOnes" ? RamState::AllOnes : RamState::Random;
	std::string blend = Setting("Snes/HighResBlend", "None");
	snes.HighResBlendMode = blend == "BlendAll" ? SnesHighResBlendMode::BlendAll : blend == "BlendEvenOdd" ? SnesHighResBlendMode::BlendEvenOdd : SnesHighResBlendMode::None;
	std::string interpolation = Setting("Snes/Interpolation", "Gauss");
	snes.InterpolationType = interpolation == "Cubic" ? DspInterpolationType::Cubic
		: interpolation == "Sinc" ? DspInterpolationType::Sinc
		: interpolation == "None" ? DspInterpolationType::None
		: DspInterpolationType::Gauss;
	snes.Overscan.Top = std::clamp(SettingInt("Snes/OverscanTop", 7), 0, 100);
	snes.Overscan.Bottom = std::clamp(SettingInt("Snes/OverscanBottom", 8), 0, 100);
	snes.Overscan.Left = std::clamp(SettingInt("Snes/OverscanLeft", 0), 0, 100);
	snes.Overscan.Right = std::clamp(SettingInt("Snes/OverscanRight", 0), 0, 100);
	snes.PpuExtraScanlinesBeforeNmi = std::clamp(SettingInt("Snes/ExtraScanlinesBeforeNmi", 0), 0, 1000);
	snes.PpuExtraScanlinesAfterNmi = std::clamp(SettingInt("Snes/ExtraScanlinesAfterNmi", 0), 0, 1000);
	snes.GsuClockSpeed = std::clamp(SettingInt("Snes/GsuClockSpeed", 100), 100, 1000);
	snes.AllowInvalidInput = SettingBool("Snes/AllowInvalidInput", false);
	snes.EnableRandomPowerOnState = false;
	settings->SetSnesConfig(snes);

	VideoConfig video = settings->GetVideoConfig();
	std::string aspect = Setting("Video/AspectRatio", "Auto");
	video.AspectRatio = aspect == "NoStretching" ? VideoAspectRatio::NoStretching
		: aspect == "Standard" ? VideoAspectRatio::Standard
		: aspect == "Widescreen" ? VideoAspectRatio::Widescreen
		: VideoAspectRatio::Auto;
	std::string filter = Setting("Video/Filter", "None");
	video.VideoFilter = filter == "NtscBlargg" ? VideoFilterType::NtscBlargg
		: filter == "NtscBisqwit" ? VideoFilterType::NtscBisqwit
		: filter == "Scale2x" ? VideoFilterType::Scale2x
		: filter == "HQ2x" ? VideoFilterType::HQ2x
		: filter == "xBRZ2x" ? VideoFilterType::xBRZ2x
		: VideoFilterType::None;
	video.ScanlineIntensity = std::clamp(SettingInt("Video/Scanlines", 0), 0, 100) / 100.0;
	settings->SetVideoConfig(video);
	s_presenter.SetScaling(Setting("Video/Scaling", "Sharp"));

	AudioConfig audio = settings->GetAudioConfig();
	audio.EnableAudio = !SettingBool("Audio/Muted", false);
	audio.MasterVolume = std::clamp(SettingInt("Audio/Volume", 100), 0, 100);
	audio.SampleRate = 48000;
	audio.AudioLatency = std::clamp(SettingInt("Audio/LatencyMs", 60), 15, 300);
	int ffVolume = std::clamp(SettingInt("Audio/FastForwardVolume", 100), 0, 100);
	audio.ReduceSoundInFastForward = ffVolume < 100;
	audio.VolumeReduction = 100 - ffVolume;
	audio.MuteSoundInBackground = false;
	audio.ReduceSoundInBackground = false;
	settings->SetAudioConfig(audio);

	EmulationConfig emulation = settings->GetEmulationConfig();
	emulation.EmulationSpeed = std::clamp(SettingInt("Emulation/SpeedPercent", 100), 0, 1000);
	int ffSpeed = std::clamp(SettingInt("Emulation/FastForwardPercent", 0), 0, 1000);
	emulation.TurboSpeed = ffSpeed;
	emulation.RunAheadFrames = std::clamp(SettingInt("Emulation/RunAheadFrames", 0), 0, 10);
	settings->SetEmulationConfig(emulation);

	PreferencesConfig preferences = settings->GetPreferences();
	preferences.DisableOsd = true;
	preferences.ShowFps = false;
	preferences.AutoSaveStateDelay = 0;
	// The app keeps its own rewind points (RewindLoop); the core's continuous history is off.
	preferences.RewindBufferSize = 0;
	preferences.DisableGameSelectionScreen = true;
	std::string saves = s_data_dir + "/saves";
	std::string states = s_data_dir + "/savestates";
	std::string shots = s_data_dir + "/screenshots";
	preferences.SaveFolderOverride = saves.c_str();
	preferences.SaveStateFolderOverride = states.c_str();
	preferences.ScreenshotFolderOverride = shots.c_str();
	settings->SetPreferences(preferences);
	// SetPreferences clears the firmware folder; the app keeps it beside the others.
	FolderUtilities::SetFolderOverrides(saves, states, shots, s_data_dir + "/firmware");

	// Fast forward: a speed of 0 is as fast as it goes.
	settings->ClearFlag(EmulationFlags::Turbo);
	settings->ClearFlag(EmulationFlags::MaximumSpeed);
	if(s_fast_forward) {
		settings->SetFlag(ffSpeed == 0 ? EmulationFlags::MaximumSpeed : EmulationFlags::Turbo);
	}
}

bool s_emu_started = false;

void StartEmulator()
{
	if(s_emu_started) {
		return;
	}
	s_emu_started = true;
	s_emu->Initialize(false);
	s_emu->GetVideoRenderer()->RegisterRenderingDevice(&s_presenter);
	s_audio.reset(new AAudioSoundManager());
	s_emu->GetSoundMixer()->RegisterAudioDevice(s_audio.get());
	KeyManager::RegisterKeyManager(&s_keys);
	s_listener.reset(new HostNotificationListener());
	s_emu->GetNotificationManager()->RegisterNotificationListener(s_listener);
	std::thread(RewindLoop).detach();
}

void InstallCheats()
{
	std::lock_guard<std::mutex> lock(s_cheat_mutex);
	vector<CheatCode> codes = s_cheats;
	if(codes.empty()) {
		s_emu->GetCheatManager()->ClearCheats(false);
	} else {
		s_emu->GetCheatManager()->SetCheats(codes);
	}
}

bool ToCheatCode(const std::string& text, CheatCode& code)
{
	std::string upper;
	for(char c : text) {
		if(!isspace((unsigned char)c)) {
			upper += (char)toupper((unsigned char)c);
		}
	}
	if(upper.empty() || upper.size() >= sizeof(code.Code)) {
		return false;
	}
	// Game Genie codes have a dash (XXXX-XXXX); Pro Action Replay ones are 8 hex digits.
	code.Type = upper.find('-') != std::string::npos ? CheatType::SnesGameGenie : CheatType::SnesProActionReplay;
	memset(code.Code, 0, sizeof(code.Code));
	memcpy(code.Code, upper.c_str(), upper.size());
	return true;
}

} // namespace

namespace AndroidHost {

void SetJavaVM(JavaVM* vm, jclass native_core)
{
	s_vm = vm;
	s_native_core = native_core;
	JNIEnv* env = GetEnv();
	s_on_vm_paused_changed = env->GetStaticMethodID(native_core, "onVmPausedChanged", "(Z)V");
	s_saf_open = env->GetStaticMethodID(native_core, "safOpen", "(Ljava/lang/String;)I");
}

std::string Initialize(const std::string& data_dir)
{
	if(s_emu) {
		return "";
	}
	s_data_dir = data_dir;
	for(const char* sub : { "", "/saves", "/savestates", "/screenshots", "/firmware", "/mesen" }) {
		mkdir((data_dir + sub).c_str(), 0755);
	}
	FolderUtilities::SetHomeFolder(data_dir + "/mesen");
	MessageManager::RegisterMessageManager(&s_messages);

	s_emu.reset(new Emulator());
	KeyManager::SetSettings(s_emu->GetSettings());
	ApplySettings();
	s_presenter.Start();
	return "";
}

std::string GetVersion()
{
	return s_emu ? s_emu->GetSettings()->GetVersionString() : "";
}

std::string RunSystem(const std::string& path, const std::string& save_state)
{
	std::vector<uint8_t> rom;
	std::string ext, error;
	if(!ReadRom(path, rom, ext, error)) {
		return error;
	}

	StartEmulator();
	ApplySettings();
	s_keys.ResetKeyState();
	{
		std::lock_guard<std::mutex> lock(s_listener->mutex);
		s_listener->missing_firmware.clear();
	}
	{
		std::lock_guard<std::mutex> lock(s_rewind_mutex);
		s_rewind.clear();
	}
	{
		std::lock_guard<std::mutex> lock(s_run_mutex);
		s_shutdown_requested = false;
	}

	// Named by the serial, so the battery save (<serial>.srm) follows the game whatever its file is called.
	VirtualFile file(rom.data(), rom.size(), SerialOf(rom) + ext);
	if(!s_emu->LoadRom(file, VirtualFile())) {
		std::lock_guard<std::mutex> lock(s_listener->mutex);
		if(!s_listener->missing_firmware.empty()) {
			return "This game needs the firmware file " + s_listener->missing_firmware;
		}
		return "Could not start the game";
	}
	InstallCheats();

	if(!save_state.empty() && !s_emu->GetSaveStateManager()->LoadState(save_state, false)) {
		LOGW("Could not load the boot state %s", save_state.c_str());
	}

	s_paused = false;
	s_active = true;
	NotifyPaused(false);

	{
		std::unique_lock<std::mutex> lock(s_run_mutex);
		s_run_cv.wait(lock, [] { return s_shutdown_requested; });
	}

	s_active = false;
	s_emu->Stop(true);
	s_audio->Stop();
	s_presenter.ClearFrame();
	{
		std::lock_guard<std::mutex> lock(s_rewind_mutex);
		s_rewind.clear();
	}
	return "";
}

bool HasActiveSystem()
{
	return s_active;
}

void RequestShutdown()
{
	std::lock_guard<std::mutex> lock(s_run_mutex);
	s_shutdown_requested = true;
	s_run_cv.notify_all();
}

void RequestPause(bool paused)
{
	if(!s_active || s_paused == paused) {
		return;
	}
	s_paused = paused;
	if(paused) {
		s_emu->Pause();
		s_audio->Pause();
	} else {
		s_emu->Resume();
	}
	NotifyPaused(paused);
}

void SetSetting(const std::string& section, const std::string& key, const std::string& value)
{
	std::lock_guard<std::mutex> lock(s_settings_mutex);
	s_settings[section + "/" + key] = value;
}

void CommitSettings()
{
	if(s_emu) {
		ApplySettings();
	}
}

void SetWindow(ANativeWindow* window, uint32_t, uint32_t)
{
	s_presenter.SetWindow(window);
}

void SetDisplayLayout(bool portrait_top, uint32_t top_margin)
{
	s_presenter.SetLayout(portrait_top, top_margin);
}

void SetPadBind(uint32_t port, uint32_t index, float value)
{
	if(port < kPorts && index < kButtons) {
		s_keys.keys[KeyCode(port, index)] = value > 0.5f;
	}
}

void SetFastForward(bool enabled)
{
	s_fast_forward = enabled;
	if(s_emu) {
		ApplySettings();
	}
}

std::string SaveState(const std::string& path)
{
	if(!s_active) {
		return "No game is running";
	}
	return s_emu->GetSaveStateManager()->SaveState(path, false) ? "" : "Could not write the state";
}

std::string LoadState(const std::string& path)
{
	if(!s_active) {
		return "No game is running";
	}
	if(!s_emu->GetSaveStateManager()->LoadState(path, false)) {
		return "Could not load the state";
	}
	InstallCheats();
	return "";
}

bool GetSaveStateInfo(const std::string& path, std::string* rom_name, std::vector<uint8_t>* png)
{
	if(!s_emu) {
		return false;
	}
	// The header: "MSS", the version, the format, the console type, the screen, then the rom's name.
	ifstream in(path, std::ios::binary);
	char magic[3];
	if(!in.read(magic, 3) || memcmp(magic, "MSS", 3) != 0) {
		return false;
	}
	auto readValue = [&in]() {
		uint8_t b[4] = {};
		in.read((char*)b, 4);
		return (uint32_t)b[0] | ((uint32_t)b[1] << 8) | ((uint32_t)b[2] << 16) | ((uint32_t)b[3] << 24);
	};
	readValue(); // version
	readValue(); // format
	readValue(); // console type
	readValue(); // frame buffer size
	readValue(); // width
	readValue(); // height
	readValue(); // scale
	uint32_t compressed = readValue();
	in.seekg(compressed, std::ios::cur);
	uint32_t nameLength = readValue();
	if(!in || nameLength > 4096) {
		return false;
	}
	std::string name(nameLength, '\0');
	in.read(name.data(), nameLength);
	*rom_name = name;

	vector<uint8_t> buffer(1024 * 1024);
	int32_t size = s_emu->GetSaveStateManager()->GetSaveStatePreview(path, buffer.data());
	if(size > 0) {
		png->assign(buffer.begin(), buffer.begin() + size);
	}
	return true;
}

std::vector<RewindPoint> GetRewindPoints()
{
	std::lock_guard<std::mutex> lock(s_rewind_mutex);
	std::vector<RewindPoint> points;
	uint64_t now = s_run_ms;
	for(const StoredRewindPoint& p : s_rewind) {
		points.push_back({ now > p.run_ms ? now - p.run_ms : 0, p.png });
	}
	return points;
}

bool LoadRewindPoint(uint32_t index)
{
	if(!s_active) {
		return false;
	}
	std::string state;
	{
		std::lock_guard<std::mutex> lock(s_rewind_mutex);
		if(index >= s_rewind.size()) {
			return false;
		}
		state = s_rewind[index].state;
		// The point itself stays, as the newest.
		for(uint32_t i = 0; i < index; i++) {
			s_rewind.pop_front();
		}
	}
	std::stringstream in(state);
	{
		auto emuLock = s_emu->AcquireLock();
		if(!s_emu->GetSaveStateManager()->LoadState(in)) {
			return false;
		}
	}
	InstallCheats();
	return true;
}

void SaveScreenshot(const std::string& path)
{
	std::thread([path] {
		vector<uint32_t> pixels;
		uint32_t width, height;
		double aspect;
		if(s_presenter.CopyFrame(pixels, width, height, aspect)) {
			PNGHelper::WritePNG(path, pixels.data(), width, height);
		}
	}).detach();
}

void SetCheats(const std::vector<std::string>& codes)
{
	{
		std::lock_guard<std::mutex> lock(s_cheat_mutex);
		s_cheats.clear();
		for(const std::string& text : codes) {
			CheatCode code;
			if(ToCheatCode(text, code)) {
				s_cheats.push_back(code);
			}
		}
	}
	if(s_active) {
		InstallCheats();
	}
}

bool IsValidCheat(const std::string& text)
{
	CheatCode code;
	if(!s_emu) {
		return ToCheatCode(text, code);
	}
	InternalCheatCode converted;
	return ToCheatCode(text, code) && s_emu->GetCheatManager()->GetConvertedCheat(code, converted);
}

std::optional<std::vector<uint8_t>> ReadMemory(uint32_t address, uint32_t length)
{
	if(!s_active) {
		return std::nullopt;
	}
	auto lock = s_emu->AcquireLock();
	ConsoleMemoryInfo ram = s_emu->GetMemory(MemoryType::SnesWorkRam);
	if(!ram.Memory || (uint64_t)address + length > ram.Size) {
		return std::nullopt;
	}
	auto* bytes = static_cast<uint8_t*>(ram.Memory);
	return std::vector<uint8_t>(bytes + address, bytes + address + length);
}

bool WriteMemory(uint32_t address, uint32_t size, uint32_t value)
{
	if(!s_active || (size != 1 && size != 2 && size != 4)) {
		return false;
	}
	auto lock = s_emu->AcquireLock();
	ConsoleMemoryInfo ram = s_emu->GetMemory(MemoryType::SnesWorkRam);
	if(!ram.Memory || (uint64_t)address + size > ram.Size) {
		return false;
	}
	auto* bytes = static_cast<uint8_t*>(ram.Memory);
	for(uint32_t i = 0; i < size; i++) {
		bytes[address + i] = (uint8_t)(value >> (8 * i));
	}
	return true;
}

float GetFps()
{
	return s_active ? s_presenter.Fps() : 0.0f;
}

uint64_t GetFrameNumber()
{
	return s_active ? s_emu->GetFrameCount() : 0;
}

std::string GetGameSerial(const std::string& path)
{
	std::vector<uint8_t> rom;
	std::string ext, error;
	if(!ReadRom(path, rom, ext, error)) {
		LOGW("No serial for %s: %s", path.c_str(), error.c_str());
		return "";
	}
	return SerialOf(rom);
}

} // namespace AndroidHost
