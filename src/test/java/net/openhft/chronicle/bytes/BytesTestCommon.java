package net.openhft.chronicle.bytes;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.io.AbstractReferenceCounted;
import net.openhft.chronicle.core.onoes.ExceptionKey;
import net.openhft.chronicle.core.onoes.Slf4jExceptionHandler;
import net.openhft.chronicle.core.threads.CleaningThread;
import net.openhft.chronicle.core.threads.ThreadDump;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

import static net.openhft.chronicle.core.io.AbstractCloseable.waitForCloseablesToClose;
import static net.openhft.chronicle.core.io.AbstractReferenceCounted.assertReferencesReleased;

public class BytesTestCommon {

    private final Map<Predicate<ExceptionKey>, String> expectedExceptions = new LinkedHashMap<>();
    protected ThreadDump threadDump;
    protected Map<ExceptionKey, Integer> exceptions;

    public BytesTestCommon() {
        // This warning message can be raised by any test
        expectException("Bytes::hashCode/Bytes::equals");
    }

    @Before
    @BeforeEach
    public void enableReferenceTracing() {
        AbstractReferenceCounted.enableReferenceTracing();
    }

    @Before
    @BeforeEach
    public void threadDump() {
        threadDump = new ThreadDump();
    }

    public void checkThreadDump() {
        threadDump.assertNoNewThreads();
    }

    @Before
    @BeforeEach
    public void recordExceptions() {
        exceptions = Jvm.recordExceptions();
    }

    public void expectException(String message) {
        expectException(k -> k.message.contains(message) || (k.throwable != null && k.throwable.getMessage().contains(message)), message);
    }

    public void expectException(Predicate<ExceptionKey> predicate, String description) {
        expectedExceptions.put(predicate, description);
    }

    public void checkExceptions() {
        for (Map.Entry<Predicate<ExceptionKey>, String> expectedException : expectedExceptions.entrySet()) {
            if (!exceptions.keySet().removeIf(expectedException.getKey()))
                Slf4jExceptionHandler.WARN.on(getClass(), "No error for " + expectedException.getValue());
        }
        expectedExceptions.clear();
        if (Jvm.hasException(exceptions)) {
            Jvm.dumpException(exceptions);
            Jvm.resetExceptionHandlers();
            Assert.fail();
        }
    }

    @After
    @AfterEach
    public void afterChecks() {
        CleaningThread.performCleanup(Thread.currentThread());

        // find any discarded resources.
        System.gc();
        waitForCloseablesToClose(100);

        assertReferencesReleased();
        checkThreadDump();
        checkExceptions();
    }
}