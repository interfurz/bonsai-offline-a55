#include "ptq_recode.h"
#include "ggml.h"
#include "quants.h"
#include <cstring>
#include <iostream>
#include <random>
#include <stdexcept>
#include <vector>

static void require(bool ok, const char * message) {
    if (!ok) throw std::runtime_error(message);
}
int main() {
    try {
        constexpr size_t row_blocks=3, rows=12, count=row_blocks*rows, guard=64;
        std::mt19937 rng(5120136);
        for (int pattern=0;pattern<257;++pattern) {
            std::vector<block_ptq1_0> input(count);
            for (size_t b=0;b<count;++b) {
                for (auto & q : input[b].qs) q=uint8_t(pattern<256 ? pattern : rng());
                for (auto & q : input[b].qh) q=uint8_t(pattern<256 ? pattern : rng());
                input[b].d=uint16_t(b*1793); // Raw scale bits, including nonfinite encodings.
            }
            std::vector<uint8_t> storage(count*34+2*guard,0xA5);
            auto * packed=reinterpret_cast<BonsaiPtqCache4 *>(storage.data()+guard);
            // Reverse, block-aligned slices cross row and four-row group boundaries.
            for (size_t end=count;end;) {
                const size_t first=end>5 ? end-5 : 0;
                bonsai_ptq_recode_upload(input.data()+first,packed,first,end-first,row_blocks);
                end=first;
            }
            for (size_t b=0;b<count;++b) {
                const size_t row=b/row_blocks, lane=row%4;
                const auto & actual=packed[(row/4)*row_blocks+b%row_blocks];
                require(actual.d[lane]==input[b].d,"Planar upload changed FP16 scale bits");
                auto unit=input[b];unit.d=ggml_fp32_to_fp16(1.f);
                float weights[128];
                ggml_get_type_traits(GGML_TYPE_PTQ1_0)->to_float(&unit,weights,128);
                for (int k=0;k<4;++k) for (int j=0;j<32;++j) {
                    const unsigned code=(actual.qs[k*32+lane*8+j%8]>>(2*(j/8)))&3;
                    require(int(code)-1==int(weights[k*32+j]),"Planar upload did not preserve weight position");
                }
            }
            for (size_t i=0;i<guard;++i)
                require(storage[i]==0xA5 && storage[storage.size()-guard+i]==0xA5,"Planar upload overwrote guard bytes");
        }
        std::cout << "Planar loadcache: all 256 bytecodes + random payloads, exact positions/scales, reverse partial uploads and guard bytes passed\n";
    } catch (const std::exception & e) { std::cerr << e.what() << '\n'; return 1; }
}
