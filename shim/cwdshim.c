/*
 * getcwd shim —— 让 node 在 App 进程的 seccomp 下也能拿到 cwd。
 *
 * 背景：Android 给 App 进程挂了 seccomp filter，getcwd(2) 会直接返回
 * ENOSYS。node 的 libuv 一启动就要 cwd（uv_cwd），拿不到就抛
 *   Error: ENOSYS: process.cwd failed ... uv_cwd
 * 整个进程当场死，5099 起不来。
 *
 * maibot 是 Python，靠 sitecustomize 把 os.getcwd 换成返回常量绕过了；
 * node 是 C++，只能在内核边界之外做文章 —— 这个 .so 就是干这个的。
 *
 * 这里同时拦三层，因为不同版本 libuv 取 cwd 的方式不一样：
 *   1. libc 的 getcwd()       —— 最常见
 *   2. libc 的 __getcwd()     —— 内部别名
 *   3. syscall(SYS_getcwd)    —— libuv 在部分版本里直接走 syscall
 */
#define _GNU_SOURCE
#include <unistd.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <stdarg.h>
#include <errno.h>
#include <dlfcn.h>
#include <sys/syscall.h>

static const char *fake_cwd(void) {
    const char *p = getenv("PWD");
    if (p && *p) return p;
    return "/root/snowluma";
}

/* 加载时打一行，方便确认这个 .so 到底有没有被 ld.so 装上。
   （glibc 会在加载后把 LD_PRELOAD 从环境里清掉，
     所以看 process.env.LD_PRELOAD 是看不到的，只能靠这行确认。） */
__attribute__((constructor))
static void cwdshim_init(void) {
    fprintf(stderr, "[cwdshim] loaded, PWD=%s\n", fake_cwd());
    fflush(stderr);
}

/* 把路径写进调用方给的 buf，语义对齐 getcwd(3) */
static char *fill(char *buf, size_t size) {
    const char *p = fake_cwd();
    if (!buf) return strdup(p);
    if (size == 0) { errno = EINVAL; return NULL; }
    size_t n = strlen(p);
    if (n + 1 > size) { errno = ERANGE; return NULL; }
    memcpy(buf, p, n + 1);
    return buf;
}

char *getcwd(char *buf, size_t size) {
    return fill(buf, size);
}

char *__getcwd(char *buf, size_t size) {
    return fill(buf, size);
}

/* 拦 syscall()：libuv 某些版本直接 syscall(SYS_getcwd, buf, size)，
   绕开 libc 的 getcwd 符号，只覆盖 getcwd 就管不住它。 */
long syscall(long number, ...) {
    va_list ap;
    va_start(ap, number);

    if (number == SYS_getcwd) {
        char *buf = va_arg(ap, char *);
        size_t size = va_arg(ap, size_t);
        va_end(ap);
        char *r = fill(buf, size);
        /* getcwd(2) 成功时返回写入的字节数（含结尾 NUL） */
        return r ? (long) strlen(r) + 1 : -1;
    }

    /* 其他 syscall 原样转发。aarch64 最多 6 个参数。 */
    long a1 = va_arg(ap, long);
    long a2 = va_arg(ap, long);
    long a3 = va_arg(ap, long);
    long a4 = va_arg(ap, long);
    long a5 = va_arg(ap, long);
    long a6 = va_arg(ap, long);
    va_end(ap);

    static long (*real_syscall)(long, ...) = NULL;
    if (!real_syscall) {
        real_syscall = (long (*)(long, ...)) dlsym(RTLD_NEXT, "syscall");
    }
    if (!real_syscall) {
        errno = ENOSYS;
        return -1;
    }
    return real_syscall(number, a1, a2, a3, a4, a5, a6);
}
