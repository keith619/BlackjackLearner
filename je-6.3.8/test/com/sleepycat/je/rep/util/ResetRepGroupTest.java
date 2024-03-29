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

package com.sleepycat.je.rep.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import static com.sleepycat.je.rep.utilint.RepTestUtils.TEST_REP_GROUP_NAME;

import java.io.File;
import java.io.FileWriter;
import java.io.Writer;

import com.sleepycat.je.CommitToken;
import com.sleepycat.je.rep.utilint.RepTestUtils;
import com.sleepycat.je.utilint.VLSN;
import org.junit.Before;
import org.junit.Test;

import com.sleepycat.je.CheckpointConfig;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.rep.InsufficientLogException;
import com.sleepycat.je.rep.NetworkRestore;
import com.sleepycat.je.rep.NetworkRestoreConfig;
import com.sleepycat.je.rep.NodeType;
import com.sleepycat.je.rep.ReplicatedEnvironment;
import com.sleepycat.je.rep.ReplicationConfig;
import com.sleepycat.je.rep.impl.RepParams;
import com.sleepycat.je.rep.impl.RepTestBase;
import com.sleepycat.je.rep.impl.node.FeederManager;
import com.sleepycat.je.rep.utilint.RepTestUtils.RepEnvInfo;
import com.sleepycat.je.util.TestUtils;

/**
 * Tests operation of the DbResetGroup utility
 */
public class ResetRepGroupTest extends RepTestBase {

    /* Prefix associated with new nodes in the reset rep group. */
    private static final String NEW_NODE_PREFIX = "nx";

    /* The new "reset" rep group name. */
    private static final String NEW_GROUP_NAME = "gx";

    @Override
    @Before
    public void setUp()
        throws Exception {

        groupSize = 4;
        super.setUp();
    }

    /** Test reset with old master as new master */
    @Test
    public void testBasicMaster()
        throws Exception {

        testBasic(0, false);
    }

    /** Same, but recreating the group in place. */
    @Test
    public void testBasicMasterInPlace()
        throws Exception {

        testBasic(0, true);
    }

    /** Test reset with old electable replica as new master */
    @Test
    public void testBasicReplica()
        throws Exception {

        testBasic(1, false);
    }

    /** Same, but recreating the group in place. */
    @Test
    public void testBasicReplicaInPlace()
        throws Exception {

        testBasic(1, true);
    }

    /** Test reset with old secondary as new master */
    @Test
    public void testBasicSecondary()
        throws Exception {

        testBasic(3, false);
    }

    /** Same, but recreating the group in place. */
    @Test
    public void testBasicSecondaryInPlace()
        throws Exception {

        testBasic(3, true);
    }

