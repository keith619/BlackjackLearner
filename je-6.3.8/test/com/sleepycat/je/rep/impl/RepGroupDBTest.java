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

package com.sleepycat.je.rep.impl;

import static org.junit.Assert.assertEquals;

import java.util.Collection;

import org.junit.Test;

import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.rep.RepInternal;
import com.sleepycat.je.rep.ReplicatedEnvironment;
import com.sleepycat.je.rep.utilint.RepTestUtils;
import com.sleepycat.je.rep.utilint.RepTestUtils.RepEnvInfo;

public class RepGroupDBTest extends RepTestBase {

    public RepGroupDBTest() {
    }

    @Test
    public void testBasic()
        throws DatabaseException, InterruptedException {

        RepTestUtils.joinGroup(repEnvInfo);
        RepTestUtils.syncGroupToLastCommit(repEnvInfo, repEnvInfo.length);
        verifyRepGroupDB();
    }

    /**
     * This is a test to verify the fix for SR 20607, where a failure during
     * the process of adding a node can result in a circular wait situation.
     * This test case implements the scenario described in the SR.
     *
     * @see <a href="https://sleepycat.oracle.com/trac/ticket/20607">SR 20607</a>
     */
    @Test
    public void testInitFailure()
        throws DatabaseException {

        /* Create a two node group. */
        RepEnvInfo r1 = repEnvInfo[0];
        RepEnvInfo r2 = repEnvInfo[1];

        r1.openEnv();
        r2.openEnv();

        /* Simulate a process kill of r2 */
        RepInternal.getRepImpl(r2.getEnv()).abnormalClose();

        r1.closeEnv();
        r2.closeEnv();

        /*
         * restart the group,the group should not timeout due to a circular
         * wait, with r1 waiting to conclude an election and r2 trying to
         * locate a mater.
         */
        RepTestUtils.restartGroup(r1, r2);
    }

    /**
     * Verifies that the contents of the database matches the contents of the
     * individual repConfigs.
     *
     * @throws DatabaseException
     */
    private void verifyRepGroupDB()
        throws DatabaseException {
        /*
         * master and replica must all agree on the contents of the
         * rep group db and the local info about the node.
         */
        for (RepEnvInfo repi : repEnvInfo) {

            ReplicatedEnvironment rep = repi.getEnv();
            Collection<RepNodeImpl> nodes =
                RepGroupDB.getGroup(RepInternal.getRepImpl(rep),
                                    RepTestUtils.TEST_REP_GROUP_NAME)
                .getElectableMembers();
            assertEquals(repEnvInfo.length, nodes.size());
            for (RepNodeImpl n : nodes) {
                int nodeId = n.getNodeId();
                RepImpl repImpl =
                    RepInternal.getRepImpl(repEnvInfo[nodeId-1].getEnv());
                assertEquals(repImpl.getPort(), n.getPort());
                assertEquals(repImpl.getHostName(), n.getHostName());
                assertEquals(n.isQuorumAck(), true);
            }
        }
    }
}
