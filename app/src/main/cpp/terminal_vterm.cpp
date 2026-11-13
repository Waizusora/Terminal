#include <jni.h>
#include <cstdint>
#include <vector>
#include <deque>
#include <algorithm>
#include <cerrno>
#include <csignal>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <string>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>
#include <utility>
#include <time.h>

#include "vterm.h"

namespace {
constexpr int kCellStride = 10;
constexpr size_t kMaximumScrollbackLines = 2000;
constexpr size_t kMaximumScrollbackCells = 200000;

struct TerminalState {
    VTerm* terminal;
    VTermScreen* screen;
    int rows;
    int columns;
    std::deque<std::vector<VTermScreenCell>> scrollback;
    size_t scrollbackCellCount = 0;
    uint64_t scrollbackVersion = 0;
    int cursorRow = 0;
    int cursorColumn = 0;
    bool cursorVisible = true;
};

TerminalState* stateFrom(jlong handle) {
    return reinterpret_cast<TerminalState*>(handle);
}

jint packColor(const VTermColor& color, bool foreground) {
    if ((foreground && VTERM_COLOR_IS_DEFAULT_FG(&color)) ||
        (!foreground && VTERM_COLOR_IS_DEFAULT_BG(&color))) {
        return 0;
    }
    if (VTERM_COLOR_IS_INDEXED(&color)) {
        return 0x01000000 | color.indexed.idx;
    }
    return 0x02000000 |
        (static_cast<jint>(color.rgb.red) << 16) |
        (static_cast<jint>(color.rgb.green) << 8) |
        static_cast<jint>(color.rgb.blue);
}

void encodeCell(const VTermScreenCell& cell, jint* output) {
    for (int index = 0; index < VTERM_MAX_CHARS_PER_CELL; ++index) {
        output[index] = static_cast<jint>(cell.chars[index]);
    }
    output[6] = static_cast<jint>(cell.width);
    jint attributes = 0;
    if (cell.attrs.bold) attributes |= 1;
    if (cell.attrs.underline) attributes |= 2;
    if (cell.attrs.italic) attributes |= 4;
    if (cell.attrs.blink) attributes |= 8;
    if (cell.attrs.reverse) attributes |= 16;
    if (cell.attrs.strike) attributes |= 32;
    output[7] = attributes;
    output[8] = packColor(cell.fg, true);
    output[9] = packColor(cell.bg, false);
}

int pushScrollbackLine(int columns, const VTermScreenCell* cells, void* user) {
    auto* state = static_cast<TerminalState*>(user);
    if (state == nullptr || cells == nullptr || columns <= 0) return 0;
    state->scrollback.emplace_back(cells, cells + columns);
    ++state->scrollbackVersion;
    state->scrollbackCellCount += static_cast<size_t>(columns);
    while (!state->scrollback.empty() &&
           (state->scrollback.size() > kMaximumScrollbackLines ||
            state->scrollbackCellCount > kMaximumScrollbackCells)) {
        state->scrollbackCellCount -= state->scrollback.front().size();
        state->scrollback.pop_front();
    }
    return 1;
}

int popScrollbackLine(int columns, VTermScreenCell* cells, void* user) {
    auto* state = static_cast<TerminalState*>(user);
    if (state == nullptr || cells == nullptr || columns <= 0 ||
        state->scrollback.empty()) return 0;
    std::fill(cells, cells + columns, VTermScreenCell{});
    const auto& line = state->scrollback.back();
    const size_t count = std::min(line.size(), static_cast<size_t>(columns));
    std::copy(line.begin(), line.begin() + count, cells);
    state->scrollbackCellCount -= line.size();
    state->scrollback.pop_back();
    ++state->scrollbackVersion;
    return 1;
}

int clearScrollback(void* user) {
    auto* state = static_cast<TerminalState*>(user);
    if (state != nullptr) {
        state->scrollback.clear();
        state->scrollbackCellCount = 0;
        ++state->scrollbackVersion;
    }
    return 1;
}

int moveCursor(VTermPos position, VTermPos, int visible, void* user) {
    auto* state = static_cast<TerminalState*>(user);
    if (state == nullptr) return 0;
    state->cursorRow = position.row;
    state->cursorColumn = position.col;
    state->cursorVisible = visible != 0;
    return 1;
}

int setTerminalProperty(VTermProp property, VTermValue* value, void* user) {
    auto* state = static_cast<TerminalState*>(user);
    if (state == nullptr || value == nullptr) return 0;
    if (property == VTERM_PROP_CURSORVISIBLE) {
        state->cursorVisible = value->boolean != 0;
    }
    return 1;
}

const VTermScreenCallbacks kScreenCallbacks = {
    nullptr, nullptr, moveCursor, setTerminalProperty, nullptr, nullptr,
    pushScrollbackLine, popScrollbackLine, clearScrollback
};

extern "C" JNIEXPORT jlong JNICALL
Java_com_waizusora_terminal_NativeTerminal_nativeCreate(
    JNIEnv*, jclass, jint rows, jint columns) {
    if (rows <= 0 || columns <= 0) return 0;

    auto* state = new TerminalState{};
    state->rows = rows;
    state->columns = columns;
    state->terminal = vterm_new(rows, columns);
    if (state->terminal == nullptr) {
        delete state;
        return 0;
    }

    vterm_set_utf8(state->terminal, 1);
    state->screen = vterm_obtain_screen(state->terminal);
    vterm_screen_enable_altscreen(state->screen, 1);
    vterm_screen_set_callbacks(state->screen, &kScreenCallbacks, state);
    vterm_screen_set_damage_merge(state->screen, VTERM_DAMAGE_ROW);
    vterm_screen_reset(state->screen, 1);
    return reinterpret_cast<jlong>(state);
}

extern "C" JNIEXPORT void JNICALL
Java_com_waizusora_terminal_NativeTerminal_nativeDestroy(
    JNIEnv*, jclass, jlong handle) {
    TerminalState* state = stateFrom(handle);
    if (state == nullptr) return;
    vterm_free(state->terminal);
    delete state;
}

extern "C" JNIEXPORT void JNICALL
Java_com_waizusora_terminal_NativeTerminal_nativeFeed(
    JNIEnv* env, jclass, jlong handle, jbyteArray input) {
    TerminalState* state = stateFrom(handle);
    if (state == nullptr || input == nullptr) return;

    const jsize length = env->GetArrayLength(input);
    if (length <= 0) return;
    jbyte* bytes = env->GetByteArrayElements(input, nullptr);
    if (bytes == nullptr) return;

    vterm_input_write(
        state->terminal,
        reinterpret_cast<const char*>(bytes),
        static_cast<size_t>(length)
    );
    env->ReleaseByteArrayElements(input, bytes, JNI_ABORT);
    vterm_screen_flush_damage(state->screen);
}

extern "C" JNIEXPORT void JNICALL
Java_com_waizusora_terminal_NativeTerminal_nativeResize(
    JNIEnv*, jclass, jlong handle, jint rows, jint columns) {
    TerminalState* state = stateFrom(handle);
    if (state == nullptr || rows <= 0 || columns <= 0) return;
    state->rows = rows;
    state->columns = columns;
    vterm_set_size(state->terminal, rows, columns);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_waizusora_terminal_NativeTerminal_nativeReadCursor(
    JNIEnv* env, jclass, jlong handle) {
    TerminalState* state = stateFrom(handle);
    jint values[3] = {0, 0, 0};
    if (state != nullptr) {
        values[0] = state->cursorRow;
        values[1] = state->cursorColumn;
        values[2] = state->cursorVisible ? 1 : 0;
    }
    jintArray result = env->NewIntArray(3);
    if (result != nullptr) env->SetIntArrayRegion(result, 0, 3, values);
    return result;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_waizusora_terminal_NativeTerminal_nativeReadCells(
    JNIEnv* env, jclass, jlong handle) {
    TerminalState* state = stateFrom(handle);
    if (state == nullptr) return nullptr;

    const size_t cellCount =
        static_cast<size_t>(state->rows) * static_cast<size_t>(state->columns);
    std::vector<jint> cells(cellCount * kCellStride, 0);

    for (int row = 0; row < state->rows; ++row) {
        for (int column = 0; column < state->columns; ++column) {
            VTermScreenCell cell{};
            vterm_screen_get_cell(state->screen, VTermPos{row, column}, &cell);
            const size_t offset =
                (static_cast<size_t>(row) * state->columns + column) * kCellStride;
            encodeCell(cell, cells.data() + offset);
        }
    }

    jintArray result = env->NewIntArray(static_cast<jsize>(cells.size()));
    if (result != nullptr) {
        env->SetIntArrayRegion(
            result, 0, static_cast<jsize>(cells.size()), cells.data()
        );
    }
    return result;
}

 
extern "C" JNIEXPORT jlong JNICALL
Java_com_waizusora_terminal_NativeTerminal_nativeScrollbackVersion(
    JNIEnv*, jclass, jlong handle) {
    TerminalState* state = stateFrom(handle);
    return state == nullptr ? 0 : static_cast<jlong>(state->scrollbackVersion);
}


extern "C" JNIEXPORT jintArray JNICALL
Java_com_waizusora_terminal_NativeTerminal_nativeReadScrollback(
    JNIEnv* env, jclass, jlong handle) {
    TerminalState* state = stateFrom(handle);
    if (state == nullptr) return env->NewIntArray(0);

    const size_t rowSize = static_cast<size_t>(state->columns) * kCellStride;
    std::vector<jint> cells(state->scrollback.size() * rowSize, 0);
    for (size_t row = 0; row < state->scrollback.size(); ++row) {
        const auto& source = state->scrollback[row];
        const size_t count = std::min(source.size(), static_cast<size_t>(state->columns));
        for (size_t column = 0; column < count; ++column) {
            encodeCell(source[column], cells.data() + row * rowSize + column * kCellStride);
        }
    }

    jintArray result = env->NewIntArray(static_cast<jsize>(cells.size()));
    if (result != nullptr && !cells.empty()) {
        env->SetIntArrayRegion(result, 0, static_cast<jsize>(cells.size()), cells.data());
    }
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_waizusora_terminal_NativeTerminal_nativeReadResponse(
    JNIEnv* env, jclass, jlong handle) {
    TerminalState* state = stateFrom(handle);
    if (state == nullptr) return env->NewByteArray(0);

    const size_t pending = vterm_output_get_buffer_current(state->terminal);
    if (pending == 0) return env->NewByteArray(0);

    std::vector<char> response(pending);
    const size_t count = vterm_output_read(
        state->terminal, response.data(), response.size()
    );
    jbyteArray result = env->NewByteArray(static_cast<jsize>(count));
    if (result != nullptr && count > 0) {
        env->SetByteArrayRegion(
            result, 0, static_cast<jsize>(count),
            reinterpret_cast<const jbyte*>(response.data())
        );
    }
    return result;
}


} // namespace

extern "C" char** environ;

namespace {
struct PtyProcess {
    int masterFd;
    pid_t childPid;
};

PtyProcess* ptyFrom(jlong handle) {
    return reinterpret_cast<PtyProcess*>(handle);
}

void throwIoException(JNIEnv* env, const char* operation) {
    jclass type = env->FindClass("java/io/IOException");
    if (type == nullptr) return;
    std::string message(operation);
    message += ": ";
    message += std::strerror(errno);
    env->ThrowNew(type, message.c_str());
}

std::string javaString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_waizusora_terminal_NativePty_nativeStart(
    JNIEnv* env, jclass, jstring shellValue, jstring workingDirectoryValue,
    jstring homeValue, jstring prefixValue, jstring pathValue,
    jint rows, jint columns) {
    const std::string shell = javaString(env, shellValue);
    const std::string workingDirectory = javaString(env, workingDirectoryValue);
    const std::string home = javaString(env, homeValue);
    const std::string prefix = javaString(env, prefixValue);
    const std::string path = javaString(env, pathValue);
    if (shell.empty() || workingDirectory.empty()) {
        errno = EINVAL;
        throwIoException(env, "Invalid PTY launch arguments");
        return 0;
    }

    const int master = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master < 0) {
        throwIoException(env, "posix_openpt");
        return 0;
    }
    if (grantpt(master) != 0 || unlockpt(master) != 0) {
        const int saved = errno;
        close(master);
        errno = saved;
        throwIoException(env, "Preparing PTY slave");
        return 0;
    }

    char slaveName[256] = {};
    const int slaveNameError = ptsname_r(master, slaveName, sizeof(slaveName));
    if (slaveNameError != 0) {
        const int saved = slaveNameError;
        close(master);
        errno = saved;
        throwIoException(env, "Resolving PTY slave");
        return 0;
    }

    const std::vector<std::pair<std::string, std::string>> overrides = {
        {"HOME", home},
        {"PWD", workingDirectory},
        {"SHELL", shell},
        {"PREFIX", prefix},
        {"PATH", path},
        {"LD_LIBRARY_PATH", prefix + "/lib"},
        {"TMPDIR", prefix + "/tmp"},
        {"TERMINFO", prefix + "/share/terminfo"},
        {"TERM", "xterm-256color"},
        {"COLORTERM", "truecolor"},
        {"LANG", "C.UTF-8"}
    };
    std::vector<std::string> environmentStrings;
    for (char** current = environ; current != nullptr && *current != nullptr; ++current) {
        const std::string entry(*current);
        const size_t separator = entry.find('=');
        if (separator == std::string::npos) continue;
        const std::string name = entry.substr(0, separator);
        bool replaced = false;
        for (const auto& overrideValue : overrides) {
            if (name == overrideValue.first) {
                replaced = true;
                break;
            }
        }
        if (!replaced) environmentStrings.push_back(entry);
    }
    for (const auto& overrideValue : overrides) {
        environmentStrings.push_back(overrideValue.first + "=" + overrideValue.second);
    }
    std::vector<char*> environment;
    environment.reserve(environmentStrings.size() + 1);
    for (std::string& entry : environmentStrings) {
        environment.push_back(entry.data());
    }
    environment.push_back(nullptr);

    char* arguments[] = {
        const_cast<char*>(shell.c_str()),
        nullptr
    };

    struct winsize windowSize {};
    windowSize.ws_row = static_cast<unsigned short>(rows > 0 ? rows : 24);
    windowSize.ws_col = static_cast<unsigned short>(columns > 0 ? columns : 80);
    if (ioctl(master, TIOCSWINSZ, &windowSize) != 0) {
        const int saved = errno;
        close(master);
        errno = saved;
        throwIoException(env, "Setting initial PTY size");
        return 0;
    }

    const pid_t child = fork();
    if (child < 0) {
        const int saved = errno;
        close(master);
        errno = saved;
        throwIoException(env, "fork");
        return 0;
    }

    if (child == 0) {
        close(master);
        if (setsid() < 0) _exit(126);

        const int slave = open(slaveName, O_RDWR);
        if (slave < 0) _exit(126);
#ifdef TIOCSCTTY
        if (ioctl(slave, TIOCSCTTY, 0) < 0) _exit(126);
#endif
        if (dup2(slave, STDIN_FILENO) < 0 ||
            dup2(slave, STDOUT_FILENO) < 0 ||
            dup2(slave, STDERR_FILENO) < 0) {
            _exit(126);
        }
        if (slave > STDERR_FILENO) close(slave);
        if (chdir(workingDirectory.c_str()) != 0) _exit(126);

        execve(shell.c_str(), arguments, environment.data());
        _exit(127);
    }

    auto* process = new PtyProcess{master, child};
    return reinterpret_cast<jlong>(process);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_waizusora_terminal_NativePty_nativeRead(
    JNIEnv* env, jclass, jlong handle, jint maximumBytes) {
    PtyProcess* process = ptyFrom(handle);
    if (process == nullptr || process->masterFd < 0) return env->NewByteArray(0);

    const size_t capacity = static_cast<size_t>(
        maximumBytes > 0 && maximumBytes < 65536 ? maximumBytes : 4096
    );
    std::vector<jbyte> buffer(capacity);
    ssize_t count;
    do {
        count = read(process->masterFd, buffer.data(), buffer.size());
    } while (count < 0 && errno == EINTR);

    if (count <= 0) return env->NewByteArray(0);
    jbyteArray result = env->NewByteArray(static_cast<jsize>(count));
    if (result != nullptr) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(count), buffer.data());
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_waizusora_terminal_NativePty_nativeWrite(
    JNIEnv* env, jclass, jlong handle, jbyteArray input) {
    PtyProcess* process = ptyFrom(handle);
    if (process == nullptr || process->masterFd < 0 || input == nullptr) return;

    const jsize length = env->GetArrayLength(input);
    if (length <= 0) return;
    jbyte* bytes = env->GetByteArrayElements(input, nullptr);
    if (bytes == nullptr) return;

    size_t written = 0;
    while (written < static_cast<size_t>(length)) {
        const ssize_t count = write(
            process->masterFd,
            bytes + written,
            static_cast<size_t>(length) - written
        );
        if (count > 0) {
            written += static_cast<size_t>(count);
        } else if (count < 0 && errno == EINTR) {
            continue;
        } else {
            break;
        }
    }
    env->ReleaseByteArrayElements(input, bytes, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_waizusora_terminal_NativePty_nativeResize(
    JNIEnv*, jclass, jlong handle, jint rows, jint columns) {
    PtyProcess* process = ptyFrom(handle);
    if (process == nullptr || process->masterFd < 0 || rows <= 0 || columns <= 0) return;

    struct winsize windowSize {};
    windowSize.ws_row = static_cast<unsigned short>(rows);
    windowSize.ws_col = static_cast<unsigned short>(columns);
    ioctl(process->masterFd, TIOCSWINSZ, &windowSize);
}

extern "C" JNIEXPORT void JNICALL
Java_com_waizusora_terminal_NativePty_nativeClose(
    JNIEnv*, jclass, jlong handle) {
    PtyProcess* process = ptyFrom(handle);
    if (process == nullptr) return;

    kill(-process->childPid, SIGHUP);
    kill(process->childPid, SIGHUP);

    int status = 0;
    pid_t result = 0;
    const struct timespec interval = {0, 10000000};
    for (int attempt = 0; attempt < 50; ++attempt) {
        result = waitpid(process->childPid, &status, WNOHANG);
        if (result == process->childPid || (result < 0 && errno == ECHILD)) break;
        nanosleep(&interval, nullptr);
    }
    if (result != process->childPid && !(result < 0 && errno == ECHILD)) {
        kill(-process->childPid, SIGKILL);
        kill(process->childPid, SIGKILL);
        do {
            result = waitpid(process->childPid, &status, 0);
        } while (result < 0 && errno == EINTR);
    }

    if (process->masterFd >= 0) close(process->masterFd);
    delete process;
}
