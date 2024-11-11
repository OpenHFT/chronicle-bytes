/*
 * Copyright (c) 2016-2022 chronicle.software
 *
 *     https://chronicle.software
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.openhft.chronicle.bytes;

import net.openhft.chronicle.bytes.internal.NativeBytesStore;
import net.openhft.chronicle.bytes.internal.ReferenceCountedUtil;
import net.openhft.chronicle.bytes.render.DecimalAppender;
import net.openhft.chronicle.bytes.render.Decimaliser;
import net.openhft.chronicle.core.annotation.NonNegative;
import net.openhft.chronicle.core.io.*;
import net.openhft.chronicle.core.util.Histogram;
import net.openhft.chronicle.core.util.ThrowingConsumer;
import net.openhft.chronicle.core.util.ThrowingConsumerNonCapturing;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Scanner;
import java.util.regex.Pattern;

import static net.openhft.chronicle.bytes.internal.ReferenceCountedUtil.throwExceptionIfReleased;
import static net.openhft.chronicle.core.util.Ints.requireNonNegative;
import static net.openhft.chronicle.core.util.Longs.requireNonNegative;
import static net.openhft.chronicle.core.util.ObjectUtils.requireNonNull;

/**
 * A class that implements the {@link Bytes} interface for generating a hex dump of byte data. The hex dump is a
 * human-readable display of data in hexadecimal and ASCII formats. It's commonly used for debugging, forensics,
 * and analyzing low-level data.
 * <p>
 * It supports setting specific number wrap for byte data and custom offset formatting to provide more flexibility
 * and control over the output of the hex dump. The class also enables indentation adjustment, which can be useful
 * for nested data structures or logically grouped data within the byte array.
 */

