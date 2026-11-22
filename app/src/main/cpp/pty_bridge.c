#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <limits.h>
#include <poll.h>
#include <pty.h>
#include <signal.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <unistd.h>
#include <android/log.h>

#define LOG_TAG "PtyBridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern char **environ;

static void free_strv(char **vec, jsize count) {
    if (!vec) return;
    for (jsize i = 0; i < count; i++) {
        free(vec[i]);
    }
    free(vec);
}

static void reap_child_now(pid_t pid) {
    if (pid <= 0) return;
    kill(pid, SIGKILL);
    int status = 0;
    pid_t r;
    do {
        r = waitpid(pid, &status, 0);
    } while (r == -1 && errno == EINTR);
}

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

    if (!argvArray) {
        LOGE("nativeSpawnPty called with null argv");
        return NULL;
    }

    jsize argc = (*env)->GetArrayLength(env, argvArray);
    if (argc <= 0) {
        LOGE("nativeSpawnPty called with empty argv");
        return NULL;
    }

    if (cols < 1 || cols > 65535 || rows < 1 || rows > 65535) {
        LOGE("nativeSpawnPty invalid winsize: cols=%d rows=%d", (int)cols, (int)rows);
        return NULL;
    }

    char **argv = calloc((size_t)argc + 1, sizeof(char *));
    if (!argv) {
        LOGE("nativeSpawnPty OOM allocating argv");
        return NULL;
    }

    for (jsize i = 0; i < argc; i++) {
        jstring jarg = (jstring)(*env)->GetObjectArrayElement(env, argvArray, i);
        const char *utf = NULL;
        if (jarg) {
            utf = (*env)->GetStringUTFChars(env, jarg, NULL);
            if (!utf) {
                (*env)->DeleteLocalRef(env, jarg);
                free_strv(argv, i);
                return NULL;
            }
        }
        argv[i] = strdup(utf ? utf : "");
        if (jarg && utf) {
            (*env)->ReleaseStringUTFChars(env, jarg, utf);
        }
        if (jarg) {
            (*env)->DeleteLocalRef(env, jarg);
        }
        if (!argv[i]) {
            LOGE("nativeSpawnPty OOM duplicating argv[%d]", (int)i);
            free_strv(argv, i);
            return NULL;
        }
    }

    char **envp = NULL;
    jsize envc = 0;
    if (envpArray != NULL) {
        envc = (*env)->GetArrayLength(env, envpArray);
        envp = calloc((size_t)envc + 1, sizeof(char *));
        if (!envp) {
            LOGE("nativeSpawnPty OOM allocating envp");
            free_strv(argv, argc);
            return NULL;
        }
        for (jsize i = 0; i < envc; i++) {
            jstring jenv = (jstring)(*env)->GetObjectArrayElement(env, envpArray, i);
            const char *utf = NULL;
            if (jenv) {
                utf = (*env)->GetStringUTFChars(env, jenv, NULL);
                if (!utf) {
                    (*env)->DeleteLocalRef(env, jenv);
                    free_strv(envp, i);
                    free_strv(argv, argc);
                    return NULL;
                }
            }
            envp[i] = strdup(utf ? utf : "");
            if (jenv && utf) {
                (*env)->ReleaseStringUTFChars(env, jenv, utf);
            }
            if (jenv) {
                (*env)->DeleteLocalRef(env, jenv);
            }
            if (!envp[i]) {
                LOGE("nativeSpawnPty OOM duplicating envp[%d]", (int)i);
                free_strv(envp, i);
                free_strv(argv, argc);
                return NULL;
            }
        }
    }

    char *cwdCopy = NULL;
    if (workingDir != NULL) {
        const char *cwd = (*env)->GetStringUTFChars(env, workingDir, NULL);
        if (!cwd) {
            free_strv(envp, envc);
            free_strv(argv, argc);
            return NULL;
        }
        cwdCopy = strdup(cwd);
        (*env)->ReleaseStringUTFChars(env, workingDir, cwd);
        if (!cwdCopy) {
            LOGE("nativeSpawnPty OOM duplicating cwd");
            free_strv(envp, envc);
            free_strv(argv, argc);
            return NULL;
        }
    }

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = (unsigned short)cols;
    ws.ws_row = (unsigned short)rows;

    int masterFd = -1;
    pid_t pid = forkpty(&masterFd, NULL, NULL, &ws);
    if (pid < 0) {
        LOGE("forkpty failed: %s", strerror(errno));
        free_strv(argv, argc);
        free_strv(envp, envc);
        free(cwdCopy);
        return NULL;
    }

    if (pid == 0) {
        if (cwdCopy && cwdCopy[0] != '\0') {
            if (chdir(cwdCopy) != 0) {
                /* Best effort; continue with inherited cwd */
            }
        }

        sigset_t empty_mask;
        sigemptyset(&empty_mask);
        sigprocmask(SIG_SETMASK, &empty_mask, NULL);
        signal(SIGPIPE, SIG_DFL);

        if (envp != NULL) {
            if (strchr(argv[0], '/') != NULL) {
                execve(argv[0], argv, envp);
            } else {
                environ = envp;
                execvp(argv[0], argv);
            }
        } else {
            execvp(argv[0], argv);
        }
        _exit(127);
    }

    free_strv(argv, argc);
    free_strv(envp, envc);
    free(cwdCopy);

    int fd_flags = fcntl(masterFd, F_GETFD);
    if (fd_flags >= 0) {
        fcntl(masterFd, F_SETFD, fd_flags | FD_CLOEXEC);
    }
    int fl_flags = fcntl(masterFd, F_GETFL);
    if (fl_flags >= 0) {
        fcntl(masterFd, F_SETFL, fl_flags | O_NONBLOCK);
    }

    jintArray out = (*env)->NewIntArray(env, 2);
    if (!out) {
        LOGE("nativeSpawnPty NewIntArray failed");
        close(masterFd);
        reap_child_now(pid);
        return NULL;
    }

    jint result[2] = { (jint)pid, (jint)masterFd };
    (*env)->SetIntArrayRegion(env, out, 0, 2, result);
    return out;
}

