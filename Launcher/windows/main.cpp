#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <bcrypt.h>
#include <shellapi.h>
#include <jni.h>

#include <algorithm>
#include <array>
#include <cstdio>
#include <cwchar>
#include <exception>
#include <memory>
#include <string>
#include <string_view>
#include <vector>

#include "bundled_jli.h"

namespace {

constexpr wchar_t workerSelector[] = L"--cloth-shuttle-worker";
constexpr int startupFailure = 2;
using Arguments = std::vector<std::wstring>;
using Handle = std::unique_ptr<void, decltype(&CloseHandle)>;

struct LaunchError {
    std::wstring message;
    int status = startupFailure;
};

[[noreturn]] void windowsError(const std::wstring& operation) {
    const auto error = GetLastError();
    throw LaunchError{operation + L" (Windows error " + std::to_wstring(error) + L")"};
}

std::wstring modulePath() {
    for (DWORD capacity = 512; capacity <= 32768; capacity *= 2) {
        std::wstring path(capacity, L'\0');
        const auto length = GetModuleFileNameW(nullptr, path.data(), capacity);
        if (length == 0) windowsError(L"Unable to locate the launcher");
        if (length < capacity) {
            path.resize(length);
            return path;
        }
    }
    throw LaunchError{L"The launcher path is too long"};
}

DWORD attributes(const std::wstring& path) {
    const auto result = GetFileAttributesW(path.c_str());
    if (result == INVALID_FILE_ATTRIBUTES) {
        const auto error = GetLastError();
        if (error != ERROR_FILE_NOT_FOUND && error != ERROR_PATH_NOT_FOUND) {
            windowsError(L"Unable to inspect '" + path + L"'");
        }
    }
    return result;
}

void requireFile(const std::wstring& path, const std::wstring& description) {
    const auto flags = attributes(path);
    if (flags == INVALID_FILE_ATTRIBUTES || (flags & FILE_ATTRIBUTE_DIRECTORY)) {
        throw LaunchError{description + L" is missing: '" + path + L"'"};
    }
}

std::wstring environment(const wchar_t* name) {
    std::wstring value(32768, L'\0');
    const auto capacity = static_cast<DWORD>(value.size());
    const auto length = GetEnvironmentVariableW(name, value.data(), capacity);
    if (length >= capacity) throw LaunchError{std::wstring(name) + L" is too long"};
    value.resize(length);
    return value;
}

void appendOptions(Arguments& arguments, const wchar_t* name) {
    const auto options = environment(name);
    if (options.empty()) return;
    int count = 0;
    const auto parsed = CommandLineToArgvW((L"java " + options).c_str(), &count);
    if (!parsed) windowsError(L"Unable to read Java options");
    const std::unique_ptr<wchar_t*, decltype(&LocalFree)> cleanup(parsed, &LocalFree);
    arguments.insert(arguments.end(), parsed + 1, parsed + count);
}

std::wstring commandLine(const Arguments& arguments) {
    std::wstring line;
    for (const auto& argument : arguments) {
        if (!line.empty()) line += L' ';
        // Quote every argument to keep empty strings and suppress JLI wildcard expansion.
        line += L'"';
        size_t slashes = 0;
        for (const auto character : argument) {
            if (character == L'\\') {
                ++slashes;
            } else {
                line.append(character == L'"' ? 2 * slashes + 1 : slashes, L'\\');
                line += character;
                slashes = 0;
            }
        }
        line.append(2 * slashes, L'\\');
        line += L'"';
    }
    return line;
}

std::string utf8(const std::wstring& text) {
    const auto length = static_cast<int>(text.size());
    const auto bytes = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, text.data(), length, nullptr, 0, nullptr, nullptr);
    if (!bytes) windowsError(L"Unable to encode the launch command as UTF-8");
    std::string encoded(bytes, '\0');
    if (!WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, text.data(), length, encoded.data(), bytes, nullptr, nullptr)) {
        windowsError(L"Unable to encode the launch command as UTF-8");
    }
    return encoded;
}

void report(const std::wstring& message) {
    const auto line = L"Cloth launcher: " + message + L"\n";
    const auto error = GetStdHandle(STD_ERROR_HANDLE);
    DWORD mode = 0;
    DWORD written = 0;
    if (GetConsoleMode(error, &mode) && WriteConsoleW(error, line.data(), static_cast<DWORD>(line.size()), &written, nullptr)) return;
    try {
        const auto bytes = utf8(line);
        std::fwrite(bytes.data(), 1, bytes.size(), stderr);
    } catch (const LaunchError&) {
        std::fputs("Cloth launcher: unable to start Java\n", stderr);
    }
}

