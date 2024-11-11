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

import net.openhft.chronicle.bytes.internal.BytesInternal;
import net.openhft.chronicle.core.Maths;
import net.openhft.chronicle.core.UnsafeMemory;
import net.openhft.chronicle.core.annotation.NonNegative;
import net.openhft.chronicle.core.io.ClosedIllegalStateException;
import net.openhft.chronicle.core.io.IORuntimeException;
import net.openhft.chronicle.core.io.ThreadingIllegalStateException;
import net.openhft.chronicle.core.util.Histogram;
import net.openhft.chronicle.core.util.ThrowingConsumer;
import net.openhft.chronicle.core.util.ThrowingConsumerNonCapturing;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

import static net.openhft.chronicle.bytes.internal.ReferenceCountedUtil.throwExceptionIfReleased;
import static net.openhft.chronicle.core.UnsafeMemory.MEMORY;
import static net.openhft.chronicle.core.util.ObjectUtils.requireNonNull;

/**
 * The StreamingDataInput interface represents a data stream for reading in binary data.
 * It provides a range of read methods to retrieve different types of data from the stream,
 * such as integers, longs, floating-point numbers, strings, byte arrays, etc.
 * <p>
 * Reading methods in this interface are usually expected to advance the read position by the
 * number of bytes read. This allows consecutive calls to the read methods to sequentially
 * read chunks of data from the stream.
 * <p>
 * Additionally, StreamingDataInput provides support for leniency and conversion of data into
 * BigInteger or BigDecimal objects. When lenient mode is enabled, methods will return default
 * values when there's no more data to read, instead of throwing exceptions.
 * <p>
 * The interface includes methods for handling exceptions and managing the state of the stream,
 * such as checking the remaining bytes or throwing an exception if the stream has been previously released.
 * <p>
 * Defines a data input interface that supports setting and getting positions and limits.
 * Implementing classes can be used to read data from a data source, typically a byte stream or byte buffer.
 * <p>
 * Note: Implementations of this interface may choose to handle the actual reading of data
 * in various ways, such as through direct memory access or other optimized mechanisms.
 *
 * @param <S> the type of StreamingDataInput
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public interface StreamingDataInput<S extends StreamingDataInput<S>> extends StreamingCommon<S> {

    /**
     * Sets the read position of this StreamingDataInput.
     *
     * @param position the new read position, must be non-negative
     * @return this StreamingDataInput instance, for chaining
     * @throws BufferUnderflowException       If the new position is greater than the limit
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    @NotNull
    S readPosition(@NonNegative long position)
            throws BufferUnderflowException, IllegalStateException, ClosedIllegalStateException, ThreadingIllegalStateException;

    /**
     * Sets the read position of this StreamingDataInput without limiting it to the current read limit.
     *
     * @param position the new read position, must be non-negative
     * @return this StreamingDataInput instance, for chaining
     * @throws BufferUnderflowException       If the new position is greater than the capacity
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    @NotNull
    default S readPositionUnlimited(@NonNegative long position)
            throws BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        return readLimitToCapacity().readPosition(position);
    }

    /**
     * Sets the read position and limit of this StreamingDataInput based on the specified position and remaining values.
     *
     * @param position  the new read position, must be non-negative
     * @param remaining the remaining size, which is used to set the read limit
     * @return this StreamingDataInput instance, for chaining
     * @throws BufferUnderflowException       If the new position is greater than the read limit
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    @NotNull
    default S readPositionRemaining(@NonNegative long position, @NonNegative long remaining)
            throws BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        readLimit(position + remaining);
        return readPosition(position);
    }

    /**
     * Sets the read limit of this StreamingDataInput.
     *
     * @param limit the new read limit, must be non-negative
     * @return this StreamingDataInput instance, for chaining
     * @throws BufferUnderflowException       If the new limit is less than the read position
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    @NotNull
    S readLimit(@NonNegative long limit)
            throws BufferUnderflowException, ThreadingIllegalStateException;

    /**
     * Sets the read limit of this StreamingDataInput to its capacity.
     *
     * @return this StreamingDataInput instance, for chaining
     * @throws BufferUnderflowException       If the capacity is less than the read position
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    default S readLimitToCapacity()
            throws BufferUnderflowException, ThreadingIllegalStateException {
        return readLimit(capacity());
    }

    /**
     * Skips the specified number of bytes by advancing the read position.
     * The number of bytes to skip must be less than or equal to the read limit.
     *
     * @param bytesToSkip the number of bytes to skip
     * @return this StreamingDataInput instance, for chaining
     * @throws BufferUnderflowException       If the new read position is outside the limits of the data source
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    @NotNull
    S readSkip(long bytesToSkip)
            throws BufferUnderflowException, IllegalStateException, ClosedIllegalStateException, ThreadingIllegalStateException;

    /**
     * Obtains the current read position, optionally skipping padding bytes if specified.
     * Useful when reading data with headers that may include padding.
     *
     * @param skipPadding if true, aligns the read position to a 4-byte boundary by skipping padding bytes
     * @return the current read position
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    default long readPositionForHeader(boolean skipPadding) throws ClosedIllegalStateException, ThreadingIllegalStateException {
        long position = readPosition();
        if (skipPadding)
            return readSkip(BytesUtil.padOffset(position)).readPosition();
        return position;
    }

    /**
     * Unchecked version of readSkip(1). This method skips one byte without performing any limit checks.
     * Use this method only when you are certain that it is safe to do so, and you have identified a performance issue with readSkip(1).
     */
    void uncheckedReadSkipOne();

    /**
     * Unchecked version of readSkip(-1). This method skips back one byte without performing any limit checks.
     * Use this method only when you are certain that it is safe to do so, and you have identified a performance issue with readSkip(-1).
     */
    void uncheckedReadSkipBackOne();

    /**
     * Perform a set of actions within a temporary bounds mode. The bounds are defined by the specified length.
     * After the consumer has been executed, the original read limit is restored and the read position is moved forward by the specified length.
     *
     * @param length        the length to set the temporary bounds to
     * @param bytesConsumer the consumer to execute within the temporary bounds
     * @param sb            the StringBuilder to use
     * @param toBytes       the BytesOut to use
     * @throws BufferUnderflowException       If the specified length is greater than the number of bytes remaining to read
     * @throws IORuntimeException             If the bytesConsumer encounters an IO error
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    default void readWithLength0(@NonNegative long length, @NotNull ThrowingConsumerNonCapturing<S, IORuntimeException, BytesOut> bytesConsumer, StringBuilder sb, BytesOut<?> toBytes)
            throws BufferUnderflowException, IORuntimeException, ClosedIllegalStateException, ThreadingIllegalStateException {
        requireNonNull(bytesConsumer);
        if (length > readRemaining())
            throw new BufferUnderflowException();
        long limit0 = readLimit();
        long limit = readPosition() + length;
        try {
            readLimit(limit);
            bytesConsumer.accept((S) this, sb, toBytes);
        } finally {
            readLimit(limit0);
            readPosition(limit);
        }
    }

    /**
     * Perform a set of actions within a temporary bounds mode. The bounds are defined by the specified length.
     * After the consumer has been executed, the original read limit is restored and the read position is moved forward by the specified length.
     *
     * @param length        the length to set the temporary bounds to
     * @param bytesConsumer the consumer to execute within the temporary bounds
     * @throws BufferUnderflowException       If the specified length is greater than the number of bytes remaining to read
     * @throws IORuntimeException             If the bytesConsumer encounters an IO error
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    default void readWithLength(@NonNegative long length, @NotNull ThrowingConsumer<S, IORuntimeException> bytesConsumer)
            throws BufferUnderflowException, IORuntimeException, ClosedIllegalStateException, ThreadingIllegalStateException {
        requireNonNull(bytesConsumer);
        if (length > readRemaining())
            throw new BufferUnderflowException();
        long limit0 = readLimit();
        long limit = readPosition() + length;
        try {
            readLimit(limit);
            bytesConsumer.accept((S) this);
        } finally {
            readLimit(limit0);
            readPosition(limit);
        }
    }

    /**
     * Provides an InputStream for the data represented by this StreamingDataInput.
     *
     * @return an InputStream over the underlying data
     */
    @NotNull
    default InputStream inputStream() {
        return new StreamingInputStream(this);
    }

    /**
     * Reads a variable-length integer encoded using the stop bit encoding.
     * This method is equivalent to calling {@code BytesInternal.readStopBit(this)}.
     *
     * @return the decoded integer
     * @throws IORuntimeException             If an I/O error occurs
     * @throws BufferUnderflowException       If there's not enough data to read
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    default long readStopBit()
            throws IORuntimeException, ClosedIllegalStateException, BufferUnderflowException {
        return BytesInternal.readStopBit(this);
    }

    /**
     * Reads a variable-length character encoded using the stop bit encoding.
     * This method is equivalent to calling {@code BytesInternal.readStopBitChar(this)}.
     *
     * @return the decoded character
     * @throws IORuntimeException             If an I/O error occurs
     * @throws BufferUnderflowException       If there's not enough data to read
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    default char readStopBitChar()
            throws IORuntimeException, ClosedIllegalStateException, BufferUnderflowException {
        return BytesInternal.readStopBitChar(this);
    }

    /**
     * Reads a variable-length double encoded using the stop bit encoding.
     * This method is equivalent to calling {@code BytesInternal.readStopBitDouble(this)}.
     *
     * @return the decoded double
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    default double readStopBitDouble()
            throws ClosedIllegalStateException {
        return BytesInternal.readStopBitDouble(this);
    }

    /**
     * Reads a decimal number represented as a variable-length double and scale encoded using the stop bit encoding.
     * The absolute value of the returned double represents the value of the decimal,
     * and the sign represents the sign of the decimal.
     *
     * @return the decoded decimal number
     * @throws BufferUnderflowException       If there's not enough data to read
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    default double readStopBitDecimal()
            throws ClosedIllegalStateException, BufferUnderflowException {
        long value = readStopBit();
        int scale = (int) (Math.abs(value) % 10);
        value /= 10;
        return (double) value / Maths.tens(scale);
    }

    /**
     * Reads a boolean value from the input stream.
     * It reads a byte and converts it into a boolean using {@code BytesUtil.byteToBoolean(b)}.
     *
     * @return the boolean value
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    default boolean readBoolean()
            throws ClosedIllegalStateException, ThreadingIllegalStateException {
        byte b = readByte();
        return BytesUtil.byteToBoolean(b);
    }

    /**
     * Reads a byte value from the input stream.
     *
     * @return the byte value
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    byte readByte()
            throws ClosedIllegalStateException, ThreadingIllegalStateException;

    /**
     * Reads a raw byte value from the input stream.
     * The main difference between this method and {@code readByte()} is that the latter might perform additional processing or checks.
     *
     * @return the raw byte value
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    default byte rawReadByte()
            throws ClosedIllegalStateException, ThreadingIllegalStateException {
        return readByte();
    }

    /**
     * Reads a character value from the input stream.
     * The character is read using the stop bit encoding.
     *
     * @return the character value
     * @throws ClosedIllegalStateException If the resource has been released or closed.
     * @throws BufferUnderflowException    If there's not enough data to read
     */
    default char readChar()
            throws ClosedIllegalStateException, BufferUnderflowException {
        return readStopBitChar();
    }

    /**
     * Reads the next unsigned 8-bit value from the input stream.
     * If there is no byte available, it returns -1.
     *
     * @return the next unsigned 8-bit value or -1
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    int readUnsignedByte()
            throws ClosedIllegalStateException, ThreadingIllegalStateException;

    /**
     * Reads the next unsigned 8-bit value from the input stream without performing boundary checks.
     * If there is no byte available, it returns -1. Use this method with caution as it bypasses checks for data availability.
     *
     * @return the next unsigned 8-bit value or -1
     */
    int uncheckedReadUnsignedByte();

    /**
     * Reads a 16-bit short value from the input stream.
     *
     * @return the 16-bit short value
     * @throws BufferUnderflowException       If there's not enough data to read
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    short readShort()
            throws BufferUnderflowException, IllegalStateException, ClosedIllegalStateException, ThreadingIllegalStateException;

    /**
     * Reads a 16-bit value from the input stream and converts it to an unsigned short by masking the sign bit.
     *
     * @return the unsigned 16-bit value
     * @throws BufferUnderflowException       If there's not enough data to read
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    default int readUnsignedShort()
            throws BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        return readShort() & 0xFFFF;
    }

    /**
     * Reads a 24-bit signed integer value from the input stream.
     * This is achieved by reading an unsigned short and an unsigned byte, and then
     * bit-shifting and combining them to form a 24-bit integer.
     *
     * @return the 24-bit integer value
     * @throws BufferUnderflowException       If there's not enough data to read
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    default int readInt24()
            throws BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        return readUnsignedShort() | (readUnsignedByte() << 24 >> 8);
    }

    /**
     * Reads a 24-bit unsigned integer value from the input stream.
     * This is achieved by reading an unsigned short and an unsigned byte, and then
     * bit-shifting and combining them to form a 24-bit unsigned integer.
     *
     * @return the 24-bit unsigned integer value
     * @throws BufferUnderflowException       If there's not enough data to read
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    default int readUnsignedInt24()
            throws BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        return readUnsignedShort() | (readUnsignedByte() << 16);
    }

    /**
     * Reads a 32-bit integer value from the input stream.
     *
     * @return the 32-bit integer value
     * @throws BufferUnderflowException       If there's not enough data to read
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    int readInt()
            throws BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException;

    /**
     * Reads a 32-bit integer value from the input stream without performing boundary checks.
     * Use this method with caution as it bypasses checks for data availability.
     *
     * @return the 32-bit integer value
     * @throws BufferUnderflowException       If there's not enough data to read
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    default int rawReadInt()
            throws BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        return readInt();
    }

    /**
     * Reads a 32-bit value from the input stream and converts it to an unsigned integer by masking the sign bit.
     *
     * @return the unsigned 32-bit integer value
     * @throws BufferUnderflowException       If there's not enough data to read
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    default long readUnsignedInt()
            throws BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        return readInt() & 0xFFFFFFFFL;
    }

    /**
     * Reads a 64-bit long value from the input stream.
     *
     * @return the 64-bit long value
     * @throws BufferUnderflowException       If there's not enough data to read
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    long readLong()
            throws BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException;

    /**
     * Reads a 64-bit long value from the input stream without performing boundary checks.
     * Use this method with caution as it bypasses checks for data availability.
     *
     * @return the 64-bit long value
     * @throws BufferUnderflowException       If there's not enough data to read
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    default long rawReadLong()
            throws BufferUnderflowException, IllegalStateException, ClosedIllegalStateException, ThreadingIllegalStateException {
        return readLong();
    }

    /**
     * @return a long using the bytes remaining
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    default long readIncompleteLong()
            throws ClosedIllegalStateException, ThreadingIllegalStateException {
        long left = readRemaining();
        if (left >= 8)
            return readLong();
        if (left == 4)
            return readInt();
        long l = 0;
        for (int i = 0, remaining = (int) left; i < remaining; i++) {
            l |= (long) readUnsignedByte() << (i * 8);
        }
        return l;

    }

    /**
     * Reads a 32-bit floating-point number from the input stream.
     *
     * @return the float value read from the stream
     * @throws BufferUnderflowException       If there's not enough data to read
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    float readFloat()
            throws BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException;

    /**
     * Reads a 64-bit floating-point number from the input stream.
     *
     * @return the double value read from the stream
     * @throws BufferUnderflowException       If there's not enough data to read
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    double readDouble()
            throws BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException;

    /**
     * Reads a UTF-8 encoded string from the input stream. This method supports {@code null} values and
     * utilizes stop bit encoding for length, saving one byte for strings shorter than 128 characters.
     *
     * @return a Unicode string or {@code null} if {@code writeUtf8(null)} was called
     * @throws BufferUnderflowException    If there's not enough data to read
     * @throws IORuntimeException          If an IO error occurs
     * @throws ClosedIllegalStateException If the resource has been released or closed.
     * @throws ArithmeticException         If numeric overflow or underflow occurs
     */
    @Nullable
    default String readUtf8()
            throws BufferUnderflowException, IORuntimeException, ClosedIllegalStateException, ArithmeticException {
        return BytesInternal.readUtf8(this);
    }

    /**
     * Reads an 8-bit encoded string from the input stream.
     *
     * @return an 8-bit string or {@code null} if {@code write8bit(null)} was called
     * @throws BufferUnderflowException    If there's not enough data to read
     * @throws IORuntimeException          If an IO error occurs
     * @throws ClosedIllegalStateException If the resource has been released or closed.
     * @throws ArithmeticException         If numeric overflow or underflow occurs
     */
    @Nullable
    default String read8bit()
            throws IORuntimeException, BufferUnderflowException, ClosedIllegalStateException, ArithmeticException {
        return BytesInternal.read8bit(this);
    }

    /**
     * Reads a UTF-8 encoded string from the input stream and appends it to the provided appendable.
     * This method is similar to {@code readUtf8()}, except it populates a provided appendable instead of creating a new string.
     *
     * @param sb the appendable to which the read string will be appended
     * @return {@code true} if there was a String, or {@code false} if it was {@code null}
     * @throws IORuntimeException          If an IO error occurs
     * @throws BufferUnderflowException    If there's not enough data to read
     * @throws ArithmeticException         If numeric overflow or underflow occurs
     * @throws ClosedIllegalStateException If the resource has been released or closed.
     * @throws IllegalArgumentException    If the appendable does not allow setting length
     */
    default <C extends Appendable & CharSequence> boolean readUtf8(@NotNull C sb)
            throws IORuntimeException, BufferUnderflowException, ArithmeticException, ClosedIllegalStateException, IllegalArgumentException {
        AppendableUtil.setLength(sb, 0);

        if (readRemaining() <= 0)
            return true;
        long len0 = readStopBit();
        if (len0 == -1)
            return false;
        int len = Maths.toUInt31(len0);
        if (len > 0)
            BytesInternal.parseUtf8(this, sb, true, len);
        return true;
    }

    /**
     * Reads a UTF-8 encoded string from the input stream and appends it to the provided Bytes.
     * This method is similar to {@code readUtf8()}, except it populates a provided Bytes instance instead of creating a new string.
     *
     * @param sb the Bytes instance to which the read string will be appended
     * @return {@code true} if there was a String, or {@code false} if it was {@code null}
     * @throws IORuntimeException             If an IO error occurs
     * @throws BufferUnderflowException       If there's not enough data to read
     * @throws ArithmeticException            If numeric overflow or underflow occurs
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    default boolean readUtf8(@NotNull Bytes<?> sb)
            throws IORuntimeException, BufferUnderflowException, ArithmeticException, ClosedIllegalStateException, ThreadingIllegalStateException {
        sb.readPositionRemaining(0, 0);
        if (readRemaining() <= 0)
            return true;
        long len0 = readStopBit();
        if (len0 == -1)
            return false;
        int len = Maths.toUInt31(len0);
        if (len > 0)
            BytesInternal.parseUtf8(this, sb, true, len);
        return true;
    }

    /**
     * Reads a UTF-8 encoded string from the input stream and appends it to the provided StringBuilder.
     * This method is similar to {@code readUtf8()}, except it populates a provided StringBuilder instead of creating a new string.
     *
     * @param sb the StringBuilder to which the read string will be appended
     * @return {@code true} if there was a String, or {@code false} if it was {@code null}
     * @throws IORuntimeException             If an IO error occurs
     * @throws BufferUnderflowException       If there's not enough data to read
     * @throws ArithmeticException            If numeric overflow or underflow occurs
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    default boolean readUtf8(@NotNull StringBuilder sb)
            throws IORuntimeException, BufferUnderflowException, ArithmeticException, ClosedIllegalStateException {
        sb.setLength(0);
        if (readRemaining() <= 0)
            return true;
        long len0 = readStopBit();
        if (len0 == -1)
            return false;
        int len = Maths.toUInt31(len0);
        if (len > 0)
            BytesInternal.parseUtf8(this, sb, true, len);
        return true;
    }

    /**
     * Reads an 8-bit encoded string from the input stream and appends it to the provided Bytes.
     *
     * @param b the Bytes instance to which the read string will be appended
     * @return {@code true} if there was a String, or {@code false} if it was {@code null}
     * @throws BufferUnderflowException    If there's not enough data to read
     * @throws ArithmeticException         If numeric overflow or underflow occurs
     * @throws BufferOverflowException     If the buffer is full
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    default boolean read8bit(@NotNull Bytes<?> b)
            throws BufferUnderflowException, ClosedIllegalStateException, ArithmeticException, BufferOverflowException, ThreadingIllegalStateException {
        b.clear();
        if (readRemaining() <= 0)
            return true;
        long len0;
        byte b1;
        if ((b1 = rawReadByte()) >= 0) {
            len0 = b1;
        } else if (b1 == -128 && peekUnsignedByte() == 0) {
            ((StreamingDataInput) this).readSkip(1);
            return false;
        } else {
            len0 = BytesInternal.readStopBit0(this, b1);
        }

        int len = Maths.toUInt31(len0);
        b.write((BytesStore) this, readPosition(), len);
        readSkip(len);
        return true;
    }

    /**
     * Reads an 8-bit encoded string from the input stream and appends it to the provided StringBuilder.
     *
     * @param sb the StringBuilder to which the read string will be appended
     * @return {@code true} if there was a String, or {@code false} if it was {@code null}
     * @throws IORuntimeException             If an IO error occurs
     * @throws BufferUnderflowException       If there's not enough data to read
     * @throws ArithmeticException            If numeric overflow or underflow occurs
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    default boolean read8bit(@NotNull StringBuilder sb)
            throws IORuntimeException, BufferUnderflowException, ArithmeticException, ClosedIllegalStateException {
        sb.setLength(0);
        if (readRemaining() <= 0)
            return true;
        long len0 = BytesInternal.readStopBit(this);
        if (len0 == -1)
            return false;
        int len = Maths.toUInt31(len0);
        try {
            AppendableUtil.parse8bit(this, sb, len);
        } catch (IOException e) {
            throw new IORuntimeException(e);
        }
        return true;
    }

    /**
     * Reads the input stream into the provided byte array.
     *
     * @param bytes the byte array to fill with the read data
     * @return the number of bytes read, or -1 if the end of the stream is reached
     * @throws BufferUnderflowException       If there's not enough data to read
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    default int read(byte[] bytes)
            throws BufferUnderflowException, IllegalStateException, ClosedIllegalStateException, ThreadingIllegalStateException {
        return read(bytes, 0, bytes.length);
    }

    /**
     * Reads the input stream into the provided byte array, starting from the given offset and reading up to the specified length.
     *
     * @param bytes the byte array to fill with the read data
     * @param off   the start offset in the byte array
     * @param len   the maximum number of bytes to read
     * @return the number of bytes read, or -1 if the end of the stream is reached
     * @throws BufferUnderflowException       If there's not enough data to read
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    default int read(byte[] bytes, @NonNegative int off, @NonNegative int len)
            throws BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        requireNonNull(bytes);
        long remaining = readRemaining();
        if (remaining <= 0)
            return -1;
        int len2 = (int) Math.min(len, remaining);
        int i = 0;
        for (; i < len2 - 7; i += 8)
            UnsafeMemory.unsafePutLong(bytes, i + off, rawReadLong());
        for (; i < len2; i++)
            bytes[off + i] = rawReadByte();
        return len2;
    }

    /**
     * Reads the input stream into the provided char array, starting from the given offset and reading up to the specified length.
     *
     * @param bytes the char array to fill with the read data
     * @param off   the start offset in the char array
     * @param len   the maximum number of chars to read
     * @return the number of chars read, or -1 if the end of the stream is reached
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    default int read(char[] bytes, @NonNegative int off, @NonNegative int len)
            throws ClosedIllegalStateException, ThreadingIllegalStateException {
        requireNonNull(bytes);
        long remaining = readRemaining();
        if (remaining <= 0)
            return -1;
        int len2 = (int) Math.min(len, remaining);
        for (int i = 0; i < len2; i++)
            bytes[off + i] = (char) readUnsignedByte();
        return len2;
    }

    /**
     * Reads the input stream into the provided ByteBuffer.
     *
     * @param buffer the ByteBuffer to fill with the read data
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    default void read(@NotNull ByteBuffer buffer)
            throws ClosedIllegalStateException, ThreadingIllegalStateException {
        requireNonNull(buffer);
        for (int i = (int) Math.min(readRemaining(), buffer.remaining()); i > 0; i--)
            buffer.put(readByte());
    }

    /**
     * Transfers as many bytes as possible from the input stream into the provided Bytes object.
     *
     * @param bytes the Bytes object to fill with the read data
     * @see StreamingDataOutput#write(BytesStore)
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    default void read(@NotNull final Bytes<?> bytes) throws ClosedIllegalStateException, ThreadingIllegalStateException {
        int length = Math.toIntExact(Math.min(readRemaining(), bytes.writeRemaining()));
        read(bytes, length);
    }

    /**
     * Transfers the specified number of bytes from the input stream into the provided Bytes object.
     *
     * @param bytes  the Bytes object to fill with the read data
     * @param length the number of bytes to read
     * @throws BufferUnderflowException       If there's not enough data to read
     * @throws BufferOverflowException        If there's not enough space in the provided Bytes object
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    default void read(@NotNull final Bytes<?> bytes,
                      @NonNegative final int length)
            throws BufferUnderflowException, BufferOverflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        requireNonNull(bytes);
        int len2 = (int) Math.min(length, readRemaining());
        int i = 0;
        for (; i < len2 - 7; i += 8)
            bytes.rawWriteLong(rawReadLong());
        for (; i < len2; i++)
            bytes.rawWriteByte(rawReadByte());
    }

    /**
     * Reads data from the input stream into the provided object, starting from the given offset.
     *
     * @param o      the object to fill with the read data
     * @param offset the start offset in the object
     * @param length the number of bytes to read
     * @throws BufferUnderflowException       If there's not enough data to read
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    default void unsafeReadObject(@NotNull Object o, @NonNegative int offset, @NonNegative int length)
            throws BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        requireNonNull(o);
        assert BytesUtil.isTriviallyCopyable(o.getClass(), offset, length);
        if (readRemaining() < length)
            throw new BufferUnderflowException();
        if (isDirectMemory()) {
            final long src = addressForRead(readPosition());
            readSkip(length); // blow up here first
            MEMORY.copyMemory(src, o, offset, length);
            return;
        }
        int i = 0;
        for (; i < length - 7; i += 8)
            UnsafeMemory.unsafePutLong(o, (long) offset + i, rawReadLong());
        if (i < length - 3) {
            UnsafeMemory.unsafePutInt(o, (long) offset + i, rawReadInt());
            i += 4;
        }
        for (; i < length; i++)
            UnsafeMemory.unsafePutByte(o, (long) offset + i, rawReadByte());
    }

    /**
     * Reads data from the input stream into the memory at the provided address.
     *
     * @param address the address of the memory to fill with the read data
     * @param length  the number of bytes to read
     * @return a reference to this object
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    default S unsafeRead(long address, @NonNegative int length) throws ClosedIllegalStateException, ThreadingIllegalStateException {
        if (isDirectMemory()) {
            long src = addressForRead(readPosition());
            readSkip(length);
            UnsafeMemory.copyMemory(src, address, length);
        } else {
            int i = 0;
            for (; i < length - 7; i += 8)
                MEMORY.writeLong(address + i, readLong());
            for (; i < length; ++i)
                MEMORY.writeByte(address + i, readByte());
        }

        return (S) this;
    }

    /**
     * Reads a volatile (concurrently mutable) integer value from the input stream.
     *
     * @return the read integer value
     * @throws BufferUnderflowException       If there's not enough data to read
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    int readVolatileInt()
            throws BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException;

    /**
     * Reads a volatile (concurrently mutable) long value from the input stream.
     *
     * @return the read long value
     * @throws BufferUnderflowException       If there's not enough data to read
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    long readVolatileLong()
            throws BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException;

    /**
     * Peeks (reads without moving the read pointer) the next unsigned byte from the input stream.
     *
     * @return the peeked byte value
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    int peekUnsignedByte()
            throws ClosedIllegalStateException, ThreadingIllegalStateException;

    /**
     * Reads an Enum value from the input stream.
     *
     * @param eClass the class of the Enum
     * @return the read Enum value
     * @throws IORuntimeException          If an I/O error occurs
     * @throws BufferUnderflowException    If there's not enough data to read
     * @throws ArithmeticException         If the number format is invalid
     * @throws ClosedIllegalStateException If the resource has been released or closed.
     * @throws BufferOverflowException     If there's not enough space in the buffer
     */
    @NotNull
    default <E extends Enum<E>> E readEnum(@NotNull Class<E> eClass)
            throws IORuntimeException, BufferUnderflowException, ArithmeticException, ClosedIllegalStateException, BufferOverflowException {
        return BytesInternal.readEnum(this, eClass);
    }

    /**
     * Parses a UTF-8 string from the input stream into the provided Appendable.
     *
     * @param sb            the Appendable to fill with the parsed string
     * @param encodedLength the length of the UTF-8 encoded data in bytes
     * @throws IllegalArgumentException       If an illegal argument is provided
     * @throws BufferUnderflowException       If there's not enough data to read
     * @throws UTFDataFormatRuntimeException  If the string is not valid UTF-8
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    default void parseUtf8(@NotNull Appendable sb, @NonNegative int encodedLength)
            throws IllegalArgumentException, BufferUnderflowException, UTFDataFormatRuntimeException, ClosedIllegalStateException {
        parseUtf8(sb, true, encodedLength);
    }

    /**
     * Parses a UTF-8 string from the input stream into the provided Appendable.
     *
     * @param sb     the Appendable to fill with the parsed string
     * @param utf    true if the length is the UTF-8 encoded length, false if the length is the length of chars
     * @param length the maximum number of bytes to read
     * @throws IllegalArgumentException       If an illegal argument is provided
     * @throws BufferUnderflowException       If there's not enough data to read
     * @throws UTFDataFormatRuntimeException  If the string is not valid UTF-8
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    default void parseUtf8(@NotNull Appendable sb, boolean utf, @NonNegative int length)
            throws IllegalArgumentException, BufferUnderflowException, UTFDataFormatRuntimeException, ClosedIllegalStateException {
        AppendableUtil.setLength(sb, 0);
        BytesInternal.parseUtf8(this, sb, utf, length);
    }

    /**
     * Parses a hexadecimal long value from the input stream.
     *
     * @return the parsed long value
     * @throws BufferUnderflowException       If there's not enough data to read
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    default long parseHexLong()
            throws BufferUnderflowException, ClosedIllegalStateException {
        return BytesInternal.parseHexLong(this);
    }

    /**
     * Copies the data from the input stream to the provided OutputStream.
     *
     * @param out the OutputStream to copy the data to
     * @throws IOException                    If an I/O error occurs
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    void copyTo(@NotNull OutputStream out)
            throws IOException, ClosedIllegalStateException;

    /**
     * Copies the data from the input stream to the provided BytesStore.
     *
     * @param to the BytesStore to copy the data to
     * @return the number of bytes copied
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    long copyTo(@NotNull BytesStore<?, ?> to)
            throws ClosedIllegalStateException;

    /**
     * Reads data from the input stream into the provided Histogram.
     *
     * @param histogram the Histogram to fill with data
     * @throws BufferUnderflowException    If there's not enough data to read
     * @throws ClosedIllegalStateException If the resource has been released or closed.
     * @throws ArithmeticException         If the number format is invalid
     */
    default void readHistogram(@NotNull Histogram histogram)
            throws BufferUnderflowException, IllegalStateException, ArithmeticException, ClosedIllegalStateException {
        BytesInternal.readHistogram(this, histogram);
    }

    /**
     * Reads data from the input stream with specified length into the provided Bytes.
     *
     * @param bytes the Bytes to fill with data
     * @throws ArithmeticException            If the number format is invalid
     * @throws BufferUnderflowException       If there's not enough data to read
     * @throws BufferOverflowException        If there's not enough space in the buffer
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    default void readWithLength(@NotNull final Bytes<?> bytes)
            throws ArithmeticException, BufferUnderflowException, BufferOverflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        bytes.clear();
        int length = Maths.toUInt31(readStopBit());
        int i;
        for (i = 0; i < length - 7; i += 8)
            bytes.writeLong(readLong());
        for (; i < length; i++)
            bytes.writeByte(readByte());
    }

    /**
     * When there is no more data to read, return zero, {@code false} and empty string.
     *
     * @param lenient if true, return nothing rather than error.
     */
    void lenient(boolean lenient);

    boolean lenient();

    /**
     * Creates and returns a new BigDecimal representing the contents of this Bytes object.
     * <p>
     * If this Byte object is empty, an object equal to {@link BigDecimal#ZERO} is returned.
     *
     * @return a new BigDecimal
     * @throws ArithmeticException            If the content of this Bytes object could not be successfully converted
     * @throws BufferUnderflowException       If the content of this Bytes object is insufficient to be successfully converted
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    @NotNull
    default BigDecimal readBigDecimal()
            throws ArithmeticException, BufferUnderflowException, IllegalStateException, ClosedIllegalStateException {
        throwExceptionIfReleased(this);
        return new BigDecimal(readBigInteger(), Maths.toUInt31(readStopBit()));
    }

    /**
     * Creates and returns a new BigInteger representing the contents of this Bytes object or {@link BigInteger#ZERO}
     * if this Bytes object is empty.
     *
     * @return a new BigInteger
     * @throws ArithmeticException            If the content of this Bytes object could not be successfully converted
     * @throws BufferUnderflowException       If the content of this Bytes object is insufficient to be successfully converted
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    @NotNull
    default BigInteger readBigInteger()
            throws ArithmeticException, BufferUnderflowException, ClosedIllegalStateException {
        throwExceptionIfReleased(this);
        int length = Maths.toUInt31(readStopBit());
        if (length == 0) {
            if (lenient()) {
                return BigInteger.ZERO;
            } else {
                throw new BufferUnderflowException();
            }
        }
        byte[] bytes = new byte[length];
        read(bytes);
        return new BigInteger(bytes);
    }
}
