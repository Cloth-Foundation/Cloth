; Filename: hello.asm
default rel
extern GetStdHandle
extern WriteConsoleA
extern ExitProcess

section .data
    msg db "Hello, World!", 13, 10
    msglen equ $ - msg

section .bss
    dummy resq 1

section .text
    global main
main:
    sub rsp, 40

    ; stdout
    mov rcx, -11
    call GetStdHandle
    mov r10, rax

    ; Write
    mov rcx, r10
    lea rdx, [msg]
    mov r8, msglen
    lea r9, [dummy]
    push 0
    call WriteConsoleA

    ; Exit
    xor rcx, rcx
    call ExitProcess