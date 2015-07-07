import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * Created by hao-linyang on 6/11/15.
 *
 * This Buffer to the learner's memory lookup table attempts to improve performance of training by reducing disk accesses.
 * Database uses Berkely DB Java Edition
 */
public class LearnerMemBufferBerkeleyDB {

    public static final int MAX_BUFFER_SIZE = 2000000;
    public static final int REPORT_INTERVAL = 1 * 1000;

    public static final String LEARNER_MEM = "./learner_memory";  // Path to the environment home

    // Files paths for storage of size and training count
    public static final String LEARNER_MEM_SIZE = "./learner_memory_size.dat";
    public static final String LEARNER_TRAINED_COUNT = "./learner_memory_trained_count.dat";
    public static final String CHARSET = "US-ASCII";

    // Stuff for Berkeley DB Java Edition. Stores our hash table on disk because it is not going to fit in memory
    private File mLearnerMemEnvFile;
    private LearnerMemAccessor mLearnerMemAccessor;
    private LearnerMemEnv mLearnerMemEnv = new LearnerMemEnv();

    // In memory map as the buffer
    private Map<String, Advice> mMemBuffer;

    // Size of the learner memory table
    private long mUniqueKeys;
    // Training count
    private long mTrainedCount;

    public LearnerMemBufferBerkeleyDB() {
        // Setup the learner environment
        mLearnerMemEnvFile = new File(LEARNER_MEM);
        mLearnerMemEnv.setup(mLearnerMemEnvFile);

        // Open the data accessor. This is used to store
        // persistent objects.
        mLearnerMemAccessor = new LearnerMemAccessor(mLearnerMemEnv.getEntityStore());

        mMemBuffer = new HashMap<String, Advice>();

        // Load in the size of the table
        Path sizePath = FileSystems.getDefault().getPath(LEARNER_MEM_SIZE);
        Path trainedCountPath = FileSystems.getDefault().getPath(LEARNER_TRAINED_COUNT);
        try {
            List<String> sizeLines = Files.readAllLines(sizePath, Charset.forName(CHARSET));
            List<String> countLines = Files.readAllLines(trainedCountPath, Charset.forName(CHARSET));

            if (sizeLines.isEmpty()) {
                mUniqueKeys = 0;
            } else if (sizeLines.size() == 1) {
                mUniqueKeys = Long.parseLong(sizeLines.get(0));
            } else {
                // Our size file contains more than 2 entries, something is wrong
                System.out.println("Size file contains more than 1 entry!");
            }

            if (countLines.isEmpty()) {
                mTrainedCount = 0;
            } else if (sizeLines.size() == 1) {
                mTrainedCount = Long.parseLong(countLines.get(0));
            } else {
                // Our trainded count file contains more than 2 entries, something is wrong
                System.out.println("Trained count file contains more than 1 entry!");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Used by trainer
    public Advice get(String key) {
        if (mMemBuffer.containsKey(key)) {
            return mMemBuffer.get(key);
        } else {
            return null;
        }
    }

    // Used by player
    public Advice getPlayerAdvice(History key) {
        return mLearnerMemAccessor.adviceByHistory.get(key.toString());
    }

    public void put(String key, Advice advice) {
        if (mMemBuffer.size() >= MAX_BUFFER_SIZE) {
            flushBuffer();
        }
        mMemBuffer.put(key, advice);
    }

    // Writes the changes in the buffer to persistent storage
    public void flushBuffer() {
        System.out.println("<---Flushing learner memory buffer to disk...--->");

        // Initialize some bookkeeping for progress reports
        int numKeysTotal = mMemBuffer.size();
        int currentKeysFlushed = 0;
        long startTime = System.currentTimeMillis();
        long lastReportTime = startTime;

        for (String key : mMemBuffer.keySet()) {
            Advice newAdvice = mMemBuffer.get(key);
            Advice oldAdvice = mLearnerMemAccessor.adviceByHistory.get(key);
            if (oldAdvice == null) {
                // This is a new key!
                mUniqueKeys++;
                mLearnerMemAccessor.adviceByHistory.put(newAdvice);
            } else {
                // update the old Advice with the new votes
                oldAdvice.setVoteHigh(oldAdvice.getVoteHigh() + newAdvice.getVoteHigh());
                oldAdvice.setVoteLow(oldAdvice.getVoteLow() + newAdvice.getVoteLow());

                // put the advice back in the table to update it
                mLearnerMemAccessor.adviceByHistory.put(oldAdvice);
            }

            currentKeysFlushed++;

            long currentTime = System.currentTimeMillis();
            if (currentTime - lastReportTime > REPORT_INTERVAL) {
                System.out.println("\tBuffer flush progress: " + currentKeysFlushed + "/" + numKeysTotal + " ETA: " + Main.msToClockTime((long) (((numKeysTotal - currentKeysFlushed) / ((double) currentKeysFlushed) * (currentTime - startTime)))));
                lastReportTime = currentTime;
            }
        }
        mMemBuffer.clear();

        long endTime = System.currentTimeMillis();
        System.out.println("Done flushing " + numKeysTotal + " keys! Took: " + Main.msToClockTime(endTime - startTime));
    }

    public void close() {
        mLearnerMemEnv.close();
        save();
    }

    public long getUniqueKeys() {
        return mUniqueKeys;
    }

    public long getTrainedCount() { return mTrainedCount; }

    public void addTrainingCount(long count) { mTrainedCount += count; }

    public void save() {
        Path sizePath = FileSystems.getDefault().getPath(LEARNER_MEM_SIZE);
        Path countPath = FileSystems.getDefault().getPath(LEARNER_TRAINED_COUNT);
        try {
            Files.write(sizePath, Long.toString(mUniqueKeys).getBytes(), StandardOpenOption.TRUNCATE_EXISTING);
            Files.write(countPath, Long.toString(mTrainedCount).getBytes(), StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


}
