"""Exercise actual PTQ upload -> selected planar ARM kernel, not manual packing."""
from pathlib import Path
import random
import struct
import subprocess
import tempfile
import unittest
from unittest import mock

from arm_kernel_runner import ArmKernelRunner, ROOT
from arm_recode_reference import PinnedArmPtqRunner, fp32_bits


class ArmPlanarUploadTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory(prefix="bonsai-planar-upload-")
        cls.addClassCleanup(cls.directory.cleanup)
        source = Path(cls.directory.name) / "upload.c"
        kernels = (ROOT / "app/src/main/cpp/ptq_recode_kernels.c").as_posix()
        planar = (ROOT / "app/src/main/cpp/ternary_arm_planar.c").as_posix()
        source.write_text(f'''#define BONSAI_PTQ_PLANAR 1
#include "{kernels}"
#include "{planar}"
static BonsaiPtqCache4 cache[128];
int bonsai_test_planar_upload(int n, float * output, const void * weights, const void * activations) {{
    if (n < 0 || n > 16384 || n % 128) return 0;
    const size_t row_blocks = (size_t)n/128;
    const block_ptq1_0 * source = weights;
    for (size_t end=4*row_blocks; end;) {{
        const size_t first = end>5 ? end-5 : 0;
        bonsai_ptq_recode_upload(source+first,cache,first,end-first,row_blocks);
        end=first;
    }}
    return bonsai_ptq_recode_dot4(n,output,cache,activations);
}}
''', encoding="utf-8")
        cls.runner = ArmKernelRunner(str(source.resolve()), "bonsai_test_planar_upload")
        cls.addClassCleanup(cls.runner.close)
        cls.original = PinnedArmPtqRunner()
        cls.addClassCleanup(cls.original.close)

    def compare_original(self, n, payloads, values, scale_bits):
        weights = b"".join(payloads[row][b] + struct.pack("<H", scale_bits[(row+b)%len(scale_bits)])
                           for row in range(4) for b in range(n//128))
        dy_bits = [0x2E67, 0x3555, 0x3AAB, 0x0401, 0x0001, 0x0000, 0x3C01]
        activations = b"".join(struct.pack("<H32b", dy_bits[b%len(dy_bits)], *values[b*32:(b+1)*32])
                               for b in range(n//32))
        accepted, actual = self.runner.execute(n, weights, activations, output_count=4)
        expected_accepted, expected = self.original.execute(n, weights, activations)
        self.assertEqual((accepted, expected_accepted), (1, 1))
        self.assertEqual([fp32_bits(v) for v in actual], [fp32_bits(v) for v in expected])

    def test_upload_all_bytecodes_preserves_original_arm_output(self):
        for byte in range(256):
            with self.subTest(byte=byte):
                payloads = [[bytes([byte])*26] for _ in range(4)]
                self.compare_original(128, payloads, [-128,127,-127,126,0,1,-1,42]*16,
                                      [0x3555,0xB6DB,0x3C01,0x4103])

    def test_reverse_partial_uploads_irregular_scales_and_long_widths(self):
        rng = random.Random(341361)
        for n in [128,384,640,5120,16384]:
            with self.subTest(n=n):
                payloads = [[rng.randbytes(26) for _ in range(n//128)] for _ in range(4)]
                self.compare_original(n,payloads,[rng.randrange(-128,128) for _ in range(n)],
                                      [0x3555,0x3C01,0xB6DB,0x4103,0x0401,0x03FF,0x8001,0x7BFF])

    def test_invalid_widths_do_not_write_outputs(self):
        for n in [-128,1,127,129,16512]:
            self.assertEqual(self.runner.execute(n,output_count=4),(0,(12345.0,)*4))

    def test_empty_upload_has_zero_output(self):
        self.assertEqual(self.runner.execute(0,output_count=4),(1,(0.0,)*4))

    def test_scalar_fallback_reports_planar_layout(self):
        with tempfile.TemporaryDirectory(prefix="bonsai-planar-scalar-") as directory:
            source=Path(directory)/"scalar.c"
            kernels=(ROOT/"app/src/main/cpp/ptq_recode_kernels.c").as_posix()
            source.write_text(f'''#define BONSAI_PTQ_PLANAR 1
#include "{kernels}"
int check_scalar_name(int n,float * output,const void * weights,const void * activations) {{
    (void)n;(void)weights;(void)activations;
    const char * name=bonsai_ptq_recode_kernel_name();
    const char expected[]="scalar-planar";
    int match=bonsai_ptq_recode_select_dot4()==NULL;
    for (size_t i=0;i<sizeof(expected);++i) {{
        if (name[i]!=expected[i]) {{ match=0;break; }}
    }}
    *output=(float)match;return 1;
}}
''',encoding="utf-8")
            actual_run=subprocess.run
            def compile_baseline(command,**kwargs):
                return actual_run([arg.replace("-march=armv8.2-a+dotprod","-march=armv8-a")
                                   for arg in command],**kwargs)
            with mock.patch("arm_kernel_runner.subprocess.run",side_effect=compile_baseline):
                runner=ArmKernelRunner(str(source.resolve()),"check_scalar_name")
            try:
                self.assertEqual(runner.execute(0),(1,1.0))
            finally:
                runner.close()


if __name__ == "__main__":
    unittest.main()
