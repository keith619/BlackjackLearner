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

package com.sleepycat.je.util;

import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.sleepycat.util.test.TestBase;

/**
 * Checks the DbCacheSize returns consistent results by comparing the
 * calculated and measured values.  If this test fails, it probably means the
 * technique used by DbCacheSize for estimating or measuring has become
 * outdated or incorrect.  Or, it could indicate a bug in memory budget
 * calculations or IN memory management.  Try running DbCacheSize manually to
 * debug, using the cmd string for the test that failed.
 */
@RunWith(Parameterized.class)
public class DbCacheSizeTest extends TestBase {

    private static final boolean VERBOSE = false;

    /*
     * It is acceptable for the measured values to be somewhat different than
     * the calculated values, due to differences in actual BIN density, for
     * example.
     */
    private static double ERROR_ALLOWED = 0.10;

    static final String[] COMMANDS = {
        /* 0*/ "-records 100000 -key 10 -data 100",
        /* 1*/ "-records 100000 -key 10 -data 100 -orderedinsertion",
        /* 2*/ "-records 100000 -key 10 -data 100 -duplicates",
        /* 3*/ "-records 100000 -key 10 -data 100 -duplicates " +
               "-orderedinsertion",
        /* 4*/ "-records 100000 -key 10 -data 100 -nodemax 250",
        /* 5*/ "-records 100000 -key 10 -data 100 -nodemax 250 " +
               "-orderedinsertion",
        /* 6*/ "-records 100000 -key 20 -data 100 -keyprefix 10",
        /* 7*/ "-records 100000 -key 20 -data 100 -keyprefix 2 " +
               "-je.tree.compactMaxKeyLength 19",
        /* 8*/ "-records 100000 -key 10 -data 100 -replicated",
        /* 9*/ "-records 100000 -key 10 -data 100 " +
               "-replicated -je.rep.preserveRecordVersion true",
        /*10*/ "-records 100000 -key 10 -data 100 -duplicates " +
               "-replicated -je.rep.preserveRecordVersion true",
        /*11*/ "-records 100000 -key 10 -data 100 -orderedinsertion " +
               "-replicated -je.rep.preserveRecordVersion true",
    };

    /*
     * We always use a large file size so that the LSN compact representation
     * is not used.  This representation is usually not effective for larger
     * data sets, and is disabled by DbCacheSize except under certain
     * conditions.  In this test we use smallish data sets, so we use a large
     * file size to ensure that the compact representation is not used.
     */
    private int ONE_GB = 1024 * 1024 * 1024;
    private final String ADD_COMMANDS =
        "-measure -btreeinfo -je.log.fileMax " + ONE_GB;

    private String cmd;
    private int testNum;

    @Parameters
    public static List<Object[]> genParams() {
       List<Object[]> list = new ArrayList<Object[]>();
       int i = 0;
       for (String cmd : COMMANDS) {
           list.add(new Object[]{cmd, i});
           i++;
       }
       
       return list;
    }
    
    public DbCacheSizeTest(String cmd, int testNum){
        this.cmd = cmd;
        this.testNum = testNum;
        customName = "-" + testNum;
       
    }
    
    @Test
    public void testSize() {

        /* Get estimated cache sizes and measured sizes. */
        final String[] args = (cmd + " " + ADD_COMMANDS).split(" ");
        DbCacheSize util = new DbCacheSize();
        try {
            util.parseArgs(args);
            util.calculateCacheSizes();
            if (VERBOSE) {
                util.printCacheSizes(System.out);
            }
            util.measure(VERBOSE ? System.out : null);
        } finally {
            util.cleanup();
        }

        /*
         * Check that calculated and measured sizes are within some error
         * tolerance.
         */
        check("noLNsOrVLSNs", util.getBtreeSizeNoLNsOrVLSNs(),
              util.getMeasuredBtreeSizeNoLNsOrVLSNs(), ERROR_ALLOWED);
        check("noLNsWithVLSNs", util.getBtreeSizeNoLNsWithVLSNs(),
              util.getMeasuredBtreeSizeNoLNsWithVLSNs(), ERROR_ALLOWED);
        check("withLNsAndVLSNs", util.getBtreeSizeWithLNsAndVLSNs(),
              util.getMeasuredBtreeSizeWithLNsAndVLSNs(), ERROR_ALLOWED);

        /*
         * Do the same for the preloaded values, which is really a self-check
         * to ensure that preload gives the same results.
         */
        check("noLNsOrVLSNsPreload", util.getBtreeSizeNoLNsOrVLSNs(),
              util.getPreloadBtreeSizeNoLNsOrVLSNs(), ERROR_ALLOWED);
        check("noLNsWithVLSNsPreload", util.getBtreeSizeNoLNsWithVLSNs(),
              util.getPreloadBtreeSizeNoLNsWithVLSNs(), ERROR_ALLOWED);
        check("withLNsAndVLSNsPreload", util.getBtreeSizeWithLNsAndVLSNs(),
              util.getPreloadBtreeSizeWithLNsAndVLSNs(), ERROR_ALLOWED);
    }

    private void check(String name,
                       double expected,
                       double actual,
                       double errorAllowed) {
        if ((Math.abs(expected - actual) / expected) > errorAllowed) {
            fail("Error allowed (" + errorAllowed + ") is exceeded for " +
                 name + ", expected=" + expected + ", actual=" + actual +
                 ", testNum=" + testNum);
        }
    }
}
