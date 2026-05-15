// Vortek Mali-G57 Vulkan ICD wrapper/proxy - experimental
// Goal: force DXVK away from Vortek memory Type 0 (DEVICE_LOCAL|HOST_VISIBLE|HOST_COHERENT)
// by making Type 1 (DEVICE_LOCAL|HOST_VISIBLE|HOST_CACHED) appear coherent and Type 0 non-coherent.
// This targets DXVK logs like:
//   VK_ERROR_MEMORY_MAP_FAILED, Mem flags: 0x6, Mem types: 0x3
// Build target: aarch64-linux-gnu shared object, not Android/NDK.

#define _GNU_SOURCE
#include <vulkan/vulkan.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <stdint.h>
#include <limits.h>
#include <unistd.h>

#ifndef VKAPI_ATTR
#define VKAPI_ATTR
#endif
#ifndef VKAPI_CALL
#define VKAPI_CALL
#endif

// Vulkan ICD private entry type used by loader.
typedef PFN_vkVoidFunction (VKAPI_CALL *PFN_vk_icdGetInstanceProcAddr)(VkInstance instance, const char* pName);
typedef VkResult (VKAPI_CALL *PFN_vk_icdNegotiateLoaderICDInterfaceVersion)(uint32_t* pSupportedVersion);

static void* g_real = NULL;
static PFN_vk_icdGetInstanceProcAddr g_real_icd_gipa = NULL;
static PFN_vkGetInstanceProcAddr g_real_gipa = NULL;
static PFN_vkGetDeviceProcAddr g_real_gdpa = NULL;
static PFN_vkGetPhysicalDeviceMemoryProperties g_real_get_mem_props = NULL;
static PFN_vkGetPhysicalDeviceMemoryProperties2 g_real_get_mem_props2 = NULL;
static PFN_vkMapMemory g_real_map_memory = NULL;

static int env_enabled(const char* name, int def) {
  const char* v = getenv(name);
  if (!v || !*v) return def;
  if (!strcmp(v, "0") || !strcasecmp(v, "false") || !strcasecmp(v, "off")) return 0;
  return 1;
}

static void mw_log(const char* fmt, ...) {
  if (!env_enabled("VORTEK_MALI_LOG_ENABLE", 1)) return;

  const char* path = getenv("VORTEK_MALI_LOG");
  if (!path || !*path) path = "/storage/emulated/0/Download/vortek_mali_wrapper.log";

  FILE* f = fopen(path, "a");
  if (!f) f = stderr;

  va_list ap;
  va_start(ap, fmt);
  fprintf(f, "[vortek-mali-wrapper] ");
  vfprintf(f, fmt, ap);
  fprintf(f, "\n");
  va_end(ap);

  if (f != stderr) fclose(f);
}

static void load_real_icd(void) {
  if (g_real) return;

  char origin_path[PATH_MAX] = {0};
  char cwd_path[PATH_MAX] = {0};
  char proc_maps_path[PATH_MAX] = {0};

  // Prefer loading the renamed original ICD from the exact same directory as this wrapper.
  Dl_info info;
  if (dladdr((void*)&load_real_icd, &info) && info.dli_fname && info.dli_fname[0]) {
    snprintf(origin_path, sizeof(origin_path), "%s", info.dli_fname);
    char* slash = strrchr(origin_path, '/');
    if (slash) {
      slash[1] = '\0';
      strncat(origin_path, "libvulkan_vortek_real.so", sizeof(origin_path) - strlen(origin_path) - 1);
    } else {
      origin_path[0] = '\0';
    }
  }

  if (getcwd(cwd_path, sizeof(cwd_path))) {
    size_t len = strlen(cwd_path);
    if (len + strlen("/libvulkan_vortek_real.so") + 1 < sizeof(cwd_path))
      strcat(cwd_path, "/libvulkan_vortek_real.so");
    else
      cwd_path[0] = '\0';
  } else {
    cwd_path[0] = '\0';
  }

  const char* env_path = getenv("VORTEK_REAL_ICD_PATH");
  const char* candidates[] = {
    env_path,
    origin_path,
    cwd_path,
    "/usr/lib/libvulkan_vortek_real.so",
    "/usr/lib/aarch64-linux-gnu/libvulkan_vortek_real.so",
    "/usr/local/lib/libvulkan_vortek_real.so",
    "./libvulkan_vortek_real.so",
    "libvulkan_vortek_real.so",
    NULL
  };

  for (int i = 0; candidates[i]; i++) {
    if (!candidates[i] || !*candidates[i]) continue;
    dlerror();
    g_real = dlopen(candidates[i], RTLD_NOW | RTLD_GLOBAL);
    if (g_real) {
      mw_log("loaded real ICD: %s", candidates[i]);
      break;
    }
    const char* e = dlerror();
    mw_log("dlopen failed for %s: %s", candidates[i], e ? e : "unknown");
  }

  if (!g_real) {
    mw_log("FATAL: could not load libvulkan_vortek_real.so; wrapper path=%s cwd_candidate=%s", origin_path, cwd_path);
    return;
  }

  g_real_icd_gipa = (PFN_vk_icdGetInstanceProcAddr)dlsym(g_real, "vk_icdGetInstanceProcAddr");
  g_real_gipa = (PFN_vkGetInstanceProcAddr)dlsym(g_real, "vkGetInstanceProcAddr");
  g_real_gdpa = (PFN_vkGetDeviceProcAddr)dlsym(g_real, "vkGetDeviceProcAddr");
  g_real_get_mem_props = (PFN_vkGetPhysicalDeviceMemoryProperties)dlsym(g_real, "vkGetPhysicalDeviceMemoryProperties");
  g_real_get_mem_props2 = (PFN_vkGetPhysicalDeviceMemoryProperties2)dlsym(g_real, "vkGetPhysicalDeviceMemoryProperties2");
  g_real_map_memory = (PFN_vkMapMemory)dlsym(g_real, "vkMapMemory");

  mw_log("symbols: icd_gipa=%p gipa=%p gdpa=%p mem=%p mem2=%p map=%p",
    (void*)g_real_icd_gipa, (void*)g_real_gipa, (void*)g_real_gdpa,
    (void*)g_real_get_mem_props, (void*)g_real_get_mem_props2, (void*)g_real_map_memory);
}
static PFN_vkVoidFunction real_instance_proc(VkInstance instance, const char* name) {
  load_real_icd();
  if (g_real_icd_gipa) return g_real_icd_gipa(instance, name);
  if (g_real_gipa) return g_real_gipa(instance, name);
  return g_real ? (PFN_vkVoidFunction)dlsym(g_real, name) : NULL;
}