Handle verifyJli(const std::wstring& path) {
    // Keep the verified DLL open without write/delete sharing until JLI has been loaded.
    const auto file = CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (file == INVALID_HANDLE_VALUE) windowsError(L"Unable to open bundled JLI '" + path + L"'");
    Handle handle(file, &CloseHandle);
    LARGE_INTEGER size{};
    if (!GetFileSizeEx(file, &size)) windowsError(L"Unable to inspect bundled JLI '" + path + L"'");
    const LaunchError incompatible{L"Incompatible bundled JLI '" + path + L"'. Reinstall the toolchain with its original runtime."};
    if (size.QuadPart != expectedJliSize) throw incompatible;
    std::vector<BYTE> contents(expectedJliSize);
    DWORD read = 0;
    if (!ReadFile(file, contents.data(), expectedJliSize, &read, nullptr) || read != expectedJliSize) {
        windowsError(L"Unable to read bundled JLI '" + path + L"'");
    }
    std::array<BYTE, sizeof(expectedJliHash)> digest{};
    if (!BCRYPT_SUCCESS(BCryptHash(BCRYPT_SHA256_ALG_HANDLE, nullptr, 0, contents.data(), read, digest.data(), static_cast<ULONG>(digest.size())))) {
        throw LaunchError{L"Unable to check the bundled JLI digest"};
    }
    if (!std::equal(digest.begin(), digest.end(), expectedJliHash)) throw incompatible;
    return handle;
}

template<typename Function>
Function jliExport(HMODULE library, const char* name) {
    const auto address = GetProcAddress(library, name);
    if (!address) throw LaunchError{L"The bundled JLI does not expose the Java 25 launcher interface"};
    // GetProcAddress exposes an untyped procedure address.
#pragma warning(suppress : 4191)
    return reinterpret_cast<Function>(address);
}

// OpenJDK 25's internal Windows launcher interface, checked against the packaged DLL.
struct StdArg {
    char* text;
    jboolean hasWildcard;
};
using InitArgs = void (JNICALL*)(jboolean, jboolean);
using ParseArgs = void (JNICALL*)(char*);
using ArgCount = int (JNICALL*)();
using ArgValues = StdArg* (JNICALL*)();
using Launch = int (JNICALL*)(int, char**, int, const char**, int, const char**,
    const char*, const char*, const char*, const char*, jboolean, jboolean, jboolean, jint);

int launchBundled(const std::wstring& runtime, const Arguments& arguments) {
    if (GetACP() != CP_UTF8) throw LaunchError{L"The bundled Java launcher requires a Windows UTF-8 process code page"};
    requireFile(runtime + L"\\bin\\java.exe", L"Bundled Java executable");
    requireFile(runtime + L"\\bin\\java.dll", L"Bundled Java library");
    requireFile(runtime + L"\\bin\\server\\jvm.dll", L"Bundled JVM");
    requireFile(runtime + L"\\lib\\modules", L"Bundled Java modules");
    requireFile(runtime + L"\\lib\\jvm.cfg", L"Bundled JVM configuration");
    const auto jli = runtime + L"\\bin\\jli.dll";
    const auto verified = verifyJli(jli);
    const auto library = LoadLibraryExW(jli.c_str(), nullptr, LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_SYSTEM32);
    if (!library) windowsError(L"Unable to load bundled JLI '" + jli + L"'");
    // JLI and HotSpot own process-lifetime state, including shutdown handlers.
    const auto initialize = jliExport<InitArgs>(library, "JLI_InitArgProcessing");
    const auto parse = jliExport<ParseArgs>(library, "JLI_CmdToArgs");
    const auto count = jliExport<ArgCount>(library, "JLI_GetStdArgc");
    const auto values = jliExport<ArgValues>(library, "JLI_GetStdArgs");
    const auto launch = jliExport<Launch>(library, "JLI_Launch");
    auto command = utf8(commandLine(arguments));
    initialize(JNI_FALSE, JNI_FALSE);
    parse(command.data());
    const auto argc = count();
    const auto parsed = values();
    std::vector<char*> argv;
    for (int index = 0; index < argc; ++index) argv.push_back(parsed[index].text);
    argv.push_back(nullptr);
    return launch(argc, argv.data(), 0, nullptr, 0, nullptr, bundledJavaVersion, "25", "java", "java",
        JNI_FALSE, JNI_TRUE, JNI_FALSE, 0);
}

std::wstring externalJava() {
    auto javaHome = environment(L"JAVA_HOME");
    if (!javaHome.empty()) {
        if (javaHome.size() >= 2 && javaHome.front() == L'"' && javaHome.back() == L'"') {
            javaHome = javaHome.substr(1, javaHome.size() - 2);
        }
        const auto java = javaHome + L"\\bin\\java.exe";
        const auto flags = attributes(java);
        if (flags == INVALID_FILE_ATTRIBUTES || (flags & FILE_ATTRIBUTE_DIRECTORY)) {
            throw LaunchError{L"JAVA_HOME does not contain bin\\java.exe: '" + javaHome + L"'", 1};
        }
        return java;
    }
    const auto path = environment(L"PATH");
    std::wstring java(32768, L'\0');
    const auto capacity = static_cast<DWORD>(java.size());
    const auto length = path.empty() ? 0 : SearchPathW(path.c_str(), L"java.exe", nullptr, capacity, java.data(), nullptr);
    if (!length || length >= capacity) throw LaunchError{L"Set JAVA_HOME or put Java 25 on PATH", 1};
    java.resize(length);
    return java;
}

