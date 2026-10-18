#pragma once
#include <cstddef>
#include <string>

// Tokens may end in the middle of a UTF-8 character. Only send complete bytes to Java.
inline std::size_t complete_utf8_prefix(const std::string & bytes) {
    std::size_t i = 0;
    while (i < bytes.size()) {
        const auto c = static_cast<unsigned char>(bytes[i]);
        std::size_t length = c < 0x80 ? 1 : (c & 0xe0) == 0xc0 ? 2 :
                             (c & 0xf0) == 0xe0 ? 3 : (c & 0xf8) == 0xf0 ? 4 : 1;
        if (i + length > bytes.size()) break;
        i += length;
    }
    return i;
}
