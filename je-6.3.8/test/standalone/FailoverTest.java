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

import java.io.File;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.StatsConfig;
import com.sleepycat.je.Transaction;
import com.sleepycat.je.rep.RepInternal;
import com.sleepycat.je.rep.ReplicatedEnvironment;
import com.sleepycat.je.rep.ReplicationConfig;
import com.sleepycat.je.rep.utilint.RepTestUtils;
import com.sleepycat.je.rep.utilint.RepTestUtils.RepEnvInfo;
import com.sleepycat.je.utilint.LoggerUtils;
import com.sleepycat.je.utilint.VLSN;
import com.sleepycat.persist.EntityStore;
import com.sleepycat.persist.PrimaryIndex;

/**
 * Exercises replica-only, master-only, and replica/master hybrid failover. Will
 * eventually supercede ReplicaFailover.
 */
public class FailoverTest {
    private static final boolean verbose = Boolean.getBoolean("verbose");

    /* Time to wait for each node to syncup to the master. */
    private static final long NODE_SYNC_MS = 60 * 1000;

    private RepEnvInfo[] repEnvInfo;
    private final StatsConfig statsConfig;
    
    /* -------------------Configurable params----------------*/
    /* Environment home root for whole replication group. */
    private File envRoot;
    /* Replication group size. */
    private int nNodes = 5;
    /* Database size. */
    private int dbSize = 2000;
    /* Steady state will finish after doing this number of transactions. */
    private int steadyTxns = 60000;

    /* 
     * Specify the JE log file size and checkpoint bytes to provoke log
     * cleaning.
     */
    private String logFileSize = "409600";
    private String checkpointBytes = "1000000";

    /* Select a new master after doing this number of operations. */
    private int txnsPerRound = 30;
    private final int opsPerTxn = 20;

    /* Cycle through the array nodes when provoking failovers. */
    private int roundRobinIdx;

    /* A value to use to create new records. */
    private int nextVal = dbSize + 1;

    /* Used for generating random keys. */
    private final Random random = new Random();

    /* Determines whether master, replica, or hybrid failover is tested. */
    private FailoverAgent failoverAgent;
    private int subDir = 0;

    Logger logger = LoggerUtils.getLoggerFixedPrefix(getClass(), 
                                                     "FailoverTest");

    FailoverTest() {
        statsConfig = new StatsConfig();
        statsConfig.setFast(false);
        statsConfig.setClear(true);
    }

    public static void main(String args[]) {
        try {
            FailoverTest test = new FailoverTest();
            test.parseArgs(args);
            test.doRampup();
            test.doSteadyState();
            if (verbose) {
                System.err.println("Test done");
            }
        } catch (Throwable t) {
            t.printStackTrace(System.err);
            System.exit(1);
        } 
    }

    /**
     * Grow the data store to the appropriate size for the steady state 
     * portion of the test.
     */
    private void doRampup()
        throws Exception {

        /* 
         * Clean up from previous runs. This test will not succeed if there is
         * anything in the test environments.
         */
        RepTestUtils.removeRepEnvironments(envRoot);

        EnvironmentConfig envConfig = 
            Utils.createEnvConfig(logFileSize, checkpointBytes, subDir);

        /* 
         * We have a lot of environments open in a single process, so reduce
         * the cache size lest we run out of file descriptors.
         */
        envConfig.setConfigParam("je.log.fileCacheSize", "30");

        repEnvInfo = RepTestUtils.setupEnvInfos(envRoot, nNodes, envConfig);

        /* Increase timeout to avoid InsufficientAcksException. */
        for (RepEnvInfo info : repEnvInfo) {
            info.getRepConfig().setReplicaAckTimeout(50, TimeUnit.SECONDS);
        }
        
        if (subDir > 0) {
            RepTestUtils.createRepSubDirs(repEnvInfo, subDir);
        }

        ReplicatedEnvironment master = Utils.getMaster(repEnvInfo);
        EntityStore store = Utils.openStore(master, Utils.DB_NAME);
        RepTestData.insertData(store, dbSize);
        Utils.doSyncAndCheck(repEnvInfo);
    }

