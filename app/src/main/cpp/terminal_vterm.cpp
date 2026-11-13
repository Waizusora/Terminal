#include <jni.h>
#include <cstdint>
#include <vector>

#include "vterm.h"

namespace {
constexpr int kCellStride = 10;

struct TerminalState {
    VTerm* terminal;
    VTermScreen* screen;
    int rows;
    int columns;
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
}

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
            for (int index = 0; index < VTERM_MAX_CHARS_PER_CELL; ++index) {
                cells[offset + index] = static_cast<jint>(cell.chars[index]);
            }

            cells[offset + 6] = static_cast<jint>(cell.width);
            jint attributes = 0;
            if (cell.attrs.bold) attributes |= 1;
            if (cell.attrs.underline) attributes |= 2;
            if (cell.attrs.italic) attributes |= 4;
            if (cell.attrs.blink) attributes |= 8;
            if (cell.attrs.reverse) attributes |= 16;
            if (cell.attrs.strike) attributes |= 32;
            cells[offset + 7] = attributes;
            cells[offset + 8] = packColor(cell.fg, true);
            cells[offset + 9] = packColor(cell.bg, false);
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
