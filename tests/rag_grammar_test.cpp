#include "llama-grammar.h"
#include "unicode.h"
#include <fstream>
#include <iostream>
#include <iterator>
#include <memory>
#include <stdexcept>
#include <string>

static bool accepts(const std::string & rules,const std::string & text) {
    std::unique_ptr<llama_grammar,decltype(&llama_grammar_free_impl)> grammar(
        llama_grammar_init_impl(nullptr,rules.c_str(),"root",false,nullptr,0,nullptr,0),llama_grammar_free_impl);
    if(!grammar)throw std::runtime_error("Invalid exported grammar");
    for(size_t offset=0;offset<text.size();) {
        llama_grammar_accept(grammar.get(),unicode_cpt_from_utf8(text,offset));
        if(llama_grammar_get_stacks(grammar.get()).empty())return false;
    }
    for(const auto & stack:llama_grammar_get_stacks(grammar.get()))if(stack.empty())return true;
    return false;
}
static std::string read(const std::string & path) {
    std::ifstream file(path);
    if(!file.good())throw std::runtime_error("Missing exported Java grammar");
    return std::string(std::istreambuf_iterator<char>(file),{});
}
int main(int argc,char ** argv) {
    try {
        if(argc!=2)throw std::runtime_error("Pass directory with tools.gbnf and final.gbnf");
        const auto tools=read(std::string(argv[1])+"/tools.gbnf");
        const auto final=read(std::string(argv[1])+"/final.gbnf");
        const std::string valid=R"(<tool>{"name":"suche","args":{"query":"Wälder und Bäume"}}</tool>)";
        if(!accepts(tools,valid) || accepts(final,valid))throw std::runtime_error("suche round constraint failed");
        for(const auto & invalid:{
            R"(<tool>{"name":"suche","args":{"expr":"Wald"}}</tool>)",
            R"(<tool>{"name":"suche","args":{"query":7}}</tool>)",
            R"(<tool>{"name":"suche","args":{"query":"Wald","extra":"x"}}</tool>)",
            R"(<tool>{"name":"suche","args":{"query":"Wald"}}</tool>suffix)"})
            if(accepts(tools,invalid))throw std::runtime_error("Invalid suche call accepted");
        std::cout<<"suche GBNF: valid Unicode query, invalid arguments and final-only round passed\n";
        return 0;
    } catch(const std::exception & e) {std::cerr<<e.what()<<'\n';return 1;}
}
