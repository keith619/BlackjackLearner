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

package com.sleepycat.je.tree;

import static com.sleepycat.je.tree.INTargetRep.NONE;
import static org.junit.Assert.assertEquals;

import java.util.Random;

import org.junit.Test;

import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.DbInternal;
import com.sleepycat.je.LockMode;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.cleaner.LocalUtilizationTracker;
import com.sleepycat.je.dbi.DatabaseImpl;
import com.sleepycat.je.dbi.EnvironmentImpl;
import com.sleepycat.je.dbi.INList;
import com.sleepycat.je.log.LogEntryType;
import com.sleepycat.je.log.Loggable;
import com.sleepycat.je.tree.INTargetRep.Default;
import com.sleepycat.je.tree.INTargetRep.Sparse;
import com.sleepycat.je.tree.INTargetRep.Type;

public class INTargetRepTest extends INEntryTestBase {

    final int size = 32;
    final IN parent = new TestIN(size);

    /**
     * Test use of the representations at the IN level. Checks memory
     * bookkeeping after each operation.
     */
    @Test
    public void testINs() {

        int keySize = 8; // same size used for data as well

        Database db = createDb(DB_NAME, keySize, nodeMaxEntries);
        DatabaseImpl dbImpl = DbInternal.getDatabaseImpl(db);
        EnvironmentImpl env = dbImpl.getEnv();

        boolean embeddedLNs = (env.getMaxEmbeddedLN() >= keySize);

        BIN firstBin;

        if (embeddedLNs) {
            firstBin = verifyAcrossINEvict(db, Type.NONE, Type.NONE);
        } else {
            firstBin = verifyAcrossINEvict(db, Type.DEFAULT, Type.NONE);
        }

        /* Mutate to sparse. */
        DatabaseEntry key = new DatabaseEntry();
        key.setData(createByteVal(0, 8));
        DatabaseEntry data = new DatabaseEntry();
        db.get(null, key, data, LockMode.DEFAULT);

        if (embeddedLNs) {
            assertEquals(Type.NONE, firstBin.getTargets().getType());
        } else {
            assertEquals(Type.SPARSE, firstBin.getTargets().getType());
        }

        for (int i = 0; i < nodeMaxEntries; i++) {
            key.setData(createByteVal(i, keySize));
            OperationStatus status = db.get(null, key, data, LockMode.DEFAULT);
            assertEquals(OperationStatus.SUCCESS, status);
            verifyINMemorySize(dbImpl);
        }

        if (embeddedLNs) {
            assertEquals(Type.NONE, firstBin.getTargets().getType());
        } else {
            assertEquals(Type.DEFAULT, firstBin.getTargets().getType());
        }

        db.close();
    }

    private BIN verifyAcrossINEvict(Database db,
                                    Type pre,
                                    Type post) {

        DatabaseImpl dbImpl = DbInternal.getDatabaseImpl(db);

        BIN firstBin = (BIN)(dbImpl.getTree().getFirstNode(cacheMode));

        assertEquals(pre, firstBin.getTargets().getType());

        firstBin.evictLNs();
        firstBin.releaseLatch();
        assertEquals(post, firstBin.getTargets().getType());

        verifyINMemorySize(dbImpl);
        return firstBin;
    }

    @Test
    public void testBasic() {
        commonTest(new Default(size));
        commonTest(new Sparse(size));
    }

    public void commonTest(INArrayRep<INTargetRep, Type, Node> targets) {
        targets = targets.set(1,new TestNode(1), parent);
        assertEquals(1, ((TestNode) targets.get(1)).id);

        targets.copy(0, 5, 1, parent);
        assertEquals(1, ((TestNode) targets.get(1)).id);

        targets.copy(0, 5, 2, parent);
        assertEquals(1, ((TestNode) targets.get(6)).id);

        targets.set(1, null, parent);

        assertEquals(null, targets.get(1));

        targets.copy(5, 0, 2, parent);
        assertEquals(1, ((TestNode) targets.get(1)).id);
    }

    @Test
    public void testCompact() {
        Default te = new Default(size);
        INArrayRep<INTargetRep, Type, Node> rep = te.compact(parent);
        assertEquals(Type.NONE, rep.getType());

        te = new Default(size);
        for (int i=0; i < Sparse.MAX_ENTRIES; i++) {
            te.set(i, new TestNode(i), parent);
        }
        assertEquals(Type.DEFAULT, te.getType());
        rep = te.compact(parent);
        assertEquals(Type.SPARSE, rep.getType());

        te = new Default(size);
        for (int i=0; i <= Sparse.MAX_ENTRIES; i++) {
            te.set(i, new TestNode(i), parent);
        }

        /* Above the threshold. */
        assertEquals(Type.DEFAULT, te.getType());
        rep = te.compact(parent);
        assertEquals(Type.DEFAULT, rep.getType());
    }

