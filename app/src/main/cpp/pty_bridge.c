#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <pty.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <unistd.h>
#include <android/log.h>

#define LOG_TAG "PtyBridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

JNIEXPORT jintArray JNICALL
Java_com_pocketlinux_runtime_PtyBridge_nativeSpawnPty(
        JNIEnv *env,
        jobject thiz,
        jobjectArray argvArray,
        jobjectArray envpArray,
        jstring workingDir,
        jint cols,
        jint rows) {
    (void)thiz;

    jsize argc = argvArray ? (*env)->GetArrayLength(env, argvArray) : 0;
    if (argc <= 0) {
        LOGE("nativeSpawnPty called with empty argv");
        return NULL;
    }

    char **argv = calloc((size_t)argc + 1, sizeof(char *));
    if (!argv) return NULL;
    for (jsize i = 0; i < argc; i++) {
        jstring jarg = (jstring)(*env)->GetObjectArrayElement(env, argvArray, i);
        const char *utf = jarg ? (*env)->GetStringUTFChars(env, jarg, NULL) : "";
        argv[i] = strdup(utf ? utf : "");
        if (jarg && utf) (*env)->ReleaseStringUTFChars(env, jarg, utf);
        if (jarg) (*env)->DeleteLocalRef(env, jarg);
    }

    jsize envc = envpArray ? (*env)->GetArrayLength(env, envpArray) : 0;
    char **envp = NULL;
    if (envc > 0) {
        envp = calloc((size_t)envc + 1, sizeof(char *));
        if (envp) {
            for (jsize i = 0; i < envc; i++) {
                jstring jenv = (jstring)(*env)->GetObjectArrayElement(env, envpArray, i);
                const char *utf = jenv ? (*env)->GetStringUTFChars(env, jenv, NULL) : "";
                envp[i] = strdup(utf ? utf : "");
                if (jenv && utf) (*env)->ReleaseStringUTFChars(env, jenv, utf);
                if (jenv) (*env)->DeleteLocalRef(env, jenv);
            }
        }
    }

    const char *cwd = NULL;
    if (workingDir) {
        cwd = (*env)->GetStringUTFChars(env, workingDir, NULL);
    }
    char *cwdCopy = cwd ? strdup(cwd) : NULL;
    if (workingDir && cwd) {
        (*env)->ReleaseStringUTFChars(env, workingDir, cwd);
    }

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = (unsigned short)(cols > 0 ? cols : 80);
    ws.ws_row = (unsigned short)(rows > 0 ? rows : 24);

    int masterFd = -1;
    pid_t pid = forkpty(&masterFd, NULL, NULL, &ws);
    if (pid < 0) {
        LOGE("forkpty failed: %s", strerror(errno));
        for (jsize i = 0; i < argc; i++) free(argv[i]);
        free(argv);
        if (envp) {
            for (jsize i = 0; i < envc; i++) free(envp[i]);
            free(envp);
        }
        free(cwdCopy);
        return NULL;
    }

    if (pid == 0) {
        if (cwdCopy && cwdCopy[0] != '\0') {
            if (chdir(cwdCopy) != 0) {
                /* Best effort; continue with inherited cwd */
            }
        }
        if (envp) {
            execve(argv[0], argv, envp);
        } else {
            execv(argv[0], argv);
        }
        _exit(127);
    }

    for (jsize i = 0; i < argc; i++) free(argv[i]);
    free(argv);
    if (envp) {
        for (jsize i = 0; i < envc; i++) free(envp[i]);
        free(envp);
    }
    free(cwdCopy);

    jint result[2] = { (jint)pid, (jint)masterFd };
    jintArray out = (*env)->NewIntArray(env, 2);
    if (out) {
        (*env)->SetIntArrayRegion(env, out, 0, 2, result);
    }
    return out;
}

JNIEXPORT jint JNICALL
Java_com_pocketlinux_runtime_PtyBridge_nativeSetWindowSize(
        JNIEnv *env,
        jobject thiz,
        jint masterFd,
        jint cols,
        jint rows) {
    (void)env;
    (void)thiz;
    if (masterFd < 0 || cols <= 0 || rows <= 0) return -1;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = (unsigned short)cols;
    ws.ws_row = (unsigned short)rows;
    return ioctl(masterFd, TIOCSWINSZ, &ws) == 0 ? 0 : -errno;
}

JNIEXPORT jint JNICALL
Java_com_pocketlinux_runtime_PtyBridge_nativeWaitFor(
        JNIEnv *env,
        jobject thiz,
        jint pid) {
    (void)env;
    (void)thiz;
    if (pid <= 0) return -1;
    int status = 0;
    pid_t r;
    do {
        r = waitpid((pid_t)pid, &status, 0);
    } while (r == -1 && errno == EINTR);
    if (r < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

JNIEXPORT void JNICALL
Java_com_pocketlinux_runtime_PtyBridge_nativeKill(
        JNIEnv *env,
        jobject thiz,
        jint pid,
        jint sig) {
    (void)env;
    (void)thiz;
    if (pid > 0) {
        kill((pid_t)pid, sig);
    }
}

JNIEXPORT void JNICALL
Java_com_pocketlinux_runtime_PtyBridge_nativeCloseFd(
        JNIEnv *env,
        jobject thiz,
        jint fd) {
    (void)env;
    (void)thiz;
    if (fd >= 0) {
        close(fd);
    }
}
