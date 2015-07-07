import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.*;

import kyotocabinet.*;

/**
 * Created by hao-linyang on 6/11/15.
 *
 * TODO READ api design methods
 * TODO READ java design patterns, programming books
 *
 * TODO need a name for this super fast and scale-able database
 *
 * This Buffer to the learner's memory lookup table attempts to improve performance of training by reducing disk accesses.
 * TODO ADVANCED feature: Automatic resizing of the database file numbers, redistribution of shards
 * TODO ADVANCED profiling and optimization
 *
 * TODO ADVANCED: LOW MEMORY: load one at a time, HIGH MEMORY: load all(HIGH PRIORITY), LOAD FIXED SIZED(Can be auto detect based on leftover memory size): loads the ones closest to flush,
 * TODO after a flush, the next closest is chosen and loaded, once loaded it stays until a flush. If a flush on a bin happens but it is not
 * TODO pre-loaded, just open an instance and flush (So should leave a bit of memory margin for these situs).
 */
// TODO check if db is singleton.... fuck...
public class LearnerMemBufferKyotoCabinet {

    // Parameters for tuning the database
    public static final int BATCH_SIZE = 1000;
    public static final String BUCKET_NUM = "100M";
    public static final String MAP_SIZE = "1G";
    public static final int NUM_DB_FILES = 10;
    public static final int DEFRAG_UNIT = 8;

    // Parameters for tuning the buffer
    public static final int MAX_BUFFER_SIZE = 12 * 1000 * 1000;
    public static final int REPORT_INTERVAL = 2 * 1000;
    public static final int FLUSH_KEY_THRESHOLD = 10;
    //public static final int MAX_BIN_BUFFER_SIZE = MAX_BUFFER_SIZE / NUM_DB_FILES;


    public static final String LEARNER_MEM = "./learner_memory"; // path to the database file

    // Charset used
    public static final String CHARSET = "US-ASCII";

    // Database object for Kyoto Cabinet
    // TODO make option to have all databases open in memory at once
    private DB[] mMemDBs;

    // in memory maps for the buffer of long keys waiting to be written to disk at next flush
    private Map<String, Advice>[] mLongKeyBuffers;

    // In memory map as the buffer for short keys
    private Map<String, Advice> mShortKeyBuffer;

    // Training count
    private long mTrainedCount;
    // Number of keys in DB
    private long mTotalKeys;

    // Further stats bookkeeping
    private long mTotalTimeSaved;
    private long mTotalReadTime;
    private long mTotolWriteTime;

    public LearnerMemBufferKyotoCabinet() {

        // Initialize the databases
        mMemDBs = new DB[NUM_DB_FILES];
        for (int i = 0; i < NUM_DB_FILES; i++) {
            mMemDBs[i] = new DB();

            if (!mMemDBs[i].tune_encoding("US_ASCII")) {
                System.out.println("Failed to tune encoding to Ascii.");
            }
        }

        // Open/create the database files, load in the total training count when the
        // database that contains it is opened
        int binNum = Math.abs("TRAINED_COUNT".hashCode() % NUM_DB_FILES);
        for (int i = 0; i < NUM_DB_FILES; i++) {
            // Open or create the database files
            if (!mMemDBs[i].open(LEARNER_MEM + "_" + i + ".kch#opts=l#bnum=" + BUCKET_NUM + "#msiz=" + MAP_SIZE + "#dfunit=" + DEFRAG_UNIT, DB.OWRITER | DB.OCREATE)) {
                System.err.println("open error: " + mMemDBs[i].error());
            }

            // If this database contains the training count then get it
            if (i == binNum) {
                String trained_count = mMemDBs[i].get("TRAINED_COUNT");
                if (trained_count == null) {
                    // First time the database file is opened, so does not have this key yet, insert it
                    mMemDBs[i].add("TRAINED_COUNT", Long.toString(0));
                    mTrainedCount = 0;
                } else {
                    mTrainedCount = Long.parseLong(trained_count);
                }
            }
        }

        // initialize array of maps used to sort the keys into bins
        mLongKeyBuffers = new Map[NUM_DB_FILES];
        for (int i = 0; i < NUM_DB_FILES; i++) {
            mLongKeyBuffers[i] = new HashMap<>();
        }

        // Initialize buffer
        mShortKeyBuffer = new HashMap<String, Advice>();

        // Load in the size of the table and the total training count
        //Path sizePath = FileSystems.getDefault().getPath(LEARNER_MEM_SIZE);
        /*
        Path trainedCountPath = FileSystems.getDefault().getPath(LEARNER_TRAINED_COUNT);
        try {
            //List<String> sizeLines = Files.readAllLines(sizePath, Charset.forName(CHARSET));
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
            } else if (countLines.size() == 1) {
                mTrainedCount = Long.parseLong(countLines.get(0));
            } else {
                // Our trainded count file contains more than 2 entries, something is wrong
                System.out.println("Trained count file contains more than 1 entry!");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        */
    }

