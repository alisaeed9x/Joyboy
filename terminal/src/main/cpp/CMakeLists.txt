cmake_minimum_required(VERSION 3.22.1)
project(termuxpty LANGUAGES C)
# (v394) pty لطرفية Termux الداخلية — نفس termux.c الأصلي من termux-app v0.118.0
add_library(termux SHARED termux.c)
target_link_libraries(termux log)
