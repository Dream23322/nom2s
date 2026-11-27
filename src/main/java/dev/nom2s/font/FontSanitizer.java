package dev.nom2s.font;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Set;

/**
 * Removes executable TrueType hinting bytecode from an in-memory font without
 * changing glyph outlines, metrics, or table offsets.
 */
public final class FontSanitizer {

    public static final int REJECTED = -1;

    private static final int MAX_FONT_BYTES = 32 * 1024 * 1024;
    private static final int MAX_TABLES = 4096;

    private static final int SFNT_TRUETYPE = 0x00010000;
    private static final int SFNT_TRUE = 0x74727565;
    private static final int SFNT_OTTO = 0x4F54544F;
    private static final int SFNT_TYPE1 = 0x74797031;
    private static final int SFNT_TTCF = 0x74746366;
    private static final int SFNT_WOFF = 0x774F4646;
    private static final int SFNT_WOFF2 = 0x774F4632;

    private static final int TAG_FPGM = 0x6670676D;
    private static final int TAG_PREP = 0x70726570;
    private static final int TAG_GLYF = 0x676C7966;
    private static final int TAG_LOCA = 0x6C6F6361;
    private static final int TAG_HEAD = 0x68656164;
    private static final int TAG_MAXP = 0x6D617870;

    private static final int ARG_1_AND_2_ARE_WORDS = 0x0001;
    private static final int WE_HAVE_A_SCALE = 0x0008;
    private static final int MORE_COMPONENTS = 0x0020;
    private static final int WE_HAVE_AN_X_AND_Y_SCALE = 0x0040;
    private static final int WE_HAVE_A_TWO_BY_TWO = 0x0080;
    private static final int WE_HAVE_INSTRUCTIONS = 0x0100;

    private FontSanitizer() {
    }

    /**
     * Removes the bytecode programs from {@code source}.
     *
     * @return number of bytes neutralized, {@code 0} when nothing needed
     *         changing, or {@link #REJECTED} when the data is unsupported or
     *         malformed
     */
    public static int sanitize(ByteBuffer source) {
        if (source == null) {
            return REJECTED;
        }

        int size = source.remaining();
        if (size < 12 || size > MAX_FONT_BYTES) {
            return REJECTED;
        }

        ByteBuffer data = source.duplicate().order(ByteOrder.BIG_ENDIAN).slice();
        int version = data.getInt(0);

        if (version == SFNT_OTTO || version == SFNT_TYPE1) {
            return 0;
        }
        if (version == SFNT_TTCF || version == SFNT_WOFF || version == SFNT_WOFF2) {
            return REJECTED;
        }
        if (version != SFNT_TRUETYPE && version != SFNT_TRUE) {
            return REJECTED;
        }

        return sanitizeTrueType(data);
    }

