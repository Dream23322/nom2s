# minecraft2shell: a technical report

## At a glance

`minecraft2shell` is a remote code execution chain against the Minecraft Java client. A malicious server sends an ordinary resource pack containing a crafted TrueType font. The client loads that font through its native font engine, FreeType. During glyph loading, FreeType executes the font's own hinting bytecode. A bug in the `SHZ` instruction lets that bytecode write past a point buffer into FreeType's interpreter context. The font then uses FreeType's own error and instruction-dispatch machinery to repair the damage, stage a ROP chain, and transfer native control to it. The public demonstration calls `WinExec("calc.exe")` and then `ExitProcess`.

The important point is that the font's bytecode is not data. It is a program, running inside the client process with the client's privileges, outside the Java sandbox.

This report describes the public proof of concept published at `github.com/bikini/minecraft2shell` (pack SHA-1 `F2E2859318152E32FC331D2291C2EC40FFB3C372`), which was built for the Mojang-signed Minecraft 26.3 Windows x64 client with bundled FreeType 2.14.3.

## Why a resource pack can become native code

Minecraft resource packs are more than textures and sounds. They can contain font definitions under `assets/<namespace>/font/*.json`. The `ttf` provider points at a font file and asks the client to load it with FreeType or STB TrueType, depending on the Minecraft version.

The server controls:

- the pack contents,
- the pack URL and SHA-1 advertised in `server.properties`,
- whether the pack is required,
- and the ordinary chat or action-bar text that selects a font.

The client controls only whether it downloads and applies the pack. A server the player has trusted can deliver the pack without a prompt. An untrusted server can still deliver it with a normal resource-pack acceptance prompt. Accepting a pack is a normal action; it is not treated as consent to run native code.

Minecraft's TTF provider reads the font into a direct native buffer and hands the buffer to the font engine. In 1.20.5 and later, that path ends at:

```text
org.lwjgl.util.freetype.FreeType.FT_New_Memory_Face
```

Before 1.20.5, the client uses STB TrueType instead of FreeType. That is why the FreeType-specific `minecraft2shell` chain does not apply to those older versions, even though the same server-controlled font path exists.

## TrueType hinting is a virtual machine

TrueType fonts contain outlines plus optional hinting programs. Hinting is a stack-based bytecode language used to align outlines to a pixel grid. FreeType implements the interpreter in `src/truetype/ttinterp.c`.

There are three places where font-supplied bytecode can execute:

- `fpgm`: the font program, run when a font size is created. It can define functions and instruction definitions (`IDEF`).
- `prep`: the control-value program, also run during size setup.
- The instruction stream attached to each simple or composite glyph, run when that glyph is loaded.

The interpreter operates on zones of points. A glyph normally has a point buffer, contour endpoints, and a small number of "phantom" points used for metrics. Instructions such as `SHP`, `SHPIX`, `SHC`, and `SHZ` move points in those zones.

`IDEF` is especially important: it lets a font replace the implementation of an opcode. The exploit uses that to replace `SHZ` (opcode `0x36`) with a font-defined function.

## The bug: an `SHZ` sweep past the point buffer

FreeType 2.14.3's `Ins_SHZ` computes the number of points to move from the target zone. For the glyph zone it uses the final contour endpoint:

```c
limit = exc->zp2.contours[exc->zp2.n_contours - 1] + 1;
```

It then calls `Move_Zp2_Point` for each index. In the vulnerable build, that path does not validate the index against the physical allocation for the zone.

The public exploit grooms the heap with a composite glyph:

- A 100-point glyph causes a 104-`FT_Vector` allocation, 832 bytes or `0x340`.
- A nested component creates a zone whose `cur` pointer begins at `P + 0x208`.
- That zone advertises 66 apparent points.
- The crafted `SHZ` displacement is `+0x80` on the x axis.

The write therefore sweeps vector x lanes through `P + 0x410`, past the end of the allocation at `P + 0x340`, into the live `TT_ExecContextRec` that starts around `P + 0x350`. That is not a random heap corruption. The exploit has arranged for the interpreter context to sit immediately after the point buffer, and it knows which fields the sweep will hit.

