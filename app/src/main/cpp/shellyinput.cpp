#include <jni.h>
#include <linux/input.h>
#include <fcntl.h>
#include <unistd.h>
#include <poll.h>
#include <pthread.h>
#include <android/log.h>
#include <cstring>
#include <vector>
#include <string>

#define TAG "ShellyInput"
#define EV_KEY 1

static volatile bool    g_running = false;
static pthread_t        g_thread  = 0;

struct MonitorArgs {
    JavaVM*    jvm;
    jobject    callback;
    jmethodID  method;
    std::vector<int> fds;
};

static void* monitorLoop(void* arg) {
    auto* args = static_cast<MonitorArgs*>(arg);

    std::vector<pollfd> pfds(args->fds.size());
    for (size_t i = 0; i < args->fds.size(); i++) {
        pfds[i].fd     = args->fds[i];
        pfds[i].events = POLLIN;
    }

    JNIEnv* env = nullptr;
    args->jvm->AttachCurrentThread(&env, nullptr);

    while (g_running) {
        int ret = poll(pfds.data(), (nfds_t)pfds.size(), 500);
        if (ret <= 0) continue;

        for (size_t i = 0; i < pfds.size(); i++) {
            if (!(pfds[i].revents & POLLIN)) continue;

            struct input_event ev;
            ssize_t n = read(pfds[i].fd, &ev, sizeof(ev));
            if (n != (ssize_t)sizeof(ev)) continue;
            if (ev.type != EV_KEY) continue;
            // ev.value: 0=UP, 1=DOWN, 2=REPEAT (matches Android KeyEvent action values)
            env->CallVoidMethod(args->callback, args->method,
                                (jint)ev.code, (jint)ev.value, (jint)0);
        }
    }

    for (int fd : args->fds) close(fd);
    env->DeleteGlobalRef(args->callback);
    args->jvm->DetachCurrentThread();
    delete args;
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_me_rapierxbox_shellyelevatev2_helper_InputMonitor_nativeStart(
        JNIEnv* env, jobject /*thiz*/, jobject callback, jobjectArray paths) {

    // Stop any previous monitor before starting a new one.
    g_running = false;
    if (g_thread) {
        pthread_join(g_thread, nullptr);
        g_thread = 0;
    }

    auto* args = new MonitorArgs();
    env->GetJavaVM(&args->jvm);
    args->callback = env->NewGlobalRef(callback);
    jclass cls = env->GetObjectClass(callback);
    args->method  = env->GetMethodID(cls, "onHardwareKey", "(III)V");

    jsize len = env->GetArrayLength(paths);
    for (jsize i = 0; i < len; i++) {
        auto jpath = (jstring)env->GetObjectArrayElement(paths, i);
        const char* path = env->GetStringUTFChars(jpath, nullptr);
        int fd = open(path, O_RDONLY | O_NONBLOCK);
        if (fd >= 0) {
            args->fds.push_back(fd);
            __android_log_print(ANDROID_LOG_INFO, TAG, "Opened %s (fd=%d)", path, fd);
        } else {
            __android_log_print(ANDROID_LOG_WARN, TAG, "Cannot open %s", path);
        }
        env->ReleaseStringUTFChars(jpath, path);
        env->DeleteLocalRef(jpath);
    }

    if (args->fds.empty()) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "No input devices opened");
        env->DeleteGlobalRef(args->callback);
        delete args;
        return;
    }

    g_running = true;
    pthread_create(&g_thread, nullptr, monitorLoop, args);
    __android_log_print(ANDROID_LOG_INFO, TAG, "Monitor started with %zu devices", args->fds.size());
}

extern "C" JNIEXPORT void JNICALL
Java_me_rapierxbox_shellyelevatev2_helper_InputMonitor_stop(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    g_running = false;
    if (g_thread) {
        pthread_join(g_thread, nullptr);
        g_thread = 0;
    }
    __android_log_print(ANDROID_LOG_INFO, TAG, "Monitor stopped");
}
