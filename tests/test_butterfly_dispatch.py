"""Verify opt-in dispatch wiring independently of the candidate arithmetic."""
import os
import platform
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ButterflyDispatchTests(unittest.TestCase):
    @unittest.skipUnless(platform.machine().lower() in ("amd64", "x86_64", "i386", "i686"),
                         "x86-specific dispatch probe")
    def test_opt_in_routes_both_public_entry_points(self):
        compiler = os.environ.get("BONSAI_HOST_CC") or shutil.which("gcc")
        if not compiler:
            self.skipTest("Set BONSAI_HOST_CC to GCC")
        source = r'''
#include "ptq_recode.h"
#include <stdio.h>
#include <string.h>
static int calls;
void bonsai_ptq_butterfly_dot4(int n,float * out,const void * w,const void * a) {
    (void)n;(void)w;(void)a;++calls;out[0]=17;
}
int main(void) {
    __builtin_cpu_init();
    if (!(__builtin_cpu_supports("avx512f") && __builtin_cpu_supports("avx512bw") &&
          __builtin_cpu_supports("avx512dq") && __builtin_cpu_supports("avx512vl") &&
          __builtin_cpu_supports("avx512vnni") && __builtin_cpu_supports("fma") &&
          __builtin_cpu_supports("f16c"))) return 77;
    if (bonsai_ptq_recode_select_dot4()!=bonsai_ptq_butterfly_dot4) return 1;
    float out[4]={0};
    if (!bonsai_ptq_recode_dot4(0,out,NULL,NULL) || calls!=1 || out[0]!=17) return 2;
    bonsai_ptq_recode_select_dot4()(0,out,NULL,NULL);
    if (calls!=2) return 3;
    if (bonsai_ptq_recode_dot4(127,out,NULL,NULL) || calls!=2) return 4;
    const char * expected=BONSAI_PTQ_PLANAR ? "avx512-vnni-butterfly-planar" : "avx512-vnni-butterfly";
    if (strcmp(bonsai_ptq_recode_kernel_name(),expected)) return 5;
    puts("Butterfly dispatch and exact layout identity verified");
    return 0;
}
'''
        with tempfile.TemporaryDirectory(prefix="bonsai-butterfly-dispatch-") as directory:
            path = Path(directory)
            probe = path / "probe.c"
            probe.write_text(source, encoding="utf-8")
            for planar in (0, 1):
                with self.subTest(planar=planar):
                    executable = path / f"probe-{planar}.exe"
                    command = [compiler, "-std=c11", "-O2", "-DBONSAI_RECODE_BUTTERFLY=1",
                               f"-DBONSAI_PTQ_PLANAR={planar}", "-I", str(ROOT / "app/src/main/cpp"),
                               "-I", str(ROOT / "vendor/llama.cpp/ggml/src"), str(probe),
                               str(ROOT / "app/src/main/cpp/ptq_recode_kernels.c"), "-o", str(executable)]
                    build = subprocess.run(command, capture_output=True, text=True)
                    self.assertEqual(build.returncode, 0, build.stdout + build.stderr)
                    run = subprocess.run([str(executable)], capture_output=True, text=True)
                    if run.returncode == 77:
                        self.skipTest("AVX512 VNNI unavailable on this host")
                    self.assertEqual(run.returncode, 0, run.stdout + run.stderr)


if __name__ == "__main__":
    unittest.main()
