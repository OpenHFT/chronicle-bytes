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

import net.openhft.chronicle.bytes.algo.OptimisedBytesStoreHash;
import net.openhft.chronicle.bytes.algo.VanillaBytesStoreHash;
import net.openhft.chronicle.bytes.internal.*;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.annotation.NonNegative;
import net.openhft.chronicle.core.io.ClosedIllegalStateException;
import net.openhft.chronicle.core.io.IORuntimeException;
import net.openhft.chronicle.core.io.ReferenceCounted;
import net.openhft.chronicle.core.io.ThreadingIllegalStateException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.crypto.Cipher;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static java.lang.Math.min;
import static net.openhft.chronicle.bytes.internal.ReferenceCountedUtil.throwExceptionIfReleased;
import static net.openhft.chronicle.core.util.ObjectUtils.requireNonNull;

/**
 * This interface represents an immutable reference to a segment of bytes with a fixed range.
 * It can be safely shared across threads given that the data it references is accessed in a thread-safe manner.
 * Direct access is only possible within the allocated capacity of the BytesStore.
 *
 * @param <B> The BytesStore type
 * @param <U> The underlying data type the BytesStore manages
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public interface BytesStore<B extends BytesStore<B, U>, U>
        extends RandomDataInput, RandomDataOutput<B>, ReferenceCounted, CharSequence {

    /**
     * Converts a CharSequence into a BytesStore. The characters are encoded using ISO_8859_1.
     *
     * @param cs the CharSequence to be converted
     * @return a BytesStore which contains the bytes from the CharSequence
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way.
     */
    static BytesStore<?, ?> from(@NotNull CharSequence cs) throws ClosedIllegalStateException, ThreadingIllegalStateException {
        if (cs.length() == 0)
            return empty();
        if (cs instanceof BytesStore)
            return from((BytesStore) cs);
        return from(cs.toString());
    }

    /**
     * Returns a BytesStore using the bytes in another specified BytesStore.
     *
     * @param cs the source BytesStore
     * @return a new BytesStore that is a copy of the source
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way.
     */
    static <A extends BytesStore<A, B>,B> BytesStore<A,B> from(@NotNull BytesStore<A, B> cs)
            throws ClosedIllegalStateException {
        return cs.copy();
    }

    /**
     * Converts a String into a BytesStore. The characters in the String are encoded using ISO_8859_1.
     *
     * @param cs the String to be converted
     * @return a BytesStore which contains the bytes from the String
     */
    static BytesStore<?,byte[]> from(@NotNull String cs) {
        return cs.length() == 0 ? empty() : BytesStore.wrap(cs.getBytes(StandardCharsets.ISO_8859_1));
    }

    /**
     * Provides a BytesStore that allows access to a group of fields in a given object.
     *
     * @param o         the object that contains the fields
     * @param groupName the group name of the fields
     * @param padding   the padding to be used
     * @return a BytesStore which points to the fields
     */
    static <T> BytesStore<?, T> forFields(Object o, String groupName, int padding) {
        return HeapBytesStore.forFields(o, groupName, padding);
    }

    /**
     * Wraps a byte array into a BytesStore. There will be only one copy in memory.
     *
     * @param bytes the byte array to be wrapped
     * @return a BytesStore that contains the bytes from the array
     */
    static BytesStore<?, byte[]> wrap(byte[] bytes) {
        return HeapBytesStore.wrap(bytes);
    }

    /**
     * Wraps a ByteBuffer into a BytesStore. The ByteBuffer can be either on heap or off heap.
     * When the resulting BytesStore is closed, the direct ByteBuffer will be deallocated and should not be used anymore.
     *
     * @param bb the ByteBuffer to be wrapped
     * @return a BytesStore that contains the bytes from the ByteBuffer
     * @see #follow(ByteBuffer)
     */
    @NotNull
    static BytesStore<?, ByteBuffer> wrap(@NotNull ByteBuffer bb) {
        return bb.isDirect()
                ? NativeBytesStore.wrap(bb)
                : HeapBytesStore.wrap(bb);
    }

    /**
     * Wraps a ByteBuffer which can be either on heap or off heap without taking ownership of that buffer.
     * <p>
     * When resulting BytesStore instance is closed, direct {@code byteBuffer} will not be deallocated so its
     * life cycle should be tracked elsewhere.
     *
     * @param bb the ByteBuffer to follow
     * @return a BytesStore that directly interfaces with the given ByteBuffer
     * @see #wrap(ByteBuffer)
     */
    @NotNull
    static BytesStore<?, ByteBuffer> follow(@NotNull ByteBuffer bb) {
        return bb.isDirect()
                ? NativeBytesStore.follow(bb)
                : HeapBytesStore.wrap(bb);
    }

    /**
     * This is an elastic native store.
     * Creates a flexible BytesStore instance that resides in native memory.
     *
     * @param capacity the initial capacity of the buffer
     * @return a BytesStore with the provided capacity in native memory
     */
    static BytesStore<?, Void> nativeStore(@NonNegative long capacity) {
        return NativeBytesStore.nativeStore(capacity);
    }

    /**
     * Creates a BytesStore instance with a fixed capacity that resides in native memory.
     *
     * @param capacity the fixed capacity of the buffer
     * @return a BytesStore with the provided fixed capacity in native memory
     */
    static BytesStore<?, Void> nativeStoreWithFixedCapacity(@NonNegative long capacity) {
        return NativeBytesStore.nativeStoreWithFixedCapacity(capacity);
    }

    /**
     * Creates a lazily initialized BytesStore instance with a fixed capacity that resides in native memory.
     *
     * @param capacity the fixed capacity of the buffer
     * @return a BytesStore with the provided fixed capacity in native memory, initialized on demand
     */
    static BytesStore<?, Void> lazyNativeBytesStoreWithFixedCapacity(@NonNegative long capacity) {
        return NativeBytesStore.lazyNativeBytesStoreWithFixedCapacity(capacity);
    }

    /**
     * Creates a flexible ByteBuffer instance that resides in native memory.
     *
     * @param size    the initial size of the ByteBuffer
     * @param maxSize the maximum allowable size of the ByteBuffer
     * @return a ByteBuffer with the provided initial size and maximum size in native memory
     */
    static BytesStore<?, ByteBuffer> elasticByteBuffer(@NonNegative int size, @NonNegative long maxSize) {
        return NativeBytesStore.elasticByteBuffer(size, maxSize);
    }

    /**
     * Creates and returns a new BytesStore that resides in native memory whereby the contents and
     * size of the native memory is determined by the provided {@code bytes} array.
     *
     * @param bytes the content to initialize the new ByteStore
     * @return a new BytesStore that resides in native memory whereby the contents and
     * size of the native memory is determined by the provided {@code bytes} array
     */
    static BytesStore<?, Void> nativeStoreFrom(byte[] bytes) {
        Objects.requireNonNull(bytes);
        return NativeBytesStore.from(bytes);
    }

    /**
     * Creates a new PointerBytesStore that can be set to any address in memory.
     *
     * @return a new, uninitialized PointerBytesStore
     */
    @NotNull
    static PointerBytesStore nativePointer() {
        return new PointerBytesStore();
    }

    /**
     * Creates a PointerBytesStore that wraps bytes starting from a specific address in memory.
     *
     * @param address the starting memory address for the PointerBytesStore
     * @param length  the length of the memory segment to be wrapped by the PointerBytesStore
     * @return a PointerBytesStore that wraps the specified memory segment
     */
    @NotNull
    static PointerBytesStore wrap(long address, @NonNegative long length) {
        @NotNull PointerBytesStore pbs = nativePointer();
        pbs.set(address, length);
        return pbs;
    }

    /**
     * Provides an empty, fixed-sized and immutable BytesStore.
     *
     * @return an instance of an empty BytesStore
     */
    @SuppressWarnings("unchecked")
    static <B extends BytesStore<B, T>, T> BytesStore<B, T> empty() {
        return (BytesStore<B, T>) NoBytesStore.NO_BYTES_STORE;
    }

    /**
     * Performs a compare-and-swap operation on the specified float value at the given offset.
     * If the current float value at the offset equals the expected value, it's replaced with the provided new value.
     *
     * @param offset   the position where the float value is stored
     * @param expected the expected current value
     * @param value    the new value to be set if the current value equals the expected value
     * @return true if the compare-and-swap was successful, false otherwise
     * @throws BufferOverflowException     If the offset is out of bounds
     * @throws ClosedIllegalStateException If the resource has been released or closed.
     */
    @Override
    default boolean compareAndSwapFloat(@NonNegative long offset, float expected, float value)
            throws BufferOverflowException, ClosedIllegalStateException {
        return compareAndSwapInt(offset, Float.floatToRawIntBits(expected), Float.floatToRawIntBits(value));
    }

    /**
     * Similar to {@link #compareAndSwapFloat(long, float, float)} but operates on a double value.
     */
    @Override
    default boolean compareAndSwapDouble(@NonNegative long offset, double expected, double value)
            throws BufferOverflowException, ClosedIllegalStateException {
        return compareAndSwapLong(offset, Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(value));
    }

    /**
     * Adds an integer to the current integer value at the specified offset and returns the result.
     *
     * @param offset the position where the integer is stored
     * @param adding the integer to add
     * @return the result of the addition
     * @throws BufferUnderflowException    If the offset is out of bounds
     * @throws ClosedIllegalStateException If the resource has been released or closed.
     */
    @SuppressWarnings("deprecation")
    default int addAndGetInt(@NonNegative long offset, int adding)
            throws BufferUnderflowException, ClosedIllegalStateException {
        return BytesInternal.addAndGetInt(this, offset, adding);
    }

    /**
     * Similar to {@link #addAndGetInt(long, int)} but operates on a long value.
     */
    @SuppressWarnings("deprecation")
    default long addAndGetLong(@NonNegative long offset, long adding)
            throws BufferUnderflowException, ClosedIllegalStateException {
        return BytesInternal.addAndGetLong(this, offset, adding);
    }

    /**
     * Similar to {@link #addAndGetInt(long, int)} but operates on a float value.
     */
    @SuppressWarnings("deprecation")
    default float addAndGetFloat(@NonNegative long offset, float adding)
            throws BufferUnderflowException, ClosedIllegalStateException {
        return BytesInternal.addAndGetFloat(this, offset, adding);
    }

    /**
     * Similar to {@link #addAndGetInt(long, int)} but operates on a double value.
     */
    @SuppressWarnings("deprecation")
    default double addAndGetDouble(@NonNegative long offset, double adding)
            throws BufferUnderflowException, ClosedIllegalStateException {
        return BytesInternal.addAndGetDouble(this, offset, adding);
    }

    /**
     * Checks if this BytesStore uses direct memory.
     *
     * @return true if it uses direct memory, false otherwise
     */
    @Override
    boolean isDirectMemory();

    /**
     * Creates and returns a copy of this BytesStore.
     *
     * @return a new instance of BytesStore that is a copy of this BytesStore
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way.
     */
    BytesStore<B, U> copy()
            throws ClosedIllegalStateException, ThreadingIllegalStateException;

    /**
     * Returns a Bytes that wraps this ByteStore from the {@code start} to the {@code realCapacity}.
     * <p>
     * The returned Bytes is not elastic and can be both read and written using cursors.
     *
     * @return a Bytes that wraps this ByteStore
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way.
     */
    @Override
    @NotNull
    default Bytes<U> bytesForRead()
            throws ClosedIllegalStateException, ThreadingIllegalStateException {
        try {
            Bytes<U> ret = bytesForWrite();
            ret.readLimit(writeLimit());
            ret.writeLimit(realCapacity());
            ret.readPosition(start());
            return ret;
        } catch (BufferUnderflowException | BufferOverflowException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Returns a Bytes that wraps this ByteStore from the {@code start} to the {@code realCapacity}.
     * <p>
     * The returned Bytes is not elastic and can be both read and written using cursors.
     *
     * @return a Bytes that wraps this BytesStore
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way.
     */
    @Override
    @NotNull
    default Bytes<U> bytesForWrite()
            throws ClosedIllegalStateException {
        try {
            return new VanillaBytes<>(this, writePosition(), writeLimit());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Returns if the {@code readPosition} is at the {@code start} and
     * the {@code writeLimit} is at the {@code end}.
     * <p>
     * I.e {@code start() == readPosition() && writeLimit() == capacity()}
     *
     * @return if the {@code readPosition} is at the {@code start} and
     * the {@code writeLimit} is at the {@code end}
     */
    default boolean isClear() {
        return true;
    }

    /**
     * Returns the actual capacity of the ByteStore before any resizing occurs.
     *
     * @return the current capacity of the ByteStore
     */
    @Override
    default @NonNegative long realCapacity() {
        return capacity();
    }

    /**
     * Provides the maximum limit that can be set for the ByteStore.
     *
     * @return the maximum capacity of the ByteStore
     */
    @Override
    @NonNegative
    long capacity();

    /**
     * @return the underlying object being wrapped, if there is one, or null if not.
     */
    @Nullable
    U underlyingObject();

    /**
     * Returns if a specified offset is inside this BytesStore limits.
     * <p>
     * Use this test to determine if an offset is considered safe for reading from. Note that it checks we are
     * inside the BytesStore limits *without* including the overlap
     *
     * @param offset the specified offset to check
     * @return {@code true} if offset is safe
     */
    default boolean inside(@NonNegative long offset) {
        return start() <= offset && offset < safeLimit();
    }

    /**
     * Returns if a number of bytes starting from an offset are inside this ByteStore limits.
     *
     * @param offset     the starting index to check
     * @param bufferSize the number of bytes to be read/written
     * @return {@code true} if the bytes between the offset and offset+buffer are inside the BytesStore
     */
    default boolean inside(@NonNegative long offset, @NonNegative long bufferSize) {
        return start() <= offset && offset + bufferSize <= safeLimit();
    }

    /**
     * @return how many bytes can be safely read, i.e. what is the real capacity of the underlying data.
     */
    default long safeLimit() {
        return capacity();
    }

    /**
     * Returns the number of bytes that were copied from this BytesStore to a destination BytesStore.
     * <p>
     * Copies the data to another BytesStore as long as space is available in the destination BytesStore.
     *
     * @param store the BytesStore to copy to
     * @return how many bytes were copied
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way.
     */
    default long copyTo(@NotNull BytesStore<?, ?> store)
            throws ClosedIllegalStateException, ThreadingIllegalStateException {
        requireNonNull(store);
        throwExceptionIfReleased(this);
        throwExceptionIfReleased(store);
        long readPos = readPosition();
        long writePos = store.writePosition();
        long copy = min(readRemaining(), store.writeRemaining());
        long i = 0;
        try {
            for (; i < copy - 7; i += 8)
                store.writeLong(writePos + i, readLong(readPos + i));
            for (; i < copy; i++)
                store.writeByte(writePos + i, readByte(readPos + i));
        } catch (BufferOverflowException | BufferUnderflowException e) {
            throw new IllegalStateException(e);
        }
        return copy;
    }

    /**
     * Copies the bytes in the BytesStore to an OutputStream object.
     *
     * @param out the specified OutputStream that this BytesStore is copied to
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way.
     * @see java.io.OutputStream
     */
    default void copyTo(@NotNull OutputStream out)
            throws IOException, ClosedIllegalStateException, ThreadingIllegalStateException {
        BytesInternal.copy(this, out);
    }

    /**
     * Fills the BytesStore with zeros.
     *
     * @param start first byte inclusive
     * @param end   last byte exclusive
     * @return this
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way.
     */
    @Override
    @NotNull
    default B zeroOut(@NonNegative long start, @NonNegative long end)
            throws ClosedIllegalStateException, ThreadingIllegalStateException {
        if (end <= start)
            return (B) this;
        if (start < start())
            start = start();
        if (end > capacity())
            end = capacity();
        long i = start;
        try {
            for (; i < end - 7; i += 8L)
                writeLong(i, 0L);
            for (; i < end; i++)
                writeByte(i, 0);
        } catch (BufferOverflowException | IllegalArgumentException | ArithmeticException e) {
            throw new IllegalStateException(e);
        }
        return (B) this;
    }

    /**
     * This method is inherited from CharSequence so result should be the length of the contained
     * chars sequence although it actually returns the number of underlying bytes. These 2 numbers are only the same
     * if the encoding we are using is single char for single byte.
     *
     * @return length in bytes to read or Integer.MAX_VALUE if longer.
     */
    @Override
    @NonNegative
    default int length() {
        return (int) Math.min(Integer.MAX_VALUE, readRemaining());
    }

    /**
     * Assume ISO-8859-1 encoding, subclasses can override this.
     *
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way.
     */
    @Override
    default char charAt(@NonNegative int index)
            throws IndexOutOfBoundsException {
        try {
            return (char) readUnsignedByte(readPosition() + index);

        } catch (BufferUnderflowException e) {
            throw new IndexOutOfBoundsException((readPosition() + index) + " >= " + readLimit());
        }
    }

    /**
     * {@inheritDoc}
     *
     * <br>
     * This method constructs a new , memory storage type (heap or native) is preserved.
     */
    @NotNull
    @Override
    default CharSequence subSequence(@NonNegative int start, @NonNegative int end) {
        if (start < 0 || end > length() || end < start)
            throw new IndexOutOfBoundsException("start " + start + ", end " + end + ", length " + length());

        try {
            return subBytes(readPosition() + start, (long) end - start);
        } catch (ClosedIllegalStateException e) {
            throw Jvm.rethrow(e);
        }
    }

    /**
     * By default, the maximum length of data shown is 256 characters. Use {@link #toDebugString(long)} if you want more.
     * <p>
     * This is assumed to be used to print the contents on a best effort basis. If an Error occurs it will be returned in the String.
     *
     * @return this BytesStore as a DebugString
     */
    @NotNull
    default String toDebugString() {
        return toDebugString(512);
    }

    /**
     * <p>
     * This is assumed to be used to print the contents on a best effort basis. If an Error occurs it will be returned in the String.
     * @param maxLength the maximum length of the output
     * @return this BytesStore as a DebugString.
     */
    @NotNull
    default String toDebugString(@NonNegative long maxLength) {
        try {
            return BytesInternal.toDebugString(this, maxLength);
        } catch (Exception e) {
            return e.toString();
        }
    }

    /**
     * @return the underlying BytesStore
     */
    @Nullable
    default BytesStore<?, ?> bytesStore() {
        return this;
    }

    /**
     * Returns if a portion of a specified BytesStore matches this BytesStore.
     *
     * @param bytesStore the BytesStore to match against
     * @param length     the length to match
     * @return {@code true} if the bytes up to min(length, this.length(), bytesStore.length()) matched.
     * @throws BufferUnderflowException       If there's not enough data to be moved
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    default boolean equalBytes(@NotNull BytesStore<?, ?> bytesStore, @NonNegative long length)
            throws BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        return length == 8 && bytesStore.length() >= 8
                ? readLong(readPosition()) == bytesStore.readLong(bytesStore.readPosition())
                : BytesInternal.equalBytesAny(this, bytesStore, length);
    }

    /**
     * Returns the bytes sum of the readable bytes in this BytesStore.
     *
     * @return unsigned bytes sum
     * @throws BufferUnderflowException       If there's not enough data to be moved
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    default int byteCheckSum()
            throws IORuntimeException, BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        try {
            return byteCheckSum(readPosition(), readLimit());
        } catch (BufferUnderflowException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Returns the bytes sum between the specified indexes; start (inclusive) and end (exclusive).
     *
     * @param start the index of the first byte to sum
     * @param end   the index of the last byte to sum
     * @return unsigned bytes sum
     * @throws BufferUnderflowException       If the specified indexes are outside the limits of the BytesStore
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way.
     */
    default int byteCheckSum(@NonNegative long start, @NonNegative long end)
            throws BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        int sum = 0;
        for (long i = start; i < end; i++) {
            sum += readByte(i);
        }
        return sum & 0xFF;
    }

    /**
     * Returns if the BytesStore ends with a specified character.
     *
     * @param c the character to look for
     * @return {@code true} if the specified character is the same as the last character of this BytesStore
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way.
     */
    default boolean endsWith(char c)
            throws ClosedIllegalStateException, ThreadingIllegalStateException {
        try {
            return readRemaining() > 0 && readUnsignedByte(readLimit() - 1) == c;
        } catch (BufferUnderflowException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Returns if the BytesStore starts with a specified character.
     *
     * @param c the character to look for
     * @return {@code true} if the specified character is the same as the first character of this BytesStore
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way.
     */
    default boolean startsWith(char c)
            throws ClosedIllegalStateException, ThreadingIllegalStateException {
        try {
            return readRemaining() > 0 && readUnsignedByte(readPosition()) == c;
        } catch (BufferUnderflowException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Returns if the content of this BytesStore is the same as the content of a specified BytesStore.
     *
     * @param bytesStore the BytesStore to compare with
     * @return {@code true} if this BytesStore and the input BytesStore contain the same data
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way.
     */
    default boolean contentEquals(@Nullable BytesStore<?, ?> bytesStore)
            throws ClosedIllegalStateException, ThreadingIllegalStateException {
        return BytesInternal.contentEqual(this, bytesStore);
    }

    /**
     * Returns if the content of this BytesStore starts with bytes equal to the content of a specified BytesStore.
     *
     * @param bytesStore the BytesStore to compare with
     * @return {@code true} if the content of this BytesStore starts with bytesStore
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way.
     */
    default boolean startsWith(@Nullable BytesStore<?, ?> bytesStore)
            throws ClosedIllegalStateException, ThreadingIllegalStateException {
        return bytesStore != null && BytesInternal.startsWith(this, bytesStore);
    }

    /**
     * Returns the content of this BytesStore in 8bitString format.
     *
     * @return a String from the content of this BytesStore
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way.
     */
    @NotNull
    default String to8bitString()
            throws ClosedIllegalStateException, ThreadingIllegalStateException {
        return BytesInternal.to8bitString(this);
    }

    /**
     * Returns the content of this BytesStore as a UTF-8 string.
     *
     * @return a String from the content of this BytesStore
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way.
     */
    @NotNull
    default String toUtf8String()
            throws ClosedIllegalStateException, ThreadingIllegalStateException {
        return BytesInternal.toUtf8String(this);
    }

    /**
     * Perform a <i>not</i> atomic add and get operation for an unsigned byte value. This method
     * <i>does not</i> check for unsigned byte overflow.
     *
     * @param offset to add and get
     * @param adding value to add, can be 1
     * @return the sum
     * @throws BufferUnderflowException       If there's not enough data to be moved
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way.
     */
    default int addAndGetUnsignedByteNotAtomic(@NonNegative long offset, int adding)
            throws BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        try {
            int r = (readUnsignedByte(offset) + adding) & 0xFF;
            writeByte(offset, (byte) r);
            return r;
        } catch (BufferOverflowException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Perform a <i>not</i> atomic add and get operation for a short value.
     *
     * @param offset to add and get
     * @param adding value to add, can be 1
     * @return the sum
     * @throws BufferUnderflowException       If there's not enough data to be moved
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way.
     */
    default short addAndGetShortNotAtomic(@NonNegative long offset, short adding)
            throws BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        try {
            short r = (short) (readShort(offset) + adding);
            writeByte(offset, r);
            return r;
        } catch (BufferOverflowException | IllegalArgumentException | ArithmeticException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Perform a <i>not</i> atomic add and get operation for an int value.
     *
     * @param offset to add and get
     * @param adding value to add, can be 1
     * @return the sum
     * @throws BufferUnderflowException       If there's not enough data to be moved
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way.
     */
    default int addAndGetIntNotAtomic(@NonNegative long offset, int adding)
            throws BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        try {
            int r = readInt(offset) + adding;
            writeInt(offset, r);
            return r;
        } catch (BufferOverflowException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Perform a <i>not</i> atomic add and get operation for a float value.
     *
     * @param offset to add and get
     * @param adding value to add, can be 1
     * @return the sum
     * @throws BufferUnderflowException       If there's not enough data to be moved
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way.
     */
    default double addAndGetDoubleNotAtomic(@NonNegative long offset, double adding)
            throws BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        try {
            double r = readDouble(offset) + adding;
            writeDouble(offset, r);
            return r;
        } catch (BufferOverflowException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Perform a <i>not</i> atomic add and get operation for a float value.
     *
     * @param offset to add and get
     * @param adding value to add, can be 1
     * @return the sum
     * @throws BufferUnderflowException       If there's not enough data to be moved
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way.
     */
    default float addAndGetFloatNotAtomic(@NonNegative long offset, float adding)
            throws BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        try {
            float r = readFloat(offset) + adding;
            writeFloat(offset, r);
            return r;
        } catch (BufferOverflowException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Moves a sequence of bytes within this BytesStore from the source to destination index.
     *
     * @param from   the index of the first byte to be moved
     * @param to     the index where the first byte should be moved to
     * @param length the number of bytes to be moved
     * @throws BufferUnderflowException       If there's not enough data to be moved
     * @throws ArithmeticException            If the move would result in an index overflow
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way.
     */
    void move(@NonNegative long from, @NonNegative long to, @NonNegative long length)
            throws BufferUnderflowException, ArithmeticException, ClosedIllegalStateException, ThreadingIllegalStateException;

    /**
     * Writes a long value at a specified offset if the value is not smaller than the current value at that offset.
     *
     * @param offset  the offset to write to
     * @param atLeast the long value that is to be written at offset if it is not less than the current value at offset
     * @throws BufferUnderflowException       If there's not enough data to be moved
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    default void writeMaxLong(@NonNegative long offset, long atLeast)
            throws BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        try {
            for (; ; ) {
                long v = readVolatileLong(offset);
                if (v >= atLeast)
                    return;
                if (compareAndSwapLong(offset, v, atLeast))
                    return;
                Jvm.nanoPause();
            }
        } catch (BufferOverflowException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Writes an int value at a specified offset if the value is not smaller than the current value at that offset.
     *
     * @param offset  the offset to write to
     * @param atLeast the int value that is to be written at offset if it is not less than the current value at offset
     * @throws BufferUnderflowException       If there's not enough data to be moved
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    default void writeMaxInt(@NonNegative long offset, int atLeast)
            throws BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        try {
            for (; ; ) {
                int v = readVolatileInt(offset);
                if (v >= atLeast)
                    return;
                if (compareAndSwapInt(offset, v, atLeast))
                    return;
                Jvm.nanoPause();
            }
        } catch (BufferOverflowException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * @return {@code true} if the number of readable bytes of this BytesStore is zero.
     */
    default boolean isEmpty() {
        return readRemaining() == 0;
    }

    /**
     * Encrypts or decrypts this BytesStore using the provided Cipher and writes the result to the outBytes.
     * It ensures that outBytes has sufficient capacity for the result and restores the original read position of outBytes after operation.
     *
     * @param cipher   the Cipher to use for encryption or decryption
     * @param outBytes the Bytes object where the result will be written
     * @param using1   a ByteBuffer to use as temporary buffer during the operation
     * @param using2   another ByteBuffer to use as temporary buffer during the operation
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    default void cipher(@NotNull Cipher cipher, @NotNull Bytes<?> outBytes, @NotNull ByteBuffer using1, @NotNull ByteBuffer using2)
            throws ClosedIllegalStateException, ThreadingIllegalStateException {
        final long readPos = outBytes.readPosition();
        try {
            long writePos = outBytes.writePosition();
            BytesStore inBytes;
            long size = readRemaining();
            if (this.isDirectMemory()) {
                inBytes = this;
            } else {
                inBytes = BytesStore.nativeStore(size);
                this.copyTo(inBytes);
            }
            BytesInternal.assignBytesStoreToByteBuffer(inBytes, using1);
            int outputSize = cipher.getOutputSize(Math.toIntExact(size));
            outBytes.ensureCapacity(writePos + outputSize);
            outBytes.readPositionRemaining(writePos, outputSize);
            BytesInternal.assignBytesStoreToByteBuffer(outBytes, using2);
            int len = cipher.update(using1, using2);
            len += cipher.doFinal(using1, using2);
            assert len == using2.position();
            outBytes.writePosition(writePos + using2.position());

        } catch (IllegalStateException e) {
            throw e;
        } catch (@NotNull Exception e) {
            throw new IllegalStateException(e);
        } finally {
            // This would never fail as readPos is final and was valid from the beginning.
            outBytes.readPosition(readPos);
        }
    }

    /**
     * Convenience method to perform the cipher operation using thread local ByteBuffers for temporary buffers.
     * It encrypts or decrypts this BytesStore using the provided Cipher and writes the result to the outBytes.
     *
     * @param cipher   the Cipher to use for encryption or decryption
     * @param outBytes the Bytes object where the result will be written
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    default void cipher(@NotNull Cipher cipher, @NotNull Bytes<?> outBytes)
            throws ClosedIllegalStateException, ThreadingIllegalStateException {
        cipher(cipher, outBytes, BytesInternal.BYTE_BUFFER_TL.get(), BytesInternal.BYTE_BUFFER2_TL.get());
    }

    /**
     * Returns if this ByteStore can be both read from and written to.
     * <p>
     * This is in contrast to a ByteStore than can only be read.
     *
     * @return if this ByteStore can be both read from and written to
     */
    default boolean readWrite() {
        return true;
    }

    /**
     * Computes a hash code for this ByteStore's content up to the specified length.
     *
     * @param length the length up to which the hash code is to be computed
     * @return the computed hash code
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way.
     */
    default long hash(long length) {
        return bytesStore() instanceof NativeBytesStore
                ? OptimisedBytesStoreHash.INSTANCE.applyAsLong(this, length)
                : VanillaBytesStoreHash.INSTANCE.applyAsLong(this, length);
    }

    /**
     * Returns if a specified portion of this BytesStore is equal to a specified String.
     * The portion is specified with its offset and length.
     *
     * @param start  the portion offset
     * @param length the number of bytes from this BytesStore that should be compared to s
     * @param s      the String to compare to
     * @return {@code true} if the specified portion of this BytesStore is equal to s
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way.
     */
    default boolean isEqual(@NonNegative long start, @NonNegative long length, String s)
            throws ClosedIllegalStateException, ThreadingIllegalStateException {
        if (s == null || s.length() != length)
            return false;
        int length2 = (int) length;
        for (int i = 0; i < length2; i++)
            if (s.charAt(i) != readUnsignedByte(start + i))
                return false;
        return true;
    }
}