    /**
     * Invalidates font data so a native font engine refuses it.
     *
     * @return {@code true} when the buffer was writable and is now invalid
     */
    public static boolean invalidate(ByteBuffer source) {
        if (source == null) {
            return true;
        }
        if (source.isReadOnly()) {
            return false;
        }

        try {
            ByteBuffer data = source.duplicate().order(ByteOrder.BIG_ENDIAN);
            int base = data.position();
            int remaining = data.remaining();
            int count = Math.min(12, remaining);
            for (int index = 0; index < count; index++) {
                data.put(base + index, (byte) 0);
            }
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static int sanitizeTrueType(ByteBuffer data) {
        int size = data.limit();
        int numTables = u16(data, 4);
        if (numTables == 0 || numTables > MAX_TABLES) {
            return REJECTED;
        }

        int directoryEnd = 12 + numTables * 16;
        if (directoryEnd > size) {
            return REJECTED;
        }

        Set<Integer> seenTags = new HashSet<Integer>(numTables);
        int[] tableStarts = new int[numTables];
        int[] tableEnds = new int[numTables];

        int fpgmRecord = -1;
        int fpgmOffset = 0;
        int fpgmLength = 0;
        int prepRecord = -1;
        int prepOffset = 0;
        int prepLength = 0;

        int headOffset = -1;
        int headLength = 0;
        int maxpOffset = -1;
        int maxpLength = 0;
        int locaOffset = -1;
        int locaLength = 0;
        int glyfOffset = -1;
        int glyfLength = 0;

        for (int index = 0; index < numTables; index++) {
            int record = 12 + index * 16;
            int tag = data.getInt(record);
            if (!seenTags.add(tag)) {
                return REJECTED;
            }

            long offsetValue = u32(data, record + 8);
            long lengthValue = u32(data, record + 12);
            if (offsetValue > size || lengthValue > size || offsetValue + lengthValue > size) {
                return REJECTED;
            }

            int offset = (int) offsetValue;
            int length = (int) lengthValue;
            if (length > 0 && offset < directoryEnd) {
                return REJECTED;
            }

            int end = offset + length;
            for (int previous = 0; previous < index; previous++) {
                if (length > 0 && tableEnds[previous] > tableStarts[previous]
                        && offset < tableEnds[previous] && end > tableStarts[previous]) {
                    return REJECTED;
                }
            }
            tableStarts[index] = offset;
            tableEnds[index] = end;

            if (tag == TAG_FPGM) {
                fpgmRecord = record;
                fpgmOffset = offset;
                fpgmLength = length;
            } else if (tag == TAG_PREP) {
                prepRecord = record;
                prepOffset = offset;
                prepLength = length;
            } else if (tag == TAG_HEAD) {
                headOffset = offset;
                headLength = length;
            } else if (tag == TAG_MAXP) {
                maxpOffset = offset;
                maxpLength = length;
            } else if (tag == TAG_LOCA) {
                locaOffset = offset;
                locaLength = length;
            } else if (tag == TAG_GLYF) {
                glyfOffset = offset;
                glyfLength = length;
            }
        }

        int stripped = 0;

        if (fpgmRecord >= 0 && fpgmLength > 0) {
            zero(data, fpgmOffset, fpgmLength);
            data.putInt(fpgmRecord + 12, 0);
            stripped += fpgmLength;
        }
        if (prepRecord >= 0 && prepLength > 0) {
            zero(data, prepOffset, prepLength);
            data.putInt(prepRecord + 12, 0);
            stripped += prepLength;
        }

        boolean hasGlyf = glyfLength > 0;
        boolean hasLoca = locaLength > 0;
        if (hasGlyf != hasLoca) {
            return REJECTED;
        }
        if (!hasGlyf) {
            return stripped;
        }

        if (headLength < 54 || maxpLength < 6) {
            return REJECTED;
        }

        int locaFormat = s16(data, headOffset + 50);
        if (locaFormat != 0 && locaFormat != 1) {
            return REJECTED;
        }

        int numGlyphs = u16(data, maxpOffset + 4);
        int entrySize = locaFormat == 1 ? 4 : 2;
        long requiredLoca = (long) (numGlyphs + 1) * entrySize;
        if (locaLength < requiredLoca) {
            return REJECTED;
        }

        long previousOffset = 0;
        for (int glyph = 0; glyph <= numGlyphs; glyph++) {
            long offset = readLoca(data, locaOffset, glyph, entrySize);
            if (offset < previousOffset || offset > glyfLength) {
                return REJECTED;
            }
            if (glyph > 0 && offset > previousOffset) {
                int result = sanitizeGlyph(data, glyfOffset, glyfLength,
                        (int) previousOffset, (int) offset);
                if (result < 0) {
                    return REJECTED;
                }
                stripped += result;
            }
            previousOffset = offset;
        }

        return stripped;
    }

    private static int sanitizeGlyph(ByteBuffer data, int glyfOffset, int glyfLength,
                                     int glyphStart, int glyphEnd) {
        if (glyphStart == glyphEnd) {
            return 0;
        }
        if (glyphStart < 0 || glyphEnd < glyphStart || glyphEnd > glyfLength
                || glyphEnd - glyphStart < 10) {
            return REJECTED;
        }

        int glyph = glyfOffset + glyphStart;
        int end = glyfOffset + glyphEnd;
        int contours = s16(data, glyph);

        if (contours >= 0) {
            long instructionLengthOffsetValue = (long) glyph + 10L + 2L * contours;
            if (instructionLengthOffsetValue + 2L > end) {
                return REJECTED;
            }

            int instructionLengthOffset = (int) instructionLengthOffsetValue;
            int instructionLength = u16(data, instructionLengthOffset);
            long instructionStartValue = (long) instructionLengthOffset + 2L;
            if (instructionStartValue + instructionLength > end) {
                return REJECTED;
            }
            if (instructionLength == 0) {
                return 0;
            }

            int instructionStart = (int) instructionStartValue;
            int tail = end - (instructionStart + instructionLength);
            for (int index = 0; index < tail; index++) {
                data.put(instructionStart + index, data.get(instructionStart + instructionLength + index));
            }
            for (int index = end - instructionLength; index < end; index++) {
                data.put(index, (byte) 0);
            }
            data.putShort(instructionLengthOffset, (short) 0);
            return instructionLength;
        }

        int position = glyph + 10;
        int lastFlags = 0;
        while (true) {
            if (position + 4 > end) {
                return REJECTED;
            }

            int flagsPosition = position;
            int flags = u16(data, position);
            position += 4;

            position += (flags & ARG_1_AND_2_ARE_WORDS) != 0 ? 4 : 2;
            if ((flags & WE_HAVE_A_SCALE) != 0) {
                position += 2;
            } else if ((flags & WE_HAVE_AN_X_AND_Y_SCALE) != 0) {
                position += 4;
            } else if ((flags & WE_HAVE_A_TWO_BY_TWO) != 0) {
                position += 8;
            }
            if (position > end) {
                return REJECTED;
            }

            if ((flags & WE_HAVE_INSTRUCTIONS) != 0) {
                data.putShort(flagsPosition, (short) (flags & ~WE_HAVE_INSTRUCTIONS));
            }
            lastFlags = flags;

            if ((flags & MORE_COMPONENTS) == 0) {
                break;
            }
        }

        if ((lastFlags & WE_HAVE_INSTRUCTIONS) == 0) {
            return 0;
        }
        if (position + 2 > end) {
            return REJECTED;
        }

        int instructionLength = u16(data, position);
        int instructionStart = position + 2;
        if (instructionStart + instructionLength > end) {
            return REJECTED;
        }

        zero(data, instructionStart, instructionLength);
        return instructionLength;
    }

    private static long readLoca(ByteBuffer data, int locaOffset, int glyph, int entrySize) {
        if (entrySize == 4) {
            return u32(data, locaOffset + glyph * 4);
        }
        return (long) u16(data, locaOffset + glyph * 2) * 2L;
    }

    private static void zero(ByteBuffer data, int offset, int length) {
        for (int index = 0; index < length; index++) {
            data.put(offset + index, (byte) 0);
        }
    }

    private static int u16(ByteBuffer data, int offset) {
        return data.getShort(offset) & 0xFFFF;
    }

    private static int s16(ByteBuffer data, int offset) {
        return data.getShort(offset);
    }

    private static long u32(ByteBuffer data, int offset) {
        return data.getInt(offset) & 0xFFFFFFFFL;
    }
}
