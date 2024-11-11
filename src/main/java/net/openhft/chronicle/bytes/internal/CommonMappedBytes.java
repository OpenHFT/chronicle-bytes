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
package net.openhft.chronicle.bytes.internal;

import net.openhft.chronicle.bytes.*;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.Memory;
import net.openhft.chronicle.core.OS;
import net.openhft.chronicle.core.annotation.NonNegative;
import net.openhft.chronicle.core.io.*;
import net.openhft.chronicle.core.util.ObjectUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;

import static net.openhft.chronicle.bytes.algo.OptimisedBytesStoreHash.IS_LITTLE_ENDIAN;
import static net.openhft.chronicle.core.util.Ints.requireNonNegative;
import static net.openhft.chronicle.core.util.Longs.requireNonNegative;
import static net.openhft.chronicle.core.util.ObjectUtils.requireNonNull;
import static net.openhft.chronicle.core.util.StringUtils.*;

/**
 * Bytes to wrap memory mapped data.
 * <p>
 * NOTE These Bytes are single Threaded as are all Bytes.
 */
public abstract class CommonMappedBytes extends MappedBytes {
    private final AbstractCloseable closeable = new AbstractCloseable() {
        @Override
        protected void performClose() throws ClosedIllegalStateException {
            CommonMappedBytes.this.performClose();
        }
    };

    protected final MappedFile mappedFile;
    private final boolean backingFileIsReadOnly;
    private final long capacity;

    protected long lastActualSize = 0;
    private boolean initReleased;

    // assume the mapped file is reserved already.
    protected CommonMappedBytes(@NotNull final MappedFile mappedFile)
            throws ClosedIllegalStateException {
        this(mappedFile, "");
    }

    @SuppressWarnings("this-escape")
    protected CommonMappedBytes(@NotNull final MappedFile mappedFile, final String name)
            throws ClosedIllegalStateException, ThreadingIllegalStateException {
        super(name);

        assert mappedFile != null;
        this.mappedFile = mappedFile;
        mappedFile.reserve(this);
        this.backingFileIsReadOnly = mappedFile.readOnly();
        assert !mappedFile.isClosed();
        clear();
        capacity = mappedFile.capacity();
    }

    @Override
    public void singleThreadedCheckReset() {
        super.singleThreadedCheckReset();
        closeable.singleThreadedCheckReset();
    }

    @Override
    public @NotNull CommonMappedBytes write(final byte[] byteArray,
                                            @NonNegative final int offset,
                                            @NonNegative final int length)
            throws ClosedIllegalStateException, BufferOverflowException {
        requireNonNull(byteArray);
        requireNonNegative(offset);
        requireNonNegative(length);
        throwExceptionIfClosed();

        write(writePosition(), byteArray, offset, length);
        uncheckedWritePosition(writePosition() + Math.min(length, byteArray.length - offset));
        return this;
    }

    @NotNull
    public CommonMappedBytes write(@NonNegative final long offsetInRDO, @NotNull final RandomDataInput bytes)
            throws BufferOverflowException, ClosedIllegalStateException {
        requireNonNegative(offsetInRDO);
        requireNonNull(bytes);
        throwExceptionIfClosed();

        write(offsetInRDO, bytes, bytes.readPosition(), bytes.readRemaining());
        return this;
    }

    @NotNull
    public MappedFile mappedFile() {
        return mappedFile;
    }

    @Override
    public BytesStore<Bytes<Void>, Void> copy()
            throws ClosedIllegalStateException {
        return BytesUtil.copyOf(this);
    }

    @Override
    public @NonNegative long capacity() {
        return capacity;
    }

    @Override
    public Bytes<Void> readLimitToCapacity() {
        uncheckedWritePosition(capacity);
        return this;
    }

    @Override
    public long realReadRemaining() {
        long limit = readLimit();
        if (limit > lastActualSize)
            limit = Math.min(realCapacity(), limit);
        return limit - readPosition();
    }

    @Override
    public long realWriteRemaining() {
        long limit = writeLimit();
        if (limit > lastActualSize)
            limit = Math.min(realCapacity(), limit);
        return limit - writePosition();
    }

