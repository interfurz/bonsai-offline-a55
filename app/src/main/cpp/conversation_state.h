#pragma once
#include <algorithm>
#include <string>
#include <vector>
#include <functional>

// No second KV allocation: only token IDs for the positions in sequence 0.
template<class Token> size_t conversation_prefix(const std::vector<Token> & cached,const std::vector<Token> & prompt,bool logits) {
    size_t n=0;
    while(n<cached.size() && n<prompt.size() && cached[n]==prompt[n])++n;
    // KV does not retain logits for an arbitrary earlier position.
    if(logits && n==prompt.size() && n>0)--n;
    return n;
}

// Keeps at most a tag prefix while thinking; optionally streams thoughts to a separate UI channel.
struct ReasoningFilter {
    bool thinking=false;
    std::string pending, visible;
    std::function<void(char)> on_thinking;
    void feed(const std::string & bytes) {
        for(char c:bytes) {
            pending+=c;
            for(;;) {
                if(pending=="<think>") { thinking=true;pending.clear();break; }
                if(pending=="</think>") { thinking=false;pending.clear();break; }
                if(std::string("<think>").compare(0,pending.size(),pending)==0 ||
                   std::string("</think>").compare(0,pending.size(),pending)==0)break;
                if(!thinking)visible+=pending[0];
                else if(on_thinking)on_thinking(pending[0]);
                pending.erase(0,1);
                if(pending.empty())break;
            }
        }
    }
    void close() { pending.clear();thinking=false; }
    void finish() {
        if(!thinking)visible+=pending;
        pending.clear();
    }
};
inline std::string without_reasoning(const std::string & text) {
    ReasoningFilter filter;filter.feed(text);filter.finish();return filter.visible;
}
