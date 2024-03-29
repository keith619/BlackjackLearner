/*-
 *
 *  This file is part of Oracle Berkeley DB Java Edition
 *  Copyright (C) 2002, 2015 Oracle and/or its affiliates.  All rights reserved.
 *
 *  Oracle Berkeley DB Java Edition is free software: you can redistribute it
 *  and/or modify it under the terms of the GNU Affero General Public License
 *  as published by the Free Software Foundation, version 3.
 *
 *  Oracle Berkeley DB Java Edition is distributed in the hope that it will be
 *  useful, but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero
 *  General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License in
 *  the LICENSE file along with Oracle Berkeley DB Java Edition.  If not, see
 *  <http://www.gnu.org/licenses/>.
 *
 *  An active Oracle commercial licensing agreement for this product
 *  supercedes this license.
 *
 *  For more information please contact:
 *
 *  Vice President Legal, Development
 *  Oracle America, Inc.
 *  5OP-10
 *  500 Oracle Parkway
 *  Redwood Shores, CA 94065
 *
 *  or
 *
 *  berkeleydb-info_us@oracle.com
 *
 *  [This line intentionally left blank.]
 *  [This line intentionally left blank.]
 *  [This line intentionally left blank.]
 *  [This line intentionally left blank.]
 *  [This line intentionally left blank.]
 *  [This line intentionally left blank.]
 *  EOF
 *
 */

package com.sleepycat.je.evictor;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.sleepycat.je.CursorConfig;
import com.sleepycat.je.dbi.CursorImpl;
import com.sleepycat.je.dbi.DatabaseImpl;
import com.sleepycat.je.dbi.DbTree;
import com.sleepycat.je.dbi.EnvironmentImpl;
import com.sleepycat.je.dbi.MemoryBudget;
import org.junit.After;
import org.junit.Test;

import com.sleepycat.bind.tuple.IntegerBinding;
import com.sleepycat.je.CacheMode;
import com.sleepycat.je.Cursor;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DbInternal;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.EnvironmentMutableConfig;
import com.sleepycat.je.EnvironmentStats;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.StatsConfig;
import com.sleepycat.je.tree.BIN;
import com.sleepycat.je.tree.IN;
import com.sleepycat.je.txn.BasicLocker;
import com.sleepycat.je.utilint.TestHook;
import com.sleepycat.util.test.SharedTestUtils;
import com.sleepycat.util.test.TestBase;
import com.sleepycat.je.util.TestUtils;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(Parameterized.class)
public class CacheModeTest extends TestBase {

    /* Records occupy three BINs. */
    private static final int FIRST_REC = 0;
    private static final int LAST_REC = 7;
    private static final int NODE_MAX = 5;

    private File envHome;
    private Environment env;
    private Database db;
    private IN root;
    private BIN[] bins;
    private DatabaseEntry[] keys;
    private boolean resetOnFailure;
    private CursorConfig cursorConfig;

    private boolean embeddedLNs = false;

    @Parameterized.Parameters
    public static List<Object[]> genParams() {
        final List<Object[]> params = new ArrayList<Object[]>();
        /*0*/ params.add(new Object[] { Boolean.FALSE, Boolean.FALSE });
        /*1*/ params.add(new Object[] { Boolean.FALSE, Boolean.TRUE });
        /*2*/ params.add(new Object[] { Boolean.TRUE, Boolean.FALSE });
        /*3*/ params.add(new Object[] { Boolean.TRUE, Boolean.TRUE });
        return params;
    }

    public CacheModeTest(final boolean resetOnFailure,
                         final boolean embeddedLNs) {

        envHome = SharedTestUtils.getTestDir();

        this.resetOnFailure = resetOnFailure;
        this.embeddedLNs = embeddedLNs;

        cursorConfig =
            resetOnFailure ?
            (new CursorConfig().setNonSticky(true)) :
            null;
    }

    @After
    public void tearDown() {
        
        if (env != null) {
            try {
                env.close();
            } catch (Throwable e) {
                System.out.println("tearDown: " + e);
            }
        }

        try {
            TestUtils.removeLogFiles("TearDown", envHome, false);
        } catch (Throwable e) {
            System.out.println("tearDown: " + e);
        }
        envHome = null;
        env = null;
        db = null;
        root = null;
        bins = null;
        keys = null;
    }

