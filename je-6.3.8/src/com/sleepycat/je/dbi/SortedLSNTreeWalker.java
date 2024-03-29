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

package com.sleepycat.je.dbi;

import java.io.FileNotFoundException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.sleepycat.je.CacheMode;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.EnvironmentFailureException;
import com.sleepycat.je.evictor.Evictor;
import com.sleepycat.je.log.LogEntryType;
import com.sleepycat.je.log.LogManager;
import com.sleepycat.je.log.WholeEntry;
import com.sleepycat.je.log.entry.BINDeltaLogEntry;
import com.sleepycat.je.log.entry.OldBINDeltaLogEntry;
import com.sleepycat.je.log.entry.LNLogEntry;
import com.sleepycat.je.log.entry.LogEntry;
import com.sleepycat.je.tree.BIN;
import com.sleepycat.je.tree.OldBINDelta;
import com.sleepycat.je.tree.IN;
import com.sleepycat.je.tree.LN;
import com.sleepycat.je.tree.Node;
import com.sleepycat.je.utilint.DbLsn;
import com.sleepycat.je.utilint.Pair;
import com.sleepycat.je.utilint.SizeofMarker;

/**
 * SortedLSNTreeWalker uses ordered disk access rather than random access to
 * iterate over a database tree. Faulting in data records by on-disk order can
 * provide much improved performance over faulting in by key order, since the
 * latter may require random access.  SortedLSN walking does not obey cursor
 * and locking constraints, and therefore can only be guaranteed consistent for
 * a quiescent tree which is not being modified by user or daemon threads.
 *
 * The class walks over the tree using sorted LSN fetching for parts of the
 * tree that are not in memory. It returns LSNs for each node in the tree,
 * <b>except</b> the root IN, in an arbitrary order (i.e. not key
 * order). The caller is responsible for getting the root IN's LSN explicitly.
 * <p>
 * A callback function specified in the constructor is executed for each LSN
 * found.
 * <p>
 * The walker works in two phases.  The first phase is to gather and return all
 * the resident INs using the roots that were specified when the SLTW was
 * constructed.  For each child of each root, if the child is resident it is
 * passed to the callback method (processLSN).  If the child was not in memory,
 * it is added to a list of LSNs to read.  When all of the in-memory INs have
 * been passed to the callback for all LSNs collected, phase 1 is complete.
 * <p>
 * In phase 2, for each of the sorted LSNs, the target is fetched, the type
 * determined, and the LSN and type passed to the callback method for
 * processing.  LSNs of the children of those nodes are retrieved and the
 * process repeated until there are no more nodes to be fetched for this
 * database's tree.  LSNs are accumulated in batches in this phase so that
 * memory consumption is not excessive.  For instance, if batches were not used
 * then the LSNs of all of the BINs would need to be held in memory.
 */
public class SortedLSNTreeWalker {

    /*
     * The interface for calling back to the user with each LSN.
     */
    public interface TreeNodeProcessor {
        void processLSN(long childLSN,
                        LogEntryType childType,
                        Node theNode,
                        byte[] lnKey,
                        int lastLoggedSize)
            throws FileNotFoundException, DatabaseException;

        /* Used for processing dirty (unlogged) deferred write LNs. [#15365] */
        void processDirtyDeletedLN(long childLSN, LN ln, byte[] lnKey)
            throws DatabaseException;

        /* Called when the internal memory limit is exceeded. */
        void noteMemoryExceeded();
    }

    /*
     * Optionally passed to the SortedLSNTreeWalker to be called when an
     * exception occurs.
     */
    public interface ExceptionPredicate {
        /* Return true if the exception can be ignored. */
        boolean ignoreException(Exception e);
    }

    protected final DatabaseImpl[] dbImpls;
    protected final EnvironmentImpl envImpl;

    /*
     * Save the root LSN at construction time, because the root may be
     * nulled out before walk() executes.
     */
    private final long[] rootLsns;

    /*
     * Whether to call DatabaseImpl.finishedINListHarvest().
     */
    private final boolean setDbState;

    /* The limit on memory to be used for internal structures during SLTW. */
    private long internalMemoryLimit = Long.MAX_VALUE;

    /* The current memory usage by internal SLTW structures. */
    private long internalMemoryUsage;

    private final TreeNodeProcessor callback;