JNIEXPORT jint JNICALL
Java_com_pocketlinux_runtime_PtyBridge_nativeRead(
        JNIEnv *env,
        jobject thiz,
        jint masterFd,
        jbyteArray buffer,
        jint offset,
        jint length,
        jint timeoutMs) {
    (void)thiz;

    if (masterFd < 0 || !buffer || offset < 0 || length < 0) {
        return -EINVAL;
    }
    jsize bufLen = (*env)->GetArrayLength(env, buffer);
    if (offset > bufLen || length > bufLen - offset) {
        return -EINVAL;
    }
    if (length == 0) {
        return 0;
    }

    struct pollfd pfd;
    pfd.fd = masterFd;
    pfd.events = POLLIN;
    pfd.revents = 0;

    int pr = poll(&pfd, 1, (int)timeoutMs);
    if (pr == 0) {
        return 0;
    }
    if (pr < 0) {
        int err = errno;
        if (err == EINTR) return 0;
        int ret = -err;
        return ret == -1 ? -2 : ret;
    }
    if (pfd.revents & POLLNVAL) {
        return -EBADF;
    }

    uint8_t tmp[16384];
    size_t toRead = (size_t)length > sizeof(tmp) ? sizeof(tmp) : (size_t)length;
    ssize_t n = read(masterFd, tmp, toRead);
    if (n > 0) {
        (*env)->SetByteArrayRegion(env, buffer, offset, (jsize)n, (const jbyte *)tmp);
        return (jint)n;
    }
    if (n == 0) {
        return -1;
    }

    int err = errno;
    if (err == EIO) {
        return -1;
    }
    if (err == EAGAIN || err == EWOULDBLOCK || err == EINTR) {
        if ((pfd.revents & POLLHUP) && !(pfd.revents & POLLIN)) {
            return -1;
        }
        return 0;
    }
    int ret = -err;
    return ret == -1 ? -2 : ret;
}