Two fields matter:

- `error` at context offset `+0x20`: adding `0x80` turns it into FreeType's `Invalid_Opcode` error value (`0x80`).
- `zp2.cur` at context offset `+0xC0`: adding `0x80` makes later point operations read and write context memory instead of the point array.

That gives the font a same-glyph transition. It does not need a second pack, a second glyph, or an address leak. The font supplies the recovery code through the `IDEF` it installed for opcode `0x36`.

## From corruption to control flow

When FreeType's error path sees `Invalid_Opcode`, it checks whether the font defined an `IDEF` for the current opcode. It does. Execution enters the font's recovery program instead of aborting.

The recovery program:

1. Uses `GC` to read the swept interpreter callback and the glyph-instruction pointer as low and high 32-bit values. These are live, relocated addresses, so the chain does not need to know where ASLR placed FreeType.
2. Uses `SHPIX` to repair the heap header and the context fields needed by the interpreter and allocator.
3. Checks that the context still has the expected callback layout and that the instruction pointer is the one currently advancing. These checks reduce false positives on other heap layouts.
4. Uses `WS` to allocate persistent glyph storage and writes the staged ROP data there. The font's `F` glyph triggers that allocation; the `A` glyph's `IDEF` program fills it.
5. Uses the corrupted `zp2.cur` as a constrained read/write primitive to assemble pointers and 64-bit values, handling carries and borrows.

The font declares `maxp.maxStackElements = 8192`. In this FreeType build the VM stack becomes `8192 + max(8192 / 2, 128) = 12,288` `FT_Long` cells, or 49,152 bytes. The bytecode:

- preserves `exec->stack`,
- copies a small bootstrap to the base,
- pushes 7,164 filler cells,
- pushes 94 ROP cells,
- leaves the final VM top at 7,262 cells, safely inside the 12,288-cell allocation.

The ROP chain sits at `exec->stack + 0x7000`, 16-byte aligned, with roughly 28 KiB below the native `RSP` for Windows API frames.

At the end of the `IDEF` body, `SCFS` changes two things:

- the interpreter callback to a FreeType RVA,
- the context slot normally holding the VM stack size to the address of a `pop rsp; ret` gadget.

The callback transfers native execution through the gadget to the bootstrap at the unchanged VM stack base. The bootstrap pivots to the chain at `stack + 0x7000`.

The chain contains no injected shellcode and no executable heap. It uses code already present in the module and imports already present in the IAT:

- `GetModuleHandleExW` through FreeType's IAT to obtain `kernel32.dll`,
- `GetProcAddress` to resolve `WinExec`,
- `WinExec` with a command string,
- `ExitProcess`.

The public proof uses `calc.exe` so the result is visible and harmless. The same primitive can run any command with the user's privileges.

## Delivery and triggering

The delivered artifact is one ZIP:

- `pack.mcmeta`,
- 144 JSON font definitions,
- 144 same-length TTF variants.

Each JSON has a `space` provider before the `ttf` provider. The space provider gives `A`, `F`, and `G` an advance of `64.0` and leaves `@` unmasked. The unmasked `@` keeps and grooms the TTF during font reload. The large advance keeps the action-bar glyph selection away from the space provider, so later `F` and `A` text reaches the TTF provider and triggers native glyph loads.

The server advertises the ZIP through ordinary `server.properties` keys:

```properties
resource-pack=http://server/resource-pack.zip
resource-pack-sha1=...
resource-pack-id=...
require-resource-pack=true
```

The fixed sender uses RCON only to issue ordinary action-bar commands: first `F` to each of the 144 font faces, then `A` to each face. No address input, memory scan, or adaptive face selection is involved. The sender does not learn which face won.

A client that has already trusted the server stores `acceptTextures=true` in `servers.dat` and loads the pack without a prompt. The public PoC configures its own localhost server that way. The same pack can be delivered by an untrusted server, but the player must accept the normal resource-pack prompt first. The prompt does not inspect the font, so accepting it is enough.

