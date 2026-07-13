#include <jni.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cerrno>
#include <cstring>
#include <mutex>
#include <sstream>
#include <string>

namespace {
std::mutex g_mutex;
int g_usb_fd = -1;

void close_fd_locked() {
    if (g_usb_fd >= 0) {
        close(g_usb_fd);
        g_usb_fd = -1;
    }
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_tayson_rockflash_nativebridge_NativeBridge_backendVersion(
        JNIEnv* env,
        jobject /* thiz */) {
    return env->NewStringUTF("rockflash-native/0.1.0 (USB FD bridge)");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_tayson_rockflash_nativebridge_NativeBridge_openUsbFileDescriptor(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jint file_descriptor) {
    std::lock_guard<std::mutex> lock(g_mutex);
    close_fd_locked();
    if (file_descriptor < 0) {
        return JNI_FALSE;
    }
    g_usb_fd = dup(file_descriptor);
    return g_usb_fd >= 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_tayson_rockflash_nativebridge_NativeBridge_describeOpenDescriptor(
        JNIEnv* env,
        jobject /* thiz */) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_usb_fd < 0) {
        return env->NewStringUTF("Nenhum descritor USB aberto");
    }

    struct stat descriptor_stat {};
    if (fstat(g_usb_fd, &descriptor_stat) != 0) {
        const std::string error = std::string("fstat falhou: ") + std::strerror(errno);
        return env->NewStringUTF(error.c_str());
    }

    std::ostringstream output;
    output << "fd=" << g_usb_fd
           << " mode=" << std::oct << descriptor_stat.st_mode
           << " rdev=" << std::dec << descriptor_stat.st_rdev;
    const std::string result = output.str();
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_tayson_rockflash_nativebridge_NativeBridge_closeUsbFileDescriptor(
        JNIEnv* /* env */,
        jobject /* thiz */) {
    std::lock_guard<std::mutex> lock(g_mutex);
    close_fd_locked();
}
