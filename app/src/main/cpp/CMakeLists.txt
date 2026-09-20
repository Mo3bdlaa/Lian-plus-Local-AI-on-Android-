cmake_minimum_required(VERSION 3.22.1)
project(lian_native C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(NATIVE_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../native")
get_filename_component(NATIVE_ROOT "${NATIVE_ROOT}" ABSOLUTE)

option(LIAN_BUILD_NATIVE "Compile the llama.cpp / stable-diffusion.cpp engines" ON)
option(LIAN_BUILD_IMAGE  "Compile the image-generation engine"                  ON)
option(LIAN_VULKAN       "Build the Vulkan GPU backend into both engines"       ON)

# ---------------------------------------------------------------------------
# Vulkan
#
# The NDK carries the loader and the C headers, but not the C++ bindings ggml
# uses, so Vulkan-Headers is vendored at exactly the NDK's version (1.3.275).
# A mismatch there is the kind that compiles and then misbehaves on device.
#
# The shader compiler runs on the build host, not the phone. glslc comes from
# the `glslc` package and the CMake config for SPIRV-Headers from
# `spirv-headers`; without them the backend is skipped rather than failing the
# build, so a contributor without the extra packages still gets a working
# CPU-only APK.
# ---------------------------------------------------------------------------
set(LIAN_VULKAN_READY OFF)
if (LIAN_VULKAN AND LIAN_BUILD_NATIVE)
    find_program(LIAN_GLSLC glslc)
    find_package(SPIRV-Headers CONFIG QUIET)
    set(LIAN_VK_HEADERS "${NATIVE_ROOT}/Vulkan-Headers/include")
    set(LIAN_SPIRV_HEADERS "${NATIVE_ROOT}/SPIRV-Headers/include")

    if (NOT LIAN_GLSLC)
        message(WARNING "glslc not found - building without the Vulkan backend. "
                        "Install it with: apt install glslc spirv-headers")
    elseif (NOT SPIRV-Headers_FOUND)
        message(WARNING "SPIRV-Headers CMake config not found - building without "
                        "the Vulkan backend. Install it with: apt install spirv-headers")
    elseif (NOT EXISTS "${LIAN_SPIRV_HEADERS}/spirv/unified1/spirv.hpp")
        message(WARNING "native/SPIRV-Headers is empty - run "
                        "git submodule update --init --recursive")
    elseif (NOT EXISTS "${LIAN_VK_HEADERS}/vulkan/vulkan.hpp")
        message(WARNING "native/Vulkan-Headers is empty - run "
                        "git submodule update --init --recursive")
    else()
        set(LIAN_VULKAN_READY ON)
        message(STATUS "Vulkan backend enabled (glslc: ${LIAN_GLSLC})")
    endif()
endif()

# Stub build: produce the two libraries with the JNI symbols present but every
# engine call failing cleanly, so the APK still assembles without the vendored
# sources. Kotlin sees this through LlamaNative.isAvailable().
if (NOT LIAN_BUILD_NATIVE)
    message(WARNING "LIAN_BUILD_NATIVE=OFF - building stub engines")
    add_library(lian_llm SHARED stub_jni.cpp)
    target_compile_definitions(lian_llm PRIVATE LIAN_STUB_LIB="lian_llm")
    target_link_libraries(lian_llm PRIVATE log)
    add_library(lian_sd SHARED stub_jni.cpp)
    target_compile_definitions(lian_sd PRIVATE LIAN_STUB_LIB="lian_sd")
    target_link_libraries(lian_sd PRIVATE log)
    return()
endif()

if (NOT EXISTS "${NATIVE_ROOT}/llama.cpp/CMakeLists.txt")
    message(FATAL_ERROR
        "native/llama.cpp is empty. Run:  git submodule update --init --recursive\n"
        "or build with -Plian.buildNative=false to skip the engines.")
endif()

# ---------------------------------------------------------------------------
# Text generation: llama.cpp
# ---------------------------------------------------------------------------
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE       ON  CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL      OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)

if (LIAN_VULKAN_READY)
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
    # Both header sets come from the vendored copies, ahead of anything the
    # host happens to have installed: the CMake package for SPIRV-Headers only
    # satisfies find_package, it does not put the headers on the include path
    # of a cross-compile.
    include_directories(SYSTEM "${LIAN_VK_HEADERS}" "${LIAN_SPIRV_HEADERS}")
else()
    set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
endif()

# Snapdragon 8 Elite / Exynos 2500 class cores implement dotprod and i8mm; both
# give a large speed-up on Q4_0/Q4_K matmuls. ggml still probes at runtime, so a
# device without them simply falls back to the plain NEON kernels.
if (ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_NATIVE   OFF CACHE BOOL "" FORCE)
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+i8mm" CACHE STRING "" FORCE)
endif()

add_subdirectory("${NATIVE_ROOT}/llama.cpp" "${CMAKE_BINARY_DIR}/llama.cpp" EXCLUDE_FROM_ALL)

add_library(lian_llm SHARED llama_jni.cpp jni_util.cpp)
target_include_directories(lian_llm PRIVATE
    "${NATIVE_ROOT}/llama.cpp/include"
    "${NATIVE_ROOT}/llama.cpp/ggml/include")
target_link_libraries(lian_llm PRIVATE llama ggml android log)
if (LIAN_VULKAN_READY)
    target_compile_definitions(lian_llm PRIVATE LIAN_HAS_VULKAN=1)
endif()
target_link_options(lian_llm PRIVATE
    "-Wl,--exclude-libs,ALL"      # keep ggml/llama symbols out of the dynamic table
    "-Wl,--gc-sections")

# ---------------------------------------------------------------------------
# Image generation: stable-diffusion.cpp
#
# stable-diffusion.cpp vendors its own copy of ggml, which may be a different
# revision from the one inside llama.cpp. Rather than force them onto a shared
# target (a silent ODR hazard) each engine keeps its own statically linked ggml,
# hidden behind -fvisibility=hidden and --exclude-libs. The two libraries are
# also loaded in separate OS processes at runtime (see ImageGenService), so the
# copies can never meet.
# ---------------------------------------------------------------------------
if (LIAN_BUILD_IMAGE AND EXISTS "${NATIVE_ROOT}/stable-diffusion.cpp/CMakeLists.txt")
    include(ExternalProject)

    # Diffusion is pure compute, which is where a mobile GPU actually wins -
    # unlike token generation, which is bound by the memory bus the GPU shares
    # with the CPU anyway.
    if (LIAN_VULKAN_READY)
        set(LIAN_SD_VULKAN ON)
    else()
        set(LIAN_SD_VULKAN OFF)
    endif()

    set(SD_SRC     "${NATIVE_ROOT}/stable-diffusion.cpp")
    set(SD_BUILD   "${CMAKE_BINARY_DIR}/sd-build")
    set(SD_INSTALL "${CMAKE_BINARY_DIR}/sd-install")

    ExternalProject_Add(sd_ext
        SOURCE_DIR "${SD_SRC}"
        BINARY_DIR "${SD_BUILD}"
        INSTALL_DIR "${SD_INSTALL}"
        CMAKE_ARGS
            -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
            -DANDROID_ABI=${ANDROID_ABI}
            -DANDROID_PLATFORM=${ANDROID_PLATFORM}
            -DANDROID_STL=c++_shared
            -DCMAKE_BUILD_TYPE=Release
            -DCMAKE_INSTALL_PREFIX=<INSTALL_DIR>
            -DCMAKE_POSITION_INDEPENDENT_CODE=ON
            -DBUILD_SHARED_LIBS=OFF
            -DSD_BUILD_SHARED_LIBS=OFF
            -DSD_BUILD_EXAMPLES=OFF
            -DSD_WEBP=OFF
            -DSD_WEBM=OFF
            -DGGML_OPENMP=OFF
            -DGGML_NATIVE=OFF
            -DGGML_BACKEND_DL=OFF
            -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+i8mm
            -DSD_VULKAN=${LIAN_SD_VULKAN}
            -DGGML_VULKAN=${LIAN_SD_VULKAN}
            -DSPIRV-Headers_DIR=${SPIRV-Headers_DIR}
            -DCMAKE_CXX_FLAGS=-isystem\ ${LIAN_VK_HEADERS}\ -isystem\ ${LIAN_SPIRV_HEADERS}
        BUILD_BYPRODUCTS
            "${SD_BUILD}/libstable-diffusion.a"
            "${SD_BUILD}/ggml/src/libggml.a"
            "${SD_BUILD}/ggml/src/libggml-base.a"
            "${SD_BUILD}/ggml/src/libggml-cpu.a"
            "${SD_BUILD}/ggml/src/ggml-vulkan/libggml-vulkan.a"
        INSTALL_COMMAND ""
        BUILD_ALWAYS OFF
        USES_TERMINAL_BUILD OFF)

    add_library(lian_sd SHARED sd_jni.cpp jni_util.cpp)
    add_dependencies(lian_sd sd_ext)
    target_include_directories(lian_sd PRIVATE
        "${SD_SRC}/include"
        "${SD_SRC}/ggml/include")
    # The archive set is resolved at link time; --start-group lets the static
    # libraries reference each other without us having to order them by hand.
    target_link_libraries(lian_sd PRIVATE
        -Wl,--start-group
        "${SD_BUILD}/libstable-diffusion.a"
        "${SD_BUILD}/ggml/src/libggml.a"
        "${SD_BUILD}/ggml/src/libggml-base.a"
        "${SD_BUILD}/ggml/src/libggml-cpu.a"
        $<$<BOOL:${LIAN_SD_VULKAN}>:${SD_BUILD}/ggml/src/ggml-vulkan/libggml-vulkan.a>
        -Wl,--end-group
        android log
        $<$<BOOL:${LIAN_SD_VULKAN}>:vulkan>)
if (LIAN_SD_VULKAN)
    target_compile_definitions(lian_sd PRIVATE LIAN_HAS_VULKAN=1)
endif()
    target_link_options(lian_sd PRIVATE "-Wl,--exclude-libs,ALL")
else()
    message(STATUS "Image engine disabled - building lian_sd as a stub")
    add_library(lian_sd SHARED stub_jni.cpp)
    target_compile_definitions(lian_sd PRIVATE LIAN_STUB_LIB="lian_sd")
    target_link_libraries(lian_sd PRIVATE log)
endif()
