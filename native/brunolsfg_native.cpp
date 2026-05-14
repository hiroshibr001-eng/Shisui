#include <jni.h>
#include <android/log.h>
#include <vulkan/vulkan.h>

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <dirent.h>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#define LOG_TAG "BrunoFrameNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static bool gEngineReady = false;
static int gMultiplier = 2;
static float gFlowScale = 0.35f;
static int gRenderScalePercent = 100;
static int gInputWidth = 0;
static int gInputHeight = 0;
static int gOutputWidth = 0;
static int gOutputHeight = 0;
static bool gBalancedEfficiency = false;
static std::string gEngineStatus = "Motor LSFG não preparado";

static std::string vkResultToString(VkResult result) {
    switch (result) {
        case VK_SUCCESS: return "VK_SUCCESS";
        case VK_NOT_READY: return "VK_NOT_READY";
        case VK_TIMEOUT: return "VK_TIMEOUT";
        case VK_EVENT_SET: return "VK_EVENT_SET";
        case VK_EVENT_RESET: return "VK_EVENT_RESET";
        case VK_INCOMPLETE: return "VK_INCOMPLETE";
        case VK_ERROR_OUT_OF_HOST_MEMORY: return "VK_ERROR_OUT_OF_HOST_MEMORY";
        case VK_ERROR_OUT_OF_DEVICE_MEMORY: return "VK_ERROR_OUT_OF_DEVICE_MEMORY";
        case VK_ERROR_INITIALIZATION_FAILED: return "VK_ERROR_INITIALIZATION_FAILED";
        case VK_ERROR_DEVICE_LOST: return "VK_ERROR_DEVICE_LOST";
        case VK_ERROR_MEMORY_MAP_FAILED: return "VK_ERROR_MEMORY_MAP_FAILED";
        case VK_ERROR_LAYER_NOT_PRESENT: return "VK_ERROR_LAYER_NOT_PRESENT";
        case VK_ERROR_EXTENSION_NOT_PRESENT: return "VK_ERROR_EXTENSION_NOT_PRESENT";
        case VK_ERROR_FEATURE_NOT_PRESENT: return "VK_ERROR_FEATURE_NOT_PRESENT";
        case VK_ERROR_INCOMPATIBLE_DRIVER: return "VK_ERROR_INCOMPATIBLE_DRIVER";
        default: return "VK_ERROR_" + std::to_string((int) result);
    }
}

static bool checkDllHeader(const std::string& path, long* outSize) {
    if (outSize) *outSize = 0;
    FILE* file = std::fopen(path.c_str(), "rb");
    if (!file) return false;
    unsigned char header[2] = {0, 0};
    size_t read = std::fread(header, 1, 2, file);
    std::fseek(file, 0, SEEK_END);
    long size = std::ftell(file);
    std::fclose(file);
    if (outSize) *outSize = size;
    return read == 2 && header[0] == 'M' && header[1] == 'Z' && size > 1024 * 1024;
}

struct VulkanContext {
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    uint32_t queueFamily = 0;
    std::string gpuName;
    uint32_t apiMajor = 0;
    uint32_t apiMinor = 0;

    void destroy() {
        if (device != VK_NULL_HANDLE) {
            vkDeviceWaitIdle(device);
            vkDestroyDevice(device, nullptr);
            device = VK_NULL_HANDLE;
        }
        if (instance != VK_NULL_HANDLE) {
            vkDestroyInstance(instance, nullptr);
            instance = VK_NULL_HANDLE;
        }
    }
};

