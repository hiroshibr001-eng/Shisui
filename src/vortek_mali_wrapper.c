// Vortek Mali-G57 Vulkan ICD wrapper/proxy v1.3 - embedded real ICD
// Goal: intercept Vortek memory properties before DXVK chooses the problematic
// memory type that triggers VK_ERROR_MEMORY_MAP_FAILED on Mali/Vortek.
// Build target: aarch64-linux-gnu shared object, loaded inside Winlator rootfs.

#define _GNU_SOURCE
#include <vulkan/vulkan.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include "vortek_real_blob.inc"

#ifndef VKAPI_ATTR
#define VKAPI_ATTR
#endif
#ifndef VKAPI_CALL
#define VKAPI_CALL
#endif

#define WRAP_VERSION "v1.6-embedded-real-icd-surface-memtype-allocfix"

typedef VkResult (VKAPI_CALL *PFN_vk_icdNegotiateLoaderICDInterfaceVersion_LOCAL)(uint32_t* pSupportedVersion);
typedef PFN_vkVoidFunction (VKAPI_CALL *PFN_vk_icdGetInstanceProcAddr_LOCAL)(VkInstance instance, const char* pName);
typedef PFN_vkVoidFunction (VKAPI_CALL *PFN_vk_icdGetPhysicalDeviceProcAddr_LOCAL)(VkInstance instance, const char* pName);

static void* g_real_lib = NULL;
static char g_real_path[256] = {0};
static PFN_vkGetInstanceProcAddr g_real_gipa = NULL;
static PFN_vkGetDeviceProcAddr g_real_gdpa = NULL;
static PFN_vkMapMemory g_real_vkMapMemory = NULL;
static PFN_vkAllocateMemory g_real_vkAllocateMemory = NULL;
static PFN_vkGetPhysicalDeviceMemoryProperties g_real_vkGetPhysicalDeviceMemoryProperties = NULL;
static PFN_vkGetPhysicalDeviceMemoryProperties2 g_real_vkGetPhysicalDeviceMemoryProperties2 = NULL;
static PFN_vkEnumerateInstanceExtensionProperties g_real_vkEnumerateInstanceExtensionProperties = NULL;
static PFN_vk_icdNegotiateLoaderICDInterfaceVersion_LOCAL g_real_negotiate = NULL;
static PFN_vk_icdGetInstanceProcAddr_LOCAL g_real_icd_gipa = NULL;
static PFN_vk_icdGetPhysicalDeviceProcAddr_LOCAL g_real_icd_phys = NULL;

static const char* log_path(void) {
  const char* env = getenv("VORTEK_MALI_LOG");
  if (env && env[0]) return env;
  return "/storage/emulated/0/Download/vortek_mali_wrapper.log";
}

static int patch_enabled(void) {
  const char* env = getenv("VORTEK_MALI_MEMTYPE_PATCH");
  if (!env) return 1;
  if (!strcmp(env, "0") || !strcasecmp(env, "false") || !strcasecmp(env, "off")) return 0;
  return 1;
}

static void vlogf(const char* fmt, va_list ap) {
  FILE* f = fopen(log_path(), "a");
  if (!f) return;
  fprintf(f, "[vortek-mali-wrapper] ");
  vfprintf(f, fmt, ap);
  fprintf(f, "\n");
  fclose(f);
}

static void logf_wrap(const char* fmt, ...) {
  va_list ap;
  va_start(ap, fmt);
  vlogf(fmt, ap);
  va_end(ap);
}

static int write_all(int fd, const unsigned char* data, unsigned int len) {
  unsigned int off = 0;
  while (off < len) {
    ssize_t n = write(fd, data + off, len - off);
    if (n < 0) {
      if (errno == EINTR) continue;
      return -1;
    }
    if (n == 0) return -1;
    off += (unsigned int)n;
  }
  return 0;
}

