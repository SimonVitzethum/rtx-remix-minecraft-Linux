import argparse
import hashlib
import json
import os

parser = argparse.ArgumentParser(description = 'Convert SPV to C header')
parser.add_argument('--input', '-i', help = "Input SPV", required=True)
parser.add_argument('--output', '-o', help = "Output header", required=True)
args = parser.parse_args()

def xxd_c_array(data, output_filename, input_filename):
    # array name is the file name, extract filename without extension from basename
    output_basename = os.path.basename(output_filename)
    array_name = output_basename.removesuffix('.h')

    # calculate padding from byte array length
    length = len(data)
    padding = (4 - (length % 4)) % 4
    data += b'\x00' * padding

    with open(output_filename, 'w', encoding='utf-8') as f:
        # ELF COMDAT sections share identical shader data across translation units.
        # The digest makes preprocessing caches sensitive to incbin input changes.
        symbol = 'dxvk_spv_' + array_name
        source = json.dumps(os.path.abspath(input_filename), ensure_ascii=False)
        zero_padding = f'.zero {padding}\n' if padding else ''
        assembly = (
            f'/* SPIR-V SHA256: {hashlib.sha256(data).hexdigest()} */\n'
            f'.pushsection .rodata.{symbol},"aG",@progbits,{symbol},comdat\n'
            f'.balign 4\n.weak {symbol}\n.hidden {symbol}\n'
            f'.type {symbol},@object\n{symbol}:\n.incbin {source}\n'
            f'{zero_padding}.size {symbol},.-{symbol}\n.popsection\n'
        )
        f.write('#if defined(REMIX_LINUX_NATIVE) && defined(__ELF__)\n')
        f.write(f'extern const uint32_t {array_name}[{len(data) // 4}] __asm__("{symbol}");\n')
        f.write('__asm__(' + json.dumps(assembly, ensure_ascii=False) + ');\n')
        f.write('#else\n')
        f.write(f"const uint32_t {array_name}[] = {{")

        for i in range(0, len(data), 4):
            if (i // 4) % 8 == 0: # 8 columns
                f.write('\n    ')
            val = data[i] | (data[i+1] << 8) | (data[i+2] << 16) | (data[i+3] << 24)
            f.write(f'0x{val:08x}, ')

        f.write("\n};\n#endif\n\n")
        f.write(f"const size_t {array_name}_sizeInBytes = {length};\n")

if not os.path.exists(args.input):
    print(args.input + " file not found.")
    exit(1)

f = open(args.input, 'rb')
xxd_c_array(f.read(), args.output, args.input)