static void patch_memory_properties(VkPhysicalDeviceMemoryProperties* p) {
  if (!p || !env_enabled("VORTEK_MALI_MEMTYPE_PATCH", 1)) return;

  mw_log("memoryTypeCount=%u memoryHeapCount=%u", p->memoryTypeCount, p->memoryHeapCount);
  for (uint32_t i = 0; i < p->memoryTypeCount; i++) {
    mw_log("before type[%u] flags=0x%x heap=%u", i, p->memoryTypes[i].propertyFlags, p->memoryTypes[i].heapIndex);
  }

  // Target shape seen in Helio G96 / Mali-G57 / Vortek logs:
  // Type 0 = 0x7: DEVICE_LOCAL | HOST_VISIBLE | HOST_COHERENT
  // Type 1 = 0xb: DEVICE_LOCAL | HOST_VISIBLE | HOST_CACHED
  // DXVK asks for flags 0x6 and bits 0x3, then chooses Type 0 and vkMapMemory fails.
  if (p->memoryTypeCount >= 2) {
    VkMemoryPropertyFlags t0 = p->memoryTypes[0].propertyFlags;
    VkMemoryPropertyFlags t1 = p->memoryTypes[1].propertyFlags;

    const VkMemoryPropertyFlags devHostCoherent =
      VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT |
      VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
      VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;

    const VkMemoryPropertyFlags devHostCached =
      VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT |
      VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
      VK_MEMORY_PROPERTY_HOST_CACHED_BIT;

    int t0_matches = (t0 & devHostCoherent) == devHostCoherent;
    int t1_matches = (t1 & devHostCached) == devHostCached;

    if (t0_matches && t1_matches) {
      // Hide coherent from Type 0 so DXVK will not select it for HOST_VISIBLE|HOST_COHERENT.
      p->memoryTypes[0].propertyFlags &= ~VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;

      // Make Type 1 satisfy HOST_VISIBLE|HOST_COHERENT queries, pushing DXVK to cached memory.
      // This is an intentional compatibility lie. If rendering corrupts, disable with:
      // VORTEK_MALI_MEMTYPE_PATCH=0
      p->memoryTypes[1].propertyFlags |= VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;

      mw_log("PATCH APPLIED: Type0 flags 0x%x->0x%x, Type1 flags 0x%x->0x%x",
        t0, p->memoryTypes[0].propertyFlags, t1, p->memoryTypes[1].propertyFlags);
    } else {
      mw_log("patch skipped: unexpected flags t0=0x%x t1=0x%x", t0, t1);
    }
  }

  for (uint32_t i = 0; i < p->memoryTypeCount; i++) {
    mw_log("after type[%u] flags=0x%x heap=%u", i, p->memoryTypes[i].propertyFlags, p->memoryTypes[i].heapIndex);
  }
}

VKAPI_ATTR void VKAPI_CALL wrap_vkGetPhysicalDeviceMemoryProperties(
    VkPhysicalDevice physicalDevice,
    VkPhysicalDeviceMemoryProperties* pMemoryProperties) {
  if (!g_real_get_mem_props)
    g_real_get_mem_props = (PFN_vkGetPhysicalDeviceMemoryProperties)real_instance_proc(NULL, "vkGetPhysicalDeviceMemoryProperties");

  if (g_real_get_mem_props)
    g_real_get_mem_props(physicalDevice, pMemoryProperties);

  patch_memory_properties(pMemoryProperties);
}

