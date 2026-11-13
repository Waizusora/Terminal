cmake_minimum_required(VERSION 3.22.1)
project(terminal_native LANGUAGES C CXX)

include(FetchContent)

FetchContent_Declare(
    libvterm
    URL https://launchpad.net/libvterm/trunk/0.3/+download/libvterm-0.3.3.tar.gz
    URL_HASH SHA256=09156f43dd2128bd347cbeebe50d9a571d32c64e0cf18d211197946aff7226e0
)
FetchContent_GetProperties(libvterm)
if(NOT libvterm_POPULATED)
    FetchContent_Populate(libvterm)
endif()

file(GLOB LIBVTERM_SOURCES CONFIGURE_DEPENDS
    "\${libvterm_SOURCE_DIR}/src/*.c"
)

add_library(vterm STATIC \${LIBVTERM_SOURCES})
target_include_directories(vterm PUBLIC "\${libvterm_SOURCE_DIR}/include")
target_compile_features(vterm PRIVATE c_std_99)
set_target_properties(vterm PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_library(terminal_vterm SHARED terminal_vterm.cpp)
target_include_directories(terminal_vterm PRIVATE "\${libvterm_SOURCE_DIR}/include")
target_compile_features(terminal_vterm PRIVATE cxx_std_17)
target_link_libraries(terminal_vterm PRIVATE vterm)
