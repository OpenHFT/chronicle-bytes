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
import net.openhft.chronicle.bytes.internal.NativeBytesStore;
import net.openhft.chronicle.core.*;
import net.openhft.chronicle.core.annotation.Java9;
import net.openhft.chronicle.core.annotation.NonNegative;
import net.openhft.chronicle.core.io.ClosedIllegalStateException;
import net.openhft.chronicle.core.io.IORuntimeException;
import net.openhft.chronicle.core.io.ThreadingIllegalStateException;
import net.openhft.chronicle.core.util.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

import static net.openhft.chronicle.core.Jvm.uncheckedCast;
import static net.openhft.chronicle.core.util.Longs.requireNonNegative;
import static net.openhft.chronicle.core.util.ObjectUtils.requireNonNull;

/**
 * A simple Bytes implementation that is non-elastic. It does not support dynamic resizing.
 * This class provides functionality to work with a sequence of bytes, offering various read and write operations.
 *
 * @param <U> the type of the underlying object representation
 */
@SuppressWarnings("rawtypes")
public class VanillaBytes<U>
        extends AbstractBytes<U>
        implements Byteable, Comparable<CharSequence> {

    /**
     * Constructor for creating an instance of VanillaBytes with the given BytesStore,
     * using its write position and write limit.
     *
     * @param bytesStore the BytesStore to be wrapped by the VanillaBytes.
     * @throws IllegalArgumentException       If the write limit of bytesStore is negative.
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    protected VanillaBytes(@NotNull BytesStore<?, ?> bytesStore)
            throws ClosedIllegalStateException, IllegalArgumentException, ThreadingIllegalStateException {
        this(bytesStore, bytesStore.writePosition(), bytesStore.writeLimit());
    }

    /**
     * Constructor for creating an instance of VanillaBytes with the given BytesStore,
     * write position, and write limit.
     *
     * @param bytesStore    the BytesStore to be wrapped by the VanillaBytes.
     * @param writePosition the position at which the next write will occur.
     * @param writeLimit    the maximum limit up to which writing can occur.
     * @throws IllegalArgumentException       If the write limit is negative.
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    protected VanillaBytes(@NotNull BytesStore<?, ?> bytesStore, long writePosition, long writeLimit)
            throws ClosedIllegalStateException, IllegalArgumentException, ThreadingIllegalStateException {
        super(uncheckedCast(bytesStore), writePosition, writeLimit);
    }

    /**
     * Factory method for creating an instance of VanillaBytes that wraps the provided BytesStore.
     *
     * @param bytesStore the BytesStore to be wrapped by the VanillaBytes.
     * @return a new instance of VanillaBytes that wraps the given BytesStore.
     * @throws IllegalArgumentException       If the write limit is negative.
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    public static <U> VanillaBytes<U> wrap(BytesStore<?, U> bytesStore)
            throws ClosedIllegalStateException, ThreadingIllegalStateException {
        return new VanillaBytes<>(bytesStore);
    }

    /**
     * Factory method for creating an instance of VanillaBytes with no initial ByteStore.
     * This can be used in scenarios where the ByteStore is to be replaced or provided at a later point.
     *
     * @return a new instance of VanillaBytes with no ByteStore.
     */
    @NotNull
    public static VanillaBytes<Void> vanillaBytes() {
        return new NativeBytes<>(BytesStore.empty());
    }

    @Java9
    private static boolean isEqual0(byte[] bytes, byte coder, @NotNull NativeBytesStore bs, long address) {

        @Nullable Memory memory = bs.memory;

        if (coder == 0) {
            int i = 0;
            for (; i < bytes.length; i++) {
                byte b = memory.readByte(address + i);
                char c = (char) (bytes[i] & 0xFF);
                if (b != c) {
                    return false;
                }
            }
        } else {
            int i = 0;
            for (; i < bytes.length; i++) {
                byte b = memory.readByte(address + i);
                char c = (char) (((bytes[i + 1] & 0xFF) << 8) | (bytes[i] & 0xFF));

                if (b != c) {
                    return false;
                }
            }
        }

        return true;
    }

    private static boolean isEqual0(char[] chars, @NotNull NativeBytesStore bs, long address) {

        @Nullable Memory memory = bs.memory;
        int i = 0;
        for (; i < chars.length - 3; i += 4) {
            int b = memory.readInt(address + i);
            int b0 = b & 0xFF;
            int b1 = (b >> 8) & 0xFF;
            int b2 = (b >> 16) & 0xFF;
            int b3 = (b >> 24) & 0xFF;
            if (b0 != chars[i] || b1 != chars[i + 1] || b2 != chars[i + 2] || b3 != chars[i + 3])
                return false;
        }
        for (; i < chars.length; i++) {
            int b = memory.readByte(address + i) & 0xFF;
            if (b != chars[i])
                return false;
        }

        return true;
    }

    private static boolean isEqual1(char[] chars, @NotNull BytesStore<?, ?> bytesStore, @NonNegative long readPosition)
            throws BufferUnderflowException {
        for (int i = 0; i < chars.length; i++) {
            int b = bytesStore.readByte(readPosition + i) & 0xFF;
            if (b != chars[i])
                return false;
        }
        return true;
    }

    @Java9
    private static boolean isEqual1(byte[] bytes, byte coder, @NotNull BytesStore<?, ?> bytesStore, @NonNegative long readPosition)
            throws BufferUnderflowException {
        for (int i = 0; i < bytes.length; i++) {
            int b = bytesStore.readByte(readPosition + i) & 0xFF;
            char c;

            if (coder == 0) {
                c = (char) (bytes[i] & 0xFF);
            } else {
                c = (char) (((bytes[i + 1] & 0xFF) << 8) | (bytes[i] & 0xFF));
                i++;
            }

            if (b != c)
                return false;
        }
        return true;
    }

    @Override
    public long readVolatileLong(@NonNegative long offset)
            throws BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        readCheckOffset(offset, Long.BYTES, true);
        return bytesStore.readVolatileLong(offset);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override
    public void bytesStore(final @NotNull BytesStore byteStore,
                           final @NonNegative long offset,
                           final @NonNegative long length)
            throws ClosedIllegalStateException, IllegalArgumentException, BufferUnderflowException, ThreadingIllegalStateException {
        requireNonNull(byteStore);
        setBytesStore(byteStore);
        // assume its read-only
        readLimit(offset + length);
        readPosition(offset);
        writeLimit(offset + length);
    }

    private void setBytesStore(@NotNull BytesStore<?, U> bytesStore)
            throws ClosedIllegalStateException, IllegalArgumentException, ThreadingIllegalStateException {
        if (this.bytesStore != bytesStore) {
            @Nullable BytesStore<?, ?> oldBS = this.bytesStore;
            this.bytesStore(bytesStore);
            bytesStore.reserve(this);
            oldBS.release(this);
        }
        clear();
    }

    @Override
    public long offset() {
        return readPosition();
    }

    @Override
    public long maxSize() {
        return readRemaining();
    }

    @Override
    public boolean isElastic() {
        return false;
    }

    @NotNull
    @Override
    public Bytes<U> bytesForRead()
            throws ClosedIllegalStateException, ThreadingIllegalStateException {
        throwExceptionIfReleased();
        return isClear()
                ? new VanillaBytes<>(bytesStore, writePosition(), bytesStore.writeLimit())
                : new SubBytes<>(bytesStore, readPosition(), readLimit());
    }

    @Override
    public boolean isEqual(@Nullable String other) {
        if (other == null || other.length() != readRemaining()) return false;
        ReportUnoptimised.reportOnce();

        long realLength = realReadRemaining();

        if (Jvm.isJava9Plus()) {
            byte[] bytes = StringUtils.extractBytes(other);
            byte coder = StringUtils.getStringCoder(other);
            if (bytesStore instanceof NativeBytesStore && realLength == readRemaining()) {
                @NotNull NativeBytesStore bs = (NativeBytesStore) this.bytesStore;
                long address = bs.addressForRead(readPosition);
                return isEqual0(bytes, coder, bs, address);

            } else {
                return isEqual1(bytes, coder, bytesStore, readPosition);
            }
        } else {
            char[] chars = StringUtils.extractChars(other);
            if (bytesStore instanceof NativeBytesStore && realLength == readRemaining()) {
                @NotNull NativeBytesStore bs = (NativeBytesStore) this.bytesStore;
                long address = bs.addressForRead(readPosition);
                return isEqual0(chars, bs, address);

            } else {
                return isEqual1(chars, bytesStore, readPosition);
            }
        }
    }

    @Override
    public @NonNegative long realCapacity() {
        return bytesStore.realCapacity();
    }

    @Override
    public long findByte(byte stopByte) throws ClosedIllegalStateException {
        return BytesInternal.findByte(this, stopByte);
    }

    @Override
    public long parseLong() throws BufferUnderflowException, ClosedIllegalStateException {
        return BytesInternal.parseLong(this);
    }

    @NotNull
    @Override
    public BytesStore<Bytes<U>, U> copy()
            throws ClosedIllegalStateException, ThreadingIllegalStateException {
        throwExceptionIfReleased();
        ReportUnoptimised.reportOnce();

        if (bytesStore.underlyingObject() instanceof ByteBuffer) {
            ByteBuffer bb = ByteBuffer.allocateDirect(Maths.toInt32(readRemaining()));
            @NotNull ByteBuffer bbu = (ByteBuffer) bytesStore.underlyingObject();
            ByteBuffer slice = bbu.slice();
            slice.position((int) readPosition());
            slice.limit((int) readLimit());
            bb.put(slice);
            bb.clear();
            return uncheckedCast(BytesStore.wrap(bb));
        } else {
            return uncheckedCast(BytesUtil.copyOf(this));
        }
    }

    @SuppressWarnings("deprecation")
    protected void optimisedWrite(@NotNull RandomDataInput bytes, @NonNegative long offset, @NonNegative long length)
            throws BufferOverflowException, BufferUnderflowException, ClosedIllegalStateException, IllegalArgumentException, ThreadingIllegalStateException {
        requireNonNull(bytes);
        if (length <= safeCopySize() && isDirectMemory() && bytes.isDirectMemory()) {
            long len = Math.min(writeRemaining(), Math.min(bytes.capacity() - offset, length));
            if (len > 0) {
                writeCheckOffset(writePosition(), len);
                long address = bytes.addressForRead(offset);
                long address2 = addressForWritePosition();
                assert address != 0;
                assert address2 != 0;
                OS.memory().copyMemory(address, address2, len);
                writeSkip(len);
            }
        } else {
            BytesInternal.writeFully(bytes, offset, length, this);
        }
    }

    public void write(long position, @NotNull CharSequence str, @NonNegative int offset, @NonNegative int length)
            throws BufferOverflowException, IllegalArgumentException, ArithmeticException, ClosedIllegalStateException, BufferUnderflowException, ThreadingIllegalStateException {
        requireNonNull(str);
        if ((offset | length | position) < 0) {
            requireNonNegative(position);
            requireNonNegative(offset);
            requireNonNegative(length);
        }

        ensureCapacity(writePosition() + length);
        if (offset + length > str.length())
            throw new IllegalArgumentException("offset=" + offset + " + length=" + length + " > str.length =" + str.length());

        for (int i = 0; i < length; i++) {
            int index = offset + i;
            bytesStore.writeByte(position + i, charAt(str, index));
        }
    }

    private char charAt(@NotNull CharSequence str, @NonNegative int index) {
        return str.charAt(index);
    }

    @Override
    @NotNull
    public VanillaBytes<U> append(@NotNull CharSequence str, @NonNegative int start, @NonNegative int end)
            throws IndexOutOfBoundsException {
        assert end > start : "end=" + end + ",start=" + start;
        requireNonNull(str);
        try {
            if (isDirectMemory()) {
                if (str instanceof BytesStore) {

                    write((BytesStore) str, start, (long) end - start);
                    return this;
                }
                if (str instanceof String) {
                    if (Jvm.isJava9Plus()) {
                        byte coder = StringUtils.getStringCoder((String) str);
                        appendUtf8(StringUtils.extractBytes((String) str), start, end - start, coder);
                    } else {
                        appendUtf8(StringUtils.extractChars((String) str), start, end - start);
                    }

                    return this;
                }
            }
            ReportUnoptimised.reportOnce();
            super.append(str, start, end);
            return this;

        } catch (Exception e) {
            final IndexOutOfBoundsException ioobe = new IndexOutOfBoundsException(e.toString());
            ioobe.initCause(e);
            throw ioobe;
        }
    }

    @NotNull
    @Override
    public VanillaBytes<U> appendUtf8(@NotNull CharSequence str)
            throws BufferOverflowException {
        requireNonNull(str);
        ReportUnoptimised.reportOnce();
        try {
            if (isDirectMemory()) {
                if (str instanceof BytesStore) {
                    write((BytesStore) str, 0L, str.length());
                    return this;
                }
                if (str instanceof String) {
                    if (Jvm.isJava9Plus()) {
                        String str1 = (String) str;
                        byte coder = StringUtils.getStringCoder(str1);
                        appendUtf8(StringUtils.extractBytes(str1), 0, str.length(), coder);
                    } else {
                        appendUtf8(StringUtils.extractChars((String) str), 0, str.length());
                    }
                    return this;
                }
            }
            super.append(str, 0, str.length());
            return this;

        } catch (Exception e) {
            @NotNull BufferOverflowException e2 = new BufferOverflowException();
            e2.initCause(e);
            throw e2;
        }
    }

    @Override
    @NotNull
    public Bytes<U> append8bit(@NotNull CharSequence cs)
            throws BufferOverflowException, BufferUnderflowException, IndexOutOfBoundsException, ClosedIllegalStateException, ThreadingIllegalStateException {
        requireNonNull(cs);

        if (isDirectMemory() && cs instanceof String && this.bytesStore instanceof NativeBytesStore)
            return append8bitNBSS((String) cs);

        return append8bit0(cs);
    }

    @Override
    @NotNull
    public Bytes<U> append8bit(@NotNull BytesStore<?, ?> bs)
            throws BufferOverflowException, BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        long remaining = bs.readLimit() - bs.readPosition();
        return write0(bs, 0L, remaining);
    }

    @NotNull
    @Override
    public Bytes<U> write(@NotNull BytesStore<?, ?> bytes, @NonNegative long offset, @NonNegative long length)
            throws BufferOverflowException, BufferUnderflowException, ClosedIllegalStateException, IllegalArgumentException, ThreadingIllegalStateException {
        requireNonNull(bytes);
        if ((offset | length) < 0) {
            requireNonNegative(offset);
            requireNonNegative(length);
        }
        return write0(bytes, offset, length);
    }

    @NotNull
    private VanillaBytes<U> write0(@NotNull BytesStore<?, ?> bytes, @NonNegative long offset, long length) throws ClosedIllegalStateException, ThreadingIllegalStateException {
        ensureCapacity(writePosition() + length);
        if (length == (int) length) {
            if (bytes.canReadDirect(length) && canWriteDirect(length)) {
                long wAddr = addressForWritePosition();
                writeSkip(length);
                long rAddr = bytes.addressForRead(offset);
                UnsafeMemory.copyMemory(rAddr, wAddr, (int) length);
                return this;

            } else {
                bytesStore.write(writePosition(), bytes.bytesStore(), offset, length);
                uncheckedWritePosition(writePosition() + length);
                return this;
            }
        }
        ReportUnoptimised.reportOnce();
        BytesInternal.writeFully(bytes, offset, length, this);
        return this;
    }

    @Override
    @NotNull
    public Bytes<U> append8bit(@NotNull String cs)
            throws BufferOverflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        requireNonNull(cs);
        if (isDirectMemory())
            return append8bitNBSS(cs);
        return append8bit0(cs);
    }

    @NotNull
    private Bytes<U> append8bitNBSS(@NotNull String s)
            throws BufferOverflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        int length = s.length();
        long offset = writeOffsetPositionMoved(length); // can re-assign the byteStore if not large enough.
        // only valid after the previous call
        NativeBytesStore bytesStore = (NativeBytesStore) this.bytesStore;
        final long address = bytesStore.address + bytesStore.translate(offset);
        @Nullable final Memory memory = bytesStore.memory;

        if (memory == null) {
            bytesStore.throwExceptionIfReleased();
            throw new NullPointerException("byteStore.memory is null.");
        }

        if (Jvm.isJava9Plus()) {
            final byte[] chars = StringUtils.extractBytes(s);

            int i;
            for (i = 0; i < length; i++) {
                memory.writeByte(address + i, chars[i]);
            }
        } else {
            final char[] chars = StringUtils.extractChars(s);
            int i;
            for (i = 0; i < length - 3; i += 4) {
                int c0 = chars[i] & 0xFF;
                int c1 = chars[i + 1] & 0xFF;
                int c2 = chars[i + 2] & 0xFF;
                int c3 = chars[i + 3] & 0xFF;
                memory.writeInt(address + i, c0 | (c1 << 8) | (c2 << 16) | (c3 << 24));
            }
            for (; i < length; i++) {
                int c0 = chars[i];
                memory.writeByte(address + i, (byte) c0);
            }
        }
        return this;
    }

    @Override
    @NotNull
    public String toString() {
        try {
            // Reserving prevents access to this Bytes object if released by another thread
            reserve(this);
            try {
                return bytesStore instanceof NativeBytesStore
                        ? toString2((NativeBytesStore) bytesStore)
                        : toString0();
            } catch (IllegalStateException e) {
                throw Jvm.rethrow(e);
            } finally {
                release(this);
            }
        } catch (Exception e) {
            return e.toString();
        }
    }

    private String toString2(@NotNull NativeBytesStore bytesStore) {
        @Nullable final Memory memory = bytesStore.memory;
        int length = (int)
                Math.min(Bytes.MAX_HEAP_CAPACITY, realReadRemaining());
        @NotNull char[] chars = new char[length];
        final long address = bytesStore.address + bytesStore.translate(readPosition());
        for (int i = 0; i < length && i < realCapacity(); i++)
            chars[i] = (char) (memory.readByte(address + i) & 0xFF);

        return StringUtils.newString(chars);
    }

    @NotNull
    protected String toString0()
            throws ClosedIllegalStateException {
        int length = (int) Math.min(Bytes.MAX_HEAP_CAPACITY, readRemaining());
        @NotNull char[] chars = new char[length];
        try {
            for (int i = 0; i < length; i++) {
                chars[i] = (char) (bytesStore.readByte(readPosition() + i) & 0xFF);
            }
        } catch (BufferUnderflowException e) {
            // ignored
        }
        return StringUtils.newString(chars);
    }

    @NotNull
    protected Bytes<U> append8bit0(@NotNull CharSequence cs)
            throws BufferOverflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        int length = cs.length();
        long offset = writeOffsetPositionMoved(length);
        for (int i = 0; i < length; i++) {
            char c = charAt(cs, i);
            if (c > 255) c = '?';
            bytesStore.writeByte(offset + i, (byte) c);
        }
        return this;
    }

    @Override
    public boolean equalBytes(@NotNull BytesStore<?, ?> bytesStore, long length)
            throws BufferUnderflowException, ClosedIllegalStateException {
        requireNonNull(bytesStore);
        ReportUnoptimised.reportOnce();
        if (length < 0) throw new IllegalArgumentException();

        if (isDirectMemory() &&
                bytesStore instanceof VanillaBytes &&
                bytesStore.isDirectMemory()) {
            @NotNull VanillaBytes<U> b2 = uncheckedCast(bytesStore);
            @NotNull NativeBytesStore nbs0 = (NativeBytesStore) this.bytesStore;
            @Nullable NativeBytesStore nbs2 = (NativeBytesStore) b2.bytesStore();
            long i = 0;
            for (; i < length - 7; i += 8) {
                long addr0 = nbs0.address + readPosition() - nbs0.start() + i;
                long addr2 = nbs2.address + b2.readPosition() - nbs2.start() + i;
                long l0 = nbs0.memory.readLong(addr0);
                long l2 = nbs2.memory.readLong(addr2);
                if (l0 != l2)
                    return false;
            }
            for (; i < length; i++) {
                long offset2 = readPosition() + i - nbs0.start();
                long offset21 = b2.readPosition() + i - nbs2.start();
                byte b0 = nbs0.memory.readByte(nbs0.address + offset2);
                byte b1 = nbs2.memory.readByte(nbs2.address + offset21);
                if (b0 != b1)
                    return false;
            }
            return true;
        } else {
            return super.equalBytes(bytesStore, length);
        }
    }

    public void read8Bit(char[] chars, @NonNegative int length)
            throws BufferUnderflowException, ClosedIllegalStateException {
        ReportUnoptimised.reportOnce();

        if (isDirectMemory()) {
            long position = readPosition();
            @NotNull NativeBytesStore nbs = (NativeBytesStore) bytesStore();
            nbs.read8bit(position, chars, length);
        } else {
            long pos = this.readPosition();
            for (int i = 0; i < length; i++)
                chars[i] = (char) this.readUnsignedByte(pos + i);
        }
    }

    // TODO: protected?
    @Override
    public int byteCheckSum(@NonNegative int start, @NonNegative int end)
            throws IORuntimeException, BufferUnderflowException {
        byte b = 0;
        // the below cast is safe as should only be called from net.openhft.chronicle.bytes.AbstractBytes.byteCheckSum(long, long)
        @Nullable final NativeBytesStore bytesStore = (NativeBytesStore) bytesStore();
        @Nullable final Memory memory = bytesStore.memory;
        assert memory != null;
        final long addr = bytesStore.addressForRead(start);
        int i = 0;
        final int len = end - start;
        for (; i < len - 3; i += 4) {
            b += (byte) (memory.readByte(addr + i)
                                + memory.readByte(addr + i + 1)
                                + memory.readByte(addr + i + 2)
                                + memory.readByte(addr + i + 3));
        }
        for (; i < len; i++) {
            b += memory.readByte(addr + i);
        }
        return b & 0xFF;
    }

    @NotNull
    @Override
    public Bytes<U> appendUtf8(char[] chars, @NonNegative int offset, @NonNegative int length)
            throws BufferOverflowException, ClosedIllegalStateException, BufferUnderflowException, IllegalArgumentException, ThreadingIllegalStateException {
        long actualUTF8Length = AppendableUtil.findUtf8Length(chars, offset, length);
        ensureCapacity(writePosition() + actualUTF8Length);
        if (bytesStore instanceof NativeBytesStore) {
            @Nullable NativeBytesStore nbs = (NativeBytesStore) this.bytesStore;
            long position = nbs.appendUtf8(writePosition(), chars, offset, length);
            writePosition(position);
        } else {
            ReportUnoptimised.reportOnce();
            super.appendUtf8(chars, offset, length);
        }
        return this;
    }

    @Override
    public ByteBuffer toTemporaryDirectByteBuffer()
            throws IllegalArgumentException, ArithmeticException, ClosedIllegalStateException {
        throwExceptionIfReleased();
        if (isClear())
            return bytesStore.toTemporaryDirectByteBuffer();
        return super.toTemporaryDirectByteBuffer();
    }

    @Override
    public int read(byte[] bytes)
            throws BufferUnderflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        requireNonNull(bytes);
        ReportUnoptimised.reportOnce();

        int len = (int) Math.min(bytes.length, readRemaining());
        if (bytesStore instanceof NativeBytesStore) {
            @Nullable NativeBytesStore nbs = (NativeBytesStore) this.bytesStore;
            long len2 = nbs.read(readPosition(), bytes, 0, len);
            readSkip(len2);
            return (int) (len2);
        }
        return super.read(bytes);
    }

    @Override
    public int compareTo(@NotNull CharSequence cs) {
        long len1 = readRemaining();
        int len2 = cs.length();
        long lim = Math.min(len1, len2);

        int k = 0;
        while (k < lim) {
            char c1 = charAt(bytesStore, k);
            char c2 = charAt(cs, k);
            if (c1 != c2) {
                return c1 - c2;
            }
            k++;
        }
        return (int) (len1 - len2);
    }
}
