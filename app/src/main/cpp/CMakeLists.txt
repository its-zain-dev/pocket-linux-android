cmake_minimum_required(VERSION 3.22.1)
project("pocketlinux_pty" C)

add_library(
    ptybridge
    SHARED
    pty_bridge.c
)

target_compile_options(ptybridge PRIVATE -Wall -Wextra -Werror)

find_library(log-lib log)

target_link_libraries(ptybridge ${log-lib})
