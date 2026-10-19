#include "../app/src/main/cpp/utf8_stream.h"
#include <cassert>
#include <cstdint>
#include <iostream>

#include <stdexcept>
#include <vector>

static void assert_complete_chunk(const std::string & chunk) {
    // Independent scalar decoder: concatenation alone can hide split characters.
    unsigned remaining = 0;
    uint32_t scalar = 0, minimum = 0;
    for (unsigned char c : chunk) {
        if (remaining == 0) {
            if (c < 0x80) continue;
            if (c >= 0xc2 && c <= 0xdf) { remaining=1; scalar=c & 0x1f; minimum=0x80; }
            else if (c >= 0xe0 && c <= 0xef) { remaining=2; scalar=c & 0x0f; minimum=0x800; }
            else if (c >= 0xf0 && c <= 0xf4) { remaining=3; scalar=c & 0x07; minimum=0x10000; }
            else assert(false && "Invalid UTF-8 leading byte in emitted chunk");
        } else {
            assert((c & 0xc0) == 0x80);
            scalar = (scalar << 6) | (c & 0x3f);
            if (--remaining == 0) {
                assert(scalar >= minimum && scalar <= 0x10ffff);
                assert(scalar < 0xd800 || scalar > 0xdfff);
            }
        }
    }
    assert(remaining == 0 && "An emitted chunk ends inside a UTF-8 character");
}

int main() {
    // Exercise every possible byte split, including splits inside emoji and umlauts.
    const std::vector<std::string> samples = {"", "Hallo", u8"Grüße aus Köln", u8"🌲🤖 é 日本語", std::string("a\0b",3),
        "\xc2\x80\xdf\xbf\xe0\xa0\x80\xed\x9f\xbf\xee\x80\x80\xef\xbf\xbf\xf0\x90\x80\x80\xf4\x8f\xbf\xbf"};
    for (const auto & sample : samples) {
        for (size_t cut=0; cut<=sample.size(); ++cut) {
            std::string pending=sample.substr(0,cut);
            auto n=complete_utf8_prefix(pending);
            std::string rendered=pending.substr(0,n);
            assert_complete_chunk(rendered);
            pending.erase(0,n);
            pending+=sample.substr(cut);
            n=complete_utf8_prefix(pending);
            assert_complete_chunk(pending.substr(0,n));
            rendered+=pending.substr(0,n);
            assert(n==pending.size());
            assert(rendered==sample);
        }
        std::string pending, rendered;
        for(char b:sample) {
            pending+=b;
            auto n=complete_utf8_prefix(pending);
            assert_complete_chunk(pending.substr(0,n));
            rendered+=pending.substr(0,n); pending.erase(0,n);
        }
        assert(pending.empty()); assert(rendered==sample);
    }
    for (const auto & incomplete : {std::string("\xf0"), std::string("\xf0\x9f"), std::string("\xf0\x9f\x8c")}) {
        assert(complete_utf8_prefix(incomplete) == 0);
        assert(complete_utf8_prefix("A" + incomplete) == 1);
    }
    assert(complete_utf8_prefix("\xf0\x9f\x8c\xb2") == 4);
    const std::vector<std::string> malformed = {
        "\x80", "\xc0\xaf", "\xc1\xbf", "\xe2\x41", "\xe2\x41\x42",
        "\xe0\x80\x80", "\xed\xa0\x80", "\xf0\x80\x80\x80",
        "\xf4\x90\x80\x80", "\xf5", "\xff", "\xe0\x80", "\xed\xa0",
        "\xf0\x80", "\xf4\x90", "\xe2\x82\x41", "\xf0\x9f\x8c\x41"
    };
    for (const auto & bytes : malformed) {
        for (const auto & prefix : {std::string(), std::string("OK ")}) {
            bool rejected = false;
            try { complete_utf8_prefix(prefix + bytes); }
            catch (const std::runtime_error &) { rejected = true; }
            assert(rejected && "Malformed UTF-8 must not be streamed to Java");
        }
    }
    std::cout << "UTF-8 streaming: malformed input rejected; all chunks and split boundaries passed\n";
}