    private void open() {

        /* Open env, disable all daemon threads. */
        final EnvironmentConfig envConfig = new EnvironmentConfig();
        envConfig.setAllowCreate(true);
        envConfig.setConfigParam("je.env.runCleaner", "false");
        envConfig.setConfigParam("je.env.runCheckpointer", "false");
        envConfig.setConfigParam("je.env.runINCompressor", "false");
        envConfig.setConfigParam("je.env.runEvictor", "false");
        /* Use one LRU list for easier testing. */
        envConfig.setConfigParam(EnvironmentConfig.EVICTOR_N_LRU_LISTS, "1");
        /* Force embedded LN setting. We use 1 byte data values. */
        envConfig.setConfigParam(
            EnvironmentConfig.TREE_MAX_EMBEDDED_LN,
            embeddedLNs ? "1" : "0");
        env = new Environment(envHome, envConfig);

        /* TREE_MAX_EMBEDDED_LN may be overridden in a je.properties file. */
        EnvironmentImpl envImpl = DbInternal.getEnvironmentImpl(env);
        embeddedLNs = (envImpl.getMaxEmbeddedLN() >= 1);

        /* Open db. */
        final DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setAllowCreate(true);
        dbConfig.setNodeMaxEntries(NODE_MAX);
        db = env.openDatabase(null, "foo", dbConfig);

        final DatabaseEntry key = new DatabaseEntry();
        final DatabaseEntry data = new DatabaseEntry(new byte[1]);
        for (int i = FIRST_REC; i <= LAST_REC; i += 1) {
            IntegerBinding.intToEntry(i, key);
            db.put(null, key, data);
        }

        /* Sync to flush log buffer and make BINs non-dirty. */
        env.sync();

        /* Get root/parent IN in this two level tree. */
        root = DbInternal.getDatabaseImpl(db).
               getTree().getRootIN(CacheMode.UNCHANGED);
        root.releaseLatch();
        assertEquals(root.toString(), 3, root.getNEntries());

        /* Get BINs and first key in each BIN. */
        bins = new BIN[3];
        keys = new DatabaseEntry[3];
        for (int i = 0; i < 3; i += 1) {
            bins[i] = (BIN) root.getTarget(i);
            keys[i] = new DatabaseEntry();
            keys[i].setData(bins[i].getKey(0));
            //System.out.println("key " + i + ": " +
                               //IntegerBinding.entryToInt(keys[i]));
        }
    }

    private void close() {
        if (db != null) {
            db.close();
            db = null;
        }
        if (env != null) {
            env.close();
            env = null;
        }
    }

    private void setMode(CacheMode mode) {
        EnvironmentMutableConfig envConfig = env.getMutableConfig();
        envConfig.setCacheMode(mode);
        env.setMutableConfig(envConfig);
    }

    /**
     * Configure a tiny cache size and set a trap that fires an assertion when
     * eviction occurs.  This is used for testing EVICT_BIN and MAKE_COLD,
     * which should never cause critical eviction.
     */
    private void setEvictionTrap() {

        EnvironmentMutableConfig envConfig = env.getMutableConfig();
        envConfig.setCacheSize(MemoryBudget.MIN_MAX_MEMORY_SIZE);
        env.setMutableConfig(envConfig);

        class MyHook implements TestHook<Boolean> {
            public Boolean getHookValue() {
                fail("Eviction should not occur in EVICT_BIN mode");
                return false; /* For compiler, will never happen. */
            }
            public void hookSetup() {
                throw new UnsupportedOperationException();
            }
            public void doIOHook() {
                throw new UnsupportedOperationException();
            }
            public void doHook() {
                throw new UnsupportedOperationException();
            }
            public void doHook(Boolean obj) {
                throw new UnsupportedOperationException();                
            }
        }

        DbInternal.getEnvironmentImpl(env).getEvictor().setRunnableHook
            (new MyHook());
    }

    private void clearEvictionTrap() {
        DbInternal.getEnvironmentImpl(env).getEvictor().setRunnableHook(null);

        /* Bump cache size back up to a reasonable amount. */
        EnvironmentMutableConfig envConfig = env.getMutableConfig();
        envConfig.setCacheSize(64 * 1024 * 1024);
        env.setMutableConfig(envConfig);

    }

    private void readFirstAndLastRecord() {
        final DatabaseEntry key = new DatabaseEntry();
        final DatabaseEntry data = new DatabaseEntry(new byte[1]);
        OperationStatus status;
        status = db.get(null, keys[0], data, null);
        assertSame(OperationStatus.SUCCESS, status);
        status = db.get(null, keys[2], data, null);
        assertSame(OperationStatus.SUCCESS, status);
    }

    private void writeFirstAndLastRecord() {
        final DatabaseEntry key = new DatabaseEntry();
        final DatabaseEntry data = new DatabaseEntry(new byte[1]);
        OperationStatus status;
        status = db.put(null, keys[0], data);
        assertSame(OperationStatus.SUCCESS, status);
        status = db.put(null, keys[2], data);
        assertSame(OperationStatus.SUCCESS, status);
    }

    private List<IN> getLRUList() {
        final EnvironmentImpl envImpl = DbInternal.getEnvironmentImpl(env);
        return envImpl.getEvictor().getMixedLRUList();
    }

    private List<IN> getMRUList() {
        final List<IN> list = getLRUList();
        Collections.reverse(list);
        return list;
    }

    private void assertLRU(BIN... bins) {
        final List<IN> list = getLRUList();
        for (int i = 0; i < bins.length; i += 1) {
            assertTrue(i < list.size());
            assertSame(list.get(i), bins[i]);
        }
    }

    private void assertMRU(BIN... bins) {
        final List<IN> list = getMRUList();
        for (int i = 0; i < bins.length; i += 1) {
            assertTrue(i < list.size());
            assertSame(list.get(i), bins[i]);
        }
    }

