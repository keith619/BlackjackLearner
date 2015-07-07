import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;


import com.mongodb.async.SingleResultCallback;
import com.mongodb.async.client.MongoClient;
import com.mongodb.async.client.MongoClients;
import com.mongodb.async.client.MongoCollection;
import com.mongodb.async.client.MongoDatabase;

import com.mongodb.async.client.FindIterable;


import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.model.*;
import org.bson.Document;
import org.bson.conversions.Bson;

/**
 * Created by hao-linyang on 6/11/15.
 *
 * This Buffer to the learner's memory lookup table attempts to improve performance of training by reducing disk accesses.
 */
public class LearnerMemBuffer {

    public static final int MAX_BUFFER_SIZE = 5000000;
    public static final int REPORT_INTERVAL = 5 * 1000;

    // Parameters for tuning the database
    public static final int BATCH_SIZE = 5000;
    public static final int FLUSH_KEY_THRESHOLD = 12;
    public static final int MAX_CONCURRENT_BATCHES = 10;

    public static final String LEARNER_MEM = "learner_memory"; // name of the database
    public static final String COLLECTION = "advices"; // name of the collection in the database

    // Files paths for storage of size and training count
    //public static final String LEARNER_MEM_SIZE = "./learner_memory_size.dat";
    public static final String LEARNER_TRAINED_COUNT = "./learner_memory_trained_count.dat";
    public static final String CHARSET = "US-ASCII";

    // Classes for MongoDb
    private final MongoClient mMemClient;
    private final MongoDatabase mMemDB;
    private final MongoCollection<Document> mMemCollection;

    // In memory map as the buffer
    private Map<String, Advice> mMemBuffer;

    // Size of the learner memory table
    //private long mUniqueKeys;
    // Training count
    private long mTrainedCount;