    /*
     * If true, then walker should fetch LNs and pass them to the
     * TreeNodeProcessor callback method.  Even if true, dup LNs are not
     * fetched because they are normally never used (see accumulateDupLNs).
     */
    protected boolean accumulateLNs = false;

    /*
     * If true, fetch LNs in a dup DB.  Since LNs in a dup DB are not used by
     * cursor operations, fetching dup LNs should only be needed in very
     * exceptional situations.  Currently this field is never set to true.
     */
    protected boolean accumulateDupLNs = false;

    /*
     * If non-null, save any exceptions encountered while traversing nodes into
     * this savedException list, in order to walk as much of the tree as
     * possible. The caller of the tree walker will handle the exceptions.
     */
    private final List<DatabaseException> savedExceptions;

    private final ExceptionPredicate excPredicate;

    /*
     * The batch size of LSNs which will be sorted.
     */
    private long lsnBatchSize = Long.MAX_VALUE;

    /* Holder for returning LN key from fetchLSN. */
    private final DatabaseEntry lnKeyEntry = new DatabaseEntry();

    /*
     * This map provides an LSN to IN/index. When an LSN is processed by the
     * tree walker, the map is used to lookup the parent IN and child entry
     * index of each LSN processed by the tree walker.  Since fetchLSN is
     * called with an arbitrary LSN, and since when we fetch (for preload) we
     * need to setup the parent to refer to the node which we are prefetching,
     * we need to have the parent in hand at the time of the call to fetchLSN.
     * This map allows us to keep a reference to that parent so that we can
     * call fetchNode on that parent.
     *
     * It is also necessary to maintain this map for cases other than preload()
     * so that during multi-db walks (i.e. multi db preload), we can associate
     * an arbitrary LSN back to the parent IN and therefore connect a fetch'ed
     * Node into the proper place in the tree.
     *
     * LSN -> INEntry
     */
    /* struct to hold IN/entry-index pair. */
    public static class INEntry {
        final IN in;
        final int index;

        INEntry(IN in, int index) {
            assert in != null;
            assert in.getDatabase() != null;
            this.in = in;
            this.index = index;
        }

        public INEntry(@SuppressWarnings("unused") SizeofMarker marker) {
            this.in = null;
            this.index = 0;
        }

        Object getDelta() {
            return null;
        }

        long getDeltaLsn() {
            return DbLsn.NULL_LSN;
        }

        long getMemorySize() {
            return MemoryBudget.HASHMAP_ENTRY_OVERHEAD +
                   MemoryBudget.INENTRY_OVERHEAD;
        }
    }

    /**
     * Supplements INEntry with BIN-delta information.  When a BIN-delta is
     * encountered during the fetching process, we cannot immediately place it
     * in the tree.  Instead we queue a DeltaINEntry for fetching the full BIN,
     * in LSN order as usual.  When the full BIN is fetched, the DeltaINEntry
     * is used to apply the delta and place the result in the tree.
     */
    public static class DeltaINEntry extends INEntry {
        private final Object delta;
        private final long deltaLsn;

        DeltaINEntry(IN in, int index, Object delta, long deltaLsn) {
            super(in, index);
            assert (delta != null);
            assert (deltaLsn != DbLsn.NULL_LSN);
            this.delta = delta;
            this.deltaLsn = deltaLsn;
        }

        public DeltaINEntry(SizeofMarker marker) {
            super(marker);
            this.delta = null;
            this.deltaLsn = 0;
        }

        @Override
        Object getDelta() {
            return delta;
        }

        @Override
        long getDeltaLsn() {
            return deltaLsn;
        }

        @Override
        long getMemorySize() {
            final long deltaSize;
            if (delta instanceof OldBINDelta) {
                deltaSize = ((OldBINDelta) delta).getMemorySize();
            } else {
                deltaSize = ((BIN) delta).getInMemorySize();
            }
            return deltaSize +
                MemoryBudget.HASHMAP_ENTRY_OVERHEAD +
                MemoryBudget.DELTAINENTRY_OVERHEAD;
        }
    }

    private final Map<Long, INEntry> lsnINMap = new HashMap<Long, INEntry>();