    /**
     * Execute a steady stream of work until the steady state phase is over.
     */
    private void doSteadyState()
        throws Exception {

        if (verbose) {
            System.out.println("Steady state starting");
        }

        int round = 0;
        failoverAgent.init();

        while (true) {
            round++;
            if (verbose) {
                System.err.println ("Round " + round);
            }

            /*
             * If doWork returns false, it means the steady state stage is
             * over.  In this loop, the master stays up. The replicas are
             * killed in a round robin fashion. No need to sync up between
             * rounds; avoiding the syncup and check of data makes for more
             * variation.
             */
            if (!oneRoundWorkAndFailover(round)) {
                if (verbose) {
                    System.err.println("Steady state is over, ending test.");
                }
                break;
            }
        }

        /*
         * Shutdown all nodes and sync them up using DbSync. Then check
         * for node equality.
         */
        ReplicatedEnvironment master = Utils.getMaster(repEnvInfo);
        VLSN lastTxnEnd = RepInternal.getRepImpl(master).getVLSNIndex().
            getRange().getLastTxnEnd();
        for (RepEnvInfo info : repEnvInfo) {
            info.closeEnv();
        }

        /*
         * Run DbSync for each node.  This is done concurrently in a thread per
         * node, but the syncups are competing for resources, so the time to
         * syncup and gain consistency for one node could in the worst case be
         * as long as the time to do it serially for all nodes.
         */
        long maxTimeoutMs = NODE_SYNC_MS * nNodes;
        String maxTimeoutStr = String.valueOf(maxTimeoutMs) + " ms";
        for (RepEnvInfo info : repEnvInfo) {
            info.getRepConfig().setConfigParam(
                ReplicationConfig.ENV_CONSISTENCY_TIMEOUT, maxTimeoutStr);
            info.getRepConfig().setConfigParam(
                ReplicationConfig.ENV_SETUP_TIMEOUT, maxTimeoutStr);
        }
        RepTestUtils.syncupGroup(maxTimeoutMs, repEnvInfo);

        /* Re-open them to check node equality. */
        for (RepEnvInfo info : repEnvInfo) {
            info.getEnvConfig().setConfigParam("je.env.runCleaner", "false");
            info.getEnvConfig().setConfigParam("je.env.runINCompressor",
					       "false");
        }

        RepTestUtils.restartGroup(repEnvInfo);
        RepTestUtils.checkNodeEquality(lastTxnEnd, verbose, repEnvInfo);
    	RepTestUtils.checkUtilizationProfile(repEnvInfo);

        for (RepEnvInfo info : repEnvInfo) {
            info.closeEnv();
        }

        /* Use DbSpace to check utilization. */
    }
    
    /**
     * One round of work: the master executes updates. The replicas are killed
     * off in the middle of a round, so that they die on a non-commit boundary,
     * and need to do some rollback.
     *
     * @return false if the rampup stage has finished.
     */
    private boolean oneRoundWorkAndFailover(int round)
        throws Exception {

        boolean runAble = true;

        // TODO: Utils.getMaster re-opens the whole group. Maybe we should try
        //  letting the killed nodes stay down longer.

        ReplicatedEnvironment master = Utils.getMaster(repEnvInfo);
        for (RepEnvInfo rep : repEnvInfo) {
            logger.info(RepInternal.getRepImpl(rep.getEnv()).dumpState());
        }

        EntityStore dbStore = Utils.openStore(master, Utils.DB_NAME);
        PrimaryIndex<Integer, RepTestData> primaryIndex =
            dbStore.getPrimaryIndex(Integer.class, RepTestData.class);
 
        for (int whichTxn = 0; whichTxn < txnsPerRound; whichTxn++) {
            Transaction txn = master.beginTransaction(null, null);
            for (int i = 0; i < opsPerTxn; i++) {

                /* Do a random update here. */
                int key = random.nextInt(dbSize);
                RepTestData data = new RepTestData();
                data.setKey(key);
                data.setData(nextVal++);
                data.setName(RepTestData.generateNameField(key));
                primaryIndex.put(txn, data);
            }

            /*
             * Kill some set of nodes once during this round, in the middle
             * of a transaction.
             */
            if (failoverAgent.killOnIteration(whichTxn)) {
                roundRobinIdx = failoverAgent.shutdownNodes(round,
                	                                    whichTxn,
                	                                    roundRobinIdx);
            }

            if (master.isValid()) {
                /* 
                 * This txn may be invalid if the master was killed by the
                 * failover agent.
                 */
                txn.commit();
            }

            /* Check whether the steady stage should break. */
            if (--steadyTxns == 0) {
                runAble = false;
                break;
            }
        }

        try {
            dbStore.close();
        } catch (RuntimeException e) {
            if (master.isValid()) {
                throw e;
            }
        }

        return runAble;
    }