    public LearnerMemBuffer() {

        // Initialize connection to the database
        mMemClient = MongoClients.create();
        mMemDB = mMemClient.getDatabase(LEARNER_MEM);
        mMemCollection = mMemDB.getCollection(COLLECTION);

        // Set the parameters

        mMemBuffer = new HashMap<String, Advice>();

        // Load in the size of the table and the total training count
        //Path sizePath = FileSystems.getDefault().getPath(LEARNER_MEM_SIZE);
        Path trainedCountPath = FileSystems.getDefault().getPath(LEARNER_TRAINED_COUNT);
        try {
            //List<String> sizeLines = Files.readAllLines(sizePath, Charset.forName(CHARSET));
            List<String> countLines = Files.readAllLines(trainedCountPath, Charset.forName(CHARSET));

           /* if (sizeLines.isEmpty()) {
                mUniqueKeys = 0;
            } else if (sizeLines.size() == 1) {
                mUniqueKeys = Long.parseLong(sizeLines.get(0));
            } else {
                // Our size file contains more than 2 entries, something is wrong
                System.out.println("Size file contains more than 1 entry!");
            }*/

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
        FindIterable<Document> result = mMemCollection.find(new Document("_id", key.toString()));

        final CountDownLatch findLatch = new CountDownLatch(1);
        final Advice advice = new Advice();
        result.first(new SingleResultCallback<Document>() {
            @Override
            public void onResult(final Document result, final Throwable t) {
                if (result != null) {
                    advice.setVoteHigh(result.getInteger("voteHigh"));
                    advice.setVoteLow(result.getInteger("voteLow"));
                    advice.setVoteLow(result.getInteger("historyLength"));
                }
                findLatch.countDown();
            }
        });

        try {
            findLatch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        if (advice.getHistoryLength() == 0) {
            return null;
        } else {
            return advice;
        }
    }

    public void put(History key, Advice advice) {
        if (mMemBuffer.size() >= MAX_BUFFER_SIZE) {
            flushBuffer(FLUSH_KEY_THRESHOLD);
        }
        if (advice == null) {
            System.out.println("Something is wrong advice is null in learner mem PUT key is: " + key);
        }
        mMemBuffer.put(key.toString(), advice);
    }

    public void flushBuffer() {
        flushBuffer(0);
    }

    // Writes the changes in the buffer to persistent storage
    public void flushBuffer(int flushLength) {
        System.out.println("<---Flushing learner memory buffer to disk...--->");

        // Initialize some bookkeeping for progress reports
        int numKeysTotal = mMemBuffer.size();
        int numKeysFlushed = 0;
        int currentProgress = 0;
        long startTime = System.currentTimeMillis();
        long lastReportTime = startTime;

        // List used for batch updates
        List<String> keyBatch = new ArrayList<String>();

        // Set of keys to remove from the map later
        Set<String> flushedKeys = new HashSet<String>();

        List<CountDownLatch> latches = new ArrayList<CountDownLatch>();

        for (String key : mMemBuffer.keySet()) {
            int keySize = Integer.parseInt(key.split(":")[1]);

            // Add to the list of keys to be flushed if its length is long enough
            if (keySize >= flushLength) {
                keyBatch.add(key);
                flushedKeys.add(key);
            }

            // Add a batch when the size is big enough
            if (keyBatch.size() == BATCH_SIZE) {
                // wait for another batch to finish if there are too many running already
                if (latches.size() == MAX_CONCURRENT_BATCHES) {
                    CountDownLatch addLatch = latches.remove(0);
                    try {
                        addLatch.await();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                CountDownLatch latch = new CountDownLatch(1);
                latches.add(latch);
                addBatch(keyBatch, latch);
                keyBatch.clear();
                numKeysFlushed+=BATCH_SIZE;
            }

            currentProgress++;
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastReportTime > REPORT_INTERVAL) {
                System.out.println("\tProcessed Keys: " + currentProgress + "/" + numKeysTotal + " ETA: " + Main.msToClockTime((long) (((numKeysTotal - currentProgress) / ((double) currentProgress) * (currentTime - startTime)))));
                lastReportTime = currentTime;
            }
        }

        // Flush any remaining keys in the current batch
        numKeysFlushed += keyBatch.size();
        CountDownLatch latch = new CountDownLatch(1);
        latches.add(latch);
        addBatch(keyBatch, latch);

        // Remove the flushed keys from the buffer
        for (String key : flushedKeys) {
            mMemBuffer.remove(key);
        }

        // Wait for all batches to complete
        System.out.println("\tWaiting for flushing batches to complete..");
        for (CountDownLatch addLatch : latches) {
            // Wait for the batch to complete
            try {
                addLatch.await();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        long endTime = System.currentTimeMillis();
        System.out.println("\tDone processing " + numKeysTotal + " keys! Flushed: " + numKeysFlushed + " Took: " + Main.msToClockTime(endTime - startTime));
    }

    public void close() {
        mMemClient.close();
        save();
    }

    public void clearMem() {
        //mMemDB.getCollection(COLLECTION).drop();
    }

    public long getUniqueKeys() {
        final CountDownLatch getCountLatch = new CountDownLatch(1);
        final List<Long> count = new LinkedList<Long>();
        mMemDB.getCollection(COLLECTION).count(new SingleResultCallback<Long>() {
            @Override
            public void onResult(Long result, Throwable throwable) {
                count.add(result);
                getCountLatch.countDown();
            }
        });

        try {
            getCountLatch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return count.remove(0);
    }

    public long getTrainedCount() { return mTrainedCount; }

    public void addTrainingCount(long count) { mTrainedCount += count; }

    public void save() {
        //Path sizePath = FileSystems.getDefault().getPath(LEARNER_MEM_SIZE);
        Path countPath = FileSystems.getDefault().getPath(LEARNER_TRAINED_COUNT);
        try {
            //Files.write(sizePath, Long.toString(mUniqueKeys).getBytes(), StandardOpenOption.TRUNCATE_EXISTING);
            Files.write(countPath, Long.toString(mTrainedCount).getBytes(), StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Merges the values in buffer mapped by the list of keys with the values in the database
    private void addBatch(List<String> keys, CountDownLatch addLatch) {

        // Create the bulk write operation to record the operations we will be sending to the database
        List<WriteModel<Document>> bulkOps = new ArrayList<WriteModel<Document>>();

        //DBCollection collection = mMemDB.getCollection(COLLECTION);
        //BulkWriteOperation ops = mMemDB.getCollection(COLLECTION);
/*
        // If some results were returned
        if (results != null) {
            // for each document returned
            results.forEach(new Block<Document>() {
                @Override
                public void apply(final Document doc) {
                    String key = doc.getString("_id");

                    Advice newAdvice = mMemBuffer.get(key);

                    // Construct filter
                    Bson filter = Filters.eq("_id", key);

                    // Construct update details
                    Document update = new Document("$set", new Document("voteHigh", newAdvice.getVoteHigh() + doc.getInteger("voteHigh")))
                            .append("$set", new Document("voteLow", newAdvice.getVoteLow() + doc.getInteger("voteLow")));

                    // Make the update task and add it to list
                    UpdateOneModel updateTask = new UpdateOneModel(filter, update);
                    bulkOps.add(updateTask);
                }
            });
        }

        // The remaining keys are new! add them to the database
        for (String key : keySet) {
            Advice newAdvice = mMemBuffer.get(key);
            if (newAdvice == null) {
                System.out.println("Something is wrong, newAdvice is null! Key is: " + key);
            }
            Document adviceDocument = new Document()
                    .append("voteHigh", newAdvice.getVoteHigh())
                    .append("voteLow", newAdvice.getVoteLow())
                    .append("historyLength", newAdvice.getHistoryLength())
                    .append("_id", key);
            InsertOneModel insertTask = new InsertOneModel(adviceDocument);
            bulkOps.add(insertTask);
        }

*/
        // Update each key in the data base with the new advice
        UpdateOptions options = new UpdateOptions().upsert(true);
        for (String key : keys) {
            // get size of key
            int keySize = Integer.parseInt(key.split(":")[1]);

            // get the advice in the buffer
            Advice newAdvice = mMemBuffer.get(key);

            // Create the update document
            Document updateContent = new Document()
                    .append("$inc", new Document("voteHigh", newAdvice.getVoteHigh()))
                    .append("$inc", new Document("voteLow", newAdvice.getVoteLow()))
                    .append("$set", new Document("historyLength", keySize));

            // Set the filter
            Bson filter = Filters.eq("_id", key);

            // Create update task
            UpdateOneModel updateTask = new UpdateOneModel(filter, updateContent, options);

            // Add task to the bulk write list
            bulkOps.add(updateTask);
        }

        if (!bulkOps.isEmpty()) {
            // Write all the changes to the database
            mMemDB.getCollection(COLLECTION).bulkWrite(bulkOps, new BulkWriteOptions().ordered(false), new SingleResultCallback<BulkWriteResult>() {
                @Override
                public void onResult(BulkWriteResult bulkWriteResult, Throwable throwable) {
                    addLatch.countDown();
                }
            });
        }
    }
}