    /**
     * Basic test of reseting the group using the node with the specified ID as
     * the first node.  If retainGroupUUID is true, then the existing group
     * UUID is reused, allowing existing group members to rejoin without doing
     * a network restore.
     */
    void testBasic(final int firstNodeId, final boolean retainGroupUUID)
        throws Exception {

        final int numElectableNodes = 3;
        repEnvInfo[3].getRepConfig().setNodeType(NodeType.SECONDARY);

        for (RepEnvInfo info : repEnvInfo) {
            EnvironmentConfig config = info.getEnvConfig();
            /* Smaller log files to provoke faster cleaning */
            config.setConfigParam(EnvironmentConfig.LOG_FILE_MAX, "100000");
        }

        createGroup();

        /*
         * Populate, then sync group since we may use a replica as the basis
         * for resetting the group. We need enough data files for log cleaning
         * further below.
         */
        final CommitToken token =
            populateDB(repEnvInfo[0].getEnv(), "db1", 10000);
        RepTestUtils.syncGroupToVLSN(
            repEnvInfo, repEnvInfo.length, new VLSN(token.getVLSN()));

        closeNodes(repEnvInfo);

        ReplicationConfig config = repEnvInfo[firstNodeId].getRepConfig();

        File jePropertiesFile = null;
        if (retainGroupUUID) {

            /*
             * Arrange to retain the group UUID.  DbResetGroup doesn't provide
             * a way to specify arbitrary configuration parameters, so store
             * this one in the je.properties file.
             */
            jePropertiesFile = new File(repEnvInfo[firstNodeId].getEnvHome(),
                                        "je.properties");
            final Writer out = new FileWriter(jePropertiesFile);
            try {
                out.write(RepParams.RESET_REP_GROUP_RETAIN_UUID.getName() +
                          "=true\n");
            } finally {
                out.close();
            }
        }

        resetRepEnvInfo(config, firstNodeId, retainGroupUUID);

        DbResetRepGroup reset = new DbResetRepGroup(
            repEnvInfo[firstNodeId].getEnvHome(),
            config.getGroupName(),
            config.getNodeName(),
            config.getNodeHostPort());
        reset.reset();
        if (retainGroupUUID) {
            jePropertiesFile.delete();
        }

        /* Make sure the new master is electable. */
        repEnvInfo[firstNodeId].getRepConfig().setNodeType(
            NodeType.ELECTABLE);

        /*
         * Open the environment that was converted, to the new group, node and
         * hostport location
         */
        ReplicatedEnvironment env = repEnvInfo[firstNodeId].openEnv();

        assertEquals(retainGroupUUID ? TEST_REP_GROUP_NAME : NEW_GROUP_NAME,
                     env.getGroup().getName());
        assertTrue(env.getNodeName().startsWith(
                       retainGroupUUID ? "Node" : NEW_NODE_PREFIX));

        /* Check that a new internal node id was allocated. */
        final int newNodeId =
            repEnvInfo[firstNodeId].getRepNode().getNodeId();
        assertTrue("New node ID (" + newNodeId + ") should be greater than " +
                   numElectableNodes,
                   newNodeId > numElectableNodes);

        /*
         * Create enough data to provoke cleaning and truncation of the VLSN
         * index.  Truncation does not occur, though, if retaining the group
         * UUID.
         */
        populateDB(env, "db1", 1000);

        /* Create cleaner fodder */
        env.removeDatabase(null, "db1");

        /*
         * Wait for Master VLSN update.  Because there are no replicas,
         * FeederManager.runFeeders must wait for the call to
         * BlockingQueue.poll timeout, before updating the master's CBVLSN.
         */
        Thread.sleep(FeederManager.MASTER_CHANGE_CHECK_TIMEOUT * 2);

        env.cleanLog();
        env.checkpoint(new CheckpointConfig().setForce(true));
        if (!retainGroupUUID) {
            assertTrue("failed to provoke cleaning",
                       env.getStats(null).getNCleanerDeletions() > 0);
        }

        /*
         * Grow the group, expecting ILEs when opening the environment unless
         * retaining group UUIDs
         */
        for (int i=0; i < repEnvInfo.length; i++) {
            if (i == firstNodeId) {
                continue;
            }
            try {
                repEnvInfo[i].openEnv();
                if (!retainGroupUUID) {
                    fail("ILE exception expected");
                }
            } catch (InsufficientLogException ile) {
                if (retainGroupUUID) {
                    throw ile;
                }
                NetworkRestore restore = new NetworkRestore();
                restore.execute(ile, new NetworkRestoreConfig());
                ReplicatedEnvironment env2 = repEnvInfo[i].openEnv();
                assertEquals(NEW_GROUP_NAME, env2.getGroup().getName());
                assertTrue(env2.getNodeName().startsWith(NEW_NODE_PREFIX));
                int id = repEnvInfo[i].getRepNode().getNodeId();
                assertTrue("New node ID (" + id + ") for node " + i +
                           " should be greater than " + numElectableNodes,
                           id > numElectableNodes);
            }
        }
    }

    /**
     * Updates the repEnvInfo array with the configuration for the new
     * singleton group. It also removes all environment directories for all
     * nodes with the exception of the restarted one, unless retaining the
     * group UUID.
     */
    private void resetRepEnvInfo(ReplicationConfig config,
                                 int firstNodeId,
                                 boolean retainGroupUUID)
        throws IllegalArgumentException {
        /* Assign new group, name and ports for the nodes. */
        for (int j = 0; j < repEnvInfo.length; j++) {

            /* Start with the first node and wrap around */
            int i = (firstNodeId + j) % repEnvInfo.length;
            RepEnvInfo info = repEnvInfo[i];
            ReplicationConfig rconfig = info.getRepConfig();
            rconfig.setHelperHosts(config.getNodeHostPort());

            if (retainGroupUUID) {

                /* Just convert to SECONDARY */
                rconfig.setConfigParam(
                    RepParams.IGNORE_SECONDARY_NODE_ID.getName(), "true");
                rconfig.setNodeType(NodeType.SECONDARY);
                continue;
            }

            int newPort = rconfig.getNodePort() + repEnvInfo.length;
            rconfig.setGroupName(NEW_GROUP_NAME);
            rconfig.setNodeName(NEW_NODE_PREFIX + i);
            rconfig.setNodeHostPort(rconfig.getNodeHostname() + ":" + newPort);

            EnvironmentConfig econfig = info.getEnvConfig();
            /*
             * Turn off the cleaner, since it's controlled explicitly
             * by the test.
             */
            econfig.setConfigParam(EnvironmentConfig.ENV_RUN_CLEANER,
                                   "false");

            /* Remove all other environment directories. */
            if (i != firstNodeId) {
                TestUtils.removeLogFiles("RemoveRepEnvironments",
                                         info.getEnvHome(),
                                         false); // checkRemove
            }
        }
    }
}
