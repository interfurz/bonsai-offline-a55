#include "llama-grammar.h"
#include "llama-vocab.h"
#include "unicode.h"
#include "chat_sampler.h"
#include <fstream>
#include <iostream>
#include <iterator>
#include <memory>
#include <stdexcept>
#include <string>

static void require(bool condition,const std::string & message) {
    if(!condition)throw std::runtime_error(message);
}
static std::string read(const std::string & path) {
    std::ifstream file(path);
    require(file.good(),"Missing grammar: "+path);
    return std::string(std::istreambuf_iterator<char>(file),{});
}
static bool matches(const std::string & rules,const std::string & text) {
    std::unique_ptr<llama_grammar,decltype(&llama_grammar_free_impl)> grammar(
        llama_grammar_init_impl(nullptr,rules.c_str(),"root",false,nullptr,0,nullptr,0),llama_grammar_free_impl);
    require(grammar!=nullptr,"Grammar failed to initialize");
    for(size_t offset=0;offset<text.size();) {
        llama_grammar_accept(grammar.get(),unicode_cpt_from_utf8(text,offset));
        if(llama_grammar_get_stacks(grammar.get()).empty())return false;
    }
    for(const auto & stack:llama_grammar_get_stacks(grammar.get()))if(stack.empty())return true;
    return false;
}
int main(int argc,char ** argv) {
    try {
        require(argc==2,"Pass directory containing exported Java grammars");
        const auto tools=read(std::string(argv[1])+"/tools.gbnf");
        const auto final=read(std::string(argv[1])+"/final.gbnf");
        for(const auto & response:{"<final>Hallo 🪴\n2 < 4</final>","<final><</final>","<final><<</final>","<final></f</final>","<final><b>Code</b></final>"}) {
            require(matches(tools,response),std::string("Rejected answer: ")+response);
            require(matches(final,response),std::string("Rejected final answer: ")+response);
        }
        for(const auto & call:{R"GBNF(<tool>{"name":"calc","args":{"expr":"2*(3+4)"}}</tool>)GBNF",
                              R"GBNF(<tool>{"name":"merken","args":{"key":"name","value":"Alex 🪴\n\"Test\""}}</tool>)GBNF",
                              R"GBNF(<tool>{"name":"abrufen","args":{"key":"name"}}</tool>)GBNF"}) {
            require(matches(tools,call),std::string("Rejected valid tool: ")+call);
            require(!matches(final,call),"Final round must reject tools");
        }
        for(const auto & call:{
                R"(<tool>{"name":"wikidata","args":{"op":"resolve","entity":"Einstein"}}</tool>)",
                R"(<tool>{"name":"wikidata","args":{"op":"facts","entity":"Japan","property":"Hauptstadt"}}</tool>)",
                R"(<tool>{"name":"wikidata","args":{"op":"path","entity":"Q937","property":"P19/P17/P36"}}</tool>)",
                R"(<tool>{"name":"wikidata","args":{"op":"greater","entity":"Q11344","property":"P1086","value":"50","unit":"1"}}</tool>)",
                R"(<tool>{"name":"wikidata","args":{"op":"largest","entity":"Q11344","property":"P1086","unit":"1"}}</tool>)",
                R"(<tool>{"name":"wikidata","args":{"op":"count","entity":"Q634","property":"P397","value":"Q2537"}}</tool>)"}) {
            require(matches(tools,call),std::string("Rejected Wikidata tool: ")+call);
            require(!matches(final,call),"Final round must reject Wikidata");
        }
        for(const auto & invalid:{
                R"(<tool>{"name":"wikidata","args":{"op":"sql","entity":"DROP TABLE entity"}}</tool>)",
                R"(<tool>{"name":"wikidata","args":{"op":"facts","entity":"Japan"}}</tool>)",
                R"(<tool>{"name":"wikidata","args":{"op":"resolve","entity":17}}</tool>)",
                R"(<tool>{"name":"wikidata","args":{"op":"facts","entity":"Japan","property":"P36","sql":"x"}}</tool>)"})
            require(!matches(tools,invalid),std::string("Accepted invalid Wikidata call: ")+invalid);
        for(const auto & invalid:{"Hallo", "<final>unvollständig", "<final>x</final>suffix", "<final>x</final></final>",
                                 R"GBNF(<tool>{"name":"unknown","args":{}}</tool>)GBNF",
                                 R"GBNF(<tool>{'name':'calc','args':{'expr':'1+1'}}</tool>)GBNF",
                                 R"GBNF(<tool>{"name":"calc","args":{"expr":4}}</tool>)GBNF",
                                 R"GBNF(<tool>{"name":"calc","args":{"expr":"1"},"extra":"x"}</tool>)GBNF",
                                 R"GBNF(<tool>{"name":"calc","args":{"expr":"1"}}</tool><tool>{"name":"calc","args":{"expr":"2"}}</tool>)GBNF"}) {
            require(!matches(tools,invalid),std::string("Accepted invalid response: ")+invalid);
        }
        llama_vocab vocab;
        std::unique_ptr<llama_sampler,decltype(&llama_sampler_free)> constrained(bonsai_chat_sampler(&vocab,42,tools.c_str()),llama_sampler_free);
        require(llama_sampler_chain_n(constrained.get())==6,"Expected grammar plus five existing samplers");
        require(std::string(llama_sampler_name(llama_sampler_chain_get(constrained.get(),0)))=="grammar","Grammar must be first");
        std::unique_ptr<llama_sampler,decltype(&llama_sampler_free)> plain(bonsai_chat_sampler(&vocab,42),llama_sampler_free);
        require(llama_sampler_chain_n(plain.get())==5,"Disabled mode must retain original samplers");
        bool rejected=false;
        try { auto * unexpected=bonsai_chat_sampler(&vocab,42,"invalid grammar");llama_sampler_free(unexpected); }
        catch(const std::runtime_error &) { rejected=true; }
        require(rejected,"Invalid grammar must fail instead of disabling the constraint");
        std::cout<<"Tool/final GBNF acceptance, rejection and sampler ordering passed\n";
        return 0;
    } catch(const std::exception & e) { std::cerr<<e.what()<<'\n';return 1; }
}