JNIEXPORT jint JNICALL
Java_com_pocketlinux_runtime_PtyBridge_nativeWrite(
        JNIEnv *env,
        jobject thiz,
        jint masterFd,
        jbyteArray data,
        jint offset,
        jint length) {
    (void)thiz;

    if (masterFd < 0 || !data || offset < 0 || length < 0) {
        return -EINVAL;
    }
    jsize bufLen = (*env)->GetArrayLength(env, data);
    if (offset > bufLen || length > bufLen - offset) {
        return -EINVAL;
    }
    if (length == 0) {
        return 0;
    }

    uint8_t tmp[16384];
    size_t totalWritten = 0;
    size_t remaining = (size_t)length;

    while (remaining > 0) {
        size_t chunk = remaining > sizeof(tmp) ? sizeof(tmp) : remaining;
        (*env)->GetByteArrayRegion(env, data, offset + (jint)totalWritten, (jsize)chunk, (jbyte *)tmp);
        if ((*env)->ExceptionCheck(env)) {
            return totalWritten > 0 ? (jint)totalWritten : -EINVAL;
        }

        size_t chunkOffset = 0;
        while (chunkOffset < chunk) {
            ssize_t w = write(masterFd, tmp + chunkOffset, chunk - chunkOffset);
            if (w > 0) {
                chunkOffset += (size_t)w;
                totalWritten += (size_t)w;
            } else if (w < 0) {
                int err = errno;
                if (err == EINTR) {
                    continue;
                }
                if (err == EAGAIN || err == EWOULDBLOCK) {
                    struct pollfd pfd;
                    pfd.fd = masterFd;
                    pfd.events = POLLOUT;
                    pfd.revents = 0;
                    int pr = poll(&pfd, 1, 5000);
                    if (pr > 0) {
                        if (pfd.revents & (POLLERR | POLLNVAL | POLLHUP)) {
                            return totalWritten > 0 ? (jint)totalWritten : -EPIPE;
                        }
                        continue;
                    }
                    if (pr == 0) {
                        return totalWritten > 0 ? (jint)totalWritten : -ETIMEDOUT;
                    }
                    if (errno == EINTR) {
                        continue;
                    }
                    return totalWritten > 0 ? (jint)totalWritten : -errno;
                }
                return totalWritten > 0 ? (jint)totalWritten : -err;
            } else {
                return totalWritten > 0 ? (jint)totalWritten : -EIO;
            }
        }
        remaining -= chunk;
    }

    return (jint)totalWritten;
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
    if (masterFd < 0 || cols < 1 || cols > 65535 || rows < 1 || rows > 65535) {
        return -EINVAL;
    }
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

JNIEXPORT jint JNICALL
Java_com_pocketlinux_runtime_PtyBridge_nativePollExit(
        JNIEnv *env,
        jobject thiz,
        jint pid) {
    (void)env;
    (void)thiz;
    if (pid <= 0) return -1;
    int status = 0;
    pid_t r;
    do {
        r = waitpid((pid_t)pid, &status, WNOHANG);
    } while (r == -1 && errno == EINTR);
    if (r == 0) return -2;
    if (r < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

JNIEXPORT jint JNICALL
Java_com_pocketlinux_runtime_PtyBridge_nativeKill(
        JNIEnv *env,
        jobject thiz,
        jint pid,
        jint sig) {
    (void)env;
    (void)thiz;
    if (pid <= 0) return -EINVAL;
    return kill((pid_t)pid, sig) == 0 ? 0 : -errno;
}

JNIEXPORT jint JNICALL
Java_com_pocketlinux_runtime_PtyBridge_nativeCloseFd(
        JNIEnv *env,
        jobject thiz,
        jint fd) {
    (void)env;
    (void)thiz;
    if (fd < 0) return -EBADF;
    return close(fd) == 0 ? 0 : -errno;
}
