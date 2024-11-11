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

import net.openhft.chronicle.bytes.internal.*;
import net.openhft.chronicle.bytes.internal.migration.HashCodeEqualsUtil;
import net.openhft.chronicle.bytes.render.DecimalAppender;
import net.openhft.chronicle.bytes.render.Decimaliser;
import net.openhft.chronicle.bytes.render.StandardDecimaliser;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.Memory;
import net.openhft.chronicle.core.OS;
import net.openhft.chronicle.core.UnsafeMemory;
import net.openhft.chronicle.core.annotation.NonNegative;
import net.openhft.chronicle.core.io.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static net.openhft.chronicle.core.Jvm.uncheckedCast;
import static net.openhft.chronicle.core.util.Ints.requireNonNegative;
import static net.openhft.chronicle.core.util.Longs.requireNonNegative;
import static net.openhft.chronicle.core.util.ObjectUtils.requireNonNull;

/**
 * An optimized extension of AbstractBytes that performs unchecked read and write operations
 * on a Bytes instance that is backed by native memory.
 * The class bypasses bounds checking to provide high-performance access to the underlying data.
 *
 * <p>This class is intended for use in performance-critical scenarios where the client can
 * ensure that all operations stay within valid bounds, thus avoiding the overhead of bounds checking.
 *
 * <p>Warning: Using this class improperly can result in IndexOutOfBoundsException being thrown,
 * corruption of data, JVM crashes, or other undefined behavior.
 *
 * @param <U> The type of the object this Bytes can point to.
 */
