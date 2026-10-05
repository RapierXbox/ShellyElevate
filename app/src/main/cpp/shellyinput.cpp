#include <jni.h>
#include <linux/input.h>
#include <fcntl.h>
#include <unistd.h>
#include <poll.h>
#include <pthread.h>
#include <dirent.h>
#include <sys/ioctl.h>
#include <android/log.h>
#include <atomic>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#define TAG "ShellyInput"

#ifndef EV_KEY
#define EV_KEY 0x01
#endif

// poll timeout so the loop notices a stop request
static const int POLL_TIMEOUT_MS = 500;
// events drained per read so a burst does not cost one poll round trip each
static const size_t EVENTS_PER_READ = 64;

enum class Mode { KEYS, TOUCH };

// one reader thread. java holds a pointer to it as an opaque handle
// start and stop for one handle are only called from the java side one at a time
struct Monitor {
    Mode              mode = Mode::KEYS;
    JavaVM*           jvm = nullptr;
    jobject           callback = nullptr;
    jmethodID         method = nullptr;
    // reused for every touch batch so the hot path does not allocate
    jintArray         batch = nullptr;
    std::vector<int>  fds;
    std::atomic<bool> stopRequested{false};
    std::atomic<bool> finished{false};
    pthread_t         thread{};
};

static void closeAll(const std::vector<int>& fds) {
    for (int fd : fds) close(fd);
}

static void reportException(JNIEnv* env, const char* what) {
    // a throwing java callback must not leave the exception pending across later jni calls
    if (env->ExceptionCheck()) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Exception in %s callback, clearing", what);
        env->ExceptionClear();
    }
}

// reads whatever is queued on fd and forwards it to java
static void dispatchEvents(JNIEnv* env, Monitor* m, int fd) {
    struct input_event events[EVENTS_PER_READ];
    ssize_t n = read(fd, events, sizeof(events));
    if (n <= 0) return;
    size_t count = (size_t)n / sizeof(struct input_event);

    if (m->mode == Mode::KEYS) {
        for (size_t i = 0; i < count; i++) {
            const struct input_event& ev = events[i];
            if (ev.type != EV_KEY) continue;
            // ev.value is 0 up 1 down 2 repeat which matches the android KeyEvent action values
            env->CallVoidMethod(m->callback, m->method, (jint)ev.code, (jint)ev.value, (jint)0);
            reportException(env, "key");
        }
        return;
    }

    // touch hands over the raw type code value triples in one call per read
    jint triples[EVENTS_PER_READ * 3];
    for (size_t i = 0; i < count; i++) {
        triples[i * 3] = (jint)events[i].type;
        triples[i * 3 + 1] = (jint)events[i].code;
        triples[i * 3 + 2] = (jint)events[i].value;
    }
    env->SetIntArrayRegion(m->batch, 0, (jsize)(count * 3), triples);
    env->CallVoidMethod(m->callback, m->method, m->batch, (jint)count);
    reportException(env, "touch");
}

static void* monitorLoop(void* arg) {
    auto* m = static_cast<Monitor*>(arg);

    JNIEnv* env = nullptr;
    if (m->jvm->AttachCurrentThread(&env, nullptr) != JNI_OK || env == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "AttachCurrentThread failed, monitor exiting");
        closeAll(m->fds);
        m->fds.clear();
        m->finished = true;
        return nullptr;
    }

    std::vector<pollfd> pfds(m->fds.size());
    for (size_t i = 0; i < m->fds.size(); i++) {
        pfds[i].fd     = m->fds[i];
        pfds[i].events = POLLIN;
    }

    while (!m->stopRequested.load()) {
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
                m->fds.erase(m->fds.begin() + i);
                continue;
            }
            if (pfds[i].revents & POLLIN) {
                dispatchEvents(env, m, pfds[i].fd);
            }
            ++i;
        }
    }

    closeAll(m->fds);
    m->fds.clear();
    env->DeleteGlobalRef(m->callback);
    m->callback = nullptr;
    if (m->batch != nullptr) {
        env->DeleteGlobalRef(m->batch);
        m->batch = nullptr;
    }
    m->jvm->DetachCurrentThread();
    m->finished = true;
    return nullptr;
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