    @Test
    public void testRampUpDown() {
        INArrayRep<INTargetRep, Type, Node> entries = NONE;
        Node refEntries[] = new TestNode[size];

        /* Ramp up */
        for (int i=0; i < size; i++) {
            TestNode n = new TestNode(i);
            entries = entries.set(i, n, parent);
            if ((i+1) <= Sparse.MAX_ENTRIES) {
                assertEquals(Type.SPARSE, entries.getType());
            } else {
                assertEquals(Type.DEFAULT, entries.getType());
            }
            refEntries[i] = n;
            checkEquals(refEntries, entries);
        }

        /* Ramp down with compact. */
        for (int i=0; i < size; i++) {
            entries = entries.set(i, null, parent);
            entries = entries.compact(parent);
            if ((size - (i+1)) <= Sparse.MAX_ENTRIES) {
                if ((size - (i+1)) == 0) {
                    assertEquals(Type.NONE, entries.getType());
                } else {
                    assertEquals(Type.SPARSE, entries.getType());
                }
            } else {
                assertEquals(Type.DEFAULT, entries.getType());
            }
            refEntries[i] = null;
            checkEquals(refEntries, entries);
        }
    }

    @Test
    public void testRandomEntries() {
        INArrayRep<INTargetRep, Type, Node> entries = NONE;
        Node refEntries[] = new TestNode[size];
        Random rand = new Random();
        for (int repeat = 1; repeat < 100; repeat++) {
            for (int i=0; i < 10*size; i++) {
                int slot = rand.nextInt(size);
                Node n = (i % 5) == 0 ? null : new TestNode(slot);
                refEntries[slot] = n;
                entries = entries.set(slot, n, parent);
                checkEquals(refEntries, entries);
                entries = entries.compact(parent);
                checkEquals(refEntries, entries);
            }
        }
    }

    @Test
    public void testShiftEntries() {
        INArrayRep<INTargetRep, Type, Node> entries = NONE;
        Node refEntries[] = new TestNode[size];

        Random rand = new Random();

        for (int i = 0; i < 10000; i++) {
            int slot = rand.nextInt(size);
            Node n = (i % 10) == 0 ? null : new TestNode(slot);
            refEntries[slot] = n;
            entries = entries.set(slot, n, parent);
            checkEquals(refEntries, entries);

            /* Simulate an insertion */
            entries = entries.copy(slot, slot + 1, size - (slot + 1), parent);
            System.arraycopy(refEntries, slot, refEntries, slot + 1,
                             size - (slot + 1));
            checkEquals(refEntries, entries);

            /* Simulate a deletion. */
            entries = entries.copy(slot + 1, slot, size - (slot + 1), parent);
            entries = entries.set(size-1, null, parent);
            System.arraycopy(refEntries, slot + 1, refEntries,
                             slot, size - (slot + 1));
            refEntries[size - 1] = null;
            checkEquals(refEntries, entries);
        }
    }

    private void checkEquals(Node[] refEntries,
                             INArrayRep<INTargetRep, Type, Node> entries) {
        for (int i=0; i < refEntries.length; i++) {
            assertEquals(refEntries[i], entries.get(i));
        }
    }

    /* Dummy test node. */
    @SuppressWarnings("unused")
    class TestNode extends Node {
        final int id;

        public TestNode(int id) {
            this.id = id;
        }

        @Override
        void accountForSubtreeRemoval(INList inList,
                                      LocalUtilizationTracker localTracker)
            throws DatabaseException {
        }

        @Override
        void accountForDeferredWriteSubtreeRemoval(INList inList,
                                                   IN subtreeParent)
            throws DatabaseException {
        }

        @Override
        public LogEntryType getGenericLogType() {
            return null;
        }

        @Override
        public void incFetchStats(EnvironmentImpl envImpl, boolean isMiss) {
        }

        @Override
        boolean isValidForDelete() throws DatabaseException {
            return false;
        }

        @Override
        void rebuildINList(INList inList) throws DatabaseException {
        }

        public boolean logicalEquals(Loggable other) {
            return false;
        }
    }
}