    /*
     * @param dbImpls an array of DatabaseImpls which should be walked over
     * in disk order.  This array must be parallel to the rootLsns array in
     * that rootLsns[i] must be the root LSN for dbImpls[i].
     *
     * @param setDbState if true, indicate when the INList harvest has
     * completed for a particular DatabaseImpl.
     *
     * @param rootLsns is passed in addition to the dbImpls, because the
     * root may be nulled out on the dbImpl before walk() is called.
     *
     * @param callback the callback instance
     *
     * @param savedExceptions a List of DatabaseExceptions encountered during
     * the tree walk.
     *
     * @param excPredicate a predicate to determine whether a given exception
     * should be ignored.
     */
    public SortedLSNTreeWalker(DatabaseImpl[] dbImpls,
                               boolean setDbState,
                               long[] rootLsns,
                               TreeNodeProcessor callback,
                               List<DatabaseException> savedExceptions,
                               ExceptionPredicate excPredicate)
        throws DatabaseException {

        if (dbImpls == null || dbImpls.length < 1) {
            throw EnvironmentFailureException.unexpectedState
                ("DatabaseImpls array is null or 0-length for " +
                 "SortedLSNTreeWalker");
        }

        this.dbImpls = dbImpls;
        this.envImpl = dbImpls[0].getEnv();
        /* Make sure all databases are from the same environment. */
        for (DatabaseImpl di : dbImpls) {
            EnvironmentImpl ei = di.getEnv();
            if (ei == null) {
                throw EnvironmentFailureException.unexpectedState
                    ("environmentImpl is null for target db " +
                     di.getDebugName());
            }

            if (ei != this.envImpl) {
                throw new IllegalArgumentException
                    ("Environment.preload() must be called with Databases " +
                     "which are all in the same Environment. (" +
                     di.getDebugName() + ")");
            }
        }

        this.setDbState = setDbState;
        this.rootLsns = rootLsns;
        this.callback = callback;
        this.savedExceptions = savedExceptions;
        this.excPredicate = excPredicate;
    }

    void setLSNBatchSize(long lsnBatchSize) {
        this.lsnBatchSize = lsnBatchSize;
    }

    void setInternalMemoryLimit(long internalMemoryLimit) {
        this.internalMemoryLimit = internalMemoryLimit;
    }

    void incInternalMemoryUsage(long increment) {
        internalMemoryUsage += increment;
    }

    private LSNAccumulator createLSNAccumulator() {
        return new LSNAccumulator() {
            @Override
            void noteMemUsage(long increment) {
                incInternalMemoryUsage(increment);
            }
        };
    }

    /**
     * Find all non-resident nodes, and execute the callback.  The root IN's
     * LSN is not returned to the callback.
     */
    public void walk()
        throws DatabaseException {

        walkInternal();
    }

    protected void walkInternal()
        throws DatabaseException {

        /*
         * Phase 1: seed the SLTW with all of the roots of the DatabaseImpl[].
         * For each root, look for all in-memory child nodes and process them
         * (i.e. invoke the callback on those LSNs).  For child nodes which are
         * not in-memory (i.e. they are LSNs only and no Node references),
         * accumulate their LSNs to be later sorted and processed during phase
         * 2.
         */
        LSNAccumulator pendingLSNs = createLSNAccumulator();
        for (int i = 0; i < dbImpls.length; i += 1) {
            processRootLSN(dbImpls[i], pendingLSNs, rootLsns[i]);
        }

        /*
         * Phase 2: Sort and process any LSNs we've gathered so far. For each
         * LSN, fetch the target record and process it as in Phase 1 (i.e.
         * in-memory children get passed to the callback, not in-memory children
         * have their LSN accumulated for later sorting, fetching, and
         * processing.
         */
        processAccumulatedLSNs(pendingLSNs);
    }

    /*
     * Retrieve the root for the given DatabaseImpl and then process its
     * children.
     */
    private void processRootLSN(DatabaseImpl dbImpl,
                                LSNAccumulator pendingLSNs,
                                long rootLsn) {
        IN root = getOrFetchRootIN(dbImpl, rootLsn);
        if (root != null) {
            try {
                accumulateLSNs(root, pendingLSNs);
            } finally {
                releaseRootIN(root);
            }
        }

        if (setDbState) {
            dbImpl.finishedINListHarvest();
        }
    }