    /**
     * CacheMode.DEFAULT assigns next generation to BIN and all ancestors, does
     * not evict.
     */
    @Test
    public void testMode_DEFAULT() {
        open();

        setMode(CacheMode.DEFAULT);

        long[] gens = new long[3];
        gens[0] = bins[0].getGeneration();
        gens[1] = bins[1].getGeneration();
        gens[2] = bins[2].getGeneration();
        long rootGen = root.getGeneration();

        readFirstAndLastRecord();

        /* First and last BIN (and root) should have a new generation. */
        assertTrue(gens[0] < bins[0].getGeneration());
        assertTrue(gens[1] == bins[1].getGeneration());
        assertTrue(gens[2] < bins[2].getGeneration());
        assertTrue(rootGen < root.getGeneration());

        /* MRU order: last BIN, first BIN. */
        assertMRU(bins[2], bins[0]);

        /* BINs should not be evicted. */
        assertNotNull(root.getTarget(0));
        assertNotNull(root.getTarget(1));
        assertNotNull(root.getTarget(2));

        /* LNs should not be evicted. */
        if (embeddedLNs) {
            assertNull(bins[0].getTarget(0));
            assertNull(bins[1].getTarget(0));
            assertNull(bins[2].getTarget(0));
        } else {
            assertNotNull(bins[0].getTarget(0));
            assertNotNull(bins[1].getTarget(0));
            assertNotNull(bins[2].getTarget(0));
        }

        close();
    }

    /**
     * CacheMode.UNCHANGED does not change generations, does not evict.
     */
    @Test
    public void testMode_UNCHANGED() {
        open();

        setMode(CacheMode.UNCHANGED);

        long[] gens = new long[3];
        gens[0] = bins[0].getGeneration();
        gens[1] = bins[1].getGeneration();
        gens[2] = bins[2].getGeneration();
        long rootGen = root.getGeneration();

        List<IN> mruList = getMRUList();

        readFirstAndLastRecord();

        /* No generations should change. */
        assertTrue(gens[0] == bins[0].getGeneration());
        assertTrue(gens[1] == bins[1].getGeneration());
        assertTrue(gens[2] == bins[2].getGeneration());
        assertTrue(rootGen == root.getGeneration());

        /* MRU order should not have changed. */
        assertEquals(mruList, getMRUList());

        /* BINs should not be evicted. */
        assertNotNull(root.getTarget(0));
        assertNotNull(root.getTarget(1));
        assertNotNull(root.getTarget(2));
        assertTrue(!root.getTarget(0).isBINDelta(false));
        assertTrue(!root.getTarget(1).isBINDelta(false));
        assertTrue(!root.getTarget(2).isBINDelta(false));

        /* LNs should not be evicted. */
        if (embeddedLNs) {
            assertNull(bins[0].getTarget(0));
            assertNull(bins[1].getTarget(0));
            assertNull(bins[2].getTarget(0));
        } else {
            assertNotNull(bins[0].getTarget(0));
            assertNotNull(bins[1].getTarget(0));
            assertNotNull(bins[2].getTarget(0));
        }

        /* Everything is resident, no eviction should occur with UNCHANGED. */
        checkEvictionWithCursorScan(
            CacheMode.UNCHANGED,
            false /*expectLNEviction*/,
            false /*expectBINEviction*/);

        /* And LRU list should not change. */
        assertEquals(mruList, getMRUList());

        /* Evict all LNs with EVICT_LN. */
        checkEvictionWithCursorScan(
            CacheMode.EVICT_LN,
            true /*expectLNEviction*/,
            false /*expectBINEviction*/);

        mruList = getMRUList();

        /* Now LNs should be evicted using UNCHANGED. */
        checkEvictionWithCursorScan(
            CacheMode.UNCHANGED,
            true /*expectLNEviction*/,
            false /*expectBINEviction*/);

        /* But LRU list should not change. */
        assertEquals(mruList, getMRUList());

        /* Evict all BINs using EVICT_BIN. */
        checkEvictionWithCursorScan(
            CacheMode.EVICT_BIN,
            true /*expectLNEviction*/,
            true /*expectBINEviction*/);

        /* Now BINs should be evicted using UNCHANGED. */
        checkEvictionWithCursorScan(
            CacheMode.UNCHANGED,
            true /*expectLNEviction*/,
            true /*expectBINEviction*/);

        close();
    }

