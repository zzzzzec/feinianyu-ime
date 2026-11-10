cmake_minimum_required(VERSION 3.18)

project(fcitx5-android-lib-fcitx5-chinese-addons VERSION ${VERSION_NAME})

# For reproducible build
add_link_options("LINKER:--hash-style=gnu,--build-id=none")

# prefab dependency
find_package(fcitx5 REQUIRED CONFIG)
get_target_property(FCITX5_CMAKE_MODULES fcitx5::cmake INTERFACE_INCLUDE_DIRECTORIES)
set(CMAKE_MODULE_PATH ${FCITX5_CMAKE_MODULES} ${CMAKE_MODULE_PATH})

find_package(ECM MODULE)
find_package(Fcitx5Core MODULE)
find_package(Fcitx5Module MODULE)

find_package(libime REQUIRED CONFIG)
get_target_property(LIBIME_CMAKE_MODULES libime::cmake INTERFACE_INCLUDE_DIRECTORIES)
set(CMAKE_MODULE_PATH ${LIBIME_CMAKE_MODULES} ${CMAKE_MODULE_PATH})

find_package(LibIMECore MODULE)
find_package(LibIMEPinyin MODULE)
find_package(LibIMETable MODULE)

find_package(fcitx5-lua REQUIRED CONFIG)
get_target_property(FCITX5_LUA_CMAKE_MODULES fcitx5-lua::cmake INTERFACE_INCLUDE_DIRECTORIES)
set(CMAKE_MODULE_PATH ${FCITX5_LUA_CMAKE_MODULES} ${CMAKE_MODULE_PATH})

find_package(Fcitx5ModuleLuaAddonLoader MODULE)

# dummy target to export src/main/cpp/cmake
add_custom_target(cmake)

# prebuilt boost
list(APPEND CMAKE_FIND_ROOT_PATH "${PREBUILT_DIR}/boost/${ANDROID_ABI}/lib/cmake")
find_package(Boost 1.87.0 REQUIRED COMPONENTS iostreams CONFIG)
add_compile_definitions("BOOST_ALL_NO_EMBEDDED_GDB_SCRIPTS")

# prebuilt marisa-tire, OpenCC needs it
set(Marisa_DIR "${PREBUILT_DIR}/marisa/${ANDROID_ABI}/lib/cmake/Marisa")
find_package(Marisa)

# prebuilt opencc
set(OpenCC_DIR "${PREBUILT_DIR}/opencc/${ANDROID_ABI}/lib/cmake/opencc")
find_package(OpenCC)

# prebuilt nlohmann_json
set(nlohmann_json_DIR "${PREBUILT_DIR}/nlohmann_json/${ANDROID_ABI}/share/cmake/nlohmann_json")
find_package(nlohmann_json)

option(ENABLE_TEST "" OFF)
option(ENABLE_GUI "" OFF)
option(ENABLE_BROWSER "" OFF)
option(USE_WEBKIT "" OFF)
option(ENABLE_CLOUDPINYIN "" OFF)
# prefer OpenCC_DIR rather than fcitx5-chinese-addons/cmake/FindOpenCC.cmake
set(CMAKE_FIND_PACKAGE_PREFER_CONFIG ON)
add_subdirectory(fcitx5-chinese-addons)

# Add the Android-only correction hook to a generated copy of the pinned addon.
set(pinyin_source_dir "${CMAKE_CURRENT_SOURCE_DIR}/fcitx5-chinese-addons/im/pinyin")
set(pinyin_source "${pinyin_source_dir}/pinyin.cpp")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${pinyin_source}")
file(READ "${pinyin_source}" pinyin_code)
set(correction_anchor "        inputPanel.setCandidateList(std::move(candidateList));\n    } while (0);")
string(FIND "${pinyin_code}" "${correction_anchor}" correction_position)
if(correction_position EQUAL -1)
    message(FATAL_ERROR "Pinyin candidate construction changed. Review the Android correction hook.")
endif()
string(REPLACE "${correction_anchor}"
    "        inputPanel.setCandidateList(std::move(candidateList));\n        addTypingCorrections(*this, *inputContext, context, inputPanel.candidateList());\n    } while (0);"
    pinyin_code "${pinyin_code}")
string(REPLACE "#include \"pinyin.h\"" "#include \"pinyin.h\"\n#include \"typing-correction.h\""
    pinyin_code "${pinyin_code}")
set(pinyin_android_source "${CMAKE_CURRENT_BINARY_DIR}/pinyin-android.cpp")
file(CONFIGURE OUTPUT "${pinyin_android_source}" CONTENT "${pinyin_code}" @ONLY)
get_target_property(pinyin_sources pinyin SOURCES)
list(REMOVE_ITEM pinyin_sources "pinyin.cpp")
set_property(TARGET pinyin PROPERTY SOURCES "${pinyin_sources}")
target_sources(pinyin PRIVATE "${pinyin_android_source}" "${CMAKE_CURRENT_SOURCE_DIR}/typing-correction.cpp")
target_include_directories(pinyin PRIVATE "${pinyin_source_dir}" "${CMAKE_CURRENT_SOURCE_DIR}")

# rename to include executable in apk
set_target_properties(scel2org5 PROPERTIES OUTPUT_NAME libscel2org5.so)