    /*
     * Traverse the in-memory tree rooted at "parent". For each visited node N
     * call the callback method on N and put in pendingLSNs the LSNs of N's
     * non-resident children.
     *
     * On entring this method, parent is latched and remains latched on exit.
     */
    protected void accumulateLSNs(IN parent, LSNAccumulator pendingLSNs)
        throws DatabaseException {

        DatabaseImpl db = parent.getDatabase();
        boolean dups = db.getSortedDuplicates();

        /*
         * Without dups, all BINs contain only LN children.  With dups, it
         * depends on the dup format.  Preload works with the old dup format
         * and the new.
         *
         * In the new dup format (or after dup conversion), BINs contain only
         * LNs and no DBINs exist.  In the old dup format, DBINs contain only
         * LN children, but BINs may contain a mix of LNs and DINs.
         */
        boolean allChildrenAreLNs;
        if (!dups || db.getDupsConverted()) {
            allChildrenAreLNs = parent.isBIN();
        } else {
            allChildrenAreLNs = parent.isBIN() && parent.containsDuplicates();
        }

        /*
         * If LNs are not needed, there is no need to accumulate the child LSNs
         * when all children are LNs.
         */
        boolean accumulateChildren = true;
        if (allChildrenAreLNs) {
            accumulateChildren = dups ? accumulateDupLNs : accumulateLNs;
        }

        /*
         * Process all children, but only accumulate LSNs for children that are
         * not in memory.
         */
        for (int i = 0; i < parent.getNEntries(); i += 1) {

            long lsn = parent.getLsn(i);
            Node child = parent.getTarget(i);
            boolean childCached = child != null;

            byte[] lnKey = null;
            if (allChildrenAreLNs || (childCached && child.isLN())) {
                lnKey = parent.getKey(i);
            }

            if (parent.isEntryPendingDeleted(i) ||
                parent.isEntryKnownDeleted(i)) {

                /* Dirty LNs (deferred write) get special treatment. */
                processDirtyLN(child, lsn, lnKey);
                /* continue; */

            } else if (accumulateChildren &&
                       !childCached &&
                       lsn != DbLsn.NULL_LSN) {

                /*
                 * Child is not in cache. Put its LSN in the current batch of
                 * LSNs to be sorted and fetched in phase 2. But don't do
                 * this if the child is an embedded LN.
                 */
                if (!parent.isEmbeddedLN(i)) {
                    pendingLSNs.add(lsn);
                    addToLsnINMap(lsn, parent, i);
                } else {
                    callProcessLSNHandleExceptions(
                        DbLsn.NULL_LSN, LogEntryType.LOG_INS_LN, null/*child*/,
                        lnKey, 0/*lastLoggedSize*/);
                }

            } else if (childCached) {

                child.latchShared();
                boolean isLatched = true;

                try {
                    if (child.isBINDelta()) {

                        /* Deltas not allowed with deferred-write. */
                        assert (lsn != DbLsn.NULL_LSN);

                        BIN delta = (BIN)child;
                        long fullLsn = delta.getLastFullLsn();
                        pendingLSNs.add(fullLsn);
                        addToLsnINMap(fullLsn, parent, i, delta, lsn);

                    } else {

                        child.releaseLatch();
                        isLatched = false;

                        processChild(
                            lsn, child, lnKey, parent.getLastLoggedSize(i),
                            pendingLSNs);
                    }
                } finally {
                    if (isLatched) {
                        child.releaseLatch();
                    }
                }
    
            } else {
                /*
                 * We are here because the child was not cached and was not
                 * accumulated either (because it was an LN and LN accumulation
                 * is turned off or its LSN was NULL). 
                 */
                processChild(
                    lsn, child, lnKey, parent.getLastLoggedSize(i),
                    pendingLSNs);
            }

            /*
             * If we've exceeded the batch size then process the current
             * batch and start a new one.
             */
            boolean internalMemoryExceeded =
                internalMemoryUsage > internalMemoryLimit;

            if (pendingLSNs.getNTotalEntries() > lsnBatchSize ||
                internalMemoryExceeded) {
                if (internalMemoryExceeded) {
                    callback.noteMemoryExceeded();
                }
                processAccumulatedLSNs(pendingLSNs);
                pendingLSNs.clear();
            }
        }
    }

    private void processDirtyLN(Node node, long lsn, byte[] lnKey) {
        if (node != null && node.isLN()) {
            LN ln = (LN) node;
            if (ln.isDirty()) {
                callback.processDirtyDeletedLN(lsn, ln, lnKey);
            }
        }
    }

