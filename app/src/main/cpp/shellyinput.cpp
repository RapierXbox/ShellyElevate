#include <jni.h>
#include <linux/input.h>
#include <fcntl.h>
#include <unistd.h>
#include <poll.h>
#include <pthread.h>
#include <android/log.h>
#include <atomic>
#include <cerrno>
#include <cstring>
#include <vector>

#define TAG "ShellyInput"

#ifndef EV_KEY
#define EV_KEY 0x01
#endif

// poll timeout so the loop notices a stop request
static const int POLL_TIMEOUT_MS = 500;
// events drained per read so a burst does not cost one poll round trip each
static const size_t EVENTS_PER_READ = 16;

// bumped on every stop so a monitor loop only runs while its own generation is current
static std::atomic<unsigned> g_generation(0);
static pthread_t         g_thread;
// start and stop are only called from the java side one at a time so these need no lock
// a lock here would deadlock a key callback that stops the monitor while another thread joins it
static bool              g_threadValid = false;

struct MonitorArgs {
    JavaVM*          jvm      = nullptr;
    jobject          callback = nullptr;
    jmethodID        method   = nullptr;
    unsigned         generation = 0;
    std::vector<int> fds;
};

static void closeAll(const std::vector<int>& fds) {
    for (int fd : fds) close(fd);
}

// reads whatever is queued on fd and forwards key events to java
static void dispatchEvents(JNIEnv* env, MonitorArgs* args, int fd) {
    struct input_event events[EVENTS_PER_READ];
    ssize_t n = read(fd, events, sizeof(events));
    if (n <= 0) return;

    size_t count = (size_t)n / sizeof(struct input_event);
    for (size_t i = 0; i < count; i++) {
        const struct input_event& ev = events[i];
        if (ev.type != EV_KEY) continue;
        // ev.value is 0 up 1 down 2 repeat which matches the android KeyEvent action values
        env->CallVoidMethod(args->callback, args->method,
                            (jint)ev.code, (jint)ev.value, (jint)0);
        // a throwing java callback must not leave the exception pending across later jni calls
        if (env->ExceptionCheck()) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "Exception in key callback, clearing");
            env->ExceptionClear();
        }
    }
}

static void* monitorLoop(void* arg) {
    auto* args = static_cast<MonitorArgs*>(arg);

    JNIEnv* env = nullptr;
    if (args->jvm->AttachCurrentThread(&env, nullptr) != JNI_OK || env == nullptr) {
        // without an env the global ref cant be dropped so it leaks once
        __android_log_print(ANDROID_LOG_ERROR, TAG, "AttachCurrentThread failed, monitor exiting");
        closeAll(args->fds);
        delete args;
        return nullptr;
    }

    std::vector<pollfd> pfds(args->fds.size());
    for (size_t i = 0; i < args->fds.size(); i++) {
        pfds[i].fd     = args->fds[i];
        pfds[i].events = POLLIN;
    }

    while (args->generation == g_generation.load()) {
        if (pfds.empty()) {
            __android_log_print(ANDROID_LOG_WARN, TAG, "No input devices left, monitor exiting");
            break;
        }
        int ret = poll(pfds.data(), (nfds_t)pfds.size(), POLL_TIMEOUT_MS);
        if (ret <= 0) continue;

        for (size_t i = 0; i < pfds.size(); ) {
            // drop dead fds so poll does not return instantly forever
            if (pfds[i].revents & (POLLERR | POLLHUP | POLLNVAL)) {
                __android_log_print(ANDROID_LOG_WARN, TAG, "Input device fd=%d gone, closing", pfds[i].fd);
                close(pfds[i].fd);
                pfds.erase(pfds.begin() + i);
                args->fds.erase(args->fds.begin() + i);
                continue;
            }
            if (pfds[i].revents & POLLIN) {
                dispatchEvents(env, args, pfds[i].fd);
            }
            ++i;
        }
    }

    closeAll(args->fds);
    env->DeleteGlobalRef(args->callback);
    args->jvm->DetachCurrentThread();
    delete args;
    return nullptr;
}

static void stopMonitor() {
    g_generation++;
    if (!g_threadValid) return;
    if (pthread_equal(pthread_self(), g_thread)) {
        // called from a key callback on the monitor thread itself so joining would deadlock
        pthread_detach(g_thread);
    } else {
        pthread_join(g_thread, nullptr);
    }
    g_threadValid = false;
}

// opens every readable path and returns the fds
static std::vector<int> openPaths(JNIEnv* env, jobjectArray paths) {
    std::vector<int> fds;
    jsize len = env->GetArrayLength(paths);
    for (jsize i = 0; i < len; i++) {
        auto jpath = (jstring)env->GetObjectArrayElement(paths, i);
        if (jpath == nullptr) continue;
        const char* path = env->GetStringUTFChars(jpath, nullptr);
        if (path != nullptr) {
            int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
            if (fd >= 0) {
                fds.push_back(fd);
                __android_log_print(ANDROID_LOG_INFO, TAG, "Opened %s (fd=%d)", path, fd);
            } else {
                __android_log_print(ANDROID_LOG_WARN, TAG, "Cannot open %s: %s", path, strerror(errno));
            }
            env->ReleaseStringUTFChars(jpath, path);
        }
        env->DeleteLocalRef(jpath);
    }
    return fds;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_rapierxbox_shellyelevatev2_helper_InputMonitor_nativeStart(
        JNIEnv* env, jobject /*thiz*/, jobject callback, jobjectArray paths) {
    // only one monitor runs at a time
    stopMonitor();

    jclass cls = env->GetObjectClass(callback);
    jmethodID method = env->GetMethodID(cls, "onHardwareKey", "(III)V");
    env->DeleteLocalRef(cls);
    if (method == nullptr) {
        // NoSuchMethodError stays pending and surfaces in java
        __android_log_print(ANDROID_LOG_ERROR, TAG, "onHardwareKey(III)V not found on callback");
        return JNI_FALSE;
    }

    std::vector<int> fds = openPaths(env, paths);
    if (fds.empty()) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "No input devices opened");
        return JNI_FALSE;
    }

    auto* args = new MonitorArgs();
    if (env->GetJavaVM(&args->jvm) != JNI_OK) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "GetJavaVM failed");
        closeAll(fds);
        delete args;
        return JNI_FALSE;
    }
    args->callback = env->NewGlobalRef(callback);
    args->method   = method;
    args->fds      = fds;
    args->generation = g_generation.load();
    // taken before the thread starts since it owns args from then on
    size_t deviceCount = fds.size();

    int err = pthread_create(&g_thread, nullptr, monitorLoop, args);
    if (err != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "pthread_create failed: %s", strerror(err));
        closeAll(args->fds);
        env->DeleteGlobalRef(args->callback);
        delete args;
        return JNI_FALSE;
    }
    g_threadValid = true;
    __android_log_print(ANDROID_LOG_INFO, TAG, "Monitor started with %zu devices", deviceCount);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_me_rapierxbox_shellyelevatev2_helper_InputMonitor_stop(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    stopMonitor();
    __android_log_print(ANDROID_LOG_INFO, TAG, "Monitor stopped");
}
