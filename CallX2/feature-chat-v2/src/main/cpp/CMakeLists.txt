cmake_minimum_required(VERSION 3.22.1)
project(fastchat_native)

add_library(fastchat_native SHARED
    jni_bridge.cpp
    chat_renderer.cpp
    layout_engine.cpp
)

find_library(log-lib log)
find_library(gles-lib GLESv2)
find_library(egl-lib EGL)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)

target_include_directories(fastchat_native PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_link_libraries(fastchat_native
    ${log-lib}
    ${gles-lib}
    ${egl-lib}
    ${android-lib}
    ${jnigraphics-lib}
)

target_compile_options(fastchat_native PRIVATE -Wall -Wextra -O2)
