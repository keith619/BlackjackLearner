/**
 * Created by hao-linyang on 6/10/15.
 */

import java.io.File;

import com.sleepycat.je.DatabaseException;
import com.sleepycat.persist.EntityStore;
import com.sleepycat.persist.PrimaryIndex;

public class LearnerMemAccessor {

    // Open the indices
    public LearnerMemAccessor(EntityStore store)
            throws DatabaseException {

        // Primary key for Advice class
        adviceByHistory = store.getPrimaryIndex(
                String.class, Advice.class);
    }

    // advice Accessors
    PrimaryIndex<String,Advice> adviceByHistory;
}
