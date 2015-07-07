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

package com.sleepycat.je.recovery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Set;

import org.junit.Test;

import com.sleepycat.je.dbi.DatabaseId;
import com.sleepycat.je.tree.IN;
import com.sleepycat.util.test.TestBase;

public class LevelRecorderTest extends TestBase {

    public LevelRecorderTest() {
    }

    @Test
    public void testRecording () {
        LevelRecorder recorder = new LevelRecorder();

        DatabaseId id1 = new DatabaseId(1);
        DatabaseId id5 = new DatabaseId(5);
        DatabaseId id10 = new DatabaseId(10);

        int level1 = IN.BIN_LEVEL;
        int level2 = level1 + 1;
        int level3 = level1 + 2;
        int level4 = level1 + 3;

        /* Mimic the recording of various INs for various databases. */
        recorder.record(id10, level1);
        recorder.record(id5,  level3);
        recorder.record(id5,  level2);
        recorder.record(id10, level1);
        recorder.record(id1,  level1);
        recorder.record(id10, level1);
        recorder.record(id1,  level4);

        /*
         * We should only have to redo recovery for dbs 1 and 5. Db 10 had
         * INs all of the same level.
         */
        Set<DatabaseId> reprocessSet = recorder.getDbsWithDifferentLevels();
        assertEquals(2, reprocessSet.size());
        assertTrue(reprocessSet.contains(id5));
        assertTrue(reprocessSet.contains(id1));
        assertFalse(reprocessSet.contains(id10));
    }
}