static int extract_real_icd(void) {
  if (g_real_path[0]) return 0;

  const char* dirs[] = {
    getenv("TMPDIR"),
    "/tmp",
    "/data/data/com.winlator/cache",
    ".",
    NULL
  };

  for (int i = 0; dirs[i]; i++) {
    if (!dirs[i] || !dirs[i][0]) continue;
    char templ[256];
    snprintf(templ, sizeof(templ), "%s/vortek_real_XXXXXX", dirs[i]);
    int fd = mkstemp(templ);
    if (fd < 0) {
      logf_wrap("extract: mkstemp failed in %s: errno=%d", dirs[i], errno);
      continue;
    }

    if (write_all(fd, vortek_real_so, vortek_real_so_len) != 0) {
      logf_wrap("extract: write failed path=%s errno=%d", templ, errno);
      close(fd);
      unlink(templ);
      continue;
    }
    fsync(fd);
    close(fd);
    chmod(templ, 0700);
    strncpy(g_real_path, templ, sizeof(g_real_path) - 1);
    logf_wrap("embedded real ICD extracted to %s size=%u", g_real_path, vortek_real_so_len);
    return 0;
  }

  logf_wrap("FATAL: could not extract embedded real ICD");
  return -1;
}

static void* resolve_real_sym(const char* name) {
  if (!g_real_lib) return NULL;
  void* p = dlsym(g_real_lib, name);
  return p;
}

static int load_real_icd(void) {
  if (g_real_lib) return 0;

  if (extract_real_icd() != 0) return -1;

  g_real_lib = dlopen(g_real_path, RTLD_NOW | RTLD_LOCAL);
  if (!g_real_lib) {
    logf_wrap("FATAL: dlopen embedded real ICD failed path=%s err=%s", g_real_path, dlerror());
    return -1;
  }

  g_real_icd_gipa = (PFN_vk_icdGetInstanceProcAddr_LOCAL)resolve_real_sym("vk_icdGetInstanceProcAddr");
  g_real_icd_phys = (PFN_vk_icdGetPhysicalDeviceProcAddr_LOCAL)resolve_real_sym("vk_icdGetPhysicalDeviceProcAddr");
  g_real_gipa = (PFN_vkGetInstanceProcAddr)resolve_real_sym("vkGetInstanceProcAddr");
  g_real_gdpa = (PFN_vkGetDeviceProcAddr)resolve_real_sym("vkGetDeviceProcAddr");
  g_real_negotiate = (PFN_vk_icdNegotiateLoaderICDInterfaceVersion_LOCAL)resolve_real_sym("vk_icdNegotiateLoaderICDInterfaceVersion");

  if (!g_real_gipa && g_real_icd_gipa)
    g_real_gipa = (PFN_vkGetInstanceProcAddr)g_real_icd_gipa;

  if (g_real_gipa && !g_real_gdpa)
    g_real_gdpa = (PFN_vkGetDeviceProcAddr)g_real_gipa(VK_NULL_HANDLE, "vkGetDeviceProcAddr");

  logf_wrap("loaded wrapper %s; MEMTYPE_PATCH=%d", WRAP_VERSION, patch_enabled());
  logf_wrap("loaded embedded real ICD ok path=%s handle=%p gipa=%p gdpa=%p icd_gipa=%p", g_real_path, g_real_lib, (void*)g_real_gipa, (void*)g_real_gdpa, (void*)g_real_icd_gipa);

  return 0;
}

static PFN_vkVoidFunction real_gipa_call(VkInstance instance, const char* pName) {
  if (load_real_icd() != 0) return NULL;
  if (g_real_icd_gipa) {
    PFN_vkVoidFunction f = g_real_icd_gipa(instance, pName);
    if (f) return f;
  }
  if (g_real_gipa) return g_real_gipa(instance, pName);
  return NULL;
}

static PFN_vkVoidFunction real_gdpa_call(VkDevice device, const char* pName) {
  if (load_real_icd() != 0) return NULL;
  if (!g_real_gdpa && g_real_gipa)
    g_real_gdpa = (PFN_vkGetDeviceProcAddr)g_real_gipa(VK_NULL_HANDLE, "vkGetDeviceProcAddr");
  if (g_real_gdpa) return g_real_gdpa(device, pName);
  return real_gipa_call(VK_NULL_HANDLE, pName);
}