## Why 144 fonts

The `SHZ` sweep needs a specific allocator adjacency: the point buffer must be immediately followed by the interpreter context. That placement is not guaranteed by the font format alone. The pack includes 144 fonts with identical sizes and structures and exercises them in a fixed order. Each font is a separate face with its own allocation, increasing the chance that at least one lands in the required layout.

The variants are not address guesses. They are heap-layout attempts. Once a matching face is loaded, the chain is self-contained and ASLR-aware.

## Impact and scope

The public PoC is build-specific:

- Minecraft 26.3 Windows x64,
- bundled FreeType 2.14.3 x64,
- specific context offsets and gadget RVAs.

The exact offsets do not transfer to other builds. The vulnerability class does: any build where a font's hinting bytecode can corrupt memory before validation is exposed. Minecraft versions from 1.20.5 onward use FreeType; 1.20.5 through 1.21.10 shipped FreeType 2.13.2, and 26.x shipped 2.14.x before republished natives appeared. 1.20.4 and earlier use STB TrueType, which is a different interpreter and not the same bug.

The impact is native code execution in the client process:

- the JVM sandbox does not apply to FreeType,
- the code runs as the player,
- the client can read files, start processes, and make network connections with the player's permissions.

A failed exploit attempt can also crash the client. A successful one can do anything the user can do.

## Why the obvious shortcuts are not enough

**"Reject untrusted resource packs."** This works, but it removes a normal feature and depends on the player making a security decision the pack UI does not explain.

**"Use `FT_LOAD_NO_HINTING`."** This skips glyph-level hinting, but FreeType still runs `fpgm` and `prep` when the size is created. Removing only glyph instructions leaves those programs alive. It also does not fix a variant that triggers a bug from `fpgm` or `prep`.

**"Detect the exploit font."** A blocklist or signature only covers known samples. The primitive is the hinting interpreter, not a particular byte sequence.

**"Wait for a FreeType update."** That is the correct vendor fix, and 26.x has received republished 2.15.0 natives in some channels. It does not help clients pinned to older natives or versions that have not been updated.

## How nom2s removes the primitive

`nom2s` is a client-side Fabric mod that sanitizes every font before a native engine sees it. It hooks the stable LWJGL entry points:

- `FT_New_Memory_Face` for FreeType,
- both `stbtt_InitFont` overloads for STB.

For a single TrueType font it:

1. Parses the sfnt table directory with strict bounds checks.
2. Rejects duplicate, overlapping, out-of-range, TTC, WOFF, and malformed data.
3. Zeroes `fpgm` and `prep` and sets their table lengths to zero, so neither program executes.
4. For each simple glyph, moves the flags and coordinate tail left over the instruction bytes, sets `instructionLength` to zero, and zeroes the freed tail. The glyph record length and `loca` offsets do not change.
5. For each composite glyph, clears `WE_HAVE_INSTRUCTIONS` and zeroes the instruction bytes.

Outlines, metrics, cmap, colour tables, and variable-font data remain. The font still loads and still renders; it simply has no hinting VM program left to run. If the font cannot be parsed safely, it is invalidated so the native engine refuses the face. The mod does not rely on the exploit's font names, hashes, or heap layout.

The cost is paid once per font at load. There is no per-frame or per-glyph hook. Custom fonts lose hinting, which can make very small text slightly softer; vanilla Minecraft's bitmap fonts are unaffected.

## References

- Public proof of concept and writeup: `https://github.com/bikini/minecraft2shell`
- FreeType 2.14.3 `Ins_SHZ`: `https://github.com/freetype/freetype/blob/VER-2-14-3/src/truetype/ttinterp.c`
- FreeType size/program setup: `https://github.com/freetype/freetype/blob/VER-2-14-3/src/truetype/ttobjs.c`
- Minecraft 26.3 TTF provider: `https://mc-packet-reference.netlify.app/26.x/source/net/minecraft/client/gui/font/providers/truetypeglyphproviderdefinition`
- Related mitigation and version analysis: `https://github.com/sunrisemc-tw/minecraft2shell-fix`