    protected void processChild(
        long lsn,
        Node child,
        byte[] lnKey,
        int lastLoggedSize,
        LSNAccumulator pendingLSNs) {

        boolean childCached = (child != null);

        /*
         * If the child is resident, use its log type, else it must be an LN.
         */
        callProcessLSNHandleExceptions(
            lsn,
            (!childCached ?
             LogEntryType.LOG_INS_LN /* Any LN type will do */ :
             child.getGenericLogType()),
            child, lnKey, lastLoggedSize);

        if (childCached && child.isIN()) {
            IN nodeAsIN = (IN) child;
            try {
                nodeAsIN.latch(CacheMode.UNCHANGED);
                accumulateLSNs(nodeAsIN, pendingLSNs);
            } finally {
                nodeAsIN.releaseLatch();
            }
        }
    }

    /*
     * Process a batch of LSNs by sorting and fetching each of them.
     */
    protected void processAccumulatedLSNs(LSNAccumulator pendingLSNs) {

        long[] currentLSNs = null;

        while (!pendingLSNs.isEmpty()) {
            currentLSNs = pendingLSNs.getAndSortPendingLSNs();
            pendingLSNs = createLSNAccumulator();
            for (long lsn : currentLSNs) {
                fetchAndProcessLSN(lsn, pendingLSNs);
            }
        }
    }

    /*
     * Fetch the node at 'lsn' and callback to let the invoker process it.  If
     * it is an IN, accumulate LSNs for it.
     */
    private void fetchAndProcessLSN(long lsn, LSNAccumulator pendingLSNs)
        throws DatabaseException {

        lnKeyEntry.setData(null);

        Pair<Node, Integer> result = fetchLSNHandleExceptions(
            lsn, lnKeyEntry, pendingLSNs);

        if (result == null) {
            return;
        }

        Node node = result.first();
        int lastLoggedSize = result.second();
        boolean isIN = (node instanceof IN);
        IN in = null;
        try {
            if (isIN) {
                in = (IN) node;
                in.latch(CacheMode.UNCHANGED);
            }
            callProcessLSNHandleExceptions(
                lsn, node.getGenericLogType(), node, lnKeyEntry.getData(),
                lastLoggedSize);

            if (isIN) {
                accumulateLSNs(in, pendingLSNs);
            }
        } finally {
            if (isIN) {
                in.releaseLatch();
            }
        }
    }

    private Pair<Node, Integer> fetchLSNHandleExceptions(
        long lsn,
        DatabaseEntry lnKeyEntry,
        LSNAccumulator pendingLSNs) {

        DatabaseException dbe = null;

        try {
            return fetchLSN(lsn, lnKeyEntry, pendingLSNs);
        } catch (FileNotFoundException e) {
            if (excPredicate == null ||
                !excPredicate.ignoreException(e)) {
                dbe = new EnvironmentFailureException
                    (envImpl,
                     EnvironmentFailureReason.LOG_FILE_NOT_FOUND, e);
            }
        } catch (DatabaseException e) {
            if (excPredicate == null ||
                !excPredicate.ignoreException(e)) {
                dbe = e;
            }
        }

        if (dbe != null) {
            if (savedExceptions != null) {

                /*
                 * This LSN fetch hit a failure. Do as much of the rest of
                 * the tree as possible.
                 */
                savedExceptions.add(dbe);
            } else {
                throw dbe;
            }
        }

        return null;
    }

    protected void callProcessLSNHandleExceptions(long childLSN,
                                                  LogEntryType childType,
                                                  Node theNode,
                                                  byte[] lnKey,
                                                  int lastLoggedSize) {
        DatabaseException dbe = null;

        try {
            callback.processLSN(
                childLSN, childType, theNode, lnKey, lastLoggedSize);
        } catch (FileNotFoundException e) {
            if (excPredicate == null ||
                !excPredicate.ignoreException(e)) {
                dbe = new EnvironmentFailureException(
                    envImpl, EnvironmentFailureReason.LOG_FILE_NOT_FOUND, e);
            }
        } catch (DatabaseException e) {
            if (excPredicate == null ||
                !excPredicate.ignoreException(e)) {
                dbe = e;
            }
        }

        if (dbe != null) {
            if (savedExceptions != null) {

                /*
                 * This LSN fetch hit a failure. Do as much of the rest of
                 * the tree as possible.
                 */
                savedExceptions.add(dbe);
            } else {
                throw dbe;
            }
        }
    }