    private void checkEvictionWithCursorScan(CacheMode mode,
                                             boolean expectLNEviction,
                                             boolean expectBINEviction) {

        final DatabaseEntry key = new DatabaseEntry();
        final DatabaseEntry data = new DatabaseEntry();

        final Cursor cursor = db.openCursor(null, null);
        cursor.setCacheMode(mode);

        BIN prevBin = null;
        int prevIndex = -1;

        while (cursor.getNext(key, data, null) == OperationStatus.SUCCESS) {

            final CursorImpl cursorImpl = DbInternal.getCursorImpl(cursor);
            final BIN currBin = cursorImpl.getBIN();
            final int currIndex = cursorImpl.getIndex();

            if (embeddedLNs) {
                assertNull(currBin.getTarget(currIndex));
            } else {
                assertNotNull(currBin.getTarget(currIndex));
            }

            if (currBin != prevBin && prevBin != null) {
                assertEquals(expectBINEviction, !prevBin.getInListResident());
            }

            if (!embeddedLNs && prevIndex >= 0) {
                if (expectLNEviction || expectBINEviction) {
                    assertNull(prevBin.getTarget(prevIndex));
                } else {
                    assertNotNull(prevBin.getTarget(prevIndex));
                }
            }

            prevBin = currBin;
            prevIndex = currIndex;
        }

        cursor.close();

        if (prevBin != null) {
            assertEquals(expectBINEviction, !prevBin.getInListResident());
        }

        if (!embeddedLNs && prevIndex >= 0) {
            if (expectLNEviction || expectBINEviction) {
                assertNull(prevBin.getTarget(prevIndex));
            } else {
                assertNotNull(prevBin.getTarget(prevIndex));
            }
        }
    }

    /**
     * CacheMode.KEEP_HOT assigns max generation to BIN and all ancestors,
     * does not evict.
     */
    @Test
    public void testMode_KEEP_HOT() {
        open();

        setMode(CacheMode.KEEP_HOT);

        long[] gens = new long[3];
        gens[0] = bins[0].getGeneration();
        gens[1] = bins[1].getGeneration();
        gens[2] = bins[2].getGeneration();
        long rootGen = root.getGeneration();

        readFirstAndLastRecord();

        /* First and last BIN (and root) should have max generation. */
        assertTrue(Long.MAX_VALUE == bins[0].getGeneration());
        assertTrue(gens[1] == bins[1].getGeneration());
        assertTrue(Long.MAX_VALUE == bins[2].getGeneration());
        assertTrue(Long.MAX_VALUE == root.getGeneration());

        /* BINs should not be evicted. */
        assertNotNull(root.getTarget(0));
        assertNotNull(root.getTarget(1));
        assertNotNull(root.getTarget(2));
        assertTrue(!root.getTarget(0).isBINDelta(false));
        assertTrue(!root.getTarget(1).isBINDelta(false));
        assertTrue(!root.getTarget(2).isBINDelta(false));

        /* LNs should not be evicted. */
        if (embeddedLNs) {
            assertNull(bins[0].getTarget(0));
            assertNull(bins[1].getTarget(0));
            assertNull(bins[2].getTarget(0));
        } else {
            assertNotNull(bins[0].getTarget(0));
            assertNotNull(bins[1].getTarget(0));
            assertNotNull(bins[2].getTarget(0));
        }

        close();
    }

    /**
     * CacheMode.MAKE_COLD assigns min generation to BIN but not to ancestors,
     * and also operates as EVICT_BIN if the cache is full.
     */
    @Test
    public void testMode_MAKE_COLD() {
        open();

        setMode(CacheMode.MAKE_COLD);

        /* First test with a large cache. No eviction should occur. */

        long[] gens = new long[3];
        gens[0] = bins[0].getGeneration();
        gens[1] = bins[1].getGeneration();
        gens[2] = bins[2].getGeneration();
        long rootGen = root.getGeneration();

        readFirstAndLastRecord();

        /* First and last BIN (but not root) should have zero generation. */
        assertTrue(0 == bins[0].getGeneration());
        assertTrue(gens[1] == bins[1].getGeneration());
        assertTrue(0 == bins[2].getGeneration());
        assertTrue(rootGen == root.getGeneration());

        /* LRU order: last BIN, first BIN. */
        assertLRU(bins[2], bins[0]);

        /* BINs should not be evicted. */
        assertNotNull(root.getTarget(0));
        assertNotNull(root.getTarget(1));
        assertNotNull(root.getTarget(2));
        assertTrue(!root.getTarget(0).isBINDelta(false));
        assertTrue(!root.getTarget(1).isBINDelta(false));
        assertTrue(!root.getTarget(2).isBINDelta(false));

        /* LNs should not be evicted. */
        if (embeddedLNs) {
            assertNull(bins[0].getTarget(0));
            assertNull(bins[1].getTarget(0));
            assertNull(bins[2].getTarget(0));
        } else {
            assertNotNull(bins[0].getTarget(0));
            assertNotNull(bins[1].getTarget(0));
            assertNotNull(bins[2].getTarget(0));
        }

        /*
         * With a small cache configured by setEvictionTrap, eviction should
         * occur and the behavior should be the same as with EVICT_BIN.
         */
        setEvictionTrap();
        checkBinEviction(true);
        clearEvictionTrap();

        /* Now only LNs should be evicted. */
        readFirstAndLastRecord();

        /* First and last BIN (but not root) should have zero generation. */
        assertTrue(0 == bins[0].getGeneration());
        assertTrue(gens[1] == bins[1].getGeneration());
        assertTrue(0 == bins[2].getGeneration());
        assertTrue(rootGen == root.getGeneration());

        /* BINs should not be evicted. */
        assertNotNull(root.getTarget(0));
        assertNotNull(root.getTarget(1));
        assertNotNull(root.getTarget(2));
        assertTrue(!root.getTarget(0).isBINDelta(false));
        assertTrue(!root.getTarget(1).isBINDelta(false));
        assertTrue(!root.getTarget(2).isBINDelta(false));

        bins[0] = (BIN) root.getTarget(0);
        bins[1] = (BIN) root.getTarget(1);
        bins[2] = (BIN) root.getTarget(2);

        /* LRU order: last BIN, first BIN. */
        assertLRU(bins[2], bins[0]);

        /* LNs should be evicted. */
        assertNull(bins[0].getTarget(0));
        if (embeddedLNs) {
            assertNull(bins[1].getTarget(0));
        } else {
            assertNotNull(bins[1].getTarget(0));
        }
        assertNull(bins[2].getTarget(0));

        close();
    }

