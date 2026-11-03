// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <ggml-cpu.h>
#include <string>

namespace feinianyu {
__attribute__((visibility("default"))) void ensureCpuBackend();
__attribute__((visibility("default"))) std::string cpuBackendLibrary();
__attribute__((visibility("default"))) ggml_threadpool *newThreadpool(int threads);
__attribute__((visibility("default"))) void freeThreadpool(ggml_threadpool *pool);
}