static void patch_memory_properties(VkPhysicalDeviceMemoryProperties* p) {
  if (!p || !patch_enabled()) return;

  logf_wrap("vkGetPhysicalDeviceMemoryProperties: count=%u", p->memoryTypeCount);
  for (uint32_t i = 0; i < p->memoryTypeCount; i++)
    logf_wrap("  before type[%u] flags=0x%x heap=%u", i, p->memoryTypes[i].propertyFlags, p->memoryTypes[i].heapIndex);

  if (p->memoryTypeCount >= 2) {
    VkMemoryPropertyFlags t0 = p->memoryTypes[0].propertyFlags;
    VkMemoryPropertyFlags t1 = p->memoryTypes[1].propertyFlags;

    const VkMemoryPropertyFlags hostCoherent = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
    if ((t0 & hostCoherent) == hostCoherent && (t1 & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)) {
      p->memoryTypes[0].propertyFlags &= ~(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT | VK_MEMORY_PROPERTY_HOST_CACHED_BIT);
      p->memoryTypes[1].propertyFlags |= (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT | VK_MEMORY_PROPERTY_HOST_CACHED_BIT);
      logf_wrap("PATCH APPLIED: demoted type0 flags 0x%x -> 0x%x; promoted type1 flags 0x%x -> 0x%x", t0, p->memoryTypes[0].propertyFlags, t1, p->memoryTypes[1].propertyFlags);
    } else {
      logf_wrap("PATCH SKIPPED: pattern mismatch t0=0x%x t1=0x%x", t0, t1);
    }
  }

  for (uint32_t i = 0; i < p->memoryTypeCount; i++)
    logf_wrap("  after  type[%u] flags=0x%x heap=%u", i, p->memoryTypes[i].propertyFlags, p->memoryTypes[i].heapIndex);
}

VKAPI_ATTR void VKAPI_CALL vkGetPhysicalDeviceMemoryProperties(
    VkPhysicalDevice physicalDevice,
    VkPhysicalDeviceMemoryProperties* pMemoryProperties) {
  if (!g_real_vkGetPhysicalDeviceMemoryProperties)
    g_real_vkGetPhysicalDeviceMemoryProperties = (PFN_vkGetPhysicalDeviceMemoryProperties)real_gipa_call(VK_NULL_HANDLE, "vkGetPhysicalDeviceMemoryProperties");

  if (!g_real_vkGetPhysicalDeviceMemoryProperties) {
    logf_wrap("FATAL: real vkGetPhysicalDeviceMemoryProperties not found");
    memset(pMemoryProperties, 0, sizeof(*pMemoryProperties));
    return;
  }

  g_real_vkGetPhysicalDeviceMemoryProperties(physicalDevice, pMemoryProperties);
  patch_memory_properties(pMemoryProperties);
}

VKAPI_ATTR void VKAPI_CALL vkGetPhysicalDeviceMemoryProperties2(
    VkPhysicalDevice physicalDevice,
    VkPhysicalDeviceMemoryProperties2* pMemoryProperties) {
  if (!g_real_vkGetPhysicalDeviceMemoryProperties2)
    g_real_vkGetPhysicalDeviceMemoryProperties2 = (PFN_vkGetPhysicalDeviceMemoryProperties2)real_gipa_call(VK_NULL_HANDLE, "vkGetPhysicalDeviceMemoryProperties2");

  if (!g_real_vkGetPhysicalDeviceMemoryProperties2) {
    PFN_vkGetPhysicalDeviceMemoryProperties legacy = (PFN_vkGetPhysicalDeviceMemoryProperties)real_gipa_call(VK_NULL_HANDLE, "vkGetPhysicalDeviceMemoryProperties");
    if (legacy && pMemoryProperties) {
      legacy(physicalDevice, &pMemoryProperties->memoryProperties);
      patch_memory_properties(&pMemoryProperties->memoryProperties);
    } else {
      logf_wrap("FATAL: real vkGetPhysicalDeviceMemoryProperties2 not found");
    }
    return;
  }

  g_real_vkGetPhysicalDeviceMemoryProperties2(physicalDevice, pMemoryProperties);
  if (pMemoryProperties)
    patch_memory_properties(&pMemoryProperties->memoryProperties);
}