static VkResult createVulkanContext(VulkanContext& ctx) {
    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "BrunoFrameOverlay";
    appInfo.applicationVersion = VK_MAKE_VERSION(3, 0, 0);
    appInfo.pEngineName = "BrunoFrameLsfgReady";
    appInfo.engineVersion = VK_MAKE_VERSION(3, 0, 0);
    appInfo.apiVersion = VK_API_VERSION_1_1;

    VkInstanceCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    createInfo.pApplicationInfo = &appInfo;

    VkResult res = vkCreateInstance(&createInfo, nullptr, &ctx.instance);
    if (res != VK_SUCCESS) return res;

    uint32_t deviceCount = 0;
    res = vkEnumeratePhysicalDevices(ctx.instance, &deviceCount, nullptr);
    if (res != VK_SUCCESS || deviceCount == 0) return res != VK_SUCCESS ? res : VK_ERROR_INITIALIZATION_FAILED;

    std::vector<VkPhysicalDevice> devices(deviceCount);
    res = vkEnumeratePhysicalDevices(ctx.instance, &deviceCount, devices.data());
    if (res != VK_SUCCESS) return res;

    // Prefer a GPU with compute capability.
    for (VkPhysicalDevice candidate : devices) {
        uint32_t familyCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, &familyCount, nullptr);
        std::vector<VkQueueFamilyProperties> families(familyCount);
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, &familyCount, families.data());
        for (uint32_t i = 0; i < familyCount; ++i) {
            if ((families[i].queueFlags & VK_QUEUE_COMPUTE_BIT) != 0) {
                ctx.physicalDevice = candidate;
                ctx.queueFamily = i;
                break;
            }
        }
        if (ctx.physicalDevice != VK_NULL_HANDLE) break;
    }
    if (ctx.physicalDevice == VK_NULL_HANDLE) return VK_ERROR_FEATURE_NOT_PRESENT;

    VkPhysicalDeviceProperties props{};
    vkGetPhysicalDeviceProperties(ctx.physicalDevice, &props);
    ctx.gpuName = props.deviceName;
    ctx.apiMajor = VK_VERSION_MAJOR(props.apiVersion);
    ctx.apiMinor = VK_VERSION_MINOR(props.apiVersion);

    float priority = 1.0f;
    VkDeviceQueueCreateInfo queueInfo{};
    queueInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queueInfo.queueFamilyIndex = ctx.queueFamily;
    queueInfo.queueCount = 1;
    queueInfo.pQueuePriorities = &priority;

    VkDeviceCreateInfo deviceInfo{};
    deviceInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    deviceInfo.queueCreateInfoCount = 1;
    deviceInfo.pQueueCreateInfos = &queueInfo;

    res = vkCreateDevice(ctx.physicalDevice, &deviceInfo, nullptr, &ctx.device);
    return res;
}

static bool readFile(const std::string& path, std::vector<uint32_t>& words) {
    std::ifstream input(path, std::ios::binary | std::ios::ate);
    if (!input.good()) return false;
    std::streamsize size = input.tellg();
    if (size < 20 || size > 8 * 1024 * 1024 || (size % 4) != 0) return false;
    input.seekg(0, std::ios::beg);
    words.resize(static_cast<size_t>(size / 4));
    if (!input.read(reinterpret_cast<char*>(words.data()), size)) return false;
    return !words.empty() && words[0] == 0x07230203u;
}

static std::vector<std::string> listSpirvFiles(const std::string& shaderDir) {
    std::vector<std::string> result;
    DIR* dir = opendir(shaderDir.c_str());
    if (!dir) return result;
    struct dirent* ent;
    while ((ent = readdir(dir)) != nullptr) {
        std::string name = ent->d_name ? ent->d_name : "";
        if (name.size() >= 4 && name.substr(name.size() - 4) == ".spv") {
            result.push_back(shaderDir + "/" + name);
        }
    }
    closedir(dir);
    std::sort(result.begin(), result.end());
    return result;
}

