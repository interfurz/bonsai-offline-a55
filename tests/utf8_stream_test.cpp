#include "../app/src/main/cpp/utf8_stream.h"
#include <cassert>
#include <iostream>
#include <vector>

int main() {
    // Exercise every possible byte split, including splits inside emoji and umlauts.
    const std::vector<std::string> samples = {"", "Hallo", u8"Grüße aus Köln", u8"🌲🤖 é 日本語", std::string("a\0b",3)};
    for (const auto & sample : samples) {
        for (size_t cut=0; cut<=sample.size(); ++cut) {
            std::string pending=sample.substr(0,cut);
            auto n=complete_utf8_prefix(pending);
            std::string rendered=pending.substr(0,n);
            pending.erase(0,n);
            pending+=sample.substr(cut);
            n=complete_utf8_prefix(pending);
            rendered+=pending.substr(0,n);
            assert(n==pending.size());
            assert(rendered==sample);
        }
        std::string pending, rendered;
        for(char b:sample) {
            pending+=b;
            auto n=complete_utf8_prefix(pending);
            rendered+=pending.substr(0,n); pending.erase(0,n);
        }
        assert(pending.empty()); assert(rendered==sample);
    }
    std::cout << "UTF-8 streaming: all split boundaries passed\n";
}