    // Used by trainer, gets results from the buffer, not the disk
    public Advice get(String key) {

        int keySize = Integer.parseInt(key.split(":")[1]);

        // Must fetch from the correct buffer
        Advice advice;
        if (keySize >= FLUSH_KEY_THRESHOLD) {
            int binNum = Math.abs(key.hashCode() % NUM_DB_FILES);
            advice = mLongKeyBuffers[binNum].get(key);
        } else {
            advice = mShortKeyBuffer.get(key);
        }

        return advice;
    }

    // Used by player, fetches from disk, buffer should be flushed first for most up to date data
    public Advice getPlayerAdvice(History key) {
        String keyString = key.toString();
        int binNum = Math.abs(keyString.hashCode() % NUM_DB_FILES);

        String dat = mMemDBs[binNum].get(key.toString());
        if (dat == null) {
            return null;
        } else {
            return new Advice(dat);
        }
    }

    public void put(History key, Advice advice) {
        String keyString = key.toString();
        int keySize = key.size();

        if (getBufferedCount() >= MAX_BUFFER_SIZE) {
            flushBuffer(false);
        }

        if (keySize < FLUSH_KEY_THRESHOLD) {
            // Put it in short key buffer if it is below the flush length
            mShortKeyBuffer.put(keyString, advice);
        } else {
            int binNum = Math.abs(keyString.hashCode() % NUM_DB_FILES);
            mLongKeyBuffers[binNum].put(keyString, advice);
        }

    }

    public void flushBuffer() {
        flushBuffer(true);
    }

    // Writes the changes in the buffer to persistent storage, if flush all is true all the keys including the short keys will be flushed
    // if false, only the long keys will be flushed
    public void flushBuffer(boolean flushAll) {
        System.out.println("<---Flushing learner memory buffer to disk...--->");

        // Initialize some bookkeeping for progress reports
        Stat stat = new Stat();
        stat.NUM_KEYS_TOTAL = getBufferedCount();
        stat.NUM_TO_FLUSH = 0;
        stat.CURRENT_PROGRESS = 0;
        stat.CURRENT_DB_SIZE = 0;
        stat.CURRENT_DB_COUNT = 0;
        stat.START_TIME = System.currentTimeMillis();
        stat.LAST_REPORT_TIME = stat.START_TIME;

        // Sort the keys into bins to write to their respective files
        /* TODO Deprecated. Delete?
        for (String key : mMemBuffer.keySet()) {
            int keySize = Integer.parseInt(key.split(":")[1]);

            if (keySize >= flushLength) {
                int binNum = Math.abs(key.hashCode() % NUM_DB_FILES);
                mBins[binNum].put(key, mMemBuffer.get(key));
                stat.NUM_TO_FLUSH++;
            }

            stat.CURRENT_PROGRESS++;
            long currentTime = System.currentTimeMillis();
            if (currentTime - stat.LAST_REPORT_TIME > REPORT_INTERVAL) {
                System.out.println("\tProcessing keys: " + stat.CURRENT_PROGRESS + "/" + stat.NUM_KEYS_TOTAL + " ETA: " + Main.msToClockTime((long) (((stat.NUM_KEYS_TOTAL - stat.CURRENT_PROGRESS) / ((double) stat.CURRENT_PROGRESS) * (currentTime - stat.START_TIME)))));
                stat.LAST_REPORT_TIME = currentTime;
            }
        }

        // Remove the keys to be flushed from the buffer
        for (int i = 0; i < NUM_DB_FILES; i++) {
            for (String key : mBins[i].keySet()) {
                mMemBuffer.remove(key);
            }
        }
        */

        System.out.println("\t+++++Writing key values to disk+++++");

        // sort the short keys to the correct bins if flushing all
        if (flushAll) {
            for (String key : mShortKeyBuffer.keySet()) {
                int binNum = Math.abs(key.hashCode() % NUM_DB_FILES);
                mLongKeyBuffers[binNum].put(key, mShortKeyBuffer.get(key));
            }
        }

        // For each bin
        for (int i = 0; i < NUM_DB_FILES; i++) {
            System.out.println("\t### Writing keys to bin " + i +"...");

            // Map to hold the keyvals to flush
            Map<String, Advice> keyValBatch = new HashMap<>();

            /*
            // Open the database file
            if (!mMemDB.open(LEARNER_MEM + "_" + i + ".kch#opts=l#bnum=" + BUCKET_NUM + "#msiz=" + MAP_SIZE + "#dfunit=" + DEFRAG_UNIT, DB.OWRITER | DB.OCREATE)) {
                System.err.println("open error: " + mMemDB.error());
            }
            */

            // For each key in the bin
            for (String key : mLongKeyBuffers[i].keySet()) {

                // Add to batch
                keyValBatch.put(key, mLongKeyBuffers[i].get(key));

                // Batch write to disk if large enough
                if (keyValBatch.size() == BATCH_SIZE) {
                    addBatch(keyValBatch, stat, i);
                    keyValBatch = new HashMap<>();
                    stat.WRITE_PROGRESS += BATCH_SIZE;
                }

                long currentTime = System.currentTimeMillis();
                if (currentTime - stat.LAST_REPORT_TIME > REPORT_INTERVAL) {
                    System.out.println("\tWriting keys: " + stat.WRITE_PROGRESS + "/" + stat.NUM_TO_FLUSH + " ETA: " + Main.msToClockTime((long) (((stat.NUM_TO_FLUSH - stat.WRITE_PROGRESS) / ((double) stat.WRITE_PROGRESS) * (currentTime - stat.START_TIME)))));
                    stat.LAST_REPORT_TIME = currentTime;
                }
            }

            // Add any key values still in the batch
            stat.WRITE_PROGRESS += keyValBatch.size();
            addBatch(keyValBatch, stat, i);

            // Clean up the maps
            mLongKeyBuffers[i] = new HashMap<>();

            // Add info of database to stats
            stat.CURRENT_DB_SIZE += mMemDBs[i].size();
            stat.CURRENT_DB_COUNT += mMemDBs[i].count();
        }



        long endTime = System.currentTimeMillis();
        System.out.println("\t+++++Writes Completed+++++");

        stat.TIME_TOTAL = endTime - stat.START_TIME;
        stat.TIME_SAVED = (long) ( ((stat.NUM_KEYS_TOTAL - stat.NUM_TO_FLUSH) / (double) stat.NUM_TO_FLUSH) * stat.TIME_TOTAL );
        mTotalTimeSaved += stat.TIME_SAVED;
        mTotalKeys = stat.CURRENT_DB_COUNT;
        stat.TOTAL_TIME_SAVED = mTotalTimeSaved;
        System.out.println("\tDone processing " + stat.NUM_KEYS_TOTAL + " keys!");
        System.out.print(stat.toString());
    }