    /**
     * Returns the root IN, latched shared.  Allows subclasses to override
     * getResidentRootIN and/or getRootIN to modify behavior.
     * getResidentRootIN is called first,
     */
    protected IN getOrFetchRootIN(DatabaseImpl dbImpl, long rootLsn) {
        final IN root = getResidentRootIN(dbImpl);
        if (root != null) {
            return root;
        }
        if (rootLsn == DbLsn.NULL_LSN) {
            return null;
        }
        return getRootIN(dbImpl, rootLsn);
    }

    /**
     * The default behavior fetches the rootIN from the log and latches it
     * shared. Classes extending this may fetch (and latch) the root from the
     * tree.
     */
    protected IN getRootIN(DatabaseImpl dbImpl, long rootLsn) {
        final IN root = (IN)
            envImpl.getLogManager().getEntryHandleFileNotFound(rootLsn);
        if (root == null) {
            return null;
        }
        root.setDatabase(dbImpl);
        root.latchShared(CacheMode.DEFAULT);
        return root;
    }

    /**
     * The default behavior returns (and latches shared) the IN if it is
     * resident in the Btree, or null otherwise.  Classes extending this may
     * return (and latch) a known IN object.
     */
    protected IN getResidentRootIN(DatabaseImpl dbImpl) {
        return dbImpl.getTree().getResidentRootIN(true /*latched*/);
    }

    /**
     * Release the latch.  Overriding this method should not be necessary.
     */
    protected void releaseRootIN(IN root) {
        root.releaseLatch();
    }

    /**
     * Add an LSN-IN/index entry to the map.
     */
    private void addToLsnINMap(long lsn, IN in, int index) {
        addEntryToLsnMap(lsn, new INEntry(in, index));
    }

    /**
     * Add an LSN-IN/index entry, along with a delta and delta LSN, to the map.
     */
    private void addToLsnINMap(long lsn,
                               IN in,
                               int index,
                               Object delta,
                               long deltaLsn) {
        addEntryToLsnMap(lsn, new DeltaINEntry(in, index, delta, deltaLsn));
    }

    private void addEntryToLsnMap(long lsn, INEntry inEntry) {
        if (lsnINMap.put(lsn, inEntry) == null) {
            incInternalMemoryUsage(inEntry.getMemorySize());
        }
    }

