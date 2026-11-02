add_definitions(-DFCITX_GETTEXT_DOMAIN=\"fcitx5-android\")

add_library(androidfrontend MODULE androidfrontend.cpp ranking-diagnostics.cpp input-recording.cpp)
target_link_libraries(androidfrontend Fcitx5::Core LibIME::Pinyin suzu)
target_include_directories(androidfrontend PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../lib/fcitx5-chinese-addons/src/main/cpp/fcitx5-chinese-addons/im/pinyin")

configure_file(androidfrontend.conf.in.in androidfrontend.conf.in @ONLY)
fcitx5_translate_desktop_file(${CMAKE_CURRENT_BINARY_DIR}/androidfrontend.conf.in androidfrontend.conf)
install(FILES "${CMAKE_CURRENT_BINARY_DIR}/androidfrontend.conf"
        DESTINATION "${FCITX_INSTALL_PKGDATADIR}/addon"
        COMPONENT config)