@SuppressWarnings("rawtypes")
public class HexDumpBytes
        implements Bytes<Void>, DecimalAppender {

    public static final long MASK = 0xFFFFFFFFL;
    private static final char[] HEXADECIMAL = "0123456789abcdef".toCharArray();
    private static final Pattern HEX_PATTERN = Pattern.compile("[0-9a-fA-F]{1,2}");
    private final NativeBytes<Void> base;
    private final Bytes<byte[]> text;
    private final Bytes<byte[]> comment = Bytes.allocateElasticOnHeap(64);
    private OffsetFormat offsetFormat = null;
    private long startOfLine = 0;
    private int indent = 0;
    private int numberWrap = 16;

    /**
     * Constructs a HexDumpBytes instance with default settings.
     */
    public HexDumpBytes() {
        base = Bytes.allocateElasticDirect(256);
        // as it's use for diagnostics and tests rather than production.
        IOTools.unmonitor(base);
        text = Bytes.allocateElasticOnHeap(1024);
    }

    /**
     * Constructs a HexDumpBytes instance with provided base and text bytes.
     *
     * @param base NativeBytes instance representing base data.
     * @param text BytesStore instance representing text data.
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way.
     */
    HexDumpBytes(@NotNull Bytes<?> base, @NotNull BytesStore<?, ?> text) {
        final long size = base.readRemaining();
        this.base = NativeBytes.wrapWithNativeBytes(NativeBytesStore.nativeStore(size), size);
        this.base.write(base);
        this.text = Bytes.allocateElasticOnHeap((int) text.readRemaining());
        this.text.write(text);
    }

    /**
     * Creates a HexDumpBytes instance from provided text reader.
     *
     * @param reader Reader instance to read the text data.
     * @return HexDumpBytes instance initialized with the read text data.
     * @throws NumberFormatException if parsing a number fails.
     */
    public static HexDumpBytes fromText(@NotNull Reader reader) throws NumberFormatException {
        HexDumpBytes tb = new HexDumpBytes();
        Reader reader2 = new TextBytesReader(reader, tb.text);
        try (Scanner sc = new Scanner(reader2)) {
            while (sc.hasNext()) {
                if (sc.hasNext(HEX_PATTERN)) tb.base.rawWriteByte((byte) Integer.parseInt(sc.next(), 16));
                else sc.nextLine(); // assume it's a comment
            }
        }
        return tb;
    }

    /**
     * Creates a HexDumpBytes instance from provided char sequence.
     *
     * @param text CharSequence to read the text data from.
     * @return HexDumpBytes instance initialized with the read text data.
     * @throws NumberFormatException if parsing a number fails.
     */
    public static HexDumpBytes fromText(@NotNull CharSequence text) throws NumberFormatException {
        return fromText(new StringReader(text.toString()));
    }

    private static boolean startsWith(@NotNull CharSequence comment, final char first) {
        return comment.length() > 0 && comment.charAt(0) == first;
    }

    /**
     * Sets the offset format for the hex dump.
     *
     * @param offsetFormat The offset format to use.
     * @return The HexDumpBytes instance, for chaining.
     */
    public HexDumpBytes offsetFormat(OffsetFormat offsetFormat) {
        this.offsetFormat = offsetFormat;
        return this;
    }

    /**
     * Retrieves the current number wrap setting.
     *
     * @return The current number wrap.
     */
    public int numberWrap() {
        return numberWrap;
    }

    /**
     * Sets the number wrap for the hex dump.
     *
     * @param numberWrap The number wrap to use.
     * @return The HexDumpBytes instance, for chaining.
     */
    public HexDumpBytes numberWrap(int numberWrap) {
        this.numberWrap = numberWrap;
        return this;
    }

    @Override
    public long readRemaining() {
        return base.readRemaining();
    }

    @Override
    public long writeRemaining() {
        return base.writeRemaining();
    }

    @Override
    public long readLimit() {
        return base.readLimit();
    }

    @Override
    public long writeLimit() {
        return base.writeLimit();
    }

    @NotNull
    @Override
    public String toHexString() {
        try {
            throwExceptionIfReleased(this);
            if (lineLength() > 0) newLine();
            return text.toString();
        } catch (Throwable e) {
            return e.toString();
        }
    }

    @Override
    public int hashCode() {
        return base.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return base.equals(obj);
    }

    @Override
    @NotNull
    public String toString() {
        return base.toString();
    }

    @Override
    public boolean retainedHexDumpDescription() {
        return true;
    }

    @Override
    public Bytes<Void> writeHexDumpDescription(@NotNull CharSequence comment) throws IllegalStateException {
        if (this.comment.readRemaining() > 0) newLine();
        if (startsWith(comment, '#')) {
            indent = 0;
            this.text.append('#').append(comment).append('\n');
            startOfLine = this.text.writePosition();
        } else {
            this.comment.clear().append(comment);
        }
        return this;
    }

    @Override
    public BytesOut<Void> adjustHexDumpIndentation(final int n) throws IllegalStateException {
        indent += n;
        if (lineLength() > 0) {
            newLine();
        }
        return this;
    }

    private long lineLength() {
        return this.text.writePosition() - startOfLine;
    }

    private void newLine() throws IllegalStateException {
        if (this.comment.readRemaining() > 0) {
            while (lineLength() < numberWrap * 3L - 3) this.text.append("   ");
            while (lineLength() < numberWrap * 3L) this.text.append(' ');
            this.text.append("# ");
            this.text.append(comment);
            comment.clear();
        }
        this.text.append('\n');
        startOfLine = this.text.writePosition();
    }

    private void appendOffset(@NonNegative long offset) throws IllegalStateException, BufferUnderflowException {
        if (offsetFormat == null) return;
        offsetFormat.append(offset, this.text);
        long wp = text.writePosition();
        if (text.peekUnsignedByte(wp - 1) > ' ') text.append(' ');
        startOfLine = text.writePosition();
    }

    @Override
    public BytesStore<Bytes<Void>, Void> copy() throws IllegalStateException {
        throwExceptionIfReleased(this);
        return new HexDumpBytes(base, text);
    }

    @Override
    public boolean isElastic() {
        return base.isElastic();
    }

    @Override
    public void ensureCapacity(@NonNegative long desiredCapacity) throws IllegalArgumentException, IllegalStateException {
        base.ensureCapacity(desiredCapacity);
    }

    @Override
    @NotNull
    public BytesStore<?, Void> bytesStore() {
        return base.bytesStore();
    }

    @Override
    @NotNull
    public Bytes<Void> compact() {
        throw new UnsupportedOperationException();
    }

    @Override
    @NotNull
    public Bytes<Void> clear() throws IllegalStateException {
        base.clear();
        text.clear();
        comment.clear();
        startOfLine = 0;
        return this;
    }

    @Override
    public boolean isDirectMemory() {
        return false;
    }

    @Override
    public @NonNegative long capacity() {
        return base.capacity();
    }

    @Override
    public long addressForRead(@NonNegative long offset) throws UnsupportedOperationException, IllegalStateException, BufferUnderflowException {
        requireNonNegative(offset);
        return base.addressForRead(offset);
    }

    @Override
    public long addressForWrite(@NonNegative long offset) throws UnsupportedOperationException {
        requireNonNegative(offset);
        throw new UnsupportedOperationException();
    }

    @Override
    public long addressForWritePosition() throws UnsupportedOperationException, BufferOverflowException {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean compareAndSwapInt(@NonNegative long offset, int expected, int value) throws BufferOverflowException, IllegalStateException {
        if (base.compareAndSwapInt(offset & MASK, expected, value)) {
            copyToText(offset & MASK, offset >>> 32, 4);
            return true;
        }
        return false;
    }

    @Override
    public void testAndSetInt(@NonNegative long offset, int expected, int value) throws IllegalStateException, BufferOverflowException {
        long off = offset & MASK;
        base.testAndSetInt(off, expected, value);
        copyToText(off, offset >>> 32, 4);
    }

    @Override
    public boolean compareAndSwapLong(@NonNegative long offset, long expected, long value) throws BufferOverflowException, IllegalStateException {
        if (base.compareAndSwapLong(offset & MASK, expected, value)) {
            copyToText(offset & MASK, offset >>> 32, 8);
            return true;
        }
        return false;
    }

    @Override
    @Nullable
    public Void underlyingObject() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void move(@NonNegative long from, @NonNegative long to, @NonNegative long length) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void reserve(ReferenceOwner owner) throws IllegalStateException {
        base.reserve(owner);
    }

    @Override
    public void release(ReferenceOwner owner) throws IllegalStateException {
        base.release(owner);
        if (base.refCount() == 0) {
            text.releaseLast();
            comment.releaseLast();
        }
    }

    @Override
    public void releaseLast(ReferenceOwner owner) throws IllegalStateException {
        base.releaseLast(owner);
        if (base.refCount() == 0) {
            text.releaseLast();
            comment.releaseLast();
        }
    }

    @Override
    public int refCount() {
        return base.refCount();
    }

    @Override
    public void addReferenceChangeListener(ReferenceChangeListener referenceChangeListener) {
        base.addReferenceChangeListener(referenceChangeListener);
    }

    @Override
    public void removeReferenceChangeListener(ReferenceChangeListener referenceChangeListener) {
        base.removeReferenceChangeListener(referenceChangeListener);
    }

    @Override
    public boolean tryReserve(ReferenceOwner owner) throws IllegalStateException, IllegalArgumentException {
        return base.tryReserve(owner);
    }

    @Override
    @NotNull
    public Bytes<Void> writeByte(@NonNegative long offset, byte i8) throws BufferOverflowException, IllegalStateException {
        if (offset == base.writePosition())
            return writeByte(i8);
        base.writeByte(offset & MASK, i8);
        copyToText(offset & MASK, offset >>> 32, 1);
        return this;
    }

    @Override
    @NotNull
    public Bytes<Void> writeShort(@NonNegative long offset, short i) throws BufferOverflowException, IllegalStateException {
        if (offset == base.writePosition())
            return writeShort(i);
        base.writeShort(offset & MASK, i);
        copyToText(offset & MASK, offset >>> 32, 2);
        return this;
    }

    @Override
    @NotNull
    public Bytes<Void> writeInt24(@NonNegative long offset, int i) throws BufferOverflowException, IllegalStateException {
        if (offset == base.writePosition())
            return writeInt24(i);
        base.writeInt24(offset & MASK, i);
        copyToText(offset & MASK, offset >>> 32, 3);
        return this;
    }

    @Override
    @NotNull
    public Bytes<Void> writeInt(@NonNegative long offset, int i) throws BufferOverflowException, IllegalStateException {
        return writeOrderedInt(offset, i);
    }

    @Override
    @NotNull
    public Bytes<Void> writeOrderedInt(@NonNegative long offset, int i) throws BufferOverflowException, IllegalStateException {
        if (offset == base.writePosition())
            return writeOrderedInt(i);
        base.writeOrderedInt(offset & MASK, i);
        copyToText(offset & MASK, offset >>> 32, 4);
        return this;
    }

    @Override
    @NotNull
    public Bytes<Void> writeLong(@NonNegative long offset, long i) throws BufferOverflowException, IllegalStateException {
        return writeOrderedLong(offset, i);
    }

    @Override
    @NotNull
    public Bytes<Void> writeOrderedLong(@NonNegative long offset, long i) throws BufferOverflowException, IllegalStateException {
        if (offset == base.writePosition())
            return writeOrderedLong(i);
        base.writeOrderedLong(offset & MASK, i);
        copyToText(offset & MASK, offset >>> 32, 8);
        return this;
    }

    @Override
    @NotNull
    public Bytes<Void> writeFloat(@NonNegative long offset, float d) throws BufferOverflowException, IllegalStateException {
        if (offset == base.writePosition())
            return writeFloat(d);
        base.writeFloat(offset & MASK, d);
        copyToText(offset & MASK, offset >>> 32, 4);
        return this;
    }

    @Override
    @NotNull
    public Bytes<Void> writeDouble(@NonNegative long offset, double d) throws BufferOverflowException, IllegalStateException {
        if (offset == base.writePosition())
            return writeDouble(d);
        base.writeDouble(offset & MASK, d);
        copyToText(offset & MASK, offset >>> 32, 8);
        return this;
    }

    @Override
    @NotNull
    public Bytes<Void> writeVolatileByte(@NonNegative long offset, byte i8) throws BufferOverflowException {
        if (offset == base.writePosition())
            return writeByte(i8);
        throw new UnsupportedOperationException();
    }

    @Override
    @NotNull
    public Bytes<Void> writeVolatileShort(@NonNegative long offset, short i16) throws BufferOverflowException {
        if (offset == base.writePosition())
            return writeShort(i16);
        throw new UnsupportedOperationException();
    }

    @Override
    @NotNull
    public Bytes<Void> writeVolatileInt(@NonNegative long offset, int i32) throws BufferOverflowException {
        if (offset == base.writePosition())
            return writeInt(i32);
        throw new UnsupportedOperationException();
    }

    @Override
    @NotNull
    public Bytes<Void> writeVolatileLong(@NonNegative long offset, long i64) throws BufferOverflowException {
        if (offset == base.writePosition())
            return writeLong(i64);
        throw new UnsupportedOperationException();
    }

    @Override
    @NotNull
    public Bytes<Void> write(@NonNegative final long offsetInRDO, final byte[] byteArray, @NonNegative final int offset, @NonNegative final int length) {
        requireNonNegative(offsetInRDO);
        requireNonNull(byteArray);
        requireNonNegative(offset);
        requireNonNegative(length);
        throw new UnsupportedOperationException();
    }

    @Override
    public void write(@NonNegative long offsetInRDO, @NotNull ByteBuffer bytes, @NonNegative int offset, @NonNegative int length) {
        requireNonNull(bytes);
        throw new UnsupportedOperationException();
    }

    @Override
    @NotNull
    public Bytes<Void> write(@NonNegative long writeOffset, @NotNull RandomDataInput bytes, @NonNegative long readOffset, @NonNegative long length) {
        requireNonNegative(writeOffset);
        ReferenceCountedUtil.throwExceptionIfReleased(bytes);
        requireNonNegative(readOffset);
        requireNonNegative(length);
        throw new UnsupportedOperationException();
    }

    @Override
    public long write8bit(@NonNegative long position, @NotNull BytesStore<?, ?> bs) {
        requireNonNull(bs);
        throw new UnsupportedOperationException();
    }

    @Override
    public long write8bit(@NonNegative long position, @NotNull String s, @NonNegative int start, @NonNegative int length) {
        requireNonNull(s);
        throw new UnsupportedOperationException();
    }

    @Override
    public void nativeWrite(long address, @NonNegative long position, @NonNegative long size) {
        throw new UnsupportedOperationException();
    }

    @Override
    public @NotNull Bytes<Void> zeroOut(@NonNegative long start, @NonNegative long end) throws IllegalStateException {
        return base.zeroOut(start & MASK, end & MASK);
    }

    @Override
    @NotNull
    public Bytes<Void> readPosition(@NonNegative long position) throws BufferUnderflowException, IllegalStateException {
        base.readPosition(position & MASK);
        text.readPosition(position >>> 32);
        return this;
    }

    @Override
    @NotNull
    public Bytes<Void> readLimit(@NonNegative long limit) throws BufferUnderflowException {
        base.readLimit(limit & MASK);
        text.readPosition(limit >>> 32);
        return this;
    }

    @Override
    @NotNull
    public Bytes<Void> readSkip(long bytesToSkip) throws BufferUnderflowException, IllegalStateException {
        base.readSkip(bytesToSkip);
        return this;
    }

    @Override
    public void uncheckedReadSkipOne() {
        base.uncheckedReadSkipOne();
    }

    @Override
    public void uncheckedReadSkipBackOne() {
        base.uncheckedReadSkipBackOne();
    }

    @Override
    public long readStopBit() throws IORuntimeException, IllegalStateException, BufferUnderflowException {
        return base.readStopBit();
    }

    @Override
    public char readStopBitChar() throws IORuntimeException, IllegalStateException, BufferUnderflowException {
        return base.readStopBitChar();
    }

    @Override
    public double readStopBitDouble() throws IllegalStateException {
        return base.readStopBitDouble();
    }

    @Override
    public double readStopBitDecimal() throws IllegalStateException, BufferUnderflowException {
        return base.readStopBitDecimal();
    }

    @Override
    public byte readByte() throws IllegalStateException {
        return base.readByte();
    }

    @Override
    public int readUnsignedByte() throws IllegalStateException {
        return base.readUnsignedByte();
    }

    @Override
    public int uncheckedReadUnsignedByte() {
        return base.uncheckedReadUnsignedByte();
    }

    @Override
    public short readShort() throws BufferUnderflowException, IllegalStateException {
        return base.readShort();
    }

    @Override
    public int readInt() throws BufferUnderflowException, IllegalStateException {
        return base.readInt();
    }

    @Override
    public long readLong() throws BufferUnderflowException, IllegalStateException {
        return base.readLong();
    }

    @Override
    public float readFloat() throws BufferUnderflowException, IllegalStateException {
        return base.readFloat();
    }

    @Override
    public double readDouble() throws BufferUnderflowException, IllegalStateException {
        return base.readDouble();
    }

    @Override
    public int readVolatileInt() throws BufferUnderflowException, IllegalStateException {
        return base.readVolatileInt();
    }

    @Override
    public long readVolatileLong() throws BufferUnderflowException, IllegalStateException {
        return base.readVolatileLong();
    }

    @Override
    public int peekUnsignedByte() throws IllegalStateException {
        return base.peekUnsignedByte();
    }

    @Override
    public int lastDecimalPlaces() {
        return base.lastDecimalPlaces();
    }

    @Override
    public void lastDecimalPlaces(int lastDecimalPlaces) {
        base.lastDecimalPlaces(lastDecimalPlaces);
    }

    @Override
    public boolean lastNumberHadDigits() {
        return base.lastNumberHadDigits();
    }

    @Override
    public void lastNumberHadDigits(boolean lastNumberHadDigits) {
        base.lastNumberHadDigits(lastNumberHadDigits);
    }

    @NotNull
    @Override
    public BigDecimal readBigDecimal() throws BufferUnderflowException, ArithmeticException, IllegalStateException {
        return base.readBigDecimal();
    }

    @NotNull
    @Override
    public BigInteger readBigInteger() throws BufferUnderflowException, ArithmeticException, IllegalStateException {
        return base.readBigInteger();
    }

    @Override
    public void readWithLength(@NonNegative long length, @NotNull BytesOut<?> bytesOut) throws BufferUnderflowException, IORuntimeException, IllegalStateException, BufferOverflowException {
        base.readWithLength(length, bytesOut);
    }

    @Override
    public <T extends ReadBytesMarshallable> T readMarshallableLength16(@NotNull Class<T> clazz, @Nullable T using) throws BufferUnderflowException, IllegalStateException, InvalidMarshallableException {
        return base.readMarshallableLength16(clazz, using);
    }

    @NotNull
    @Override
    public Bytes<Void> readPositionUnlimited(@NonNegative long position) throws BufferUnderflowException, IllegalStateException {
        return base.readPositionUnlimited(position);
    }

    @NotNull
    @Override
    public Bytes<Void> readPositionRemaining(@NonNegative long position, @NonNegative long remaining) throws BufferUnderflowException, IllegalStateException {
        return base.readPositionRemaining(position, remaining);
    }

    @Override
    public void readWithLength0(@NonNegative long length, @NotNull ThrowingConsumerNonCapturing<Bytes<Void>, IORuntimeException, BytesOut> bytesConsumer, StringBuilder sb, BytesOut<?> toBytes) throws BufferUnderflowException, IORuntimeException, IllegalStateException {
        base.readWithLength0(length, bytesConsumer, sb, toBytes);
    }

    @Override
    public void readWithLength(@NonNegative long length, @NotNull ThrowingConsumer<Bytes<Void>, IORuntimeException> bytesConsumer) throws BufferUnderflowException, IORuntimeException, IllegalStateException {
        base.readWithLength(length, bytesConsumer);
    }

    @Override
    public boolean readBoolean() throws IllegalStateException {
        return base.readBoolean();

    }

    @Override
    public int readUnsignedShort() throws BufferUnderflowException, IllegalStateException {
        return base.readUnsignedShort();

    }

    @Override
    public int readInt24() throws BufferUnderflowException, IllegalStateException {
        return base.readInt24();

    }

    @Override
    public int readUnsignedInt24() throws BufferUnderflowException, IllegalStateException {
        return base.readUnsignedInt24();

    }

    @Override
    public long readUnsignedInt() throws BufferUnderflowException, IllegalStateException {
        return base.readUnsignedInt();

    }

    @Nullable
    @Override
    public String readUtf8() throws BufferUnderflowException, IORuntimeException, IllegalStateException, ArithmeticException {
        return base.readUtf8();

    }

    @Nullable
    @Override
    public String read8bit() throws IORuntimeException, BufferUnderflowException, IllegalStateException, ArithmeticException {
        return base.read8bit();

    }

    @Override
    public <C extends Appendable & CharSequence> boolean readUtf8(@NotNull final C sb) throws IORuntimeException, IllegalArgumentException, BufferUnderflowException, IllegalStateException, ArithmeticException {
        return base.readUtf8(sb);
    }

    @Override
    public <C extends Appendable & CharSequence> long readUtf8(@NonNegative long offset, @NotNull C sb) throws IORuntimeException, IllegalArgumentException, BufferUnderflowException, ArithmeticException, IllegalStateException {
        return base.readUtf8(offset, sb);
    }

    @Override
    public <C extends Appendable & CharSequence> long readUtf8Limited(@NonNegative long offset, @NotNull C sb, @NonNegative int maxUtf8Len) throws IORuntimeException, IllegalArgumentException, BufferUnderflowException, IllegalStateException {
        return base.readUtf8Limited(offset, sb, maxUtf8Len);
    }

    @Override
    public @Nullable String readUtf8Limited(@NonNegative long offset, @NonNegative int maxUtf8Len) throws BufferUnderflowException, IORuntimeException, IllegalArgumentException, IllegalStateException {
        return base.readUtf8Limited(offset, maxUtf8Len);
    }

    @Override
    public boolean read8bit(@NotNull Bytes<?> b) throws BufferUnderflowException, IllegalStateException, BufferOverflowException, ArithmeticException {
        return base.read8bit(b);

    }

    @Override
    public boolean read8bit(@NotNull StringBuilder sb) throws IORuntimeException, BufferUnderflowException, ArithmeticException, IllegalStateException {
        return base.read8bit(sb);

    }

    @Override
    public int read(byte[] bytes) throws IllegalStateException, BufferUnderflowException {
        return base.read(bytes);

    }

    @Override
    public int read(byte[] bytes, @NonNegative int off, @NonNegative int len) throws IllegalStateException, BufferUnderflowException {
        return base.read(bytes, off, len);
    }

    @Override
    public int read(char[] bytes, int off, @NonNegative int len) throws IllegalStateException {
        return base.read(bytes, off, len);
    }

    @Override
    public void read(@NotNull ByteBuffer buffer) throws IllegalStateException {
        base.read(buffer);
    }

    @Override
    public void read(@NotNull Bytes<?> bytes, @NonNegative int length) throws BufferUnderflowException, IllegalStateException, BufferOverflowException {
        base.read(bytes, length);

    }

    @NotNull
    @Override
    public <E extends Enum<E>> E readEnum(@NotNull Class<E> eClass) throws IORuntimeException, BufferUnderflowException, IllegalStateException, ArithmeticException, BufferOverflowException {
        return base.readEnum(eClass);

    }

    @Override
    public void readHistogram(@NotNull Histogram histogram) throws BufferUnderflowException, IllegalStateException, ArithmeticException {
        base.readHistogram(histogram);

    }

    @Override
    public void readWithLength(@NotNull Bytes<?> bytes) throws ArithmeticException, BufferOverflowException, IllegalStateException, BufferUnderflowException {
        base.readWithLength(bytes);
    }

    @Override
    @NotNull
    public Bytes<Void> writePosition(@NonNegative long position) throws BufferOverflowException {
        requireNonNegative(position);
        base.writePosition(position & MASK);
        text.writePosition(position >>> 32);
        return this;
    }

    @Override
    @NotNull
    public Bytes<Void> writeLimit(@NonNegative long limit) throws BufferOverflowException {
        base.writeLimit(limit);
        return this;
    }

    @Override
    @NotNull
    public Bytes<Void> writeSkip(long bytesToSkip) throws BufferOverflowException, IllegalStateException {
        long pos = base.writePosition();
        try {
            base.writeSkip(bytesToSkip);
            return this;
        } finally {
            copyToText(pos);
        }
    }

    @Override
    @NotNull
    public Bytes<Void> writeByte(byte i8) throws BufferOverflowException, IllegalStateException {
        long pos = base.writePosition();
        try {
            base.writeByte(i8);
            return this;
        } finally {
            copyToText(pos);
        }
    }

    /**
     * For HexDumpBytes it needs to remember the writePosition for the underlying bytes as well as the text hex dump, so it encodes both in one number so you can call writePosition later.
     *
     * @return the base and text writePositions.
     */
    @Override
    public @NonNegative long writePosition() {
        return base.writePosition() | (text.writePosition() << 32);
    }

    @Override
    public long lengthWritten(long startPosition) {
        return base.writePosition() - (startPosition & MASK);
    }

    private void copyToText(long pos) throws IllegalStateException {
        try {
            if (lineLength() == 0 && offsetFormat != null) {
                appendOffset(pos);
                startOfLine = text.writePosition();
            }
            copyToText0(pos);
        } catch (BufferUnderflowException e) {
            throw new AssertionError(e);
        }
    }

    private void copyToText0(long pos) {
        final long end = base.writePosition();
        if (pos < end) {
            doIndent();
            do {
                final int value = base.readUnsignedByte(pos);
                final long ll = lineLength();
                if (ll >= numberWrap * 3L - 1) {
                    newLine();
                    appendOffset(pos);
                    doIndent();
                    startOfLine = text.writePosition();
                }
                pos++;
                final long wp = text.writePosition();
                if (text.peekUnsignedByte(wp - 1) > ' ') {
                    text.append(' ');
                }
                appendBase16(value);
            } while (pos < end);
        }
    }

    private void appendBase16(int value) {
        text.appendBase16(value, 2);
    }

    private void copyToText(long pos, long tpos, int length) throws IllegalStateException {
        if (tpos > 0 && text.readUnsignedByte(tpos) <= ' ') tpos++;
        while (length-- > 0) {
            int value = base.readUnsignedByte(pos++);
            text.writeUnsignedByte(tpos++, HEXADECIMAL[value >> 4]);
            text.writeUnsignedByte(tpos++, HEXADECIMAL[value & 0xF]);
            if (length > 0) text.writeUnsignedByte(tpos++, ' ');
        }
    }

    private void doIndent() {
        if (lineLength() == 0 && indent > 0) {
            for (int i = 0; i < indent; i++)
                text.append("   ");
            startOfLine = text.writePosition();
        }
    }

    @Override
    @NotNull
    public Bytes<Void> writeShort(short i16) throws BufferOverflowException, IllegalStateException {
        long pos = base.writePosition();
        try {
            base.writeShort(i16);
            return this;

        } finally {
            copyToText(pos);
        }
    }

    @Override
    @NotNull
    public Bytes<Void> writeInt(int i) throws BufferOverflowException, IllegalStateException {
        long pos = base.writePosition();
        try {
            base.writeInt(i);
            return this;

        } finally {
            copyToText(pos);
        }
    }

    @Override
    @NotNull
    public Bytes<Void> writeIntAdv(int i, @NonNegative int advance) throws BufferOverflowException, IllegalStateException {
        long pos = base.writePosition();
        try {
            base.writeIntAdv(i, advance);
            return this;

        } finally {
            copyToText(pos);
        }
    }

    @Override
    @NotNull
    public Bytes<Void> writeLong(long i64) throws BufferOverflowException, IllegalStateException {
        long pos = base.writePosition();
        try {
            base.writeLong(i64);
            return this;

        } finally {
            copyToText(pos);
        }
    }

    @Override
    @NotNull
    public Bytes<Void> writeLongAdv(long i64, @NonNegative int advance) throws BufferOverflowException, IllegalStateException {
        long pos = base.writePosition();
        try {
            base.writeLongAdv(i64, advance);
            return this;

        } finally {
            copyToText(pos);
        }
    }

    @Override
    @NotNull
    public Bytes<Void> writeFloat(float f) throws BufferOverflowException, IllegalStateException {
        long pos = base.writePosition();
        try {
            base.writeFloat(f);
            return this;

        } finally {
            copyToText(pos);
        }
    }

    @Override
    @NotNull
    public Bytes<Void> writeDouble(double d) throws BufferOverflowException, IllegalStateException {
        long pos = base.writePosition();
        try {
            base.writeDouble(d);
            return this;

        } finally {
            copyToText(pos);
        }
    }

    @Override
    @NotNull
    public Bytes<Void> writeDoubleAndInt(double d, int i) throws BufferOverflowException, IllegalStateException {
        long pos = base.writePosition();
        try {
            base.writeDouble(d);
            base.writeInt(i);
            return this;

        } finally {
            copyToText(pos);
        }
    }

    @Override
    @NotNull
    public Bytes<Void> write(byte[] byteArray, int offset, int length) throws BufferOverflowException, IllegalArgumentException, IllegalStateException {
        long pos = base.writePosition();
        try {
            base.write(byteArray, offset, length);
            return this;

        } finally {
            copyToText(pos);
        }
    }

    @Override
    @NotNull
    public Bytes<Void> writeSome(@NotNull ByteBuffer buffer) throws BufferOverflowException, IllegalStateException, BufferUnderflowException {
        long pos = base.writePosition();
        try {
            base.writeSome(buffer);
            return this;

        } finally {
            copyToText(pos);
        }
    }

    @Override
    @NotNull
    public Bytes<Void> writeOrderedInt(int i) throws BufferOverflowException, IllegalStateException {
        long pos = base.writePosition();
        try {
            base.writeOrderedInt(i);
            return this;

        } finally {
            copyToText(pos);
        }
    }

    @Override
    @NotNull
    public Bytes<Void> writeOrderedLong(long i) throws BufferOverflowException, IllegalStateException {
        long pos = base.writePosition();
        try {
            base.writeOrderedLong(i);
            return this;

        } finally {
            copyToText(pos);
        }
    }

    @Override
    @NotNull
    public Bytes<Void> clearAndPad(@NonNegative long length) throws BufferOverflowException, IllegalStateException {
        long pos = base.writePosition();
        try {
            base.clearAndPad(length);
            return this;

        } finally {
            copyToText(pos);
        }
    }

    @Override
    @NotNull
    public Bytes<Void> prewrite(byte[] bytes) {
        throw new UnsupportedOperationException();
    }

    @Override
    @NotNull
    public Bytes<Void> prewrite(BytesStore<?, ?> bytes) {
        throw new UnsupportedOperationException();
    }

    @Override
    @NotNull
    public Bytes<Void> prewriteByte(byte b) {
        throw new UnsupportedOperationException();
    }

    @Override
    @NotNull
    public Bytes<Void> prewriteShort(short i) {
        throw new UnsupportedOperationException();
    }

    @Override
    @NotNull
    public Bytes<Void> prewriteInt(int i) {
        throw new UnsupportedOperationException();
    }

    @Override
    @NotNull
    public Bytes<Void> prewriteLong(long l) {
        throw new UnsupportedOperationException();
    }

    @Override
    public byte readByte(@NonNegative long offset) throws BufferUnderflowException, IllegalStateException {
        return base.readByte(offset);
    }

    @Override
    public int peekUnsignedByte(@NonNegative long offset) throws IllegalStateException, BufferUnderflowException {
        return base.peekUnsignedByte(offset);
    }

    @Override
    public short readShort(@NonNegative long offset) throws BufferUnderflowException, IllegalStateException {
        return base.readShort(offset);
    }

    @Override
    public int readInt(@NonNegative long offset) throws BufferUnderflowException, IllegalStateException {
        return base.readInt(offset);
    }

    @Override
    public long readLong(@NonNegative long offset) throws BufferUnderflowException, IllegalStateException {
        return base.readLong(offset);
    }

    @Override
    public float readFloat(@NonNegative long offset) throws BufferUnderflowException, IllegalStateException {
        return base.readFloat(offset);
    }

    @Override
    public double readDouble(@NonNegative long offset) throws BufferUnderflowException, IllegalStateException {
        return base.readDouble(offset);
    }

    @Override
    public byte readVolatileByte(@NonNegative long offset) throws BufferUnderflowException, IllegalStateException {
        return base.readVolatileByte(offset);
    }

    @Override
    public short readVolatileShort(@NonNegative long offset) throws BufferUnderflowException, IllegalStateException {
        return base.readVolatileShort(offset);
    }

    @Override
    public int readVolatileInt(@NonNegative long offset) throws BufferUnderflowException, IllegalStateException {
        return base.readVolatileInt(offset);
    }

    @Override
    public long readVolatileLong(@NonNegative long offset) throws BufferUnderflowException, IllegalStateException {
        return base.readVolatileLong(offset);
    }

    @Override
    public void nativeRead(@NonNegative long position, long address, @NonNegative long size) throws BufferUnderflowException, IllegalStateException {
        base.nativeRead(position, address, size);
    }

    @Override
    public @NonNegative long readPosition() {
        return base.readPosition() | (text.readPosition() << 32);
    }

    @Override
    public void lenient(boolean lenient) {
        base.lenient(lenient);
    }

    @Override
    public boolean lenient() {
        return base.lenient();
    }

    @Override
    public @NotNull Bytes<Void> append(char ch) throws IllegalStateException {
        long pos = base.writePosition();
        try {
            base.append(ch);
        } finally {
            copyToText(pos);
        }
        return this;
    }

    @Override
    public @NotNull Bytes<Void> append(@NotNull CharSequence cs) {
        long pos = base.writePosition();
        try {
            base.append(cs);
        } finally {
            copyToText(pos);
        }
        return this;
    }

    @Override
    public @NotNull Bytes<Void> append(boolean flag) throws BufferOverflowException, IllegalStateException {
        long pos = base.writePosition();
        try {
            base.append(flag);
        } finally {
            copyToText(pos);
        }
        return this;
    }

    @Override
    public @NotNull Bytes<Void> append(int value) throws BufferOverflowException, IllegalArgumentException, IllegalStateException {
        long pos = base.writePosition();
        try {
            base.append(value);
        } finally {
            copyToText(pos);
        }
        return this;
    }

    @Override
    public @NotNull Bytes<Void> append(long value) throws BufferOverflowException, IllegalStateException {
        long pos = base.writePosition();
        try {
            base.append(value);
        } finally {
            copyToText(pos);
        }
        return this;
    }

    @Override
    public @NotNull Bytes<Void> appendBase(long value, int base) throws BufferOverflowException, IllegalArgumentException, IllegalStateException, IndexOutOfBoundsException {
        long pos = this.base.writePosition();
        try {
            this.base.appendBase(value, base);
        } finally {
            copyToText(pos);
        }
        return this;
    }

    @Override
    public @NotNull Bytes<Void> appendBase16(long value) throws BufferOverflowException, IllegalArgumentException, IllegalStateException {
        long pos = base.writePosition();
        try {
            base.appendBase16(value);
        } finally {
            copyToText(pos);
        }
        return this;
    }

    @Override
    public @NotNull Bytes<Void> appendBase16(long value, int minDigits) throws BufferOverflowException, IllegalArgumentException, IllegalStateException {
        long pos = base.writePosition();
        try {
            base.appendBase16(value, minDigits);
        } finally {
            copyToText(pos);
        }
        return this;
    }

    @Override
    public @NotNull Bytes<Void> appendDecimal(long value, int decimalPlaces) throws BufferOverflowException, IllegalStateException, ArithmeticException, IllegalArgumentException {
        long pos = base.writePosition();
        try {
            base.appendDecimal(value, decimalPlaces);
        } finally {
            copyToText(pos);
        }
        return this;
    }

    @Override
    public @NotNull Bytes<Void> append(double d, int decimalPlaces) throws BufferOverflowException, IllegalArgumentException, IllegalStateException, ArithmeticException {
        long pos = base.writePosition();
        try {
            base.append(d, decimalPlaces);
        } finally {
            copyToText(pos);
        }
        return this;
    }

    @Override
    public Decimaliser decimaliser() {
        return base.decimaliser();
    }

    @Override
    public Bytes<Void> decimaliser(Decimaliser decimaliser) {
        base.decimaliser(decimaliser);
        return this;
    }

    @SuppressWarnings("deprecation")
    @Override
    public boolean fpAppend0() {
        return base.fpAppend0();
    }

    @SuppressWarnings("deprecation")
    @Override
    public Bytes<Void> fpAppend0(boolean append0) {
        base.fpAppend0(append0);
        return this;
    }

    @Override
    public void append(boolean isNegative, long mantissa, int exponent) {
        long pos = base.writePosition();
        try {
            ((DecimalAppender) base).append(isNegative, mantissa, exponent);
        } finally {
            copyToText(pos);
        }
    }

    @Override
    public long appendAndReturnLength(long writePosition, boolean negative, long mantissa, int exponent, boolean append0) {
        throw new UnsupportedOperationException();
    }

    @Override
    public @NotNull Bytes<Void> append(@NotNull CharSequence cs, int start, int end) throws IndexOutOfBoundsException {
        long pos = base.writePosition();
        try {
            base.append(cs, start, end);
        } finally {
            copyToText(pos);
        }
        return this;
    }

    @Override
    public @NotNull Bytes<Void> append8bit(@NotNull CharSequence cs) throws BufferOverflowException, BufferUnderflowException, IndexOutOfBoundsException, IllegalStateException {
        long pos = base.writePosition();
        try {
            base.append8bit(cs);
        } finally {
            copyToText(pos);
        }
        return this;
    }

    @Override
    public Bytes<Void> append8bit(@NotNull BytesStore<?, ?> bs) throws BufferOverflowException, BufferUnderflowException, IllegalStateException {
        long pos = base.writePosition();
        try {
            base.append8bit(bs);
        } finally {
            copyToText(pos);
        }
        return this;
    }

    @Override
    public Bytes<Void> append8bit(@NotNull String cs) throws BufferOverflowException, IllegalStateException {
        long pos = base.writePosition();
        try {
            base.append8bit(cs);
        } finally {
            copyToText(pos);
        }
        return this;
    }

    @Override
    public Bytes<Void> append8bit(@NotNull CharSequence cs, int start, int end) throws IllegalArgumentException, BufferOverflowException, BufferUnderflowException, IndexOutOfBoundsException, IllegalStateException {
        long pos = base.writePosition();
        try {
            base.append8bit(cs, start, end);
        } finally {
            copyToText(pos);
        }
        return this;
    }

    @Override
    public Bytes<Void> append8bit(@NotNull BytesStore<?, ?> bs, long start, long end) throws IllegalArgumentException, BufferOverflowException, BufferUnderflowException, IndexOutOfBoundsException, IllegalStateException {
        long pos = base.writePosition();
        try {
            base.append8bit(bs, start, end);
        } finally {
            copyToText(pos);
        }
        return this;
    }

    @NotNull
    @Override
    public Bytes<Void> appendDateMillis(long dateInMillis) throws BufferOverflowException, IllegalStateException {
        long pos = base.writePosition();
        try {
            base.appendDateMillis(dateInMillis);
        } finally {
            copyToText(pos);
        }
        return this;
    }

    @NotNull
    @Override
    public Bytes<Void> appendTimeMillis(long timeOfDayInMillis) throws BufferOverflowException, IllegalStateException, IllegalArgumentException {
        long pos = base.writePosition();
        try {
            base.appendTimeMillis(timeOfDayInMillis);
        } finally {
            copyToText(pos);
        }
        return this;
    }

    @NotNull
    @Override
    public Bytes<Void> append(@NotNull BigDecimal bigDecimal) {
        long pos = base.writePosition();
        try {
            base.append(bigDecimal);
        } finally {
            copyToText(pos);
        }
        return this;
    }

    @Override
    public @NotNull Bytes<Void> append(float f) throws BufferOverflowException, IllegalStateException {
        long pos = base.writePosition();
        try {
            base.append(f);
        } finally {
            copyToText(pos);
        }
        return this;
    }

    @Override
    public @NotNull Bytes<Void> append(double d) throws BufferOverflowException, IllegalStateException {
        long pos = base.writePosition();
        try {
            base.append(d);
        } finally {
            copyToText(pos);
        }
        return this;
    }

    @Override
    public void writeMarshallableLength16(@NotNull WriteBytesMarshallable marshallable)
            throws BufferOverflowException, BufferUnderflowException, IllegalStateException, InvalidMarshallableException {
        long pos = base.writePosition();
        try {
            base.writeMarshallableLength16(marshallable);
        } finally {
            copyToText(pos);
        }
    }

    @Override
    public Bytes<?> write(@NotNull InputStream inputStream) throws IOException, IllegalStateException, BufferOverflowException {
        long pos = base.writePosition();
        try {
            base.write(inputStream);
            return this;

        } finally {
            copyToText(pos);
        }
    }

    @NotNull
    @Override
    public Bytes<Void> writeStopBit(long x) throws BufferOverflowException, IllegalStateException {
        long pos = base.writePosition();
        try {
            base.writeStopBit(x);
            return this;

        } finally {
            copyToText(pos);
        }
    }

    @NotNull
    @Override
    public Bytes<Void> writeStopBit(char x) throws BufferOverflowException, IllegalStateException {
        long pos = base.writePosition();
        try {
            base.writeStopBit(x);
            return this;

        } finally {
            copyToText(pos);
        }
    }

    @NotNull
    @Override
    public Bytes<Void> writeStopBit(double d) throws BufferOverflowException, IllegalStateException {
        long pos = base.writePosition();
        try {
            base.writeStopBit(d);
            return this;

        } finally {
            copyToText(pos);
        }
    }

    @NotNull
    @Override
    public Bytes<Void> writeStopBitDecimal(double d) throws BufferOverflowException, IllegalStateException {
        long pos = base.writePosition();
        try {
            base.writeStopBitDecimal(d);
            return this;

        } finally {
            copyToText(pos);
        }
    }

    @NotNull
    @Override
    public Bytes<Void> writeUtf8(@Nullable CharSequence text) throws BufferOverflowException, IllegalStateException, IllegalArgumentException {
        long pos = base.writePosition();
        try {
            base.writeUtf8(text);
            return this;

        } finally {
            copyToText(pos);
        }
    }

    @NotNull
    @Override
    public Bytes<Void> writeUtf8(@Nullable String text) throws BufferOverflowException, IllegalStateException {
        long pos = base.writePosition();
        try {
            base.writeUtf8(text);
            return this;

        } finally {
            copyToText(pos);
        }
    }

    @NotNull
    @Override
    public Bytes<Void> write8bit(@Nullable CharSequence text) throws BufferOverflowException, IllegalStateException, BufferUnderflowException, ArithmeticException {
        long pos = base.writePosition();
        try {
            base.write8bit(text);
            return this;

        } finally {
            copyToText(pos);
        }
    }

    @NotNull
    @Override
    public Bytes<Void> write8bit(@NotNull CharSequence text, @NonNegative int start, @NonNegative int length) throws BufferOverflowException, IndexOutOfBoundsException, IllegalStateException, BufferUnderflowException, ArithmeticException {
        long pos = base.writePosition();
        try {
            base.write8bit(text, start, length);
            return this;

        } finally {
            copyToText(pos);
        }
    }

    @NotNull
    @Override
    public Bytes<Void> write8bit(@NotNull String text, @NonNegative int start, @NonNegative int length) throws BufferOverflowException, IndexOutOfBoundsException, IllegalStateException, BufferUnderflowException, ArithmeticException {
        long pos = base.writePosition();
        try {
            base.write8bit(text, start, length);
            return this;

        } finally {
            copyToText(pos);
        }
    }

    @NotNull
    @Override
    public Bytes<Void> write(@NotNull CharSequence text) throws BufferOverflowException, IllegalStateException, IndexOutOfBoundsException {
        long pos = base.writePosition();
        try {
            base.write(text);
            return this;
        } finally {
            copyToText(pos);
        }
    }

    @NotNull
    @Override
    public Bytes<Void> write(@NotNull CharSequence text, @NonNegative int startText, @NonNegative int length) throws BufferOverflowException, IndexOutOfBoundsException, IllegalStateException {
        long pos = base.writePosition();
        try {
            base.write(text, startText, length);
            return this;
        } finally {
            copyToText(pos);
        }
    }

    @NotNull
    @Override
    public Bytes<Void> write8bit(@Nullable String s) throws BufferOverflowException, IllegalStateException, ArithmeticException {
        long pos = base.writePosition();
        try {
            base.write8bit(s);
            return this;
        } finally {
            copyToText(pos);
        }
    }

    @NotNull
    @Override
    public Bytes<Void> writeUnsignedByte(int i) throws BufferOverflowException, IllegalStateException, ArithmeticException {
        long pos = base.writePosition();
        try {
            base.writeUnsignedByte(i);
            return this;
        } finally {
            copyToText(pos);
        }
    }

    @NotNull
    @Override
    public Bytes<Void> writeUnsignedShort(int u16) throws BufferOverflowException, IllegalStateException, ArithmeticException {
        long pos = base.writePosition();
        try {
            base.writeUnsignedShort(u16);
            return this;

        } finally {
            copyToText(pos);
        }
    }

    @NotNull
    @Override
    public Bytes<Void> writeInt24(int i) throws BufferOverflowException, IllegalStateException, ArithmeticException {
        long pos = base.writePosition();
        try {
            base.writeInt24(i);
            return this;

        } finally {
            copyToText(pos);
        }
    }

    @NotNull
    @Override
    public Bytes<Void> writeUnsignedInt24(int i) throws BufferOverflowException, IllegalStateException, ArithmeticException {
        long pos = base.writePosition();
        try {
            base.writeUnsignedInt24(i);
            return this;

        } finally {
            copyToText(pos);
        }
    }

    @NotNull
    @Override
    public Bytes<Void> writeUnsignedInt(long i) throws BufferOverflowException, ArithmeticException, IllegalStateException {
        long pos = base.writePosition();
        try {
            base.writeUnsignedInt(i);
            return this;

        } finally {
            copyToText(pos);
        }
    }

    @Override
    public Bytes<Void> write(@NotNull BytesStore<?, ?> bytes) throws IllegalStateException, BufferOverflowException {
        long pos = base.writePosition();
        try {
            base.write(bytes);
            return this;

        } finally {
            copyToText(pos);
        }
    }

    @NotNull
    @Override
    public Bytes<Void> write(@NotNull BytesStore<?, ?> bytes, @NonNegative long offset, @NonNegative long length) throws BufferOverflowException, BufferUnderflowException, IllegalStateException, IllegalArgumentException {
        throwExceptionIfReleased(bytes);
        requireNonNegative(offset);
        requireNonNegative(length);
        long pos = base.writePosition();
        try {
            base.write(bytes, offset, length);
            return this;

        } finally {
            copyToText(pos);
        }
    }

    @NotNull
    @Override
    public Bytes<Void> write(byte[] byteArray) throws BufferOverflowException, IllegalStateException {
        long pos = base.writePosition();
        try {
            base.write(byteArray);
            return this;

        } finally {
            copyToText(pos);
        }
    }

    @NotNull
    @Override
    public Bytes<Void> writeBoolean(boolean flag) throws BufferOverflowException, IllegalStateException {
        long pos = base.writePosition();
        try {
            base.writeBoolean(flag);
            return this;

        } finally {
            copyToText(pos);
        }
    }

    @Override
    public <E extends Enum<E>> Bytes<Void> writeEnum(@NotNull E e) throws BufferOverflowException, IllegalStateException, ArithmeticException {
        long pos = base.writePosition();
        try {
            base.writeEnum(e);
            return this;

        } finally {
            copyToText(pos);
        }
    }

    @Override
    public void writePositionRemaining(@NonNegative long position, @NonNegative long length) throws BufferOverflowException {
        requireNonNegative(position);
        requireNonNegative(length);
        writePosition(position);
        writeLimit(base.writePosition() + length);
    }

    @Override
    public void writeHistogram(@NotNull Histogram histogram) throws IllegalStateException, BufferOverflowException {
        long pos = base.writePosition();
        try {
            base.writeHistogram(histogram);
        } finally {
            copyToText(pos);
        }
    }

    @Override
    public void writeBigDecimal(@NotNull BigDecimal bd) throws IllegalArgumentException, IllegalStateException, BufferOverflowException {
        long pos = base.writePosition();
        try {
            base.writeBigDecimal(bd);
        } finally {
            copyToText(pos);
        }
    }

    @Override
    public void writeBigInteger(@NotNull BigInteger bi) throws IllegalArgumentException, IllegalStateException, BufferOverflowException {
        long pos = base.writePosition();
        try {
            base.writeBigInteger(bi);
        } finally {
            copyToText(pos);
        }
    }

    @Override
    public void writeWithLength(@NotNull BytesStore<?, ?> bytes) throws IllegalStateException, BufferOverflowException {
        long pos = base.writePosition();
        try {
            base.writeWithLength(bytes);
        } finally {
            copyToText(pos);
        }
    }

    @Override
    public void singleThreadedCheckReset() {
        base.singleThreadedCheckReset();
        text.singleThreadedCheckReset();
    }

    @Override
    public void singleThreadedCheckDisabled(boolean singleThreadedCheckDisabled) {
        base.singleThreadedCheckDisabled(singleThreadedCheckDisabled);
        text.singleThreadedCheckDisabled(singleThreadedCheckDisabled);
    }

    private static class TextBytesReader extends Reader {
        private final Reader reader;
        private final Bytes<?> base;

        public TextBytesReader(Reader reader, Bytes<?> base) {
            this.reader = reader;
            this.base = base;
        }

        @Override
        public int read(char[] cbuf, int off, int len) throws IOException {
            int len2 = reader.read(cbuf, off, len);
            base.append(new String(cbuf, off, len)); // TODO Optimise
            return len2;
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }
    }
}