    /*
     * Process an LSN.  Get & remove its INEntry from the map, then fetch the
     * target at the INEntry's IN/index pair.  This method will be called in
     * sorted LSN order.
     */
    private Pair<Node, Integer> fetchLSN(
        long lsn,
        DatabaseEntry lnKeyEntry,
        LSNAccumulator pendingLSNs)
        throws FileNotFoundException, DatabaseException {

        LogManager logManager = envImpl.getLogManager();

        INEntry inEntry = lsnINMap.remove(lsn);
        assert (inEntry != null) : DbLsn.getNoFormatString(lsn);
        
        incInternalMemoryUsage(- inEntry.getMemorySize());
        
        IN in = inEntry.in;
        
        boolean isLatchedAlready = in.isLatchExclusiveOwner();
        if (!isLatchedAlready) {
            in.latch();
        }

        DatabaseImpl dbImpl = in.getDatabase();
        byte[] lnKey = null;

        try {
            int index = inEntry.index;

            /*
             * Concurrent activity (e.g., log cleaning) that was active before
             * we took the root latch may have changed the state of a slot.
             * Repeat check for LN deletion and check that the LSN has not
             * changed.
             */
            if (in.isEntryPendingDeleted(index) ||
                in.isEntryKnownDeleted(index)) {
                return null;
            }

            if (inEntry.getDelta() == null) {
                if (in.getLsn(index) != lsn) {
                    return null;
                }
            } else {
                if (in.getLsn(index) != inEntry.getDeltaLsn()) {
                    return null;
                }
            }

            /*
             * If concurrent activity (e.g., log cleaning) has loaded the node,
             * then return it and continue.  During a preload it is critical
             * not to overwrite a live node (further below) with a freshly
             * fetched one, since any modifications will be effectively
             * overwritten.  [#21319]
             */
            Node residentNode = in.getTarget(index);

            if (residentNode != null) {

                if (residentNode.isBINDelta(false)) {
                    BIN delta = (BIN) residentNode;
                    long fullLsn = delta.getLastFullLsn();
                    pendingLSNs.add(fullLsn);
                    addToLsnINMap(fullLsn, in, index, delta, lsn);
                    return null;
                }
                /* Return LN key. [#21667] */
                if (residentNode.isLN()) {
                    lnKeyEntry.setData(in.getKey(index));
                }
                return new Pair<>(residentNode, in.getLastLoggedSize(index));
            }

            /* Fetch log entry. */
            WholeEntry wholeEntry = logManager.getWholeLogEntry(lsn);
            LogEntry entry = wholeEntry.getEntry();

            int lastLoggedSize = wholeEntry.getHeader().getEntrySize();

            /*
             * For a BIN delta, queue fetching of the full BIN and combine the
             * full BIN with the delta when it is processed later (see below).
             */
            if (entry instanceof BINDeltaLogEntry) {
                BINDeltaLogEntry deltaEntry = (BINDeltaLogEntry) entry;
                long fullLsn = deltaEntry.getPrevFullLsn();
                BIN delta = deltaEntry.getMainItem();
                pendingLSNs.add(fullLsn);
                addToLsnINMap(fullLsn, in, index, delta, lsn);
                return null;
            }

            if (entry instanceof OldBINDeltaLogEntry) {
                OldBINDelta delta = (OldBINDelta) entry.getMainItem();
                long fullLsn = delta.getLastFullLsn();
                pendingLSNs.add(fullLsn);
                addToLsnINMap(fullLsn, in, index, delta, lsn);
                return null;
            }

            /* For an LNLogEntry, call postFetchInit and get the lnKey. */
            if (entry instanceof LNLogEntry) {
                LNLogEntry<?> lnEntry = (LNLogEntry<?>) entry;
                lnEntry.postFetchInit(dbImpl);
                lnKey = lnEntry.getKey();
                lnKeyEntry.setData(lnKey);
            }

            /* Get the Node from the LogEntry. */
            Node ret = (Node) entry.getResolvedItem(dbImpl);

            /*
             * For an IN Node, set the database so it will be passed down to
             * nested fetches.
             */
            long lastLoggedLsn = lsn;
            if (ret instanceof IN) {
                IN retIn = (IN) ret;
                retIn.setDatabase(dbImpl);
            }

            /*
             * If there is a delta, then this is a BIN to which the delta must
             * be applied.  The delta LSN is the last logged LSN.
             */
            Object deltaObject = inEntry.getDelta();

            if (deltaObject != null) {
                final BIN fullBIN = (BIN) ret;

                if (deltaObject instanceof OldBINDelta) {
                    OldBINDelta delta = (OldBINDelta) deltaObject;
                    assert lsn == delta.getLastFullLsn();
                    delta.reconstituteBIN(dbImpl, fullBIN);
                    lastLoggedLsn = inEntry.getDeltaLsn();
                } else {
                    BIN delta = (BIN) deltaObject;
                    assert lsn == delta.getLastFullLsn();
                    delta.reconstituteBIN(dbImpl, fullBIN);
                    lastLoggedLsn = inEntry.getDeltaLsn();
                }
            }

            /* During a preload, finally place the Node into the Tree. */
            if (fetchAndInsertIntoTree()) {

                ret.postFetchInit(dbImpl, lastLoggedLsn);

                in.attachNode(index, ret, lnKey);
                /* Last logged size is not present before log version 9. */
                in.setLastLoggedSize(index, lastLoggedSize);

                if (in.isBIN()) {
                    final Evictor evictor = envImpl.getEvictor();
                    CacheMode mode = in.getDatabase().getDefaultCacheMode();
                    if (evictor != null && mode != CacheMode.EVICT_LN) {
                        evictor.moveToMixedLRU(in);
                    }
                }
            }
            return new Pair<>(ret, lastLoggedSize);
        } finally {
            if (!isLatchedAlready) {
                in.releaseLatch();
            }
        }
    }

    /*
     * Overriden by subclasses if fetch of an LSN should result in insertion
     * into tree rather than just instantiating the target.
     */
    protected boolean fetchAndInsertIntoTree() {
        return false;
    }

    public List<DatabaseException> getSavedExceptions() {
        return savedExceptions;
    }
}