VKAPI_ATTR void VKAPI_CALL vkGetPhysicalDeviceMemoryProperties2KHR(
    VkPhysicalDevice physicalDevice,
    VkPhysicalDeviceMemoryProperties2* pMemoryProperties) {
  vkGetPhysicalDeviceMemoryProperties2(physicalDevice, pMemoryProperties);
}


static int has_instance_ext(uint32_t count, const VkExtensionProperties* props, const char* name) {
  if (!props || !name) return 0;
  for (uint32_t i = 0; i < count; i++) {
    if (!strcmp(props[i].extensionName, name))
      return 1;
  }
  return 0;
}

VKAPI_ATTR VkResult VKAPI_CALL vkEnumerateInstanceExtensionProperties(
    const char* pLayerName,
    uint32_t* pPropertyCount,
    VkExtensionProperties* pProperties) {
  if (!g_real_vkEnumerateInstanceExtensionProperties)
    g_real_vkEnumerateInstanceExtensionProperties =
      (PFN_vkEnumerateInstanceExtensionProperties)real_gipa_call(VK_NULL_HANDLE, "vkEnumerateInstanceExtensionProperties");

  if (!g_real_vkEnumerateInstanceExtensionProperties) {
    logf_wrap("FATAL: real vkEnumerateInstanceExtensionProperties not found");
    if (pPropertyCount) *pPropertyCount = 0;
    return VK_ERROR_INITIALIZATION_FAILED;
  }

  // Do not touch explicit layer queries.
  if (pLayerName && pLayerName[0])
    return g_real_vkEnumerateInstanceExtensionProperties(pLayerName, pPropertyCount, pProperties);

  uint32_t realCount = 0;
  VkResult r0 = g_real_vkEnumerateInstanceExtensionProperties(pLayerName, &realCount, NULL);
  if (r0 != VK_SUCCESS && r0 != VK_INCOMPLETE) {
    logf_wrap("vkEnumerateInstanceExtensionProperties real count failed result=%d", (int)r0);
    return r0;
  }

  VkExtensionProperties* tmp = NULL;
  uint32_t gotCount = realCount;
  if (realCount > 0) {
    tmp = (VkExtensionProperties*)calloc(realCount, sizeof(VkExtensionProperties));
    if (!tmp) {
      logf_wrap("vkEnumerateInstanceExtensionProperties calloc failed count=%u", realCount);
      return VK_ERROR_OUT_OF_HOST_MEMORY;
    }
    VkResult r1 = g_real_vkEnumerateInstanceExtensionProperties(pLayerName, &gotCount, tmp);
    if (r1 != VK_SUCCESS && r1 != VK_INCOMPLETE) {
      logf_wrap("vkEnumerateInstanceExtensionProperties real list failed result=%d", (int)r1);
      free(tmp);
      return r1;
    }
  }

  const int hasSurface = has_instance_ext(gotCount, tmp, VK_KHR_SURFACE_EXTENSION_NAME);
  const uint32_t finalCount = gotCount + (hasSurface ? 0u : 1u);

  if (!pProperties) {
    if (pPropertyCount) *pPropertyCount = finalCount;
    logf_wrap("vkEnumerateInstanceExtensionProperties count real=%u final=%u injected_surface=%d", gotCount, finalCount, hasSurface ? 0 : 1);
    free(tmp);
    return VK_SUCCESS;
  }

  if (!pPropertyCount) {
    free(tmp);
    return VK_ERROR_INITIALIZATION_FAILED;
  }

  uint32_t cap = *pPropertyCount;
  uint32_t written = 0;

  for (uint32_t i = 0; i < gotCount && written < cap; i++)
    pProperties[written++] = tmp[i];

  if (!hasSurface && written < cap) {
    memset(&pProperties[written], 0, sizeof(VkExtensionProperties));
    strncpy(pProperties[written].extensionName, VK_KHR_SURFACE_EXTENSION_NAME, VK_MAX_EXTENSION_NAME_SIZE - 1);
    pProperties[written].specVersion = VK_KHR_SURFACE_SPEC_VERSION;
    written++;
    logf_wrap("PATCH APPLIED: injected VK_KHR_surface into instance extension list");
  }

  *pPropertyCount = written;
  logf_wrap("vkEnumerateInstanceExtensionProperties list real=%u final=%u cap=%u written=%u injected_surface=%d",
            gotCount, finalCount, cap, written, hasSurface ? 0 : 1);

  free(tmp);
  return (written < finalCount) ? VK_INCOMPLETE : VK_SUCCESS;
}



