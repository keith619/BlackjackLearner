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

package com.sleepycat.je.log;

import java.util.Collection;

import com.sleepycat.je.cleaner.PackedObsoleteInfo;
import com.sleepycat.je.dbi.DatabaseImpl;
import com.sleepycat.je.txn.WriteLockInfo;

/**
 * Context parameters that apply to all logged items when multiple items are
 * logged in one log operation.  Passed to LogManager log methods and to
 * beforeLog and afterLog methods.
 */
public class LogContext {

    /**
     * Database of the node(s), or null if entry is not a node.  Used for per-
     * database utilization tracking.
     *
     * Set by caller or beforeLog method.
     */
    public DatabaseImpl nodeDb = null;

    /**
     * Whether the log buffer(s) must be written to the file system.
     *
     * Set by caller or beforeLog method.
     */
    public boolean flushRequired = false;

    /**
     * Whether a new log file must be created for containing the logged
     * item(s).
     *
     * Set by caller or beforeLog method.
     */
    public boolean forceNewLogFile = false;

    /**
     * Whether an fsync must be performed after writing the item(s) to the log.
     *
     * Set by caller or beforeLog method.
     */
    public boolean fsyncRequired = false;

    /**
     * Whether the write should be counted as background IO when throttling of
     * background IO is configured.
     *
     * Set by caller or beforeLog method.
     */
    public boolean backgroundIO = false;

    /**
     * Set of obsolete LSNs which are counted when logging a commit entry.
     * This information includes the DatabaseImpl for each LSN, and the nodeDb
     * field does not apply.
     *
     * Set by caller or beforeLog method.
     */
    public Collection<WriteLockInfo> obsoleteWriteLockInfo = null;

    /**
     * Sequence of packed obsolete info which is counted when logging a
     * non-provisional IN.  This information is for a single database, the
     * nodeDb.  The nodeDb is passed as a parameter to countObosoleteNode when
     * adding this information to the global tracker.
     *
     * Set by caller or beforeLog method.
     */
    public PackedObsoleteInfo packedObsoleteInfo = null;

    /**
     * Whether it is possible that the previous version of this log
     * entry is already marked obsolete. In general, the latest version
     * of any IN or LN is alive, so that logging a new version requires making
     * the last version obsolete. Utilization tracking generally asserts
     * that this last version is not already obsolete.
     *
     * When partial rollbacks are used, some of the original intermediate
     * versions may have been pruned away, leaving a current previous that
     * was already marked obsolete. For example, a transaction might have
     * done:
     *
     * LNA (version 1)
     * LNA (version 2)
     *  -- now version 1 is obsolete
     *  -- if we do a partial rollback to version1, verison 2 is removed
     *  -- we start retransmitting
     * LNA (version 2)
     *
     * When we log this LNA/version2, this previous LNA (version1) is
     * already obsolete. obsoleteDupsAllowed supports this case.
     *
     * Set by caller or beforeLog method.
     */
    public boolean obsoleteDupsAllowed = false;

    /* Fields used internally by log method. */
    boolean wakeupCleaner = false;

    int totalNewSize = 0;

    boolean switchedLogBuffer = false;
}