    /**
     * CacheMode.EVICT_LN assigns min generation to BIN but not to ancestors.
     *
     * evicts LN, but does not evict BIN.
     */
    @Test
    public void testMode_EVICT_LN() {
        open();

        setMode(CacheMode.EVICT_LN);

        long[] gens = new long[3];
        gens[0] = bins[0].getGeneration();
        gens[1] = bins[1].getGeneration();
        gens[2] = bins[2].getGeneration();
        long rootGen = root.getGeneration();

        readFirstAndLastRecord();

        /* First and last BIN (and root) should have a new generation. */
        assertTrue(gens[0] < bins[0].getGeneration());
        assertTrue(gens[1] == bins[1].getGeneration());
        assertTrue(gens[2] < bins[2].getGeneration());
        assertTrue(rootGen < root.getGeneration());

        /* MRU order: last BIN, first BIN. */
        assertMRU(bins[2], bins[0]);

        /* BINs should not be evicted. */
        assertNotNull(root.getTarget(0));
        assertNotNull(root.getTarget(1));
        assertNotNull(root.getTarget(2));
        assertTrue(!root.getTarget(0).isBINDelta(false));
        assertTrue(!root.getTarget(1).isBINDelta(false));
        assertTrue(!root.getTarget(2).isBINDelta(false));

        /* LNs should be evicted. */
        assertNull(bins[0].getTarget(0));
        if (embeddedLNs) {
            assertNull(bins[1].getTarget(0));
        } else {
            assertNotNull(bins[1].getTarget(0));
        }
        assertNull(bins[2].getTarget(0));

        close();
    }

    /**
     * CacheMode.EVICT_BIN does not change generation of BIN ancestors, evicts
     * BIN (and its LNs).
     */
    @Test
    public void testMode_EVICT_BIN() {
        open();

        setMode(CacheMode.EVICT_BIN);

        setEvictionTrap();
        checkBinEviction(false);
        clearEvictionTrap();

        /* Scanning with EVICT_BIN should evict all BINs. */
        checkEvictionWithCursorScan(
            CacheMode.EVICT_BIN,
            true /*expectLNEviction*/,
            true /*expectBINEviction*/);

        /*
         * All LNs should be evicted when accessing a dirty BIN.
         */
        if (!embeddedLNs) {

            /* Load all BINs and LNs into cache. */
            checkEvictionWithCursorScan(
                CacheMode.DEFAULT,
                false /*expectLNEviction*/,
                false /*expectBINEviction*/);

            bins[0] = (BIN) root.getTarget(0);
            bins[1] = (BIN) root.getTarget(1);
            bins[2] = (BIN) root.getTarget(2);

            assertNotNull(bins[0]);
            assertNotNull(bins[1]);
            assertNotNull(bins[2]);

            /* Access/dirty first and last BIN with EVICT_BIN. */
            writeFirstAndLastRecord();

            assertSame(bins[0], root.getTarget(0));
            assertSame(bins[1], root.getTarget(1));
            assertSame(bins[2], root.getTarget(2));

            /* Check that first and last BIN have no LNs. */
            for (final BIN bin : new BIN[] { bins[0], bins[2] }) {
                for (int i = 0; i < bin.getNEntries(); i += 1) {
                    assertNull(bin.getTarget(i));
                }
            }

            /* Check that middle BIN still has resident LNs. */
            final BIN bin = bins[1];
            for (int i = 0; i < bin.getNEntries(); i += 1) {
                assertNotNull(bin.getTarget(i));
            }
        }

        close();
    }

