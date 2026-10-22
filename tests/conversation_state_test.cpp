#include "../app/src/main/cpp/conversation_state.h"
#include <iostream>
#include <stdexcept>

static void require(bool value) { if(!value)throw std::runtime_error("Conversation-state assertion failed"); }
int main() {
    require(conversation_prefix(std::vector<int>{1,2,3,4},std::vector<int>{1,2,9},true)==2);
    require(conversation_prefix(std::vector<int>{1,2,3},std::vector<int>{1,2,3},true)==2);
    require(conversation_prefix(std::vector<int>{1,2,3},std::vector<int>{1,2,3},false)==3);
    const std::string text="<think>geheim 🪴</think>Antwort < 4<think>mehr</think> fertig";
    for(size_t split=0;split<=text.size();++split) {
        ReasoningFilter filter;filter.feed(text.substr(0,split));filter.feed(text.substr(split));filter.finish();
        require(filter.visible=="Antwort < 4 fertig");
    }
    ReasoningFilter stream;
    for(char c:text)stream.feed(std::string(1,c));
    stream.finish();require(stream.visible=="Antwort < 4 fertig");
    require(without_reasoning("<think>unterbrochen").empty());
    ReasoningFilter capped;capped.feed("<think>secret</thi");capped.close();capped.feed("Ende");capped.finish();require(capped.visible=="Ende");
    std::cout<<"Prefix matching and split reasoning-filter checks passed\n";
}