VKAPI_ATTR VkResult VKAPI_CALL vkAllocateMemory(
    VkDevice device,
    const VkMemoryAllocateInfo* pAllocateInfo,
    const VkAllocationCallbacks* pAllocator,
    VkDeviceMemory* pMemory) {
  if (!g_real_vkAllocateMemory)
    g_real_vkAllocateMemory = (PFN_vkAllocateMemory)real_gdpa_call(device, "vkAllocateMemory");

  if (!g_real_vkAllocateMemory) {
    logf_wrap("FATAL: real vkAllocateMemory not found");
    return VK_ERROR_OUT_OF_DEVICE_MEMORY;
  }

  if (patch_enabled() && pAllocateInfo && pAllocateInfo->memoryTypeIndex == 0) {
    VkMemoryAllocateInfo alt = *pAllocateInfo;
    alt.memoryTypeIndex = 1;

    VkResult r_alt = g_real_vkAllocateMemory(device, &alt, pAllocator, pMemory);
    logf_wrap("vkAllocateMemory FORCE type0->type1 size=%llu originalType=0 altType=1 result=%d",
              (unsigned long long)pAllocateInfo->allocationSize, (int)r_alt);

    if (r_alt == VK_SUCCESS) {
      logf_wrap("PATCH APPLIED: vkAllocateMemory type0 redirected to type1");
      return r_alt;
    }

    VkResult r_orig = g_real_vkAllocateMemory(device, pAllocateInfo, pAllocator, pMemory);
    logf_wrap("vkAllocateMemory fallback original type0 size=%llu result=%d",
              (unsigned long long)pAllocateInfo->allocationSize, (int)r_orig);
    return r_orig;
  }

  VkResult r = g_real_vkAllocateMemory(device, pAllocateInfo, pAllocator, pMemory);
  if (pAllocateInfo) {
    logf_wrap("vkAllocateMemory passthrough size=%llu type=%u result=%d",
              (unsigned long long)pAllocateInfo->allocationSize,
              (unsigned int)pAllocateInfo->memoryTypeIndex, (int)r);
  }
  return r;
}


VKAPI_ATTR VkResult VKAPI_CALL vkMapMemory(
    VkDevice device,
    VkDeviceMemory memory,
    VkDeviceSize offset,
    VkDeviceSize size,
    VkMemoryMapFlags flags,
    void** ppData) {
  if (!g_real_vkMapMemory)
    g_real_vkMapMemory = (PFN_vkMapMemory)real_gdpa_call(device, "vkMapMemory");

  if (!g_real_vkMapMemory) {
    logf_wrap("FATAL: real vkMapMemory not found");
    return VK_ERROR_MEMORY_MAP_FAILED;
  }

  VkResult r = g_real_vkMapMemory(device, memory, offset, size, flags, ppData);
  logf_wrap("vkMapMemory offset=%llu size=%llu flags=0x%x result=%d ptr=%p", (unsigned long long)offset, (unsigned long long)size, (unsigned int)flags, (int)r, ppData ? *ppData : NULL);
  return r;
}


// Forward declarations required because get_wrapper_func returns these symbols before their definitions.
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance instance, const char* pName);
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetDeviceProcAddr(VkDevice device, const char* pName);
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vk_icdGetInstanceProcAddr(VkInstance instance, const char* pName);
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vk_icdGetPhysicalDeviceProcAddr(VkInstance instance, const char* pName);
VKAPI_ATTR VkResult VKAPI_CALL vkAllocateMemory(VkDevice device, const VkMemoryAllocateInfo* pAllocateInfo, const VkAllocationCallbacks* pAllocator, VkDeviceMemory* pMemory);
VKAPI_ATTR VkResult VKAPI_CALL vk_icdNegotiateLoaderICDInterfaceVersion(uint32_t* pSupportedVersion);
VKAPI_ATTR VkResult VKAPI_CALL vkEnumerateInstanceExtensionProperties(const char* pLayerName, uint32_t* pPropertyCount, VkExtensionProperties* pProperties);