VKAPI_ATTR void VKAPI_CALL wrap_vkGetPhysicalDeviceMemoryProperties2(
    VkPhysicalDevice physicalDevice,
    VkPhysicalDeviceMemoryProperties2* pMemoryProperties) {
  if (!g_real_get_mem_props2)
    g_real_get_mem_props2 = (PFN_vkGetPhysicalDeviceMemoryProperties2)real_instance_proc(NULL, "vkGetPhysicalDeviceMemoryProperties2");

  if (g_real_get_mem_props2)
    g_real_get_mem_props2(physicalDevice, pMemoryProperties);

  if (pMemoryProperties)
    patch_memory_properties(&pMemoryProperties->memoryProperties);
}

VKAPI_ATTR VkResult VKAPI_CALL wrap_vkMapMemory(
    VkDevice device,
    VkDeviceMemory memory,
    VkDeviceSize offset,
    VkDeviceSize size,
    VkMemoryMapFlags flags,
    void** ppData) {
  if (!g_real_map_memory)
    g_real_map_memory = (PFN_vkMapMemory)real_instance_proc(NULL, "vkMapMemory");

  if (!g_real_map_memory) {
    mw_log("vkMapMemory: real function missing");
    return VK_ERROR_INITIALIZATION_FAILED;
  }

  VkResult r = g_real_map_memory(device, memory, offset, size, flags, ppData);
  mw_log("vkMapMemory result=%d memory=%p offset=%llu size=%llu flags=0x%x data=%p",
    r, (void*)memory, (unsigned long long)offset, (unsigned long long)size, flags,
    ppData ? *ppData : NULL);
  return r;
}

static PFN_vkVoidFunction wrap_name(const char* name, PFN_vkVoidFunction real) {
  if (!name) return real;
  if (!strcmp(name, "vkGetInstanceProcAddr")) return (PFN_vkVoidFunction)&vkGetInstanceProcAddr;
  if (!strcmp(name, "vkGetDeviceProcAddr")) return (PFN_vkVoidFunction)&vkGetDeviceProcAddr;
  if (!strcmp(name, "vkGetPhysicalDeviceMemoryProperties")) return (PFN_vkVoidFunction)&wrap_vkGetPhysicalDeviceMemoryProperties;
  if (!strcmp(name, "vkGetPhysicalDeviceMemoryProperties2")) return (PFN_vkVoidFunction)&wrap_vkGetPhysicalDeviceMemoryProperties2;
  if (!strcmp(name, "vkMapMemory")) return (PFN_vkVoidFunction)&wrap_vkMapMemory;
  return real;
}

VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetDeviceProcAddr(VkDevice device, const char* pName) {
  load_real_icd();
  if (!g_real_gdpa) {
    PFN_vkVoidFunction f = real_instance_proc(NULL, "vkGetDeviceProcAddr");
    g_real_gdpa = (PFN_vkGetDeviceProcAddr)f;
  }
  PFN_vkVoidFunction real = g_real_gdpa ? g_real_gdpa(device, pName) : NULL;
  return wrap_name(pName, real);
}

VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance instance, const char* pName) {
  PFN_vkVoidFunction real = real_instance_proc(instance, pName);
  return wrap_name(pName, real);
}

VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vk_icdGetInstanceProcAddr(VkInstance instance, const char* pName) {
  PFN_vkVoidFunction real = real_instance_proc(instance, pName);
  return wrap_name(pName, real);
}

VKAPI_ATTR VkResult VKAPI_CALL vk_icdNegotiateLoaderICDInterfaceVersion(uint32_t* pSupportedVersion) {
  load_real_icd();
  PFN_vk_icdNegotiateLoaderICDInterfaceVersion real = NULL;
  if (g_real) real = (PFN_vk_icdNegotiateLoaderICDInterfaceVersion)dlsym(g_real, "vk_icdNegotiateLoaderICDInterfaceVersion");

  if (real) {
    VkResult r = real(pSupportedVersion);
    mw_log("vk_icdNegotiateLoaderICDInterfaceVersion real result=%d version=%u", r, pSupportedVersion ? *pSupportedVersion : 0);
    return r;
  }

  if (pSupportedVersion && *pSupportedVersion > 5) *pSupportedVersion = 5;
  mw_log("vk_icdNegotiateLoaderICDInterfaceVersion fallback version=%u", pSupportedVersion ? *pSupportedVersion : 0);
  return VK_SUCCESS;
}

__attribute__((constructor))
static void on_load(void) {
  mw_log("loaded wrapper v1.1; MEMTYPE_PATCH=%d", env_enabled("VORTEK_MALI_MEMTYPE_PATCH", 1));
}
