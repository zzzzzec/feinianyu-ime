// SPDX-License-Identifier: GPL-3.0-or-later
#include "inference-runtime.h"
#include <ggml-backend.h>
#include <llama.h>
#include <dlfcn.h>
#include <mutex>
#include <stdexcept>

namespace feinianyu {
namespace {
std::once_flag initialized;
ggml_backend_reg_t cpu = nullptr;
decltype(ggml_threadpool_new) *createPool = nullptr;
decltype(ggml_threadpool_free) *destroyPool = nullptr;
std::string library;
}

void ensureCpuBackend() {
    std::call_once(initialized, [] {
        Dl_info location{};
        if (!dladdr(reinterpret_cast<void *>(&ensureCpuBackend), &location) || !location.dli_fname) {
            throw std::runtime_error("cpu_backend_path_unavailable");
        }
        const std::string path(location.dli_fname);
        const auto slash = path.find_last_of('/');
        if (slash == std::string::npos) throw std::runtime_error("cpu_backend_path_invalid");
        // Only app-installed libraries. Android's executable directory is /system/bin.
        ggml_backend_load_all_from_path(path.substr(0, slash).c_str());
        const auto device = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
        if (!device) throw std::runtime_error("cpu_backend_unavailable");
        cpu = ggml_backend_dev_backend_reg(device);
        createPool = reinterpret_cast<decltype(createPool)>(ggml_backend_reg_get_proc_address(cpu, "ggml_threadpool_new"));
        destroyPool = reinterpret_cast<decltype(destroyPool)>(ggml_backend_reg_get_proc_address(cpu, "ggml_threadpool_free"));
        if (!createPool || !destroyPool) throw std::runtime_error("cpu_threadpool_unavailable");
        Dl_info selected{};
        if (dladdr(reinterpret_cast<void *>(createPool), &selected) && selected.dli_fname) {
            library = selected.dli_fname;
            library = library.substr(library.find_last_of('/') + 1);
        }
        llama_backend_init();
    });
}

std::string cpuBackendLibrary() { ensureCpuBackend(); return library; }
ggml_threadpool *newThreadpool(int threads) {
    ensureCpuBackend();
    auto params = ggml_threadpool_params_default(threads);
    params.poll = 0; // Sleep between computations instead of spinning while the keyboard is idle.
    return createPool(&params);
}
void freeThreadpool(ggml_threadpool *pool) { if (pool && destroyPool) destroyPool(pool); }
}
