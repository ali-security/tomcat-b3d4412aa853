/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina.webresources;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import org.apache.catalina.WebResource;
import org.apache.catalina.WebResourceLockSet.ResourceLock;
import org.apache.catalina.startup.ExpandWar;

/*
 * Tests for the locking that prevents concurrent reads and writes (e.g. HTTP GET and PUT / DELETE) of the same path
 * creating inconsistent FileResource instances (CVE-2024-50379). The lock key must ignore case so paths that differ
 * only by case are serialized on case insensitive file systems.
 */
public class TestDirResourceSetLocking {

    private static final long TIMEOUT_MILLIS = 10000;

    private File baseDir;
    private TesterWebResourceRoot root;
    private DirResourceSet dirResourceSet;


    @Before
    public void setUp() throws Exception {
        baseDir = Files.createTempDirectory("test").toFile();
        root = new TesterWebResourceRoot();
        dirResourceSet = new DirResourceSet(root, "/", baseDir.getAbsolutePath(), "/");
        dirResourceSet.setReadOnly(false);
        root.setMainResources(dirResourceSet);
        root.start();
    }


    @After
    public void tearDown() throws Exception {
        root.stop();
        root.destroy();
        ExpandWar.delete(baseDir);
    }


    @Test
    public void testLockKeyIgnoresCase() {
        ResourceLock writeLock = dirResourceSet.lockForWrite("/test.jsp");
        try {
            // A thread holding the write lock may also obtain the read lock
            ResourceLock readLock = dirResourceSet.lockForRead("/TEST.JSP");
            try {
                Assert.assertSame(writeLock, readLock);
            } finally {
                dirResourceSet.unlockForRead(readLock);
            }
        } finally {
            dirResourceSet.unlockForWrite(writeLock);
        }
    }


    @Test
    public void testLockKeyIsNormalized() {
        ResourceLock writeLock = dirResourceSet.lockForWrite("/test.jsp");
        try {
            ResourceLock readLock = dirResourceSet.lockForRead("/foo/../Test.Jsp");
            try {
                Assert.assertSame(writeLock, readLock);
            } finally {
                dirResourceSet.unlockForRead(readLock);
            }
        } finally {
            dirResourceSet.unlockForWrite(writeLock);
        }
    }


    @Test
    public void testLockReleasedWhenUnused() {
        ResourceLock first = dirResourceSet.lockForWrite("/test.jsp");
        dirResourceSet.unlockForWrite(first);
        ResourceLock second = dirResourceSet.lockForWrite("/test.jsp");
        dirResourceSet.unlockForWrite(second);
        Assert.assertNotSame(first, second);
    }


    @Test
    public void testGetResourceBlockedByConcurrentWriteDifferentCase() throws Exception {
        // Simulate an in-progress PUT of /test.jsp
        ResourceLock writeLock = dirResourceSet.lockForWrite("/test.jsp");
        AtomicReference<WebResource> result = new AtomicReference<>();
        Thread reader = new Thread(() -> result.set(dirResourceSet.getResource("/TEST.JSP")));
        try {
            reader.start();
            // The concurrent GET using a different case must wait for the write to complete
            waitForQueuedThread(writeLock);
            Assert.assertTrue(reader.isAlive());
            Assert.assertNull(result.get());
        } finally {
            dirResourceSet.unlockForWrite(writeLock);
        }
        reader.join(TIMEOUT_MILLIS);
        Assert.assertFalse(reader.isAlive());
        Assert.assertNotNull(result.get());
    }


    @Test
    public void testWriteBlockedByConcurrentReadDifferentCase() throws Exception {
        // Simulate an in-progress GET of /TEST.JSP
        ResourceLock readLock = dirResourceSet.lockForRead("/TEST.JSP");
        AtomicBoolean written = new AtomicBoolean(false);
        Thread writer = new Thread(() -> written.set(dirResourceSet.write("/test.jsp",
                new ByteArrayInputStream("test".getBytes(StandardCharsets.ISO_8859_1)), false)));
        File target = new File(baseDir, "test.jsp");
        try {
            writer.start();
            // The concurrent PUT using a different case must wait for the read to complete
            waitForQueuedThread(readLock);
            Assert.assertTrue(writer.isAlive());
            Assert.assertFalse(target.exists());
        } finally {
            dirResourceSet.unlockForRead(readLock);
        }
        writer.join(TIMEOUT_MILLIS);
        Assert.assertFalse(writer.isAlive());
        Assert.assertTrue(written.get());
        Assert.assertTrue(target.isFile());
    }


    @Test
    public void testDeleteBlockedByConcurrentReadDifferentCase() throws Exception {
        File target = new File(baseDir, "test.jsp");
        Files.write(target.toPath(), "test".getBytes(StandardCharsets.ISO_8859_1));
        WebResource resource = dirResourceSet.getResource("/test.jsp");
        Assert.assertTrue(resource.exists());

        // Simulate an in-progress GET of /TEST.JSP
        ResourceLock readLock = dirResourceSet.lockForRead("/TEST.JSP");
        AtomicBoolean deleted = new AtomicBoolean(false);
        Thread deleter = new Thread(() -> deleted.set(resource.delete()));
        try {
            deleter.start();
            // The concurrent DELETE must wait for the read to complete
            waitForQueuedThread(readLock);
            Assert.assertTrue(deleter.isAlive());
            Assert.assertTrue(target.exists());
        } finally {
            dirResourceSet.unlockForRead(readLock);
        }
        deleter.join(TIMEOUT_MILLIS);
        Assert.assertFalse(deleter.isAlive());
        Assert.assertTrue(deleted.get());
        Assert.assertFalse(target.exists());
    }


    private static void waitForQueuedThread(ResourceLock resourceLock) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (!resourceLock.reentrantLock.hasQueuedThreads()) {
            if (System.currentTimeMillis() > deadline) {
                Assert.fail("No thread blocked waiting for the lock [" + resourceLock.key + "]");
            }
            Thread.sleep(10);
        }
    }
}