    /**
     * Common method for checking EVICT_BIN behavior as well as MAKE_COLD when
     * cache is full.
     */
    private void checkBinEviction(boolean isMakeCold) {
        long[] gens = new long[3];
        gens[0] = bins[0].getGeneration();
        gens[1] = bins[1].getGeneration();
        gens[2] = bins[2].getGeneration();
        long rootGen = root.getGeneration();

        readFirstAndLastRecord();

        /* Middle BIN should have same generation. */
        assertTrue(gens[1] == bins[1].getGeneration());

        /* MAKE_COLD does not bump UIN generation, but EVICT_BIN does. */
        if (isMakeCold) {
            assertTrue(rootGen == root.getGeneration());
        } else {
            assertTrue(rootGen < root.getGeneration());
        }

        /* BINs should be evicted. */
        assertNull(root.getTarget(0));
        assertNotNull(root.getTarget(1));
        assertNull(root.getTarget(2));

        /* Dirty first and last BIN. */
        writeFirstAndLastRecord();

        /* Dirty BINs should not be evicted. */
        assertNotNull(root.getTarget(0));
        assertNotNull(root.getTarget(1));
        assertNotNull(root.getTarget(2));

        /* Refresh BINs after prior eviction. */
        bins[0] = (BIN) root.getTarget(0);
        bins[1] = (BIN) root.getTarget(1);
        bins[2] = (BIN) root.getTarget(2);

        if (isMakeCold) {
            /* LRU order: last BIN, first BIN. */
            assertLRU(bins[2], bins[0]);
        } else {
            /* MRU order: last BIN, first BIN. */
            assertMRU(bins[2], bins[0]);
        }

        /* Get rid of the dirtiness we created above. */
        env.sync();
    }

    /**
     * CacheMode.EVICT_LN does not evict the LN when two consecutive Cursor
     * operations end up on the same record.
     */
    @Test
    public void testEvictLnOnlyWhenMovingAway() {
        open();

        setMode(CacheMode.EVICT_LN);

        Cursor cursor = db.openCursor(null, cursorConfig);
        assertSame(CacheMode.EVICT_LN, cursor.getCacheMode());

        /*
         * Examine the NNotResident stat to ensure that a node is not evicted
         * and then fetched by a single operation that doesn't move the cursor.
         */
        final StatsConfig clearStats = new StatsConfig();
        clearStats.setClear(true);
        EnvironmentStats stats = env.getStats(clearStats);

        final DatabaseEntry key = new DatabaseEntry();
        final DatabaseEntry data = new DatabaseEntry();
        OperationStatus status;

        /* Find 1st record resident. */
        status = cursor.getFirst(key, data, null);
        assertSame(OperationStatus.SUCCESS, status);
        if (embeddedLNs) {
            assertNull(bins[0].getTarget(0));
        } else {
            assertNotNull(bins[0].getTarget(0));
        }
        stats = env.getStats(clearStats);
        assertEquals(0, stats.getNNotResident());

        /* Find 2nd record resident, evict 1st. */
        status = cursor.getNext(key, data, null);
        assertSame(OperationStatus.SUCCESS, status);
        if (embeddedLNs) {
            assertNull(bins[0].getTarget(1));
        } else {
            assertNotNull(bins[0].getTarget(1));
        }
        assertNull(bins[0].getTarget(0));
        stats = env.getStats(clearStats);
        assertEquals(0, stats.getNNotResident());

        /* Fetch 1st, evict 2nd. */
        status = cursor.getPrev(key, data, null);
        assertSame(OperationStatus.SUCCESS, status);
        if (embeddedLNs) {
            assertNull(bins[0].getTarget(0));
        } else {
            assertNotNull(bins[0].getTarget(0));
        }
        assertNull(bins[0].getTarget(1));
        stats = env.getStats(clearStats);
        if (embeddedLNs) {
            assertEquals(0, stats.getNNotResident());
        } else {
            assertEquals(1, stats.getNNotResident());
        }

        /* Fetch 2nd, evict 1st. */
        status = cursor.getNext(key, data, null);
        assertSame(OperationStatus.SUCCESS, status);
        assertNull(bins[0].getTarget(0));
        if (embeddedLNs) {
            assertNull(bins[0].getTarget(1));
        } else {
            assertNotNull(bins[0].getTarget(1));
        }
        stats = env.getStats(clearStats);
        if (embeddedLNs) {
            assertEquals(0, stats.getNNotResident());
        } else {
            assertEquals(1, stats.getNNotResident());
        }

        /* Fetch 1st, evict 2nd. */
        status = cursor.getPrev(key, data, null);
        assertSame(OperationStatus.SUCCESS, status);
        if (embeddedLNs) {
            assertNull(bins[0].getTarget(0));
        } else {
            assertNotNull(bins[0].getTarget(0));
        }
        assertNull(bins[0].getTarget(1));
        stats = env.getStats(clearStats);
        if (embeddedLNs) {
            assertEquals(0, stats.getNNotResident());
        } else {
            assertEquals(1, stats.getNNotResident());
        }

        /*
         * With a non-sticky cursor, if we attempt an operation that may move
         * the cursor, we will always evict the LN because there is no dup
         * cursor to compare with, to see if the position has changed.  This is
         * an expected drawback of using a non-sticky cursor.
         */
        final int expectFetchWithoutPositionChange = resetOnFailure ? 1 : 0;

        /* No fetch needed to access 1st again. */
        status = cursor.getFirst(key, data, null);
        assertSame(OperationStatus.SUCCESS, status);
        if (embeddedLNs) {
            assertNull(bins[0].getTarget(0));
        } else {
            assertNotNull(bins[0].getTarget(0));
        }
        stats = env.getStats(clearStats);

        if (embeddedLNs) {
            assertEquals(0, stats.getNNotResident());
        } else {
            assertEquals(expectFetchWithoutPositionChange,
                         stats.getNNotResident());
        }

        /*
         * No fetch needed to access 1st again.  Note that no fetch occurs here
         * even with a non-sticky cursor, because getCurrent cannot move the
         * cursor.
         */
        status = cursor.getCurrent(key, data, null);
        assertSame(OperationStatus.SUCCESS, status);
        if (embeddedLNs) {
            assertNull(bins[0].getTarget(0));
        } else {
            assertNotNull(bins[0].getTarget(0));
        }
        stats = env.getStats(clearStats);
        assertEquals(0, stats.getNNotResident());

        /* No fetch needed to access 1st again. */
        status = cursor.getSearchKey(keys[0], data, null);
        assertSame(OperationStatus.SUCCESS, status);
        if (embeddedLNs) {
            assertNull(bins[0].getTarget(0));
        } else {
            assertNotNull(bins[0].getTarget(0));
        }
        stats = env.getStats(clearStats);
        if (embeddedLNs) {
            assertNull(bins[0].getTarget(0));
        } else {
            assertEquals(expectFetchWithoutPositionChange,
                         stats.getNNotResident());
        }

        cursor.close();
        close();
    }