    public void close() {
        flushBuffer();
        save();
    }

    public long getUniqueKeys() {
        return mTotalKeys;
    }

    public long getTrainedCount() { return mTrainedCount; }

    public void addTrainingCount(long count) {
        mTrainedCount += count;
    }

    public void save() {
        // Figure out which database file the TRAINED_COUNT key is hashed to
        int binNum = Math.abs(("TRAINED_COUNT".hashCode() % NUM_DB_FILES));

        // Open the database file
        /*if (!mMemDB.open(LEARNER_MEM + "_" + binNum + ".kch#bnum=" + BUCKET_NUM + "#msiz=" + MAP_SIZE, DB.OWRITER | DB.OCREATE)) {
            System.err.println("open error: " + mMemDB.error());
        }*/

        // Record the total trained count
        mMemDBs[binNum].add("TRAINED_COUNT", "" + mTrainedCount);

        // Close the database
        // mMemDBs[binNum].close();
    }

    // Merges the values in buffer mapped by the list of keys with the values in the database
    private void addBatch(Map<String, Advice> keyVals, Stat stat, int binNum) {

        // Read in the batch
        long readBatchStart = System.currentTimeMillis();
        Map<String, String> dbMap = mMemDBs[binNum].get_bulk(new ArrayList<String>(keyVals.keySet()), false);
        long readBatchEnd = System.currentTimeMillis();

        stat.NUM_DB_HITS += dbMap.size();

        // Record time
        stat.READ_TIME += readBatchEnd - readBatchStart;

        // For each key in key val
        for (String key : keyVals.keySet()) {

            // Get the new advice
            Advice newAdvice = keyVals.get(key);
            // Get the string version of the old advice on disk
            String val = dbMap.get(key);


            if (val == null) {
                // If the returned val for old advice is null then this is a new key, put it in the map
                dbMap.put(key, newAdvice.toDat());
            } else {
                // Reconstruct the Advice object from the string format
                Advice oldAdvice = new Advice(val);

                // update the old Advice with the new votes
                oldAdvice.setVoteHigh(oldAdvice.getVoteHigh() + newAdvice.getVoteHigh());
                oldAdvice.setVoteLow(oldAdvice.getVoteLow() + newAdvice.getVoteLow());

                // put the updated advice back in the database map
                dbMap.put(key, oldAdvice.toDat());
            }
        }

        // Update the database
        long writeBatchStart = System.currentTimeMillis();
        mMemDBs[binNum].set_bulk(dbMap, false);
        long writeBatchEnd = System.currentTimeMillis();

        // Record time
        stat.WRITE_TIME += writeBatchEnd - writeBatchStart;
    }

    private long getBufferedCount() {
        long count = mShortKeyBuffer.size();
        for (int i = 0; i < NUM_DB_FILES; i++) {
            count += mLongKeyBuffers[i].size();
        }
        return count;
    }
}