    @Override
    public @NonNegative long realCapacity() {

        try {
            lastActualSize = mappedFile.actualSize();
            return lastActualSize;

        } catch (Exception e) {
            Jvm.warn().on(getClass(), "Unable to obtain the real size for " + mappedFile.file(), e);
            lastActualSize = 0;
            return lastActualSize;
        }
    }

    @Nullable
    @Override
    public String read8bit()
            throws IORuntimeException, BufferUnderflowException, ClosedIllegalStateException, ArithmeticException {

        return BytesInternal.read8bit(this);
    }

    @Override
    public @NotNull Bytes<Void> writeSkip(long bytesToSkip)
            throws BufferOverflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        // only check up to 128 bytes are real.
        writeCheckOffset(writePosition(), Math.min(128, bytesToSkip));
        // the rest can be lazily allocated.
        uncheckedWritePosition(writePosition() + bytesToSkip);
        return this;
    }

    @Override
    public @NonNegative long start() {
        return 0L;
    }

    @NotNull
    @Override
    public Bytes<Void> writePosition(@NonNegative final long position)
            throws BufferOverflowException {
        //  throwExceptionIfClosed
        if (position > writeLimit)
            throw new BufferOverflowException();
        if (position < 0L)
            throw new BufferOverflowException();
        if (position < readPosition)
            this.readPosition = position;
        uncheckedWritePosition(position);
        return this;
    }

    @NotNull
    @Override
    public Bytes<Void> clear()
            throws ClosedIllegalStateException {
        // Typically, only used at the start of an operation so reject if closed.
        throwExceptionIfClosed();

        long start = 0L;
        readPosition = start;
        uncheckedWritePosition(start);
        writeLimit = capacity;
        return this;
    }

    @Override
    protected void performRelease() throws ClosedIllegalStateException {
        super.performRelease();
        mappedFile.release(this);
    }

    @NotNull
    @Override
    public Bytes<Void> append8bit(@NotNull CharSequence text, @NonNegative int start, @NonNegative int end)
            throws IllegalArgumentException, BufferOverflowException, BufferUnderflowException,
            IndexOutOfBoundsException, ClosedIllegalStateException, ThreadingIllegalStateException {
        requireNonNull(text);
        throwExceptionIfClosed();

        // check the start.
        long pos = writePosition();
        writeCheckOffset(pos, 0);
        if (!(text instanceof String) || pos + (end - start) * 3L + 5 >= safeLimit()) {
            return super.append8bit(text, start, end);
        }
        return append8bit0((String) text, start, end - start);
    }

    @Override
    @NotNull
    public CommonMappedBytes write8bit(@NotNull CharSequence text, @NonNegative int start, @NonNegative int length)
            throws ClosedIllegalStateException, BufferUnderflowException, BufferOverflowException, ArithmeticException, IndexOutOfBoundsException, ThreadingIllegalStateException {
        requireNonNull(text);
        throwExceptionIfClosed();

        ObjectUtils.requireNonNull(text);

        // check the start.
        long pos = writePosition();
        writeCheckOffset(pos, 0);
        if (!(text instanceof String) || pos + length * 3L + 5 >= safeLimit()) {
            super.write8bit(text, start, length);
            return this;
        }

        writeStopBit(length);
        return append8bit0((String) text, start, length);
    }

    @NotNull
    private CommonMappedBytes append8bit0(@NotNull String s, @NonNegative int start, @NonNegative int length)
            throws BufferOverflowException, ClosedIllegalStateException, ThreadingIllegalStateException {

        requireNonNull(s);
        ensureCapacity(writePosition() + length);
        long address = addressForWritePosition();
        @SuppressWarnings({"unchecked", "rawtypes"})
        MappedBytesStore mbs = (MappedBytesStore) (BytesStore) bytesStore();
        Memory memory = mbs.memory;
        if (Jvm.isJava9Plus()) {
            byte[] bytes = extractBytes(s);
            int i = 0;
            for (; i < length - 3; i += 4) {
                int c0 = bytes[i + start] & 0xff;
                int c1 = bytes[i + start + 1] & 0xff;
                int c2 = bytes[i + start + 2] & 0xff;
                int c3 = bytes[i + start + 3] & 0xff;
                if (IS_LITTLE_ENDIAN) {
                    memory.writeInt(address, (c3 << 24) | (c2 << 16) | (c1 << 8) | c0);
                } else {
                    memory.writeInt(address, (c0 << 24) | (c1 << 16) | (c2 << 8) | c3);
                }
                address += 4;
            }
            for (; i < length; i++) {
                byte c = bytes[i + start];
                memory.writeByte(address++, c);
            }
            writeSkip(length);
        } else {
            char[] chars = extractChars(s);
            int i = 0;
            for (; i < length - 3; i += 4) {
                int c0 = chars[i + start] & 0xff;
                int c1 = chars[i + start + 1] & 0xff;
                int c2 = chars[i + start + 2] & 0xff;
                int c3 = chars[i + start + 3] & 0xff;
                if (IS_LITTLE_ENDIAN) {
                    memory.writeInt(address, (c3 << 24) | (c2 << 16) | (c1 << 8) | c0);
                } else {
                    memory.writeInt(address, (c0 << 24) | (c1 << 16) | (c2 << 8) | c3);
                }
                address += 4;
            }
            for (; i < length; i++) {
                char c = chars[i + start];
                memory.writeByte(address++, (byte) c);
            }
            writeSkip(length);
        }
        return this;
    }

    @NotNull
    @Override
    public Bytes<Void> appendUtf8(@NotNull CharSequence cs, @NonNegative int start, @NonNegative int length)
            throws BufferOverflowException, ClosedIllegalStateException, BufferUnderflowException, IndexOutOfBoundsException, ThreadingIllegalStateException {
        requireNonNull(cs);
        throwExceptionIfClosed();

        // check the start.
        long pos = writePosition();
        writeCheckOffset(pos, 0);
        if (!(cs instanceof String) || pos + length * 3L + 5 >= safeLimit()) {
            super.appendUtf8(cs, start, length);
            return this;
        }

        if (Jvm.isJava9Plus()) {
            final String str = (String) cs;
            long address = addressForWrite(pos);
            Memory memory = OS.memory();
            int i = 0;
            non_ascii:
            {
                for (; i < length; i++) {
                    char c = str.charAt(i + start);
                    if (c > 127) {
                        writeSkip(i);
                        break non_ascii;
                    }
                    memory.writeByte(address++, (byte) c);
                }
                writeSkip(length);
                return this;
            }
            for (; i < length; i++) {
                char c = str.charAt(i + start);
                appendUtf8(c);
            }
        } else {
            char[] chars = extractChars((String) cs);
            long address = addressForWrite(pos);
            Memory memory = OS.memory();
            int i = 0;
            non_ascii:
            {
                for (; i < length; i++) {
                    char c = chars[i + start];
                    if (c > 127) {
                        writeSkip(i);
                        break non_ascii;
                    }
                    memory.writeByte(address++, (byte) c);
                }
                writeSkip(length);
                return this;
            }
            for (; i < length; i++) {
                char c = chars[i + start];
                appendUtf8(c);
            }
        }
        return this;
    }

    @Override
    public void release(ReferenceOwner id)
            throws ClosedIllegalStateException {
        try {
            super.release(id);
        } catch (ClosedIllegalStateException ignored) {
            // ignored
        }
        initReleased |= id == INIT;
        if (refCount() <= 0)
            closeable.close();
    }

    @Override
    public void releaseLast(ReferenceOwner id)
            throws ClosedIllegalStateException {
        super.releaseLast(id);
        initReleased |= id == INIT;
        closeable.close();
    }

    @Override
    public void close() {
        closeable.close();
    }

    void performClose() throws ClosedIllegalStateException {
        if (!initReleased)
            release(INIT);
    }

    @Override
    public boolean isClosed() {
        return closeable.isClosed();
    }

    @Override
    public void warnAndCloseIfNotClosed() {
        closeable.warnAndCloseIfNotClosed();
    }

    @Override
    public void throwExceptionIfClosed() throws ClosedIllegalStateException, ThreadingIllegalStateException {
        closeable.throwExceptionIfClosed();
    }

    @NotNull
    @Override
    public Bytes<Void> writeUtf8(@Nullable CharSequence text)
            throws BufferOverflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        throwExceptionIfClosed();

        if (text instanceof String) {
            writeUtf8((String) text);
            return this;
        }
        if (text == null) {
            BytesInternal.writeStopBitNeg1(this);

        } else {
            long utfLength = AppendableUtil.findUtf8Length(text);
            this.writeStopBit(utfLength);
            BytesInternal.appendUtf8(this, text, 0, text.length());
        }
        return this;
    }

    @Override
    public @NotNull Bytes<Void> writeUtf8(@Nullable String text)
            throws BufferOverflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        throwExceptionIfClosed();

        if (text == null) {
            BytesInternal.writeStopBitNeg1(this);
            return this;
        }

        if (Jvm.isJava9Plus()) {
            byte[] strBytes = extractBytes(text);
            byte coder = getStringCoder(text);
            long utfLength = AppendableUtil.findUtf8Length(strBytes, coder);
            writeStopBit(utfLength);
            appendUtf8(strBytes, 0, text.length(), coder);
        } else {
            char[] chars = extractChars(text);
            long utfLength = AppendableUtil.findUtf8Length(chars);
            writeStopBit(utfLength);
            appendUtf8(chars, 0, chars.length);
        }
        return this;
    }

    @Override
    public long readStopBit()
            throws IORuntimeException, ClosedIllegalStateException, BufferUnderflowException {
        throwExceptionIfClosed();

        long offset = readOffsetPositionMoved(1);
        byte l = bytesStore.readByte(offset);

        if (l >= 0)
            return l;
        return BytesInternal.readStopBit0(this, l);
    }

    @Override
    public char readStopBitChar()
            throws IORuntimeException, ClosedIllegalStateException, BufferUnderflowException {
        throwExceptionIfClosed();

        long offset = readOffsetPositionMoved(1);
        byte l = bytesStore.readByte(offset);

        if (l >= 0)
            return (char) l;
        return (char) BytesInternal.readStopBit0(this, l);
    }

    @NotNull
    @Override
    public Bytes<Void> writeStopBit(long n)
            throws BufferOverflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        throwExceptionIfClosed();

        if ((n & ~0x7F) == 0) {
            writeByte((byte) (n & 0x7f));
            return this;
        }
        if ((~n & ~0x7F) == 0) {
            writeByte((byte) (0x80L | ~n));
            writeByte((byte) 0);
            return this;
        }

        if ((n & ~0x3FFF) == 0) {
            writeByte((byte) ((n & 0x7f) | 0x80));
            writeByte((byte) (n >> 7));
            return this;
        }
        BytesInternal.writeStopBit0(this, n);
        return this;
    }

    @NotNull
    @Override
    public Bytes<Void> writeStopBit(char n)
            throws BufferOverflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        throwExceptionIfClosed();

        if ((n & ~0x7F) == 0) {
            writeByte((byte) (n & 0x7f));
            return this;
        }

        if ((n & ~0x3FFF) == 0) {
            writeByte((byte) ((n & 0x7f) | 0x80));
            writeByte((byte) (n >> 7));
            return this;
        }
        BytesInternal.writeStopBit0(this, n);
        return this;
    }

    @Override
    public boolean isBackingFileReadOnly() {
        return backingFileIsReadOnly;
    }

    @Override
    public boolean isDirectMemory() {
        return true;
    }

    public void singleThreadedCheckDisabled(boolean singleThreadedCheckDisabled) {
        super.singleThreadedCheckDisabled(singleThreadedCheckDisabled);
        closeable.singleThreadedCheckDisabled(singleThreadedCheckDisabled);
    }

    @NotNull
    @Override
    public String toString() {
        if (!TRACE)
            return super.toString();
        return getClass().getSimpleName() + "{" + "\n" +
                "refCount=" + refCount() + ",\n" +
                "mappedFile=" + mappedFile.file().getAbsolutePath() + ",\n" +
                "mappedFileRefCount=" + mappedFile.refCount() + ",\n" +
                "mappedFileIsClosed=" + mappedFile.isClosed() + ",\n" +
                "mappedFileRafIsClosed=" + Jvm.getValue(mappedFile.raf(), "closed") + ",\n" +
                "mappedFileRafChannelIsClosed=" + !mappedFile.raf().getChannel().isOpen() + ",\n" +
                "isClosed=" + isClosed() +
                '}';
    }

    @Override
    public void chunkCount(long[] chunkCount) {
        mappedFile.chunkCount(chunkCount);
    }

    @Override
    public boolean readWrite() {
        return !mappedFile.readOnly();
    }
}
