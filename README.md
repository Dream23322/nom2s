# nom2s

A client-side Fabric mod that removes executable TrueType hinting bytecode from resource-pack fonts before Minecraft's native font engine can run it.

`minecraft2shell` is a native code execution chain delivered through a server resource pack. A crafted TrueType font abuses FreeType's hinting interpreter, corrupts the interpreter context through `Ins_SHZ`, and pivots into a ROP chain. The public proof launches Calculator; the primitive can run any command with the player's privileges.

`nom2s` does not try to recognise the exploit font. It removes every hinting program from every font at load time. Without `fpgm`, `prep`, or glyph instructions, there is no hinting VM code left for `Ins_SHZ` or any related variant to execute. Outlines, glyph metrics, cmap data, colour tables, and variable-font data are preserved.

Read the full technical report in [ABOUT.md](ABOUT.md).

## How it works

Minecraft hands resource-pack fonts to LWJGL:

- Minecraft 1.20.5+ uses `FreeType.FT_New_Memory_Face`.
- Minecraft 1.14–1.20.4 uses `STBTruetype.stbtt_InitFont`.

`nom2s` hooks those stable library entry points, not Minecraft classes, so one jar can cover the whole Fabric range without per-version mappings. Each font buffer is sanitized in place:

- `fpgm` and `prep` contents are zeroed and their table lengths are set to zero.
- Simple glyph instructions are removed by shifting the flags/coordinate tail left and setting `instructionLength` to zero. `loca` offsets do not change.
- Composite glyph instruction flags are cleared and the instruction bytes are zeroed.

If a font cannot be parsed safely, it is invalidated so the native engine refuses the face. The mod fails loudly if its hooks cannot be installed; it does not silently run unprotected.

This is stronger than adding `FT_LOAD_NO_HINTING`. FreeType executes `fpgm` and `prep` when a size is created, independently of glyph load flags. Removing the programs themselves is what closes the whole hinting VM path.

The cost is one linear scan per font when it loads. There is no per-frame or per-glyph hook and no steady-state overhead.

## Version support

| Minecraft | Font engine | minecraft2shell | nom2s |
|---|---|---|---|
| 1.14 – 1.20.4 | STB TrueType | Not the FreeType chain | STB hooks installed as defence in depth |
| 1.20.5 – 1.21.10 | FreeType 2.13.2 | Vulnerable | Protected |
| 26.1 – 26.2 | FreeType 2.14.1 | Vulnerable | Protected |
| 26.3+ | FreeType 2.14.3 in the PoC build; some republished 2.15.0 natives | Vulnerable only where older natives are used | Protected either way |

One jar is built with Java 8 bytecode and works with Fabric Loader **0.14.0 or newer**. Loader 0.14 is the first version that applies Mixins to library classes such as LWJGL; older loaders cannot support this hook.

The mod is client-side only. It does not need Fabric API.

## Install

1. Install Fabric Loader 0.14.0 or newer for your Minecraft version.
2. Put `nom2s-1.0.0.jar` in `.minecraft/mods`.
3. Start the client.

No configuration is required. Normal installations log a guard line when a font engine hook is applied. For per-font diagnostics, add `-Dnom2s.verbose=true` to the JVM arguments.

## Build from source

Requirements: JDK 21 or newer to run Gradle and the wrapper. The produced jar targets Java 8.

```bash
./gradlew build
```

On Windows:

```powershell
.\gradlew.bat build
```

The jar is written to `build/libs/nom2s-1.0.0.jar`.

## Verification

Tested on 2026-11-27 on Windows 11 x64 (10.0.26100) with:

- Minecraft 26.3, Mojang-signed client
- Fabric Loader 0.20.1
- Mojang Java 25.0.1
- FreeType deliberately pinned to the vulnerable 2.14.3 Windows x64 DLL from `lwjgl-freetype:3.4.3` (SHA-256 `FCAC5B5A9A8977F1BC76ECCEA5E71F6C57250CA5AB8605F06906377E91B6E6B7`)

The current 26.3 metadata points at republished FreeType 2.15.0 natives. The test pins the 2.14.3 native so the control actually reproduces the vulnerable build; this is a test of the mod, not of Mojang's current launcher metadata.

| Check | Result |
|---|---|
| Control: stock 26.3 client + exploit pack + vulnerable FreeType | Calculator launched |
| Control: Fabric 26.3 without nom2s + same pack | Calculator launched; client exited through the PoC's `ExitProcess` |
| Protected: Fabric 26.3 + nom2s + same pack | No Calculator process; client stayed connected and alive |
| Fonts neutralized in the protected run | 144/144 |
| Java sanitizer over the exploit pack | 144/144 accepted, 1,510,128 bytes of hinting bytecode removed |
| Sanitized exploit fonts loaded in FreeType 2.14.3 | 144/144 faces, 1,296/1,296 glyphs loaded |
| Clean hinted fonts sanitized and loaded | Arial, Segoe UI, ANTQUAB, AniMeMatrix: all groups load; 667,274 bytes removed; 10,828 glyphs loaded |
| Malformed/TTC/WOFF inputs | Rejected without escaping exceptions |

The integration harness, the exploit pack, and the generated fonts are intentionally not published. The full sanitizer work for the exploit pack took 136 ms including file I/O on the test machine.

## Limitations

- The mod removes TrueType hinting bytecode. It does not sandbox FreeType's table parsers, CFF interpreter, image decoders, or unrelated native code.
- Custom fonts render without hinting. Outlines and layout data are unchanged, but very small text can look slightly softer.
- TrueType collections (TTC) and WOFF/WOFF2 are rejected rather than rewritten. CFF/OpenType fonts are left untouched; Minecraft's normal TTF provider rejects non-TrueType formats anyway.
- Fonts loaded by other mods through `FT_New_Face` or other native paths outside these LWJGL entry points are outside the hook's scope.
- If a future LWJGL version changes the hook signatures, the mod fails at startup instead of silently running unprotected.
- The end-to-end exploit control exercised the FreeType path on 26.3. The STB path uses the same sanitizer and the verified 2-argument/3-argument LWJGL signatures, but it was not part of the live exploit run.

## License

Unlicense. See [LICENSE](LICENSE).