    /**
     * CacheMode.EVICT_BIN does not evict the BIN when two consecutive Cursor
     * operations end up on the same BIN.  If we stay on the same BIN but move
     * to a new LN, only the LN is evicted.  If we stay on the same LN, neither
     * LN nor BIN is evicted.
     */
    @Test
    public void testEvictBinOnlyWhenMovingAway() {
        open();

        setMode(CacheMode.EVICT_BIN);
        setEvictionTrap();

        Cursor cursor = db.openCursor(null, cursorConfig);
        assertSame(CacheMode.EVICT_BIN, cursor.getCacheMode());

        /*
         * Examine the NNotResident stat to ensure that a node is not evicted
         * and then fetched by a single operation that doesn't move the cursor.
         */
        final StatsConfig clearStats = new StatsConfig();
        clearStats.setClear(true);
        EnvironmentStats stats = env.getStats(clearStats);

        final int firstKeyInSecondBin = IntegerBinding.entryToInt(keys[1]);
        final DatabaseEntry key = new DatabaseEntry();
        final DatabaseEntry data = new DatabaseEntry();
        OperationStatus status;

        /* Find records in 1st BIN resident. */
        for (int i = FIRST_REC; i < firstKeyInSecondBin; i += 1) {
            status = cursor.getNext(key, data, null);
            assertSame(OperationStatus.SUCCESS, status);
            assertEquals(i, IntegerBinding.entryToInt(key));
            assertSame(bins[0], DbInternal.getCursorImpl(cursor).getBIN());
            assertSame(bins[0], root.getTarget(0));
            if (embeddedLNs) {
                assertNull(bins[0].getTarget(i));
            } else {
                assertNotNull(bins[0].getTarget(i));
            }
            stats = env.getStats(clearStats);
            assertEquals(0, stats.getNNotResident());
            /* Find prior LN evicted. */
            if (i > 0) {
                assertNull(bins[0].getTarget(i - 1));
            }
        }

        /* Move to 2nd BIN, find resident. */
        status = cursor.getNext(key, data, null);
        assertSame(OperationStatus.SUCCESS, status);
        assertSame(bins[1], DbInternal.getCursorImpl(cursor).getBIN());
        assertSame(bins[1], root.getTarget(1));
        if (embeddedLNs) {
            assertNull(bins[1].getTarget(0));
        } else {
            assertNotNull(bins[1].getTarget(0));
        }
        stats = env.getStats(clearStats);
        assertEquals(0, stats.getNNotResident());
        /* Find prior BIN evicted. */
        assertNull(root.getTarget(0));

        /* Move back to 1st BIN, fetch BIN and LN. */
        status = cursor.getPrev(key, data, null);
        assertSame(OperationStatus.SUCCESS, status);
        assertNotNull(root.getTarget(0));
        assertNotSame(bins[0], root.getTarget(0));
        bins[0] = (BIN) root.getTarget(0);
        if (embeddedLNs) {
            assertNull(bins[0].getTarget(firstKeyInSecondBin - 1));
        } else {
            assertNotNull(bins[0].getTarget(firstKeyInSecondBin - 1));
        }
        assertEquals(firstKeyInSecondBin - 1, IntegerBinding.entryToInt(key));
        stats = env.getStats(clearStats);
        if (embeddedLNs) {
            assertEquals(1, stats.getNNotResident());
        } else {
            assertEquals(2, stats.getNNotResident());
        }
        /* Find next BIN evicted. */
        assertNull(root.getTarget(1));

        /* When not moving the cursor, nothing is evicted. */
        status = cursor.getCurrent(key, data, null);
        assertSame(OperationStatus.SUCCESS, status);
        stats = env.getStats(clearStats);
        assertEquals(0, stats.getNNotResident());

        cursor.close();
        clearEvictionTrap();
        close();
    }