BOOL WINAPI waitForJavaOnInterrupt(DWORD event) {
    // The child shares the console and handles the interrupt itself.
    return event == CTRL_C_EVENT || event == CTRL_BREAK_EVENT;
}

int launchExternal(const Arguments& arguments) {
    auto command = commandLine(arguments);
    STARTUPINFOW startup{};
    startup.cb = sizeof(startup);
    startup.dwFlags = STARTF_USESTDHANDLES;
    startup.hStdInput = GetStdHandle(STD_INPUT_HANDLE);
    startup.hStdOutput = GetStdHandle(STD_OUTPUT_HANDLE);
    startup.hStdError = GetStdHandle(STD_ERROR_HANDLE);
    PROCESS_INFORMATION process{};
    SetConsoleCtrlHandler(waitForJavaOnInterrupt, TRUE);
    if (!CreateProcessW(arguments.front().c_str(), command.data(), nullptr, nullptr, TRUE, 0, nullptr, nullptr, &startup, &process)) {
        windowsError(L"Unable to start '" + arguments.front() + L"'");
    }
    CloseHandle(process.hThread);
    const Handle child(process.hProcess, &CloseHandle);
    if (WaitForSingleObject(child.get(), INFINITE) != WAIT_OBJECT_0) windowsError(L"Unable to wait for Java");
    DWORD status = 0;
    if (!GetExitCodeProcess(child.get(), &status)) windowsError(L"Unable to read Java's exit status");
    return static_cast<int>(status);
}

int run(int argc, wchar_t* argv[]) {
    const auto executable = modulePath();
    const auto separator = executable.find_last_of(L'\\');
    const auto bin = executable.substr(0, separator);
    const auto home = bin.substr(0, bin.find_last_of(L'\\'));
    const bool worker = _wcsicmp(executable.c_str() + separator + 1, L"cloth-jvm.exe") == 0;
    if (worker && (argc != 3 || std::wstring_view(argv[1]) != workerSelector)) {
        throw LaunchError{L"Invalid Shuttle compiler worker invocation"};
    }
    const auto runtime = home + L"\\runtime";
    const bool bundled = attributes(runtime) != INVALID_FILE_ATTRIBUTES;
    if (worker && !bundled) throw LaunchError{L"Missing bundled Java runtime '" + runtime + L"'"};
    const auto development = worker ? environment(L"CLOTH_SHUTTLE_DEVELOPMENT_HOME") : L"";
    const wchar_t* entry = worker ? L"shuttle.bridge.NativeCompilerWorker" : L"cloth.EntryKt";
    Arguments arguments{
        bundled ? executable : externalJava(),
        L"-Dcloth.toolchain.home=" + (development.empty() ? home : development),
        development.empty() ? L"-Dcloth.toolchain.layout=installed" : L"-Dcloth.toolchain.layout=development",
        L"--enable-native-access=ALL-UNNAMED",
    };
    appendOptions(arguments, L"JAVA_OPTS");
    appendOptions(arguments, worker ? L"SHUTTLE_OPTS" : L"CLOTH_OPTS");
    if (bundled) {
        // JLI checks beside the launcher executable before using its own DLL location.
        if (attributes(bin + L"\\java.dll") != INVALID_FILE_ATTRIBUTES) {
            throw LaunchError{L"Unexpected java.dll beside the launcher. Bundled Java belongs under runtime\\bin."};
        }
        const auto module = home + L"\\launcher\\cloth-launcher.jar";
        requireFile(module, L"Java bootstrap module");
        arguments.insert(arguments.end(), {L"--module-path", module, L"--module", L"cloth.launcher/cloth.launcher.Bootstrap", entry, home + L"\\lib"});
    } else {
        arguments.insert(arguments.end(), {L"-cp", home + L"\\lib\\*", entry});
    }
    arguments.insert(arguments.end(), argv + (worker ? 2 : 1), argv + argc);
    return bundled ? launchBundled(runtime, arguments) : launchExternal(arguments);
}

} // namespace

int wmain(int argc, wchar_t* argv[]) {
    try {
        return run(argc, argv);
    } catch (const LaunchError& error) {
        report(error.message);
        return error.status;
    } catch (const std::exception&) {
        report(L"Unable to prepare the Java launch");
    }
    return startupFailure;
}