    private void setFailoverAgent(String modeValue) 
        throws IllegalArgumentException {

        if (modeValue.equalsIgnoreCase("replica")) {
            failoverAgent = new ReplicaFailover();
        } else if (modeValue.equalsIgnoreCase("master")) {
            failoverAgent = new MasterFailover();
        } else if (modeValue.equalsIgnoreCase("hybrid")) {
            failoverAgent = new HybridFailover();
        } else {
            throw new IllegalArgumentException
                (modeValue + 
                 " is not a legal value for -mode. " +
                 " Only master | replica | hybrid is accepted.");
        }
    }

    private void parseArgs(String args[])
        throws Exception {

        for (int i = 0; i < args.length; i++) {
            boolean moreArgs = i < args.length - 1;
            if (args[i].equals("-h") && moreArgs) {
                envRoot = new File(args[++i]);
            } else if (args[i].equals("-repGroupSize") && moreArgs) {
                nNodes = Integer.parseInt(args[++i]);
            } else if (args[i].equals("-dbSize") && moreArgs) {
                dbSize = Integer.parseInt(args[++i]);
            } else if (args[i].equals("-logFileSize") && moreArgs) {
                logFileSize = args[++i];
            } else if (args[i].equals("-steadyTxns") && moreArgs) {
                steadyTxns = Integer.parseInt(args[++i]);
            } else if (args[i].equals("-txnsPerRound") && moreArgs) {
                txnsPerRound = Integer.parseInt(args[++i]);
            } else if (args[i].equals("-checkpointBytes") && moreArgs) {
                checkpointBytes = args[++i];
            } else if (args[i].equals("-mode") && moreArgs) {
                setFailoverAgent(args[++i]);
            } else if (args[i].equals("-subDir") && moreArgs) {
                subDir = Integer.parseInt(args[++i]);
            } else {
                usage("Unknown arg: " + args[i]);
            }
        }

        if (nNodes <= 2) {
            throw new IllegalArgumentException
                ("Replication group size should > 2!");
        }

        if (steadyTxns < txnsPerRound) {
            throw new IllegalArgumentException
                ("steadyTxns should be larger than txnsPerRound!");
        }

        if (failoverAgent == null) {
            usage("-mode must be specified");
        }
    }

    private void usage(String error) {
        if (error != null) {
            System.err.println(error);
        }

        System.err.println
            ("java " + getClass().getName() + "\n" +
             "     [-h <replication group Environment home dir>]\n" +
             "     [-mode <master | replica | hybrid>\n" +
             "     [-repGroupSize <replication group size>]\n" +
             "     [-dbSize <records' number of the tested database>]\n" +
             "     [-logFileSize <JE log file size>]\n" +
             "     [-checkpointBytes <Checkpointer wakeup interval bytes>]\n" +
             "     [-steadyTxns <the total update operations in steady state>]\n" +
             "     [-txnsPerRound <select a new master after running this " +
             "                     number of txns>]\n" +
             "     [-subDir <num directories to use with je.log.nDataDirectories");
        System.exit(2);
    }

    /**
     * The FailoverAgent decides which nodes to kill, and when to kill
     * them.
     */
    private abstract class FailoverAgent {
        protected int victimsPerRound;
        
