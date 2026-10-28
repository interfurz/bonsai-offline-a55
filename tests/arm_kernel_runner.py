"""Compile and execute a freestanding AArch64 kernel; not a timing harness."""
import io
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import unittest

from elftools.elf.elffile import ELFFile
from elftools.elf.sections import SymbolTableSection
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM
from unicorn.arm64_const import (UC_ARM64_REG_X0, UC_ARM64_REG_X1, UC_ARM64_REG_X2,
                                 UC_ARM64_REG_X3, UC_ARM64_REG_SP, UC_ARM64_REG_LR,
                                 UC_ARM64_REG_PC, UC_ARM64_REG_CPACR_EL1, UC_CPU_ARM64_MAX)

ROOT = Path(__file__).resolve().parents[1]


class ArmKernelRunner:
    def __init__(self, source, symbol):
        compiler = os.environ.get("BONSAI_ARM_CC") or shutil.which("clang")
        if not compiler:
            raise unittest.SkipTest("Set BONSAI_ARM_CC to a clang executable")
        self.temp = tempfile.TemporaryDirectory()
        obj = Path(self.temp.name) / "kernel.elf"
        subprocess.run([compiler, "--target=aarch64-none-elf", "-march=armv8.2-a+dotprod",
                        "-O3", "-ffreestanding", "-fno-stack-protector", "-nostdlib", "-fuse-ld=lld",
                        "-Wl,-Ttext=0x100000", f"-Wl,--entry={symbol}",
                        "-I", str(ROOT / "vendor/llama.cpp/ggml/src"),
                        str(ROOT / "app/src/main/cpp" / source), "-o", str(obj)], check=True)
        elf = ELFFile(io.BytesIO(obj.read_bytes()))
        text = elf.get_section_by_name(".text")
        symbols = elf.get_section_by_name(".symtab")
        if text is None or not isinstance(symbols, SymbolTableSection):
            raise AssertionError("Missing kernel code or symbols")
        self.sections = [(section["sh_addr"], section.data()) for section in elf.iter_sections()
                         if section["sh_flags"] & 2 and section["sh_size"]]
        if any(address < 0x100000 or address + len(data) >= 0x1F0000 for address, data in self.sections):
            raise AssertionError("Kernel exceeds reserved code region")
        matches = symbols.get_symbol_by_name(symbol)
        if not matches:
            raise AssertionError("Missing kernel entry point")
        self.entry = matches[0]["st_value"]

    def close(self):
        self.temp.cleanup()

    def execute(self, n, weights=b"", activations=b"", output_count=1):
        machine = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
        machine.ctl_set_cpu_model(UC_CPU_ARM64_MAX)
        base, data, stop = 0x100000, 0x300000, 0x1F0000
        machine.mem_map(base, 0x100000)
        for address, code in self.sections:
            machine.mem_write(address, code)
        machine.mem_map(data, 0x20000)
        output, w, a = data, data + 0x1000, data + 0x10000
        machine.mem_write(output, struct.pack("<"+"f"*output_count, *([12345.0]*output_count)))
        if weights:
            machine.mem_write(w, weights)
        if activations:
            machine.mem_write(a, activations)
        for reg, value in ((UC_ARM64_REG_X0, n & ((1 << 64) - 1)), (UC_ARM64_REG_X1, output),
                           (UC_ARM64_REG_X2, w), (UC_ARM64_REG_X3, a),
                           (UC_ARM64_REG_SP, data + 0x1FFF0), (UC_ARM64_REG_LR, stop),
                           (UC_ARM64_REG_CPACR_EL1, 3 << 20)):
            machine.reg_write(reg, value)
        machine.emu_start(self.entry, stop, count=1000000)
        if machine.reg_read(UC_ARM64_REG_PC) != stop:
            raise AssertionError("Kernel did not return within instruction budget")
        values = struct.unpack("<"+"f"*output_count, machine.mem_read(output, 4*output_count))
        return machine.reg_read(UC_ARM64_REG_X0), values[0] if output_count == 1 else values
