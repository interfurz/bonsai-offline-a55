#pragma once
#include <cstddef>
#include <stdexcept>
#include <string>

// Buffer only valid incomplete suffixes; the JNI boundary reports malformed model output.
inline std::size_t complete_utf8_prefix(const std::string & bytes) {
    std::size_t i = 0;
    while (i < bytes.size()) {
        const auto c = static_cast<unsigned char>(bytes[i]);
        const auto invalid = [] {
            throw std::runtime_error("Modell lieferte ungueltiges UTF-8. Antwort wurde unterbrochen.");
        };
        std::size_t length;
        if (c < 0x80) length = 1;
        else if (c >= 0xc2 && c <= 0xdf) length = 2;
        else if (c >= 0xe0 && c <= 0xef) length = 3;
        else if (c >= 0xf0 && c <= 0xf4) length = 4;
        else { invalid(); return i; }
        // Check available bytes even when the sequence is not complete yet.
        for (std::size_t j = 1; j < length && j < bytes.size() - i; ++j) {
            const auto next = static_cast<unsigned char>(bytes[i + j]);
            if (next < 0x80 || next > 0xbf) invalid();
            if (j == 1 && ((c == 0xe0 && next < 0xa0) ||
                           (c == 0xed && next > 0x9f) ||
                           (c == 0xf0 && next < 0x90) ||
                           (c == 0xf4 && next > 0x8f))) invalid();
        }
        if (length > bytes.size() - i) break;
        i += length;
    }
    return i;
}