@SuppressWarnings("rawtypes")
public class UncheckedNativeBytes<U>
        extends AbstractReferenceCounted
        implements Bytes<U>, HasUncheckedRandomDataInput, DecimalAppender {
    private static final byte[] MIN_VALUE_TEXT = ("" + Long.MIN_VALUE).getBytes(ISO_8859_1);
    @Deprecated(/* to remove in x.28 */)
    private static final boolean APPEND_0 = Jvm.getBoolean("bytes.append.0", true);

    // The real capacity of the BytesStore this UncheckedNativeBytes operates on
    protected final long capacity;
    // An instance of UncheckedRandomDataInput for accessing data without bounds checking
    private final UncheckedRandomDataInput uncheckedRandomDataInput = new UncheckedRandomDataInputHolder();
    // The Bytes instance this UncheckedNativeBytes operates on
    @NotNull
    private final Bytes<U> underlyingBytes;
    // The BytesStore that the underlying Bytes operates on
    @NotNull
    protected BytesStore<?, U> bytesStore;
    // The position of the next byte to be read
    protected long readPosition;
    // The position of the next byte to be written
    protected long writePosition;
    // The limit of the write buffer
    protected long writeLimit;
    // Tracks the number of decimal places in the last number read
    private int lastDecimalPlaces = 0;
    // Tracks if the last number read had any digits
    private boolean lastNumberHadDigits = false;
    private Decimaliser decimaliser = StandardDecimaliser.STANDARD;
    private boolean append0 = APPEND_0;

    /**
     * Constructs an UncheckedNativeBytes instance by wrapping around the provided Bytes object.
     *
     * @param underlyingBytes the Bytes object to wrap around
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    @SuppressWarnings({"unchecked", "this-escape"})
    public UncheckedNativeBytes(@NotNull Bytes<U> underlyingBytes)
            throws IllegalStateException {
        this.underlyingBytes = underlyingBytes;
        underlyingBytes.reserve(this);
        this.bytesStore = BytesInternal.failIfBytesOnBytes(underlyingBytes.bytesStore());
        assert bytesStore.start() == 0;
        writePosition = underlyingBytes.writePosition();
        readPosition = underlyingBytes.readPosition();
        capacity = bytesStore.realCapacity();
        writeLimit = Math.min(capacity, underlyingBytes.writeLimit());
    }

    @Override
    public void ensureCapacity(@NonNegative long desiredCapacity)
            throws IllegalArgumentException, IllegalStateException {
        if (desiredCapacity > realCapacity()) {
            underlyingBytes.ensureCapacity(desiredCapacity);
            bytesStore = uncheckedCast(underlyingBytes.bytesStore());
        }
    }

    @Override
    public boolean unchecked() {
        return true;
    }

    @Override
    public boolean isDirectMemory() {
        return true;
    }

    @Override
    @NotNull
    public Bytes<U> unchecked(boolean unchecked) {
        throwExceptionIfReleased();
        return this;
    }

    @Override
    public void move(@NonNegative long from, @NonNegative long to, @NonNegative long length)
            throws IllegalStateException, BufferUnderflowException {
        ensureCapacity(to + length);
        bytesStore.move(from - start(), to - start(), length);
    }

    @NotNull
    @Override
    public Bytes<U> compact()
            throws ClosedIllegalStateException, ThreadingIllegalStateException {

        // Get the start position of the buffer
        long start = start();

        // Get the number of unread bytes in the buffer, ensuring that it is not set to a negative value
        long readRemaining = Math.max(0, readRemaining());

        // if the space freed is less a than 1/4 the data that would be moved, leave it.
        if ((readPosition - start) < readRemaining / 4)
            return this;

        // Check if there are unread bytes and if they're not already at the start of the buffer
        if (readRemaining > 0 && start < readPosition) {
            // Move the unread bytes to the start of the buffer
            bytesStore.move(readPosition, start, readRemaining);
        }

        // Reset the read position to the start of the buffer
        readPosition = start;

        // Set the write position to be after the unread bytes
        writePosition = start + readRemaining;

        // Return this Bytes object to allow for method chaining
        return this;
    }
    @NotNull
    @Override
    public Bytes<U> readPosition(@NonNegative long position) {
        assert position <= bytesStore.capacity();
        readPosition = position;
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> readLimit(@NonNegative long limit) {
        assert limit <= bytesStore.capacity();
        writePosition = limit;
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> writePosition(@NonNegative long position) {
        assert position <= bytesStore.capacity();
        writePosition = requireNonNegative(position);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> readSkip(long bytesToSkip) {
        readPosition += bytesToSkip;
        assert readPosition <= readLimit();
        return this;
    }

    @Override
    public byte readVolatileByte(@NonNegative long offset)
            throws BufferUnderflowException {
        return bytesStore.readVolatileByte(offset);
    }

    @Override
    public short readVolatileShort(@NonNegative long offset)
            throws BufferUnderflowException {
        return bytesStore.readVolatileShort(offset);
    }

    @Override
    public int readVolatileInt(@NonNegative long offset)
            throws BufferUnderflowException {
        return bytesStore.readVolatileInt(offset);
    }

    @Override
    public long readVolatileLong(@NonNegative long offset)
            throws BufferUnderflowException {
        return bytesStore.readVolatileLong(offset);
    }

    @Override
    public void uncheckedReadSkipOne() {
        readPosition++;
    }

    @Override
    public void uncheckedReadSkipBackOne() {
        readPosition--;
    }

    @NotNull
    @Override
    public Bytes<U> writeSkip(long bytesToSkip) {
        writePosition += bytesToSkip;
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> writeLimit(@NonNegative long limit) {
        assert limit <= bytesStore.capacity();
        writeLimit = requireNonNegative(limit);
        return this;
    }

    @NotNull
    @Override
    public BytesStore<Bytes<U>, U> copy() {
        throwExceptionIfReleased();
        throw new UnsupportedOperationException("todo");
    }

    @Override
    public boolean isElastic() {
        return false;
    }

    protected long readOffsetPositionMoved(@NonNegative long adding) {
        long offset = readPosition;
        readPosition += adding;
        // TODO FIX MoldUdpHandlerTest
//        assert readPosition <= readLimit();
        return offset;
    }

    protected long writeOffsetPositionMoved(@NonNegative long adding) {
        return writeOffsetPositionMoved(adding, adding);
    }

    protected long writeOffsetPositionMoved(@NonNegative long adding, @NonNegative long advance) {
        long oldPosition = writePosition;
        long writeEnd = oldPosition + adding;
        assert writeEnd <= bytesStore.safeLimit();
        writePosition += advance;
        assert readPosition < writeLimit();
        return oldPosition;
    }

    protected long prewriteOffsetPositionMoved(@NonNegative long substracting) {
        readPosition -= substracting;
        return readPosition;
    }

    @Override
    @NotNull
    public Bytes<U> clear() {
        readPosition = writePosition = start();
        writeLimit = capacity();
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> clearAndPad(@NonNegative long length)
            throws BufferOverflowException {
        if (start() + length > capacity())
            throw new BufferOverflowException();
        readPosition = writePosition = start() + length;
        writeLimit = capacity();
        return this;
    }

    @Override
    public long readLimit() {
        return writePosition;
    }

    @Override
    public long writeLimit() {
        return writeLimit;
    }

    @Override
    public @NonNegative long realCapacity() {
        return bytesStore.realCapacity();
    }

    @Override
    public long realWriteRemaining() {
        return writeRemaining();
    }

    @Override
    public @NonNegative long capacity() {
        return capacity;
    }

    @Nullable
    @Override
    public U underlyingObject() {
        return bytesStore.underlyingObject();
    }

    @Override
    public @NonNegative long readPosition() {
        return readPosition;
    }

    @Override
    public @NonNegative long writePosition() {
        return writePosition;
    }

    @Override
    public boolean compareAndSwapInt(@NonNegative long offset, int expected, int value)
            throws BufferOverflowException, IllegalStateException {
        writeCheckOffset(offset, 4);
        return bytesStore.compareAndSwapInt(offset, expected, value);
    }

    @Override
    public void testAndSetInt(@NonNegative long offset, int expected, int value)
            throws BufferOverflowException, IllegalStateException {
        writeCheckOffset(offset, 4);
        bytesStore.testAndSetInt(offset, expected, value);
    }

    @Override
    public boolean compareAndSwapLong(@NonNegative long offset, long expected, long value)
            throws BufferOverflowException, IllegalStateException {
        writeCheckOffset(offset, 8);
        return bytesStore.compareAndSwapLong(offset, expected, value);
    }

    @Override
    protected void performRelease()
            throws IllegalStateException {
        this.underlyingBytes.release(this);
        final boolean interrupted = Thread.interrupted();
        try {
            // need to wait as checks rely on this completing.
            BackgroundResourceReleaser.releasePendingResources();
        } finally {
            if (interrupted)
                Thread.currentThread().interrupt();
        }
    }

    @Override
    public int readUnsignedByte() {
        long offset = readOffsetPositionMoved(1);
        return bytesStore.readByte(offset) & 0xFF;
    }

    @Override
    public int uncheckedReadUnsignedByte() {
        return readUnsignedByte();
    }

    @Override
    public byte readByte() {
        long offset = readOffsetPositionMoved(1);
        return bytesStore.readByte(offset);
    }

    @Override
    public int peekUnsignedByte() {
        try {
            return readRemaining() > 0 ? bytesStore.readUnsignedByte(readPosition) : -1;

        } catch (BufferUnderflowException e) {
            return -1;
        }
    }

    @Override
    public short readShort() {
        long offset = readOffsetPositionMoved(2);
        return bytesStore.readShort(offset);
    }

    @Override
    public int readInt() {
        long offset = readOffsetPositionMoved(4);
        return bytesStore.readInt(offset);
    }

    @Override
    public long readLong() {
        long offset = readOffsetPositionMoved(8);
        return bytesStore.readLong(offset);
    }

    @Override
    public float readFloat() {
        long offset = readOffsetPositionMoved(4);
        return bytesStore.readFloat(offset);
    }

    @Override
    public double readDouble() {
        long offset = readOffsetPositionMoved(8);
        return bytesStore.readDouble(offset);
    }

    @Override
    public int readVolatileInt() {
        long offset = readOffsetPositionMoved(4);
        return bytesStore.readVolatileInt(offset);
    }

    @Override
    public long readVolatileLong() {
        long offset = readOffsetPositionMoved(8);
        return bytesStore.readVolatileLong(offset);
    }

    @NotNull
    @Override
    public Bytes<U> writeByte(@NonNegative long offset, byte i)
            throws BufferOverflowException, IllegalStateException {
        writeCheckOffset(offset, 1);
        bytesStore.writeByte(offset, i);
        return this;
    }

    @Override
    public Bytes<U> rawWriteByte(byte i8) throws BufferOverflowException, IllegalStateException {
        bytesStore.writeByte(writePosition++, i8);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> writeShort(@NonNegative long offset, short i)
            throws BufferOverflowException, IllegalStateException {
        writeCheckOffset(offset, 2);
        bytesStore.writeShort(offset, i);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> writeInt(@NonNegative long offset, int i)
            throws BufferOverflowException, IllegalStateException {
        writeCheckOffset(offset, 4);
        bytesStore.writeInt(offset, i);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> writeOrderedInt(@NonNegative long offset, int i)
            throws BufferOverflowException, IllegalStateException {
        writeCheckOffset(offset, 4);
        bytesStore.writeOrderedInt(offset, i);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> writeLong(@NonNegative long offset, long i)
            throws BufferOverflowException, IllegalStateException {
        writeCheckOffset(offset, 8);
        bytesStore.writeLong(offset, i);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> writeOrderedLong(@NonNegative long offset, long i)
            throws BufferOverflowException, IllegalStateException {
        writeCheckOffset(offset, 8);
        bytesStore.writeOrderedLong(offset, i);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> writeFloat(@NonNegative long offset, float d)
            throws BufferOverflowException, IllegalStateException {
        writeCheckOffset(offset, 4);
        bytesStore.writeFloat(offset, d);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> writeDouble(@NonNegative long offset, double d)
            throws BufferOverflowException, IllegalStateException {
        writeCheckOffset(offset, 8);
        bytesStore.writeDouble(offset, d);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> writeVolatileByte(@NonNegative long offset, byte i8)
            throws BufferOverflowException, IllegalStateException {
        writeCheckOffset(offset, 1);
        bytesStore.writeVolatileByte(offset, i8);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> writeVolatileShort(@NonNegative long offset, short i16)
            throws BufferOverflowException, IllegalStateException {
        writeCheckOffset(offset, 2);
        bytesStore.writeVolatileShort(offset, i16);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> writeVolatileInt(@NonNegative long offset, int i32)
            throws BufferOverflowException, IllegalStateException {
        writeCheckOffset(offset, 4);
        bytesStore.writeVolatileInt(offset, i32);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> writeVolatileLong(@NonNegative long offset, long i64)
            throws BufferOverflowException, IllegalStateException {
        writeCheckOffset(offset, 8);
        bytesStore.writeVolatileLong(offset, i64);
        return this;
    }

    @Override
    @NotNull
    public Bytes<U> write(@NonNegative final long offsetInRDO,
                          final byte[] byteArray,
                          @NonNegative final int offset,
                          @NonNegative int length) throws BufferOverflowException, IllegalStateException {
        requireNonNull(byteArray);
        writeCheckOffset(offsetInRDO, length);
        bytesStore.write(offsetInRDO, byteArray, offset, length);
        return this;
    }

    @Override
    public void write(@NonNegative long offsetInRDO, @NotNull ByteBuffer bytes, @NonNegative int offset, @NonNegative int length)
            throws BufferOverflowException, IllegalStateException {
        requireNonNull(bytes);
        writeCheckOffset(offsetInRDO, length);
        bytesStore.write(offsetInRDO, bytes, offset, length);
    }

    @Override
    @NotNull
    public Bytes<U> write(@NonNegative long writeOffset,
                          @NotNull RandomDataInput bytes, @NonNegative long readOffset, @NonNegative long length)
            throws BufferUnderflowException, BufferOverflowException, IllegalStateException {
        writeCheckOffset(writeOffset, length);
        bytesStore.write(writeOffset, bytes, readOffset, length);
        return this;
    }

    @SuppressWarnings("EmptyMethod")
    void writeCheckOffset(@NonNegative long offset, long adding)
            throws BufferOverflowException {
        // Do nothing
    }

    @Override
    public byte readByte(@NonNegative long offset) {
        return bytesStore.readByte(offset);
    }

    @Override
    public int readUnsignedByte(@NonNegative long offset) {
        return bytesStore.readByte(offset) & 0xFF;
    }

    @Override
    public int peekUnsignedByte(@NonNegative long offset) {
        return offset < start() || writePosition <= offset ? -1 : readByte(offset);
    }

    @Override
    public short readShort(@NonNegative long offset) {
        return bytesStore.readShort(offset);
    }

    @Override
    public int readInt(@NonNegative long offset) {
        return bytesStore.readInt(offset);
    }

    @Override
    public long readLong(@NonNegative long offset) {
        return bytesStore.readLong(offset);
    }

    @Override
    public float readFloat(@NonNegative long offset) {
        return bytesStore.readFloat(offset);
    }

    @Override
    public double readDouble(@NonNegative long offset) {
        return bytesStore.readDouble(offset);
    }

    @NotNull
    @Override
    public Bytes<U> writeByte(byte i8) {
        long offset = writeOffsetPositionMoved(1);
        bytesStore.writeByte(offset, i8);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> prewriteByte(byte i8) {
        long offset = prewriteOffsetPositionMoved(1);
        bytesStore.writeByte(offset, i8);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> writeShort(short i16)
            throws IllegalStateException {
        long offset = writeOffsetPositionMoved(2);
        bytesStore.writeShort(offset, i16);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> prewriteShort(short i16)
            throws IllegalStateException {
        long offset = prewriteOffsetPositionMoved(2);
        bytesStore.writeShort(offset, i16);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> writeInt(int i)
            throws IllegalStateException {
        long offset = writeOffsetPositionMoved(4);
        bytesStore.writeInt(offset, i);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> writeIntAdv(int i, @NonNegative int advance)
            throws IllegalStateException {
        long offset = writeOffsetPositionMoved(4, advance);
        bytesStore.writeInt(offset, i);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> prewriteInt(int i)
            throws IllegalStateException {
        long offset = prewriteOffsetPositionMoved(4);
        bytesStore.writeInt(offset, i);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> writeLong(long i64)
            throws IllegalStateException {
        long offset = writeOffsetPositionMoved(8);
        bytesStore.writeLong(offset, i64);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> writeLongAdv(long i64, @NonNegative int advance)
            throws IllegalStateException {
        long offset = writeOffsetPositionMoved(8, advance);
        bytesStore.writeLong(offset, i64);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> prewriteLong(long i64)
            throws IllegalStateException {
        long offset = prewriteOffsetPositionMoved(8);
        bytesStore.writeLong(offset, i64);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> writeFloat(float f)
            throws IllegalStateException {
        long offset = writeOffsetPositionMoved(4);
        bytesStore.writeFloat(offset, f);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> writeDouble(double d)
            throws IllegalStateException {
        long offset = writeOffsetPositionMoved(8);
        bytesStore.writeDouble(offset, d);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> writeDoubleAndInt(double d, int i)
            throws IllegalStateException {
        long offset = writeOffsetPositionMoved(12);
        bytesStore.writeDouble(offset, d);
        bytesStore.writeInt(offset + 8, i);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> write(final byte[] byteArray,
                          @NonNegative final int offset,
                          @NonNegative final int length) throws BufferOverflowException, IllegalStateException, ArrayIndexOutOfBoundsException {
        requireNonNegative(offset);
        requireNonNegative(length);
        if (length + offset > byteArray.length)
            throw new ArrayIndexOutOfBoundsException("bytes.length=" + byteArray.length + ", " +
                    "length=" + length + ", offset=" + offset);
        if (length > writeRemaining())
            throw new BufferOverflowException();
        long offsetInRDO = writeOffsetPositionMoved(length);
        bytesStore.write(offsetInRDO, byteArray, offset, length);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> prewrite(@NotNull byte[] bytes)
            throws IllegalStateException, BufferOverflowException {
        long offsetInRDO = prewriteOffsetPositionMoved(bytes.length);
        bytesStore.write(offsetInRDO, bytes);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> prewrite(@NotNull BytesStore<?, ?> bytes)
            throws IllegalStateException, BufferOverflowException {
        long offsetInRDO = prewriteOffsetPositionMoved(bytes.length());
        bytesStore.write(offsetInRDO, bytes);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> writeSome(@NotNull ByteBuffer buffer)
            throws IllegalStateException {
        bytesStore.write(writePosition, buffer, buffer.position(), buffer.limit());
        writePosition += buffer.remaining();
        assert writePosition <= writeLimit();
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> writeOrderedInt(int i)
            throws IllegalStateException {
        long offset = writeOffsetPositionMoved(4);
        bytesStore.writeOrderedInt(offset, i);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> writeOrderedLong(long i)
            throws IllegalStateException {
        long offset = writeOffsetPositionMoved(8);
        bytesStore.writeOrderedLong(offset, i);
        return this;
    }

    @Override
    public long addressForRead(@NonNegative long offset)
            throws BufferUnderflowException {
        return bytesStore.addressForRead(offset);
    }

    @Override
    public long addressForWrite(@NonNegative long offset)
            throws BufferOverflowException {
        return bytesStore.addressForWrite(offset);
    }

    @Override
    public long addressForWritePosition()
            throws UnsupportedOperationException, BufferOverflowException {
        return bytesStore.addressForWrite(0);
    }

    @Override
    public int hashCode() {
        return HashCodeEqualsUtil.hashCode(this);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof BytesStore && BytesInternal.contentEqual(this, (BytesStore) obj);
    }

    @NotNull
    @Override
    public String toString() {
        if (refCount() <= 0)
            return "(released)";
        return BytesInternal.toString(this);
    }

    @Override
    public void lenient(boolean lenient) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean lenient() {
        return false;
    }

    @Override
    public void nativeRead(@NonNegative long position, long address, @NonNegative long size)
            throws IllegalStateException, BufferUnderflowException {
        bytesStore.nativeRead(position, address, size);
    }

    @Override
    public void nativeWrite(long address, @NonNegative long position, @NonNegative long size)
            throws IllegalStateException, BufferOverflowException {
        bytesStore.nativeWrite(address, position, size);
    }

    @Nullable
    @Override
    public BytesStore<?, U> bytesStore() {
        return bytesStore;
    }

    @Override
    public int byteCheckSum()
            throws IORuntimeException {
        @Nullable NativeBytesStore nativeBytesStore = (NativeBytesStore) bytesStore();
        return nativeBytesStore.byteCheckSum(readPosition(), readLimit());
    }

    @Override
    @NotNull
    public Bytes<U> append8bit(@NotNull CharSequence cs)
            throws BufferOverflowException, BufferUnderflowException, IllegalStateException {
        if (cs instanceof BytesStore) {
            return write((BytesStore) cs);
        }
        int length = cs.length();
        long offset = writeOffsetPositionMoved(length);
        long address = bytesStore.addressForWrite(offset);
        @Nullable Memory memory = UnsafeMemory.MEMORY;
        assert memory != null;
        int i = 0;
        for (; i < length - 1; i += 2) {
            char c = cs.charAt(i);
            char c2 = cs.charAt(i + 1);
            memory.writeByte(address + i, (byte) c);
            memory.writeByte(address + i + 1, (byte) c2);
        }
        for (; i < length; i++) {
            char c = cs.charAt(i);
            memory.writeByte(address + i, (byte) c);
        }

        return this;
    }

    @NotNull
    @Override
    public Bytes<U> appendUtf8(char[] chars, @NonNegative int offset, @NonNegative int length)
            throws BufferOverflowException, IllegalArgumentException, IllegalStateException {
        long actualUTF8Length = AppendableUtil.findUtf8Length(chars, offset, length);
        ensureCapacity(writePosition + actualUTF8Length);
        @NotNull BytesStore<?, ?> nbs = this.bytesStore;
        long position = ((NativeBytesStore) nbs).appendUtf8(writePosition(), chars, offset, length);
        writePosition(position);
        return this;
    }

    @Override
    public @NotNull UncheckedNativeBytes<U> append(double d)
            throws BufferOverflowException, IllegalStateException {
        if (!decimaliser.toDecimal(d, this))
            append8bit(Double.toString(d));
        return this;
    }

    @Override
    public @NotNull UncheckedNativeBytes<U> append(float f)
            throws BufferOverflowException, IllegalStateException {
        if (!decimaliser.toDecimal(f, this))
            append8bit(Float.toString(f));
        return this;
    }

    @Override
    public @NotNull Bytes<U> append(int value) throws BufferOverflowException, IllegalArgumentException, IllegalStateException {
        append(value < 0, Math.abs((long) value), 0);
        return this;
    }

    @Override
    public @NotNull Bytes<U> append(long value) throws BufferOverflowException, IllegalStateException {
        if (value == Long.MIN_VALUE)
            write(MIN_VALUE_TEXT);
        else
            append(value < 0, Math.abs(value), 0);
        return this;
    }

    @Override
    public Decimaliser decimaliser() {
        return decimaliser;
    }

    @Override
    public Bytes<U> decimaliser(Decimaliser decimaliser) {
        this.decimaliser = decimaliser;
        return this;
    }

    @SuppressWarnings("deprecation")
    @Override
    public boolean fpAppend0() {
        return append0;
    }

    @SuppressWarnings("deprecation")
    @Override
    public Bytes<U> fpAppend0(boolean append0) {
        this.append0 = append0;
        return this;
    }

    @Override
    public void append(boolean negative, long mantissa, int exponent) {
        ensureCapacity(writePosition() + BytesInternal.digitsForExponent(exponent));
        long length = bytesStore().appendAndReturnLength(writePosition(), negative, mantissa, exponent, fpAppend0());
        writeSkip(length);
    }

    @Override
    public long appendAndReturnLength(long writePosition, boolean negative, long mantissa, int exponent, boolean append0) {
        return bytesStore().appendAndReturnLength(writePosition, negative, mantissa, exponent, append0);
    }

    @Override
    public int lastDecimalPlaces() {
        return lastDecimalPlaces;
    }

    @Override
    public void lastDecimalPlaces(int lastDecimalPlaces) {
        this.lastDecimalPlaces = Math.max(0, lastDecimalPlaces);
    }

    @Override
    public boolean lastNumberHadDigits() {
        return lastNumberHadDigits;
    }

    @Override
    public void lastNumberHadDigits(boolean lastNumberHadDigits) {
        this.lastNumberHadDigits = lastNumberHadDigits;
    }

    @Override
    public long write8bit(@NonNegative long position, @NotNull BytesStore<?, ?> bs) {
        return bytesStore.write8bit(position, bs);
    }

    @Override
    public long write8bit(@NonNegative long position, @NotNull String s, @NonNegative int start, @NonNegative int length) {
        return bytesStore.write8bit(position, s, start, length);
    }

    public Bytes<U> write8bit(@Nullable BytesStore<?, ?> bs) throws BufferOverflowException, IllegalStateException, BufferUnderflowException {
        if (bs == null) {
            BytesInternal.writeStopBitNeg1(this);

        } else {
            final long offset = bs.readPosition();
            final long readRemaining = Math.min(writeRemaining(), bs.readLimit() - offset);
            writeStopBit(readRemaining);
            write(bs, offset, readRemaining);
        }
        return this;
    }

    @Override
    public @NotNull Bytes<U> write8bit(final @NotNull String text, final @NonNegative int start, final @NonNegative int length) {
        requireNonNull(text);
        final long toWriteLength = UnsafeMemory.INSTANCE.stopBitLength(length) + (long) length;
        final long position = writeOffsetPositionMoved(toWriteLength, 0);
        bytesStore.write8bit(position, text, start, length);
        writePosition += toWriteLength;
        return this;
    }

    @Override
    public @NotNull UncheckedRandomDataInput acquireUncheckedInput() {
        return uncheckedRandomDataInput;
    }

    @Override
    public void unmonitor() {
        super.unmonitor();
        Monitorable.unmonitor(bytesStore);
    }

    private final class UncheckedRandomDataInputHolder implements UncheckedRandomDataInput {

        @Override
        public byte readByte(@NonNegative long offset) {
            return bytesStore.readByte(offset);
        }

        @Override
        public short readShort(@NonNegative long offset) {
            return bytesStore.readShort(offset);
        }

        @Override
        public int readInt(@NonNegative long offset) {
            return bytesStore.readInt(offset);
        }

        @Override
        public long readLong(@NonNegative long offset) {
            return bytesStore.readLong(offset);
        }
    }
}