        public void init() {

            /* 
             * victimsPerRound Must be set after all args are parsed, to ensure
             * that nNodes has been initialized.
             */
            victimsPerRound = nNodes - RepTestUtils.getQuorumSize(nNodes);
        }

        /**
         * @return true if a kill should be done on this iteration of the
         * test loop.
         */
        public abstract boolean killOnIteration(int currentTxnIndex);

        /**
         * @return the next index to start a shutdown, so that the test runs
         * in a round robin fashion.
         */
        public abstract int shutdownNodes(int whichRound,
                                          int currentTxnIndex,
                                          int lastShutdownIndex);

        /**
         * Generate a handy label which says which round and txn we are
         * on, for debugging.
         */
        protected String killLabel(int whichRound, int currentTxnIndex) {
            return "Round " + whichRound +
                " countdownTxn " + steadyTxns +
                " killTxn " + currentTxnIndex + ":";
        }
        
        protected void killMaster(String label) {
            for (RepEnvInfo info : repEnvInfo) {
                if (info.isMaster()) {
                    if (verbose) {
                        System.err.println(label + " killing master " + 
                                           info.getEnv().getNodeName());
                    }
                    info.abnormalCloseEnv();

                    /* 
                     * Be sure to break out of the loop after killing a master,
                     * because an election will start, and a new master might
                     * be elected as we continue to iterate over the set.
                     */
                    break;
                }
            }
        }

        protected int killReplicas(String label,
                                   int numVictims, 
                                   int lastShutdownIndex) {
            int nShutdown = numVictims;
            int shutdownIndex = lastShutdownIndex;

            while (nShutdown > 0) {
                RepEnvInfo info = repEnvInfo[shutdownIndex];
                if (info.isReplica()) {
                    if (verbose) {
                        System.err.println(label + " killing replica " +  
                                           info.getEnv().getNodeName());
                    }
                    info.abnormalCloseEnv();
                    nShutdown--;
                }

                shutdownIndex++;
                if (shutdownIndex == nNodes) {
                    shutdownIndex = 0;
                }
            }
            return shutdownIndex;
        }
    }

    /*
     * Shutdown the maximum number of replicas that still leaves a quorum.
     */
    private class ReplicaFailover extends FailoverAgent {

        @Override
        public boolean killOnIteration(int currentTxn) {
            /* Always kill in the middle of the first txn of the round. */
            return currentTxn == 0;
        }

        @Override
        public int shutdownNodes(int whichRound,
                                 int currentTxnIndex,
                                 int lastShutdownIndex) {
            
            return killReplicas(killLabel(whichRound, currentTxnIndex),
                                victimsPerRound, lastShutdownIndex);
        }
    }

    /*
     * Shutdown the master only.
     */
    private class MasterFailover extends FailoverAgent {

        @Override
        public boolean killOnIteration(int currentTxn) {

            /*
             * Always kill in the middle of the last txn of the round. Since
             * this is the master, we need to have it alive to execute writes.
             */
            return currentTxn == (txnsPerRound - 1);
        }

        @Override
        public int shutdownNodes(int whichRound,
                                 int currentTxnIndex,
                                 int lastShutdownIndex) {
            killMaster(killLabel(whichRound, currentTxnIndex));
            return 0;
        }
    }

    /*
     * Shutdown the the master and in addition the maximum number of replicas
     * that still leaves a quorum.
     */
    private class HybridFailover extends FailoverAgent {

        @Override
        public boolean killOnIteration(int currentTxn) {
            /*
             * Always kill in the middle of the last txn of the round. Since
             * this is the master, we need to have it alive to execute writes.
             */
            return currentTxn == txnsPerRound - 1;
        }

        @Override
        public int shutdownNodes(int whichRound,
                                 int currentTxnIndex,
                                 int lastShutdownIndex) {

            String label = killLabel(whichRound, currentTxnIndex);
            killMaster(label);

            /* 
             * Kill one less than the prescribed victims per round, because
             * we already killed the master.
             */
            return killReplicas(label, victimsPerRound-1, lastShutdownIndex);
        }
    }
}
