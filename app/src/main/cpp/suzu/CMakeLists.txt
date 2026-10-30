set(CMAKE_POSITION_INDEPENDENT_CODE ON)
# Keep inference optimized in an otherwise debuggable APK.
add_compile_options(-O3)
add_compile_definitions(NDEBUG)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CPU_REPACK OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
add_subdirectory("${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third_party/llama.cpp" "${CMAKE_CURRENT_BINARY_DIR}/llama" EXCLUDE_FROM_ALL)
add_library(suzu STATIC
    suzu-reranker.cpp
    ../../../../../third_party/suzu/model/vocab.cpp
    ../../../../../third_party/suzu/model/prefix_tree.cpp
    ../../../../../third_party/suzu/model/suzu_model.cpp)
set_target_properties(suzu PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_include_directories(suzu PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}" PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third_party/suzu")
target_link_libraries(suzu PRIVATE llama log)
target_compile_features(suzu PRIVATE cxx_std_17)