    /**
     * CacheMode can be set via the Environment, Database and Cursor
     * properties.  Database CacheMode overrides Environment CacheMode.  Cursor
     * CacheMode overrides Database and Environment CacheMode.
     */
    @Test
    public void testModeProperties() {
        open();

        final DatabaseEntry key = new DatabaseEntry();
        final DatabaseEntry data = new DatabaseEntry();
        OperationStatus status;

        /* Env property is not overridden. */
        setMode(CacheMode.KEEP_HOT);
        Cursor cursor = db.openCursor(null, cursorConfig);
        assertSame(CacheMode.KEEP_HOT, cursor.getCacheMode());
        status = cursor.getFirst(key, data, null);
        assertSame(OperationStatus.SUCCESS, status);
        assertSame(CacheMode.KEEP_HOT,
                   DbInternal.getCursorImpl(cursor).getCacheMode());

        /* Then overridden by cursor. */
        cursor.setCacheMode(CacheMode.EVICT_LN);
        assertSame(CacheMode.EVICT_LN, cursor.getCacheMode());
        status = cursor.getFirst(key, data, null);
        assertSame(OperationStatus.SUCCESS, status);
        assertSame(CacheMode.EVICT_LN,
                   DbInternal.getCursorImpl(cursor).getCacheMode());
        cursor.close();

        /* Env property does not apply to internal databases. */
        DbTree dbTree = DbInternal.getEnvironmentImpl(env).getDbTree();
        DatabaseImpl dbImpl = dbTree.getDb(DbTree.ID_DB_ID);
        BasicLocker locker =
            BasicLocker.createBasicLocker(DbInternal.getEnvironmentImpl(env));
        cursor = DbInternal.makeCursor(dbImpl, locker, null);
        assertSame(CacheMode.DEFAULT, cursor.getCacheMode());
        assertSame(CacheMode.DEFAULT,
                   DbInternal.getCursorImpl(cursor).getCacheMode());
        cursor.getFirst(new DatabaseEntry(), new DatabaseEntry(), null);
        assertSame(CacheMode.DEFAULT, cursor.getCacheMode());
        assertSame(CacheMode.DEFAULT,
                   DbInternal.getCursorImpl(cursor).getCacheMode());
        cursor.close();
        locker.operationEnd();
        dbTree.releaseDb(dbImpl);

        /* Env property overridden by db property. */
        final DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setAllowCreate(true);
        dbConfig.setCacheMode(CacheMode.MAKE_COLD);
        Database db2 = env.openDatabase(null, "foo2", dbConfig);
        cursor = db2.openCursor(null, cursorConfig);
        assertSame(CacheMode.MAKE_COLD, cursor.getCacheMode());
        key.setData(new byte[1]);
        data.setData(new byte[1]);
        status = cursor.put(key, data);
        assertSame(OperationStatus.SUCCESS, status);
        assertSame(CacheMode.MAKE_COLD,
                   DbInternal.getCursorImpl(cursor).getCacheMode());

        /* Then overridden by cursor. */
        cursor.setCacheMode(CacheMode.EVICT_LN);
        assertSame(CacheMode.EVICT_LN, cursor.getCacheMode());
        status = cursor.getFirst(key, data, null);
        assertSame(OperationStatus.SUCCESS, status);
        assertSame(CacheMode.EVICT_LN,
                   DbInternal.getCursorImpl(cursor).getCacheMode());
        cursor.close();

        /* Opening another handle on the db will override the property. */
        dbConfig.setCacheMode(CacheMode.DEFAULT);
        Database db3 = env.openDatabase(null, "foo2", dbConfig);
        cursor = db3.openCursor(null, cursorConfig);
        assertSame(CacheMode.DEFAULT, cursor.getCacheMode());
        status = cursor.getFirst(key, data, null);
        assertSame(OperationStatus.SUCCESS, status);
        assertSame(CacheMode.DEFAULT,
                   DbInternal.getCursorImpl(cursor).getCacheMode());
        cursor.close();

        /* Open another handle, set mode to null, close immediately. */
        dbConfig.setCacheMode(null);
        Database db4 = env.openDatabase(null, "foo2", dbConfig);
        db4.close();
        /* Env default is now used. */
        cursor = db.openCursor(null, cursorConfig);
        assertSame(CacheMode.KEEP_HOT, cursor.getCacheMode());
        status = cursor.getFirst(key, data, null);
        assertSame(OperationStatus.SUCCESS, status);
        assertSame(CacheMode.KEEP_HOT,
                   DbInternal.getCursorImpl(cursor).getCacheMode());
        cursor.close();

        /* Set env property to null, DEFAULT is then used. */
        setMode(null);
        cursor = db3.openCursor(null, cursorConfig);
        assertSame(CacheMode.DEFAULT, cursor.getCacheMode());
        status = cursor.getFirst(key, data, null);
        assertSame(OperationStatus.SUCCESS, status);
        assertSame(CacheMode.DEFAULT,
                   DbInternal.getCursorImpl(cursor).getCacheMode());
        cursor.close();

        db3.close();
        db2.close();
        close();
    }
}
