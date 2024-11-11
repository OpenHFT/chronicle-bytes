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
import net.openhft.chronicle.bytes.util.DecoratedBufferOverflowException;
import net.openhft.chronicle.bytes.util.DecoratedBufferUnderflowException;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.Maths;
import net.openhft.chronicle.core.UnsafeMemory;
import net.openhft.chronicle.core.annotation.NonNegative;
import net.openhft.chronicle.core.annotation.UsedViaReflection;
import net.openhft.chronicle.bytes.internal.UnsafeText;
import net.openhft.chronicle.core.io.*;
import net.openhft.chronicle.core.scoped.ScopedResource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static net.openhft.chronicle.core.util.Ints.requireNonNegative;
import static net.openhft.chronicle.core.util.Longs.requireNonNegative;
import static net.openhft.chronicle.core.util.ObjectUtils.requireNonNull;

/**
 * Abstract representation of Bytes.
 *
 * @param <U> Underlying type
 */
@SuppressWarnings("rawtypes")
public abstract class AbstractBytes<U>
        extends AbstractReferenceCounted
        implements Bytes<U>,
        HasUncheckedRandomDataInput,
        DecimalAppender {
    private static final boolean BYTES_BOUNDS_UNCHECKED = Jvm.getBoolean("bytes.bounds.unchecked", false);

    private static final byte[] MIN_VALUE_TEXT = ("" + Long.MIN_VALUE).getBytes(ISO_8859_1);
    @Deprecated(/* to remove in x.28 */)
    private static final boolean APPEND_0 = Jvm.getBoolean("bytes.append.0", true);
    // used for debugging
    @UsedViaReflection
    private final String name;
    private final UncheckedRandomDataInput uncheckedRandomDataInput = new UncheckedRandomDataInputHolder();
    @NotNull
    protected BytesStore<?, U> bytesStore;
    protected long readPosition;
    protected long writeLimit;
    protected boolean isPresent;
    private long writePosition;
    private int lastDecimalPlaces = 0;
    private boolean lenient = false;
    private boolean lastNumberHadDigits = false;
    private Decimaliser decimaliser = StandardDecimaliser.STANDARD;
    private boolean append0 = APPEND_0;

    AbstractBytes(@NotNull BytesStore<Bytes<U>, U> bytesStore, @NonNegative long writePosition, @NonNegative long writeLimit)
            throws ClosedIllegalStateException, ThreadingIllegalStateException {
        this(bytesStore, writePosition, writeLimit, "");
    }

    AbstractBytes(@NotNull BytesStore<Bytes<U>, U> bytesStore, @NonNegative long writePosition, @NonNegative long writeLimit, String name)
            throws ClosedIllegalStateException, ThreadingIllegalStateException {
        super(bytesStore.isDirectMemory());
        this.bytesStore(bytesStore);
        bytesStore.reserve(this);
        readPosition = bytesStore.readPosition();
        this.uncheckedWritePosition(writePosition);
        this.writeLimit = writeLimit;
        // used for debugging
        this.name = name;
    }

    @Override
    public boolean isDirectMemory() {
        return bytesStore.isDirectMemory();
    }

    @Override
    public boolean canReadDirect(@NonNegative long length) {
        long remaining = writePosition() - readPosition;
        return bytesStore.isDirectMemory() && remaining >= length;
    }

    @Override
    public void move(@NonNegative long from, @NonNegative long to, @NonNegative long length)
            throws BufferUnderflowException, ClosedIllegalStateException, ArithmeticException, ThreadingIllegalStateException {
        if (from < 0 || to < 0) throw new IllegalArgumentException();
        if (length == 0) return;
        assert DISABLE_SINGLE_THREADED_CHECK || threadSafetyCheck(true);
        long start = start();
        ensureCapacity(to + length);
        bytesStore.move(from - start, to - start, length);
    }

    @NotNull
    @Override
    public Bytes<U> compact()
            throws ClosedIllegalStateException, ThreadingIllegalStateException {

        // Asserts that the current method is thread-safe or that single-threaded checks are disabled
        assert DISABLE_SINGLE_THREADED_CHECK || threadSafetyCheck(true);

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
        uncheckedWritePosition(start + readRemaining);

        // Return this Bytes object to allow for method chaining
        return this;
    }

    @Override
    @NotNull
    public Bytes<U> clear()
            throws ClosedIllegalStateException, ThreadingIllegalStateException {
        final long start = start();
        final long capacity = capacity();
        if (readPosition == start && writePosition() == start && writeLimit == capacity)
            return this;
        assert DISABLE_SINGLE_THREADED_CHECK || threadSafetyCheck(true);
        readPosition = start;
        uncheckedWritePosition(start);
        writeLimit = capacity;
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> clearAndPad(@NonNegative long length)
            throws BufferOverflowException, ThreadingIllegalStateException {
        assert DISABLE_SINGLE_THREADED_CHECK || threadSafetyCheck(true);
        final long start = start();
        if ((start + length) > capacity()) {
            throw newBOERange(start, length, "clearAndPad failed. Start: %d + length: %d > capacity: %d", capacity());
        }
        long l = start + length;
        readPosition = l;
        uncheckedWritePosition(l);
        writeLimit = capacity();
        return this;
    }

    @Override
    public long readLimit() {
        return writePosition();
    }

    @Override
    public long writeLimit() {
        return writeLimit;
    }

    @Override
    public @NonNegative long realCapacity() {
        return bytesStore.capacity();
    }

    @Override
    public boolean canWriteDirect(@NonNegative long count) {
        return isDirectMemory() &&
                Math.min(writeLimit, bytesStore.realCapacity())
                        >= count + writePosition();
    }

    @NonNegative
    @Override
    public long capacity() {
        return bytesStore.capacity();
    }

    @Nullable
    @Override
    public U underlyingObject() {
        return bytesStore.underlyingObject();
    }

    @Override
    public int length() {
        return (int) Math.min(Integer.MAX_VALUE, readRemaining());
    }

    @NonNegative
    @Override
    public long start() {
        return bytesStore.start();
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
            throws BufferOverflowException, ClosedIllegalStateException {
        writeCheckOffset(offset, Integer.BYTES);
        return bytesStore.compareAndSwapInt(offset, expected, value);
    }

    @Override
    public void testAndSetInt(@NonNegative long offset, int expected, int value)
            throws BufferOverflowException, ClosedIllegalStateException {
        writeCheckOffset(offset, Integer.BYTES);
        bytesStore.testAndSetInt(offset, expected, value);
    }

    @Override
    public boolean compareAndSwapLong(@NonNegative long offset, long expected, long value)
            throws BufferOverflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        writeCheckOffset(offset, Long.BYTES);
        return bytesStore.compareAndSwapLong(offset, expected, value);
    }

    /**
     * Appends the string representation of the given double value to the bytes.
     * First, it tries to convert the double value using the Decimalizer instance. If that fails,
     * it falls back to converting the double to a String and appending it.
     *
     * @param d the double value to append.
     * @return this Bytes instance.
     * @throws BufferOverflowException        If there is not enough space to write the double.
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way.
     */
    @Override
    public @NotNull AbstractBytes<U> append(double d)
            throws BufferOverflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        if (!decimaliser().toDecimal(d, this))
            append8bit(Double.toString(d));
        return this;
    }

    @NotNull
    private AbstractBytes<U> appendX23(double d) throws ClosedIllegalStateException, ThreadingIllegalStateException {
        boolean fits = canWriteDirect(32);
        if (fits) {
            long address = addressForWrite(writePosition());
            long address2 = UnsafeText.appendDouble(address, d);
            writeSkip(address2 - address);
            return this;
        } else {
            try (ScopedResource<Bytes<?>> stlBytes = BytesInternal.acquireBytesScoped()) {
                Bytes<?> bytes = stlBytes.get();
                bytes.append(d);
                append(bytes);
            }
        }
        return this;
    }

    /**
     * Appends the string representation of the given float value to the bytes.
     * First, it tries to convert the float value using the Decimalizer instance. If that fails,
     * it falls back to converting the float to a String and appending it.
     *
     * @param f the float value to append.
     * @return this Bytes instance.
     * @throws BufferOverflowException        If there is not enough space to write the float.
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way.
     */
    @Override
    public @NotNull Bytes<U> append(float f)
            throws BufferOverflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        if (!decimaliser().toDecimal(f, this))
            append8bit(Float.toString(f));
        return this;
    }

    @Override
    public @NotNull Bytes<U> append(int value) throws BufferOverflowException, IllegalArgumentException, ClosedIllegalStateException, ThreadingIllegalStateException {
        appendLong(value);
        return this;
    }

    @Override
    public @NotNull Bytes<U> append(long value) throws BufferOverflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        if (value == Long.MIN_VALUE) {
            write(MIN_VALUE_TEXT);
        } else {
            appendLong(value);
        }
        return this;
    }

    private void appendLong(long value) throws ClosedIllegalStateException, ThreadingIllegalStateException {
        ensureCapacity(writePosition() + 21);
        long length = bytesStore().appendAndReturnLength(writePosition(), value < 0, Math.abs(value), 0, false);
        writeSkip(length);
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

    /**
     * Appends a numeric value in decimal form with the specified mantissa and exponent,
     * handling negative values and decimal point placement.
     *
     * @param negative indicates if the number is negative.
     * @param mantissa the mantissa of the number to append.
     * @param exponent the exponent indicating the position of the decimal point.
     */
    @Override
    public void append(boolean negative, long mantissa, int exponent) throws ClosedIllegalStateException, ThreadingIllegalStateException {
        ensureCapacity(writePosition() + BytesInternal.digitsForExponent(exponent));
        long length = bytesStore().appendAndReturnLength(writePosition(), negative, mantissa, exponent, fpAppend0());
        writeSkip(length);
    }

    @Override
    public long appendAndReturnLength(long writePosition, boolean negative, long mantissa, int exponent, boolean append0) {
        return bytesStore().appendAndReturnLength(writePosition, negative, mantissa, exponent, append0);
    }

    @Override
    public @NotNull Bytes<U> append(double d, int decimalPlaces) throws BufferOverflowException, IllegalArgumentException, ClosedIllegalStateException, ArithmeticException {
        BytesInternal.append(this, d, decimalPlaces);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> readPosition(@NonNegative long position)
            throws BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        if (this.readPosition == position)
            return this;

        assert DISABLE_SINGLE_THREADED_CHECK || threadSafetyCheck(true);
        if (position < start()) {
            throw new DecoratedBufferUnderflowException(String.format("readPosition failed. Position: %d < start: %d", position, start()));
        }
        if (position > readLimit()) {
            throw new DecoratedBufferUnderflowException(
                    String.format("readPosition failed. Position: %d > readLimit: %d", position, readLimit()));
        }
        this.readPosition = position;
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> readLimit(@NonNegative long limit)
            throws BufferUnderflowException, ThreadingIllegalStateException {
        if (writePosition() == limit)
            return this;

        assert DISABLE_SINGLE_THREADED_CHECK || threadSafetyCheck(true);
        if (limit < start())
            throw limitLessThanStart(limit);

        if (limit > writeLimit())
            throw limitGreaterThanWriteLimit(limit);

        uncheckedWritePosition(limit);
        return this;
    }

    private DecoratedBufferUnderflowException limitGreaterThanWriteLimit(long limit) {
        return new DecoratedBufferUnderflowException(String.format("readLimit failed. Limit: %d > writeLimit: %d", limit, writeLimit()));
    }

    private DecoratedBufferUnderflowException limitLessThanStart(long limit) {
        return new DecoratedBufferUnderflowException(String.format("readLimit failed. Limit: %d < start: %d", limit, start()));
    }

    @NotNull
    @Override
    public Bytes<U> writePosition(@NonNegative long position)
            throws BufferOverflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        if (writePosition() == position)
            return this;

        if (position > writeLimit())
            throw writePositionTooLarge(position);

        if (position < start())
            throw writePositionTooSmall(position);

        if (position < readPosition())
            this.readPosition = position;
        if (isElastic())
            ensureCapacity(position);
        uncheckedWritePosition(position);
        return this;
    }

    @NotNull
    private DecoratedBufferOverflowException writePositionTooSmall(@NonNegative long position) {
        return new DecoratedBufferOverflowException(String.format("writePosition failed. Position: %d < start: %d", position, start()));
    }

    private DecoratedBufferOverflowException writePositionTooLarge(@NonNegative long position) {
        return new DecoratedBufferOverflowException(
                String.format("writePosition failed. Position: %d > writeLimit: %d", position, writeLimit()));
    }

    @NotNull
    @Override
    public Bytes<U> readSkip(long bytesToSkip)
            throws BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        assert DISABLE_SINGLE_THREADED_CHECK || threadSafetyCheck(true);
        if (lenient) {
            bytesToSkip = Math.min(bytesToSkip, readRemaining());
        }
        readOffsetPositionMoved(bytesToSkip);
        return this;
    }

    @Override
    public long readPositionForHeader(boolean skipPadding) throws ClosedIllegalStateException, ThreadingIllegalStateException {
        long position = readPosition();
        if (skipPadding)
            return readSkip(BytesUtil.padOffset(position)).readPosition();
        return position;
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
    public Bytes<U> writeSkip(long bytesToSkip)
            throws BufferOverflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        final long writePos = writePosition();
        assert DISABLE_SINGLE_THREADED_CHECK || threadSafetyCheck(true);
        writeCheckOffset(writePos, bytesToSkip);
        uncheckedWritePosition(writePos + bytesToSkip);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> writeLimit(@NonNegative long limit)
            throws BufferOverflowException {
        if (this.writeLimit == limit)
            return this;

        if (limit < start()) {
            throw writeLimitTooSmall(limit);
        }
        long capacity = capacity();
        if (limit > capacity) {
            throw writeLimitTooBig(limit, capacity);
        }
        this.writeLimit = limit;
        return this;
    }

    @NotNull
    private DecoratedBufferOverflowException writeLimitTooBig(@NonNegative long limit, @NonNegative long capacity) {
        return new DecoratedBufferOverflowException(String.format("writeLimit failed. Limit: %d > capacity: %d", limit, capacity));
    }

    @NotNull
    private DecoratedBufferOverflowException writeLimitTooSmall(@NonNegative long limit) {
        return new DecoratedBufferOverflowException(String.format("writeLimit failed. Limit: %d < start: %d", limit, start()));
    }

    @Override
    protected void performRelease() throws ClosedIllegalStateException {
        try {
            this.bytesStore.release(this);
        } catch (ClosedIllegalStateException e) {
            Jvm.warn().on(getClass(), e);
        }
    }

    @Override
    public int readUnsignedByte()
            throws ClosedIllegalStateException, ThreadingIllegalStateException {
        try {
            long offset = readOffsetPositionMoved(Byte.BYTES);
            return bytesStore.readUnsignedByte(offset);

        } catch (BufferUnderflowException e) {
            return -1;
        }
    }

    @Override
    public int readUnsignedByte(@NonNegative long offset)
            throws BufferUnderflowException, ClosedIllegalStateException {
        return readByte(offset) & 0xFF;
    }

    @Override
    public int uncheckedReadUnsignedByte() {
        try {
            int unsignedByte = bytesStore.readUnsignedByte(readPosition);
            readPosition++;
            return unsignedByte;
        } catch (BufferUnderflowException | ClosedIllegalStateException e) {
            return -1;
        }
    }

    @Override
    public byte readByte()
            throws ClosedIllegalStateException, ThreadingIllegalStateException {
        try {
            long offset = readOffsetPositionMoved(Byte.BYTES);
            return bytesStore.readByte(offset);

        } catch (BufferUnderflowException e) {
            if (lenient) {
                return 0;
            }
            throw e;
        }
    }

    @Override
    public int peekUnsignedByte()
            throws ClosedIllegalStateException, ThreadingIllegalStateException {
        try {
            return readPosition >= writePosition() ? -1 : bytesStore.readUnsignedByte(readPosition);
        } catch (BufferUnderflowException e) {
            return -1;
        }
    }

    @Override
    public short readShort()
            throws BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        try {
            long offset = readOffsetPositionMoved(Short.BYTES);
            return bytesStore.readShort(offset);
        } catch (BufferUnderflowException e) {
            if (lenient) {
                return 0;
            }
            throw e;
        }
    }

    @Override
    public int readInt()
            throws BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        try {
            long offset = readOffsetPositionMoved(Integer.BYTES);
            return bytesStore.readInt(offset);
        } catch (BufferUnderflowException e) {
            if (lenient) {
                return 0;
            }
            throw e;
        }
    }

    @Override
    public byte readVolatileByte(@NonNegative long offset)
            throws BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        readCheckOffset(offset, Byte.BYTES, true);
        return bytesStore.readVolatileByte(offset);
    }

    @Override
    public short readVolatileShort(@NonNegative long offset)
            throws BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        readCheckOffset(offset, Short.BYTES, true);
        return bytesStore.readVolatileShort(offset);
    }

    @Override
    public int readVolatileInt(@NonNegative long offset)
            throws BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        readCheckOffset(offset, Integer.BYTES, true);
        return bytesStore.readVolatileInt(offset);
    }

    @Override
    public long readVolatileLong(@NonNegative long offset)
            throws BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        readCheckOffset(offset, Long.BYTES, true);
        return bytesStore.readVolatileLong(offset);
    }

    @Override
    public long readLong()
            throws BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        try {
            long offset = readOffsetPositionMoved(Long.BYTES);
            return bytesStore.readLong(offset);
        } catch (BufferUnderflowException e) {
            if (lenient) {
                return 0;
            }
            throw e;
        }
    }

    @Override
    public float readFloat()
            throws BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        try {
            long offset = readOffsetPositionMoved(Float.BYTES);
            return bytesStore.readFloat(offset);
        } catch (BufferUnderflowException e) {
            if (lenient) {
                return 0;
            }
            throw e;
        }
    }

    @Override
    public double readDouble()
            throws BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        try {
            long offset = readOffsetPositionMoved(Double.BYTES);
            return bytesStore.readDouble(offset);
        } catch (BufferUnderflowException e) {
            if (lenient) {
                return 0;
            }
            throw e;
        }
    }

    @Override
    public int readVolatileInt()
            throws BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        try {
            long offset = readOffsetPositionMoved(Integer.BYTES);
            return bytesStore.readVolatileInt(offset);
        } catch (BufferUnderflowException e) {
            if (lenient) {
                return 0;
            }
            throw e;
        }
    }

    @Override
    public long readVolatileLong()
            throws BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        try {
            long offset = readOffsetPositionMoved(Long.BYTES);
            return bytesStore.readVolatileLong(offset);
        } catch (BufferUnderflowException e) {
            if (lenient) {
                return 0;
            }
            throw e;
        }
    }

    protected long readOffsetPositionMoved(@NonNegative long adding)
            throws BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        long offset = readPosition;
        assert DISABLE_SINGLE_THREADED_CHECK || threadSafetyCheck(true);
        readCheckOffset(readPosition, Math.toIntExact(adding), false);
        readPosition += adding;
        assert readPosition <= readLimit();
        return offset;
    }

    @NotNull
    @Override
    public Bytes<U> writeByte(@NonNegative long offset, byte i)
            throws BufferOverflowException, ClosedIllegalStateException {
        writeCheckOffset(offset, Byte.BYTES);
        bytesStore.writeByte(offset, i);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> writeShort(@NonNegative long offset, short i)
            throws BufferOverflowException, ClosedIllegalStateException {
        writeCheckOffset(offset, Short.BYTES);
        bytesStore.writeShort(offset, i);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> writeInt(@NonNegative long offset, int i)
            throws BufferOverflowException, ClosedIllegalStateException {
        writeCheckOffset(offset, Integer.BYTES);
        bytesStore.writeInt(offset, i);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> writeOrderedInt(@NonNegative long offset, int i)
            throws BufferOverflowException, ClosedIllegalStateException {
        writeCheckOffset(offset, Integer.BYTES);
        bytesStore.writeOrderedInt(offset, i);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> writeLong(@NonNegative long offset, long i)
            throws BufferOverflowException, ClosedIllegalStateException {
        writeCheckOffset(offset, Long.BYTES);
        bytesStore.writeLong(offset, i);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> writeOrderedLong(@NonNegative long offset, long i)
            throws BufferOverflowException, ClosedIllegalStateException {
        writeCheckOffset(offset, Long.BYTES);
        bytesStore.writeOrderedLong(offset, i);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> writeFloat(@NonNegative long offset, float d)
            throws BufferOverflowException, ClosedIllegalStateException {
        writeCheckOffset(offset, Float.BYTES);
        bytesStore.writeFloat(offset, d);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> writeDouble(@NonNegative long offset, double d)
            throws BufferOverflowException, ClosedIllegalStateException {
        writeCheckOffset(offset, Double.BYTES);
        bytesStore.writeDouble(offset, d);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> writeVolatileByte(@NonNegative long offset, byte i8)
            throws BufferOverflowException, ClosedIllegalStateException {
        writeCheckOffset(offset, Byte.BYTES);
        bytesStore.writeVolatileByte(offset, i8);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> writeVolatileShort(@NonNegative long offset, short i16)
            throws BufferOverflowException, ClosedIllegalStateException {
        writeCheckOffset(offset, Short.BYTES);
        bytesStore.writeVolatileShort(offset, i16);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> writeVolatileInt(@NonNegative long offset, int i32)
            throws BufferOverflowException, ClosedIllegalStateException {
        writeCheckOffset(offset, Integer.BYTES);
        bytesStore.writeVolatileInt(offset, i32);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> writeVolatileLong(@NonNegative long offset, long i64)
            throws BufferOverflowException, ClosedIllegalStateException {
        writeCheckOffset(offset, Long.BYTES);
        bytesStore.writeVolatileLong(offset, i64);
        return this;
    }

    public Bytes<U> write(@NotNull BytesStore<?, ?> bytes)
            throws BufferOverflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        assert bytes != this : "you should not write to yourself !";
        requireNonNull(bytes);

        if (bytes.readRemaining() > writeRemaining())
            throw new BufferOverflowException();
        return write(bytes, bytes.readPosition(), bytes.readRemaining());
    }

    @Override
    @NotNull
    public Bytes<U> write(@NonNegative long offsetInRDO,
                          final byte[] byteArray,
                          @NonNegative int offset,
                          @NonNegative final int length) throws BufferOverflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        requireNonNegative(offsetInRDO);
        requireNonNull(byteArray);
        requireNonNegative(offset);
        requireNonNegative(length);
        long remaining = length;
        while (remaining > 0) {
            int copy = (int) Math.min(remaining, safeCopySize()); // copy 64 KB at a time.
            writeCheckOffset(offsetInRDO, copy);
            bytesStore.write(offsetInRDO, byteArray, offset, copy);
            offsetInRDO += copy;
            offset += copy;
            remaining -= copy;
        }
        return this;
    }

    @Override
    public void write(@NonNegative long offsetInRDO, @NotNull ByteBuffer bytes, @NonNegative int offset, @NonNegative int length)
            throws BufferOverflowException, ClosedIllegalStateException {
        requireNonNull(bytes);
        if (this.bytesStore.inside(offsetInRDO, length)) {
            writeCheckOffset(offsetInRDO, length);
            bytesStore.write(offsetInRDO, bytes, offset, length);
        } else if (bytes.remaining() <= writeRemaining()) {
            // bounds check
            bytes.get(offset + length - 1);

            int i = 0;
            if (bytes.order() == ByteOrder.nativeOrder()) {
                for (; i < length - 7; i += Long.BYTES)
                    writeLong(offsetInRDO + i, bytes.getLong(offset + i));
            } else {
                for (; i < length - 7; i += Long.BYTES)
                    writeLong(offsetInRDO + i, Long.reverseBytes(bytes.getLong(offset + i)));
            }
            for (; i < length; i++)
                writeByte(offsetInRDO + i, bytes.get(offset + i));
        } else {
            throw new DecoratedBufferOverflowException("Unable to write " + length + " with " + writeRemaining() + " remaining");
        }
    }

    @Override
    @NotNull
    public Bytes<U> write(@NonNegative long writeOffset, @NotNull RandomDataInput bytes, @NonNegative long readOffset, @NonNegative long length)
            throws BufferOverflowException, BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException {

        requireNonNegative(writeOffset);
        ReferenceCountedUtil.throwExceptionIfReleased(bytes);
        requireNonNegative(readOffset);
        requireNonNegative(length);
        throwExceptionIfReleased();
        long remaining = length;
        while (remaining > 0) {
            int copy = (int) Math.min(remaining, safeCopySize()); // copy 64 KB at a time.
            writeCheckOffset(writeOffset, copy);
            bytesStore.write(writeOffset, bytes, readOffset, copy);
            writeOffset += copy;
            readOffset += copy;
            remaining -= copy;
        }
        return this;
    }

    @Override
    public @NotNull Bytes<U> write8bit(@NotNull String text, @NonNegative int start, @NonNegative int length)
            throws BufferOverflowException, IndexOutOfBoundsException, ArithmeticException, ClosedIllegalStateException, BufferUnderflowException, ThreadingIllegalStateException {
        requireNonNull(text); // This needs to be checked or else the JVM might crash
        final long toWriteLength = UnsafeMemory.INSTANCE.stopBitLength(length) + (long) length;
        final long position = writeOffsetPositionMoved(toWriteLength, 0);
        bytesStore.write8bit(position, text, start, length);
        uncheckedWritePosition(writePosition() + toWriteLength);
        return this;
    }

    public @NotNull Bytes<U> write8bit(@Nullable BytesStore<?, ?> bs)
            throws BufferOverflowException, ClosedIllegalStateException, BufferUnderflowException, ThreadingIllegalStateException {
        if (bs == null) {
            BytesInternal.writeStopBitNeg1(this);
            return this;
        }
        long readRemaining = bs.readRemaining();
        long toWriteLength = UnsafeMemory.INSTANCE.stopBitLength(readRemaining) + readRemaining;
        long position = writeOffsetPositionMoved(toWriteLength, 0);
        bytesStore.write8bit(position, bs);
        uncheckedWritePosition(writePosition() + toWriteLength);
        return this;
    }

    @Override
    public long write8bit(@NonNegative long position, @NotNull BytesStore<?, ?> bs)
            throws ClosedIllegalStateException, ThreadingIllegalStateException {
        if (position < start()) {
            if (position < 0)
                throw new IllegalArgumentException();
            throw new BufferUnderflowException();
        }
        if (position + bs.readRemaining() > writeLimit)
            throw new BufferOverflowException();
        ensureCapacity(position + bs.readRemaining());
        return bytesStore.write8bit(position, bs);
    }

    @Override
    public long write8bit(@NonNegative long position, @NotNull String s, @NonNegative int start, @NonNegative int length)
            throws ClosedIllegalStateException, ThreadingIllegalStateException {
        if (position < start()) {
            if (position < 0)
                throw new IllegalArgumentException();
            throw new BufferUnderflowException();
        }
        if (position + length > writeLimit)
            throw new BufferOverflowException();
        ensureCapacity(position + length);
        return bytesStore.write8bit(position, s, start, length);
    }

    protected void writeCheckOffset(@NonNegative long offset, @NonNegative long adding)
            throws BufferOverflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        if (BYTES_BOUNDS_UNCHECKED)
            return;
        writeCheckOffset0(offset, adding);
    }

    private void writeCheckOffset0(@NonNegative long offset, @NonNegative long adding)
            throws DecoratedBufferOverflowException {
        final long start = start();
        if (offset < start || offset + adding < start) {
            throw newBOELower(offset);
        }
        if ((offset + adding) > writeLimit()) {
            throw newBOERange(offset, adding, "writeCheckOffset failed. Offset: %d + adding %d> writeLimit: %d", writeLimit());
        }
    }

    @NotNull
    private DecoratedBufferOverflowException newBOERange(@NonNegative long offset, long adding, String msg, @NonNegative long limit) {
        return new DecoratedBufferOverflowException(
                String.format(msg, offset, adding, limit));
    }

    @NotNull
    private DecoratedBufferOverflowException newBOELower(@NonNegative long offset) {
        if (offset < 0)
            throw new IllegalArgumentException("offset: " + offset);
        return new DecoratedBufferOverflowException(String.format("writeCheckOffset failed. Offset: %d < start: %d", offset, start()));
    }

    @Override
    public byte readByte(@NonNegative long offset)
            throws BufferUnderflowException, ClosedIllegalStateException {
        readCheckOffset(offset, Byte.BYTES, true);
        return bytesStore.readByte(offset);
    }

    @Override
    public int peekUnsignedByte(@NonNegative long offset)
            throws ClosedIllegalStateException, ThreadingIllegalStateException {
        return offset < start() || readLimit() <= offset ? -1 : bytesStore.peekUnsignedByte(offset);
    }

    @Override
    public short readShort(@NonNegative long offset)
            throws BufferUnderflowException, ClosedIllegalStateException {
        readCheckOffset(offset, Short.BYTES, true);
        return bytesStore.readShort(offset);
    }

    @Override
    public int readInt(@NonNegative long offset)
            throws BufferUnderflowException, ClosedIllegalStateException {
        readCheckOffset(offset, Integer.BYTES, true);
        return bytesStore.readInt(offset);
    }

    @Override
    public long readLong(@NonNegative long offset)
            throws BufferUnderflowException, ClosedIllegalStateException {
        readCheckOffset(offset, Long.BYTES, true);
        return bytesStore.readLong(offset);
    }

    @Override
    public float readFloat(@NonNegative long offset)
            throws BufferUnderflowException, ClosedIllegalStateException {
        readCheckOffset(offset, Float.BYTES, true);
        return bytesStore.readFloat(offset);
    }

    @Override
    public double readDouble(@NonNegative long offset)
            throws BufferUnderflowException, ClosedIllegalStateException {
        readCheckOffset(offset, Double.BYTES, true);
        return bytesStore.readDouble(offset);
    }

    protected void readCheckOffset(@NonNegative long offset, long adding, boolean given)
            throws BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        if (BYTES_BOUNDS_UNCHECKED)
            return;
        readCheckOffset0(offset, adding, given);
    }

    private void readCheckOffset0(@NonNegative long offset, long adding, boolean given)
            throws DecoratedBufferUnderflowException {
        if (offset < start()) {
            throw newBOEReadLower(offset);
        }
        long limit0 = given ? writeLimit() : readLimit();
        if ((offset + adding) > limit0) {
            throw newBOEReadUpper(offset, adding, given);
        }
    }

    @NotNull
    private DecoratedBufferUnderflowException newBOEReadUpper(@NonNegative long offset, long adding, boolean given) {
        if (offset < 0)
            throw new IllegalArgumentException("offset: " + offset);
        long limit2 = given ? writeLimit() : readLimit();
        return new DecoratedBufferUnderflowException(String
                .format("readCheckOffset0 failed. Offset: %d + adding: %d > limit: %d (given: %s)", offset, adding, limit2, given));
    }

    @NotNull
    private DecoratedBufferUnderflowException newBOEReadLower(@NonNegative long offset) {
        if (offset < 0)
            throw new IllegalArgumentException("offset: " + offset);
        return new DecoratedBufferUnderflowException(String.format("readCheckOffset0 failed. Offset: %d < start: %d", offset, start()));
    }

    void prewriteCheckOffset(@NonNegative long offset, long subtracting)
            throws BufferOverflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        if (BYTES_BOUNDS_UNCHECKED)
            return;
        prewriteCheckOffset0(offset, subtracting);
    }

    private void prewriteCheckOffset0(@NonNegative long offset, long subtracting)
            throws BufferOverflowException {
        if ((offset - subtracting) < start()) {
            throw newBOERange(offset, subtracting, "prewriteCheckOffset0 failed. Offset: %d - subtracting: %d < start: %d", start());
        }
        long limit0 = readLimit();
        if (offset > limit0) {
            throw new DecoratedBufferOverflowException(
                    String.format("prewriteCheckOffset0 failed. Offset: %d > readLimit: %d", offset, limit0));
        }
    }

    @NotNull
    @Override
    public Bytes<U> writeByte(byte i8)
            throws BufferOverflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        long offset = writeOffsetPositionMoved(Byte.BYTES);
        bytesStore.writeByte(offset, i8);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> prewrite(final byte[] bytes)
            throws BufferOverflowException, ClosedIllegalStateException {
        long offset = prewriteOffsetPositionMoved(bytes.length);
        bytesStore.write(offset, bytes);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> prewrite(@NotNull BytesStore<?, ?> bytes)
            throws BufferOverflowException, ClosedIllegalStateException {
        long offset = prewriteOffsetPositionMoved(bytes.readRemaining());
        bytesStore.write(offset, bytes);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> prewriteByte(byte i8)
            throws BufferOverflowException, ClosedIllegalStateException {
        long offset = prewriteOffsetPositionMoved(Byte.BYTES);
        bytesStore.writeByte(offset, i8);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> prewriteInt(int i)
            throws BufferOverflowException, ClosedIllegalStateException {
        long offset = prewriteOffsetPositionMoved(Integer.BYTES);
        bytesStore.writeInt(offset, i);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> prewriteShort(short i)
            throws BufferOverflowException, ClosedIllegalStateException {
        long offset = prewriteOffsetPositionMoved(Short.BYTES);
        bytesStore.writeShort(offset, i);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> prewriteLong(long l)
            throws BufferOverflowException, ClosedIllegalStateException {
        long offset = prewriteOffsetPositionMoved(Long.BYTES);
        bytesStore.writeLong(offset, l);
        return this;
    }

    protected final long writeOffsetPositionMoved(@NonNegative long adding)
            throws BufferOverflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        return writeOffsetPositionMoved(adding, adding);
    }

    protected long writeOffsetPositionMoved(@NonNegative long adding, @NonNegative long advance)
            throws BufferOverflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        long oldPosition = writePosition();
        assert DISABLE_SINGLE_THREADED_CHECK || threadSafetyCheck(true);
        writeCheckOffset(oldPosition, adding);
        uncheckedWritePosition(writePosition() + advance);
        return oldPosition;
    }

    protected void uncheckedWritePosition(@NonNegative long writePosition) {
        this.writePosition = writePosition;
    }

    protected long prewriteOffsetPositionMoved(@NonNegative long subtracting)
            throws BufferOverflowException {
        prewriteCheckOffset(readPosition, subtracting);
        readPosition -= subtracting;
        return readPosition;
    }

    @NotNull
    @Override
    public Bytes<U> writeShort(short i16)
            throws BufferOverflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        long offset = writeOffsetPositionMoved(Short.BYTES);
        bytesStore.writeShort(offset, i16);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> writeInt(int i)
            throws BufferOverflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        long offset = writeOffsetPositionMoved(Integer.BYTES);
        bytesStore.writeInt(offset, i);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> writeIntAdv(int i, @NonNegative int advance)
            throws BufferOverflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        if (advance < 0) throw new IllegalArgumentException();
        long offset = writeOffsetPositionMoved(Integer.BYTES, advance);
        bytesStore.writeInt(offset, i);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> writeLong(long i64)
            throws BufferOverflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        long offset = writeOffsetPositionMoved(Long.BYTES);
        bytesStore.writeLong(offset, i64);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> writeLongAdv(long i64, @NonNegative int advance)
            throws BufferOverflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        if (advance < 0) throw new IllegalArgumentException();
        long offset = writeOffsetPositionMoved(Long.BYTES, advance);
        bytesStore.writeLong(offset, i64);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> writeFloat(float f)
            throws BufferOverflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        long offset = writeOffsetPositionMoved(Float.BYTES);
        bytesStore.writeFloat(offset, f);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> writeDouble(double d)
            throws BufferOverflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        long offset = writeOffsetPositionMoved(Double.BYTES);
        bytesStore.writeDouble(offset, d);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> writeDoubleAndInt(double d, int i)
            throws BufferOverflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        long offset = writeOffsetPositionMoved(Double.BYTES + Integer.BYTES);
        bytesStore.writeDouble(offset, d);
        bytesStore.writeInt(offset + 8, i);
        return this;
    }

    @Override
    public int read(byte[] bytes, @NonNegative int off, @NonNegative int len)
            throws BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        requireNonNull(bytes);
        long remaining = readRemaining();
        if (remaining <= 0)
            return -1;
        final int totalToCopy = (int) Math.min(len, remaining);
        int remainingToCopy = totalToCopy;
        int currentOffset = off;
        while (remainingToCopy > 0) {
            int currentBatchSize = Math.min(remainingToCopy, safeCopySize());
            long offsetInRDO = readOffsetPositionMoved(currentBatchSize);
            bytesStore.read(offsetInRDO, bytes, currentOffset, currentBatchSize);
            currentOffset += currentBatchSize;
            remainingToCopy -= currentBatchSize;
        }
        return totalToCopy;
    }

    @Override
    public long read(@NonNegative long offsetInRDI, byte[] bytes, @NonNegative int offset, @NonNegative int length)
            throws ClosedIllegalStateException {
        int len = Maths.toUInt31(Math.min(length, requireNonNegative(readLimit() - offsetInRDI)));
        return bytesStore.read(offsetInRDI, bytes, offset, len);
    }

    @NotNull
    @Override
    public Bytes<U> write(final byte[] byteArray,
                          @NonNegative final int offset,
                          @NonNegative final int length)
            throws BufferOverflowException, ClosedIllegalStateException, IllegalArgumentException, ThreadingIllegalStateException {
        requireNonNegative(offset);
        requireNonNegative(length);
        if ((length + offset) > byteArray.length) {
            throw new DecoratedBufferOverflowException("bytes.length=" + byteArray.length + ", " + "length=" + length + ", offset=" + offset);
        }
        if (length > writeRemaining()) {
            throw new DecoratedBufferOverflowException(
                    String.format("write failed. Length: %d > writeRemaining: %d", length, writeRemaining()));
        }
        ensureCapacity(writePosition() + length);
        int remaining = length;
        int pos = offset;
        while (remaining > 0) {
            int copy = Math.min(remaining, safeCopySize()); // copy 64 KB at a time.
            long offsetInRDO = writeOffsetPositionMoved(copy);
            bytesStore.write(offsetInRDO, byteArray, pos, copy);
            pos += copy;
            remaining -= copy;
        }
        return this;
    }

    protected int safeCopySize() {
        return 64 << 10;
    }

    @NotNull
    @Override
    public Bytes<U> writeSome(@NotNull ByteBuffer buffer)
            throws BufferOverflowException, ClosedIllegalStateException, BufferUnderflowException, ThreadingIllegalStateException {
        int length = (int) Math.min(buffer.remaining(), writeRemaining());
        ensureCapacity(writePosition() + length);
        bytesStore.write(writePosition(), buffer, buffer.position(), length);
        uncheckedWritePosition(writePosition() + length);
        buffer.position(buffer.position() + length);
        return this;
    }

    @Override
    public @NotNull Bytes<U> writeBoolean(boolean flag) throws BufferOverflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        return writeByte(flag ? (byte) 'Y' : (byte) 'N');
    }

    @NotNull
    @Override
    public Bytes<U> writeOrderedInt(int i)
            throws BufferOverflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        long offset = writeOffsetPositionMoved(Integer.BYTES);
        bytesStore.writeOrderedInt(offset, i);
        return this;
    }

    @NotNull
    @Override
    public Bytes<U> writeOrderedLong(long i)
            throws BufferOverflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        long offset = writeOffsetPositionMoved(Long.BYTES);
        bytesStore.writeOrderedLong(offset, i);
        return this;
    }

    @Override
    public long addressForRead(@NonNegative long offset)
            throws BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        readCheckOffset(offset, 0, true);
        return bytesStore.addressForRead(offset);
    }

    @Override
    public long addressForWrite(@NonNegative long offset)
            throws BufferOverflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        writeCheckOffset(offset, 0);
        return bytesStore.addressForWrite(offset);
    }

    @Override
    public long addressForWritePosition()
            throws BufferOverflowException, ClosedIllegalStateException {
        final long offset = writePosition();
        writeCheckOffset(offset, isElastic() ? Math.min(64, capacity() - offset) : 0);
        return bytesStore.addressForWrite(offset);
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
        // Reserving prevents illegal access to this Bytes object if released by another thread
        final ReferenceOwner toStringOwner = ReferenceOwner.temporary("toString");
        reserve(toStringOwner);
        try {
            return BytesInternal.toString(this);
        } catch (Exception e) {
            return e.toString();
        } finally {
            release(toStringOwner);
        }
    }

    @Override
    public void nativeRead(@NonNegative long position, long address, @NonNegative long size)
            throws BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        ensureCapacity(position + size);
        bytesStore.nativeRead(position, address, size);
    }

    @Override
    public void nativeWrite(long address, @NonNegative long position, @NonNegative long size)
            throws BufferOverflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        ensureCapacity(position + size);
        bytesStore.nativeWrite(address, position, size);
    }

    @NotNull
    @Override
    public BytesStore<?, U> bytesStore() {
        return bytesStore;
    }

    protected void bytesStore(BytesStore<?, U> bytesStore) {
        this.bytesStore = BytesInternal.failIfBytesOnBytes(bytesStore);
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
    public boolean isElastic() {
        return false;
    }

    @Override
    public void lenient(boolean lenient) {
        this.lenient = lenient;
    }

    @Override
    public boolean lenient() {
        return lenient;
    }

    @Override
    public int byteCheckSum()
            throws IORuntimeException, BufferUnderflowException, ClosedIllegalStateException {
        return byteCheckSum(readPosition(), readLimit());
    }

    @Override
    public int byteCheckSum(@NonNegative long start, @NonNegative long end)
            throws BufferUnderflowException, ClosedIllegalStateException {
        if (end < Integer.MAX_VALUE && isDirectMemory())
            return byteCheckSum((int) start, (int) end);
        return Bytes.super.byteCheckSum(start, end);
    }

    @Override
    public boolean startsWith(@Nullable final BytesStore<?, ?> bytesStore) throws ClosedIllegalStateException {
        // This class implements HasUncheckedRandomDataInput, so we could potentially use
        // the unchecked version of startsWith
        return bytesStore != null && BytesInternal.startsWithUnchecked(this, bytesStore);
    }

    @Override
    public @NotNull UncheckedRandomDataInput acquireUncheckedInput() {
        return uncheckedRandomDataInput;
    }

    /**
     * Returns the bytes sum between the specified indexes; start (inclusive) and end (exclusive).
     *
     * @param start the index of the first byte to sum
     * @param end   the index of the last byte to sum
     * @return unsigned bytes sum
     * @throws BufferUnderflowException    If the specified indexes are outside the limits of the BytesStore
     * @throws ClosedIllegalStateException If the resource has been released or closed.
     */
    public int byteCheckSum(@NonNegative int start, @NonNegative int end)
            throws BufferUnderflowException, ClosedIllegalStateException {
        int sum = 0;
        for (int i = start; i < end; i++) {
            sum += readByte(i);
        }
        return sum & 0xFF;
    }

    protected boolean isImmutableEmptyByteStore() {
        return bytesStore.capacity() == 0;
    }

    @Override
    public int copyTo(byte[] bytes) throws BufferUnderflowException, ClosedIllegalStateException {
        throwExceptionIfReleased();
        return (int) read(readPosition(), bytes, 0, bytes.length);
    }

    @Override
    public void unmonitor() {
        super.unmonitor();
        Monitorable.unmonitor(bytesStore);
    }

    static final class ReportUnoptimised {
        static {
            Jvm.reportUnoptimised();
        }

        private ReportUnoptimised() {
        }

        @SuppressWarnings("EmptyMethod")
        static void reportOnce() {
            // Do nothing
        }
    }

    private final class UncheckedRandomDataInputHolder implements UncheckedRandomDataInput {

        @Override
        public byte readByte(@NonNegative long offset) throws ClosedIllegalStateException {
            return bytesStore.readByte(offset);
        }

        @Override
        public short readShort(@NonNegative long offset) throws ClosedIllegalStateException {
            return bytesStore.readShort(offset);
        }

        @Override
        public int readInt(@NonNegative long offset) throws ClosedIllegalStateException {
            return bytesStore.readInt(offset);
        }

        @Override
        public long readLong(@NonNegative long offset) throws ClosedIllegalStateException {
            return bytesStore.readLong(offset);
        }
    }
}