static std::string vulkanSummary() {
    VulkanContext ctx;
    VkResult res = createVulkanContext(ctx);
    if (res != VK_SUCCESS) {
        ctx.destroy();
        return "Vulkan init falhou: " + vkResultToString(res);
    }
    std::ostringstream oss;
    oss << "Vulkan OK • GPU: " << ctx.gpuName
        << " • API " << ctx.apiMajor << "." << ctx.apiMinor
        << " • compute queue " << ctx.queueFamily;
    ctx.destroy();
    return oss.str();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bruno_frameoverlay_NativeLsfgBridge_nativeGetVulkanSummary(JNIEnv* env, jclass) {
    std::string result = vulkanSummary();
    return env->NewStringUTF(result.c_str());
}

static std::string configureInternal(const std::string& path,
                                     int inputWidth, int inputHeight,
                                     int outputWidth, int outputHeight,
                                     int multiplier, float flowScale,
                                     int renderScalePercent,
                                     bool balancedEfficiency) {
    gMultiplier = std::max(2, std::min(8, (int) multiplier));
    gFlowScale = std::max(0.10f, std::min(1.00f, (float) flowScale));
    gRenderScalePercent = std::max(50, std::min(100, (int) renderScalePercent));
    gInputWidth = std::max(1, (int) inputWidth);
    gInputHeight = std::max(1, (int) inputHeight);
    gOutputWidth = std::max(1, (int) outputWidth);
    gOutputHeight = std::max(1, (int) outputHeight);
    gBalancedEfficiency = balancedEfficiency;

    long dllSize = 0;
    bool dllOk = checkDllHeader(path, &dllSize);
    if (!dllOk) return "Native: DLL inválida ou ausente";

    double inputPixels = (double) gInputWidth * (double) gInputHeight;
    double outputPixels = (double) gOutputWidth * (double) gOutputHeight;
    double percentPixels = outputPixels > 1.0 ? (inputPixels * 100.0 / outputPixels) : 100.0;

    std::ostringstream oss;
    oss << "Native DLL OK • " << (dllSize / (1024 * 1024)) << " MB"
        << " • input " << gInputWidth << "x" << gInputHeight
        << " → output " << gOutputWidth << "x" << gOutputHeight
        << " • Render Scale " << gRenderScalePercent << "%"
        << " (~" << (int) (percentPixels + 0.5) << "% pixels)"
        << " • " << gMultiplier << "x"
        << " • flow " << gFlowScale
        << " • eficiência " << (gBalancedEfficiency ? "equilibrada" : "qualidade")
        << " • params updated";
    return oss.str();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bruno_frameoverlay_NativeLsfgBridge_nativeConfigureScaled(JNIEnv* env, jclass, jstring dllPath,
                                                                   jint inputWidth, jint inputHeight,
                                                                   jint outputWidth, jint outputHeight,
                                                                   jint multiplier, jfloat flowScale,
                                                                   jint renderScalePercent,
                                                                   jboolean balancedEfficiency) {
    const char* pathChars = env->GetStringUTFChars(dllPath, nullptr);
    std::string path = pathChars ? pathChars : "";
    if (pathChars) env->ReleaseStringUTFChars(dllPath, pathChars);
    std::string result = configureInternal(path, inputWidth, inputHeight, outputWidth, outputHeight,
                                           multiplier, flowScale, renderScalePercent,
                                           balancedEfficiency == JNI_TRUE);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bruno_frameoverlay_NativeLsfgBridge_nativeConfigure(JNIEnv* env, jclass, jstring dllPath,
                                                             jint width, jint height,
                                                             jint multiplier, jfloat flowScale) {
    const char* pathChars = env->GetStringUTFChars(dllPath, nullptr);
    std::string path = pathChars ? pathChars : "";
    if (pathChars) env->ReleaseStringUTFChars(dllPath, pathChars);
    std::string result = configureInternal(path, width, height, width, height, multiplier, flowScale, 100, false);
    return env->NewStringUTF(result.c_str());
}

static std::string prepareEngineInternal(const std::string& shaderDir,
                                         int inputWidth, int inputHeight,
                                         int outputWidth, int outputHeight,
                                         int multiplier, float flowScale,
                                         int renderScalePercent,
                                         bool balancedEfficiency) {
    gEngineReady = false;
    gMultiplier = std::max(2, std::min(8, (int) multiplier));
    gFlowScale = std::max(0.10f, std::min(1.00f, (float) flowScale));
    gRenderScalePercent = std::max(50, std::min(100, (int) renderScalePercent));
    gInputWidth = std::max(1, (int) inputWidth);
    gInputHeight = std::max(1, (int) inputHeight);
    gOutputWidth = std::max(1, (int) outputWidth);
    gOutputHeight = std::max(1, (int) outputHeight);
    gBalancedEfficiency = balancedEfficiency;
    gEngineStatus = "Motor LSFG não preparado";

    VulkanContext ctx;
    VkResult res = createVulkanContext(ctx);
    if (res != VK_SUCCESS) {
        ctx.destroy();
        gEngineStatus = "LSFG Vulkan falhou: " + vkResultToString(res);
        return gEngineStatus;
    }

    const std::string gpuName = ctx.gpuName;
    std::vector<std::string> files = listSpirvFiles(shaderDir);
    int validModules = 0;
    int invalidModules = 0;
    size_t totalWords = 0;

    for (const std::string& file : files) {
        std::vector<uint32_t> code;
        if (!readFile(file, code)) {
            invalidModules++;
            continue;
        }
        VkShaderModuleCreateInfo moduleInfo{};
        moduleInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
        moduleInfo.codeSize = code.size() * sizeof(uint32_t);
        moduleInfo.pCode = code.data();
        VkShaderModule module = VK_NULL_HANDLE;
        VkResult mres = vkCreateShaderModule(ctx.device, &moduleInfo, nullptr, &module);
        if (mres == VK_SUCCESS && module != VK_NULL_HANDLE) {
            validModules++;
            totalWords += code.size();
            vkDestroyShaderModule(ctx.device, module, nullptr);
        } else {
            invalidModules++;
        }
    }

    ctx.destroy();

    double inputPixels = (double) gInputWidth * (double) gInputHeight;
    double outputPixels = (double) gOutputWidth * (double) gOutputHeight;
    double percentPixels = outputPixels > 1.0 ? (inputPixels * 100.0 / outputPixels) : 100.0;

    std::ostringstream oss;
    if (validModules > 0) {
        gEngineReady = true;
        oss << "LSFG engine READY • SPIR-V validado " << validModules << "/" << files.size()
            << " • invalid " << invalidModules
            << " • GPU " << gpuName
            << " • native render " << gInputWidth << "x" << gInputHeight
            << " → fullscreen " << gOutputWidth << "x" << gOutputHeight
            << " • scale " << gRenderScalePercent << "%"
            << " (~" << (int) (percentPixels + 0.5) << "% pixels)"
            << " • mult " << gMultiplier << "x"
            << " • flow " << gFlowScale
            << " • modo " << (gBalancedEfficiency ? "eficiência equilibrada" : "qualidade")
            << " • IA preservada";
    } else {
        gEngineReady = false;
        oss << "LSFG engine NÃO pronto • nenhum SPIR-V aceito pelo driver"
            << " • arquivos " << files.size()
            << " • GPU " << gpuName;
    }
    gEngineStatus = oss.str();
    LOGI("prepareEngine: %s", gEngineStatus.c_str());
    return gEngineStatus;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bruno_frameoverlay_NativeLsfgBridge_nativePrepareEngineScaled(JNIEnv* env, jclass, jstring shaderDirString,
                                                                       jint inputWidth, jint inputHeight,
                                                                       jint outputWidth, jint outputHeight,
                                                                       jint multiplier, jfloat flowScale,
                                                                       jint renderScalePercent,
                                                                       jboolean balancedEfficiency) {
    const char* dirChars = env->GetStringUTFChars(shaderDirString, nullptr);
    std::string shaderDir = dirChars ? dirChars : "";
    if (dirChars) env->ReleaseStringUTFChars(shaderDirString, dirChars);
    std::string result = prepareEngineInternal(shaderDir, inputWidth, inputHeight, outputWidth, outputHeight,
                                               multiplier, flowScale, renderScalePercent,
                                               balancedEfficiency == JNI_TRUE);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bruno_frameoverlay_NativeLsfgBridge_nativePrepareEngine(JNIEnv* env, jclass, jstring shaderDirString,
                                                                 jint width, jint height,
                                                                 jint multiplier, jfloat flowScale) {
    const char* dirChars = env->GetStringUTFChars(shaderDirString, nullptr);
    std::string shaderDir = dirChars ? dirChars : "";
    if (dirChars) env->ReleaseStringUTFChars(shaderDirString, dirChars);
    std::string result = prepareEngineInternal(shaderDir, width, height, width, height, multiplier, flowScale, 100, false);
    return env->NewStringUTF(result.c_str());
}

static std::string updateParamsInternal(int multiplier, float flowScale, int renderScalePercent, bool balancedEfficiency) {
    gMultiplier = std::max(2, std::min(8, (int) multiplier));
    gFlowScale = std::max(0.10f, std::min(1.00f, (float) flowScale));
    gRenderScalePercent = std::max(50, std::min(100, (int) renderScalePercent));
    gBalancedEfficiency = balancedEfficiency;

    std::ostringstream oss;
    if (gEngineReady) {
        oss << "LSFG engine READY • runtime " << gMultiplier << "x"
            << " • flow " << gFlowScale
            << " • Render Scale native " << gRenderScalePercent << "%"
            << " • " << (gBalancedEfficiency ? "eficiência equilibrada" : "qualidade")
            << " • update leve sem revalidar SPIR-V";
    } else {
        oss << "LSFG engine ainda não pronto • params salvos " << gMultiplier << "x"
            << " • flow " << gFlowScale
            << " • Render Scale " << gRenderScalePercent << "%";
    }
    gEngineStatus = oss.str();
    LOGI("updateParams: %s", gEngineStatus.c_str());
    return gEngineStatus;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bruno_frameoverlay_NativeLsfgBridge_nativeUpdateParamsScaled(JNIEnv* env, jclass,
                                                                      jint multiplier, jfloat flowScale,
                                                                      jint renderScalePercent,
                                                                      jboolean balancedEfficiency) {
    std::string result = updateParamsInternal(multiplier, flowScale, renderScalePercent, balancedEfficiency == JNI_TRUE);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bruno_frameoverlay_NativeLsfgBridge_nativeUpdateParams(JNIEnv* env, jclass,
                                                               jint multiplier, jfloat flowScale) {
    std::string result = updateParamsInternal(multiplier, flowScale, gRenderScalePercent > 0 ? gRenderScalePercent : 100, gBalancedEfficiency);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bruno_frameoverlay_NativeLsfgBridge_nativeIsEngineReady(JNIEnv*, jclass) {
    return gEngineReady ? JNI_TRUE : JNI_FALSE;
}