static PFN_vkVoidFunction get_wrapper_func(const char* pName) {
  if (!pName) return NULL;
  if (!strcmp(pName, "vkGetInstanceProcAddr")) return (PFN_vkVoidFunction)vkGetInstanceProcAddr;
  if (!strcmp(pName, "vkGetDeviceProcAddr")) return (PFN_vkVoidFunction)vkGetDeviceProcAddr;
  if (!strcmp(pName, "vk_icdGetInstanceProcAddr")) return (PFN_vkVoidFunction)vk_icdGetInstanceProcAddr;
  if (!strcmp(pName, "vk_icdNegotiateLoaderICDInterfaceVersion")) return (PFN_vkVoidFunction)vk_icdNegotiateLoaderICDInterfaceVersion;
  if (!strcmp(pName, "vk_icdGetPhysicalDeviceProcAddr")) return (PFN_vkVoidFunction)vk_icdGetPhysicalDeviceProcAddr;
  if (!strcmp(pName, "vkGetPhysicalDeviceMemoryProperties")) return (PFN_vkVoidFunction)vkGetPhysicalDeviceMemoryProperties;
  if (!strcmp(pName, "vkGetPhysicalDeviceMemoryProperties2")) return (PFN_vkVoidFunction)vkGetPhysicalDeviceMemoryProperties2;
  if (!strcmp(pName, "vkGetPhysicalDeviceMemoryProperties2KHR")) return (PFN_vkVoidFunction)vkGetPhysicalDeviceMemoryProperties2KHR;
  if (!strcmp(pName, "vkAllocateMemory")) return (PFN_vkVoidFunction)vkAllocateMemory;
  if (!strcmp(pName, "vkMapMemory")) return (PFN_vkVoidFunction)vkMapMemory;
  if (!strcmp(pName, "vkEnumerateInstanceExtensionProperties")) return (PFN_vkVoidFunction)vkEnumerateInstanceExtensionProperties;
  return NULL;
}

VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance instance, const char* pName) {
  PFN_vkVoidFunction wrap = get_wrapper_func(pName);
  if (wrap) return wrap;
  return real_gipa_call(instance, pName);
}

VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetDeviceProcAddr(VkDevice device, const char* pName) {
  PFN_vkVoidFunction wrap = get_wrapper_func(pName);
  if (wrap) return wrap;
  return real_gdpa_call(device, pName);
}

VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vk_icdGetInstanceProcAddr(VkInstance instance, const char* pName) {
  return vkGetInstanceProcAddr(instance, pName);
}

VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vk_icdGetPhysicalDeviceProcAddr(VkInstance instance, const char* pName) {
  PFN_vkVoidFunction wrap = get_wrapper_func(pName);
  if (wrap) return wrap;
  if (load_real_icd() != 0) return NULL;
  if (g_real_icd_phys) return g_real_icd_phys(instance, pName);
  return real_gipa_call(instance, pName);
}

VKAPI_ATTR VkResult VKAPI_CALL vk_icdNegotiateLoaderICDInterfaceVersion(uint32_t* pSupportedVersion) {
  if (load_real_icd() == 0 && g_real_negotiate)
    return g_real_negotiate(pSupportedVersion);
  if (pSupportedVersion) {
    if (*pSupportedVersion > 5) *pSupportedVersion = 5;
  }
  logf_wrap("vk_icdNegotiateLoaderICDInterfaceVersion fallback ok version=%u", pSupportedVersion ? *pSupportedVersion : 0);
  return VK_SUCCESS;
}

__attribute__((constructor))
static void wrapper_ctor(void) {
  logf_wrap("loaded wrapper %s constructor; MEMTYPE_PATCH=%d", WRAP_VERSION, patch_enabled());
}