// returns the handle or 0 when nothing could be started
static jlong startMonitor(JNIEnv* env, Mode mode, jobject callback, jobjectArray paths,
                          const char* methodName, const char* signature) {
    jclass cls = env->GetObjectClass(callback);
    jmethodID method = env->GetMethodID(cls, methodName, signature);
    env->DeleteLocalRef(cls);
    if (method == nullptr) {
        // NoSuchMethodError stays pending and surfaces in java
        __android_log_print(ANDROID_LOG_ERROR, TAG, "%s%s not found on callback", methodName, signature);
        return 0;
    }

    std::vector<int> fds = openPaths(env, paths);
    if (fds.empty()) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "No input devices opened");
        return 0;
    }

    auto* m = new Monitor();
    m->mode = mode;
    if (env->GetJavaVM(&m->jvm) != JNI_OK) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "GetJavaVM failed");
        closeAll(fds);
        delete m;
        return 0;
    }
    m->callback = env->NewGlobalRef(callback);
    m->method = method;
    m->fds = fds;
    if (mode == Mode::TOUCH) {
        jintArray local = env->NewIntArray((jsize)(EVENTS_PER_READ * 3));
        m->batch = (jintArray)env->NewGlobalRef(local);
        env->DeleteLocalRef(local);
    }

    int err = pthread_create(&m->thread, nullptr, monitorLoop, m);
    if (err != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "pthread_create failed: %s", strerror(err));
        closeAll(m->fds);
        env->DeleteGlobalRef(m->callback);
        if (m->batch != nullptr) env->DeleteGlobalRef(m->batch);
        delete m;
        return 0;
    }
    __android_log_print(ANDROID_LOG_INFO, TAG, "%s monitor started with %zu devices",
                        mode == Mode::TOUCH ? "Touch" : "Key", fds.size());
    return reinterpret_cast<jlong>(m);
}

static void stopMonitor(jlong handle) {
    auto* m = reinterpret_cast<Monitor*>(handle);
    if (m == nullptr) return;
    m->stopRequested = true;
    if (pthread_equal(pthread_self(), m->thread)) {
        // called from a callback on the monitor thread itself so joining would deadlock
        // the struct then leaks once which is cheaper than a use after free
        pthread_detach(m->thread);
        return;
    }
    pthread_join(m->thread, nullptr);
    delete m;
}

// true when the device reports multitouch positions
static bool isTouchscreen(int fd) {
    unsigned long absBits[(ABS_MAX + 8 * sizeof(long)) / (8 * sizeof(long))] = {0};
    if (ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(absBits)), absBits) < 0) return false;
    auto has = [&](int bit) {
        return (absBits[bit / (8 * sizeof(long))] >> (bit % (8 * sizeof(long)))) & 1UL;
    };
    return has(ABS_MT_POSITION_X) && has(ABS_MT_POSITION_Y);
}

extern "C" JNIEXPORT jlong JNICALL
Java_me_rapierxbox_shellyelevatev2_helper_InputMonitor_nativeStart(
        JNIEnv* env, jobject /*thiz*/, jobject callback, jobjectArray paths) {
    return startMonitor(env, Mode::KEYS, callback, paths, "onHardwareKey", "(III)V");
}

extern "C" JNIEXPORT jlong JNICALL
Java_me_rapierxbox_shellyelevatev2_helper_InputMonitor_nativeStartTouch(
        JNIEnv* env, jobject /*thiz*/, jobject callback, jobjectArray paths) {
    return startMonitor(env, Mode::TOUCH, callback, paths, "onTouchEvents", "([II)V");
}

extern "C" JNIEXPORT void JNICALL
Java_me_rapierxbox_shellyelevatev2_helper_InputMonitor_nativeStop(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    stopMonitor(handle);
    __android_log_print(ANDROID_LOG_INFO, TAG, "Monitor stopped");
}

// finds the first multitouch device and returns "path,minX,maxX,minY,maxY" or null
extern "C" JNIEXPORT jstring JNICALL
Java_me_rapierxbox_shellyelevatev2_helper_InputMonitor_nativeFindTouchscreen(
        JNIEnv* env, jclass /*clazz*/) {
    DIR* dir = opendir("/dev/input");
    if (dir == nullptr) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "Cannot list /dev/input: %s", strerror(errno));
        return nullptr;
    }
    std::string result;
    struct dirent* entry;
    while ((entry = readdir(dir)) != nullptr && result.empty()) {
        if (strncmp(entry->d_name, "event", 5) != 0) continue;
        std::string path = std::string("/dev/input/") + entry->d_name;
        int fd = open(path.c_str(), O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (fd < 0) continue;
        if (isTouchscreen(fd)) {
            struct input_absinfo x{}, y{};
            if (ioctl(fd, EVIOCGABS(ABS_MT_POSITION_X), &x) == 0 &&
                ioctl(fd, EVIOCGABS(ABS_MT_POSITION_Y), &y) == 0 &&
                x.maximum > x.minimum && y.maximum > y.minimum) {
                char buf[256];
                snprintf(buf, sizeof(buf), "%s,%d,%d,%d,%d", path.c_str(), x.minimum, x.maximum, y.minimum, y.maximum);
                result = buf;
                __android_log_print(ANDROID_LOG_INFO, TAG, "Touchscreen %s", buf);
            }
        }
        close(fd);
    }
    closedir(dir);
    return result.empty() ? nullptr : env->NewStringUTF(result.c_str());
}
