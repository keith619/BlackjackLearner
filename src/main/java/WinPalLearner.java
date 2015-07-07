import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.logging.FileHandler;

import com.sleepycat.je.DatabaseException;
import com.sleepycat.persist.EntityCursor;

/**
 * Created by hao-linyang on 6/5/15.
 */
public class WinPalLearner {

    // Parameters for tuning the learner
    public static final int MAX_HISTORY_SIZE = 12;
    public static final double HIGH_BID_THRESHOLD = 0.3;
    public static final int STREAK_THRESHOLD = 5;
    public static final int MIN_KEY_LENGTH = 5;

    // History bookkeeping data
    private List<RoundSignature> mCurrentHistory;
    private List<Advice> mCurrentAdvices;
    private List<History> mCurrentAdviceKeys;

    // Streak tracking data
    private boolean mHighBid;
    private int mCurrentHighBidCount;
    private double mCurrentHighBidProfit;
    private int mLastStreakEnd; // index in the history that is the end of the last streak


    // Buffer for the trained memory on disk
    private LearnerMemBufferKyotoCabinet mLearnerMemBuffer;

    public WinPalLearner() {
        // Initialize the history bookkeeping
        mCurrentHistory = new LinkedList<RoundSignature>();
        mCurrentAdvices = new LinkedList<Advice>();
        mCurrentAdviceKeys = new LinkedList<History>();
        mLastStreakEnd = 0;

        // All counters and states reset
        mHighBid = false;
        mCurrentHighBidCount = 0;
        mCurrentHighBidProfit = 0;

        // Initialize the buffer
        mLearnerMemBuffer = new LearnerMemBufferKyotoCabinet();
    }

    // Applies the learning algorithm based on this round and current history
    public void learnRound(RoundSignature r) {

        // Remove the oldest element in the list if history size is reached
        if (mCurrentHistory.size() == MAX_HISTORY_SIZE) {
            mCurrentHistory.remove(0);
            mCurrentAdvices.remove(0);
            mCurrentAdviceKeys.remove(0);
            if (mLastStreakEnd > 0) {
                mLastStreakEnd--;
            }
        }

        // Put the new round's signature in the history
        mCurrentHistory.add(r);

        // Put the key for the current advice in the history
        History key = new History(mCurrentHistory);
        mCurrentAdviceKeys.add(key);

        // Fetch advice or create a new one if not found
        Advice advice = null;
        if (mCurrentHistory.size() >= MIN_KEY_LENGTH) {
            // only fetch if the history is long enough
            advice = mLearnerMemBuffer.get(key.toString());
        }

        if (advice == null && mCurrentHistory.size() >= MIN_KEY_LENGTH) {
            // Key has not been seen before, need to make a new advice and put it in the table
            advice = new Advice();
            advice.setVoteHigh(0);
            advice.setVoteLow(1);
            advice.setHistoryLength(key.size());

            mLearnerMemBuffer.put(key, advice);
        }
        // Add advice to current advices
        mCurrentAdvices.add(advice);

        if (mHighBid) {
            // If it is currently a high bid streak, add profit info to tally
            mCurrentHighBidProfit += r.getProfit();
            mCurrentHighBidCount++;

            // Check if newly added round puts winning below threshold
            if (mCurrentHighBidProfit / mCurrentHighBidCount >= HIGH_BID_THRESHOLD) {
                // If not, change the vote in the advice for this round to high bet
                Advice prevAdvice = mCurrentAdvices.get(mCurrentAdvices.size()-2);

                prevAdvice.setVoteHigh(prevAdvice.getVoteHigh() + 1);
                prevAdvice.setVoteLow(prevAdvice.getVoteLow() - 1);

                // Replace the old advice with the new one
                mLearnerMemBuffer.put(key, prevAdvice);
            } else {
                // End of high bid streak. Set vote for any previous rounds with losses to low bids as well
                /* TODO disabled while debugging to keep complexity lower
                for (int i = mCurrentHistory.size() - 2; i >= MIN_KEY_LENGTH; i--) {
                    if (mCurrentHistory.get(i).getProfit() < 0) {
                        // Switch vote to a low vote
                        History prevKey = mCurrentAdviceKeys.get(i-1);
                        Advice prevAdvice = mCurrentAdvices.get(i-1);

                        prevAdvice.setVoteHigh(prevAdvice.getVoteHigh() - 1);
                        prevAdvice.setVoteLow(prevAdvice.getVoteLow() + 1);

                        // Add new advice to the memory
                        mLearnerMemBuffer.put(key, prevAdvice);
                    } else {
                        mLastStreakEnd = i;
                        break;
                    }
                }
                */

                // Reset the tally
                mCurrentHighBidProfit = 0;
                mCurrentHighBidCount = 0;
                mHighBid = false;
            }
        } else if (mCurrentHistory.size() - mLastStreakEnd > STREAK_THRESHOLD && mCurrentHistory.size() > STREAK_THRESHOLD + MIN_KEY_LENGTH){
            // It is not currently a high bid streak but history size is large enough to potentially have a new
            // streak with the addition of this round

            // Calculate profitability of shortest allowed streak
            double profit = 0;
            for (int i = mCurrentHistory.size() - 1; i > mCurrentHistory.size() - STREAK_THRESHOLD - 1; i--) {
                profit += mCurrentHistory.get(i).getProfit();
            }
            // See if the profit per round is above the high bid threshold
            if (profit / STREAK_THRESHOLD >= HIGH_BID_THRESHOLD) {
                // If so, found a streak
                /* TODO disabled while debugging to reduce complexity
                // Go back further up history and add any results that may be part of the streak
                int count = STREAK_THRESHOLD;
                int index = mCurrentHistory.size() - STREAK_THRESHOLD - 1;
                while (index > mLastStreakEnd && index >= MIN_KEY_LENGTH) {
                    if ((profit + mCurrentHistory.get(index).getProfit()) / (count + 1) >= HIGH_BID_THRESHOLD) {
                        count++;
                        profit += mCurrentHistory.get(index).getProfit();
                        index--;
                    } else {
                        break;
                    }
                }
                */

                // Tally the info of the new streak
                mHighBid = true;
                mCurrentHighBidProfit = profit;
                mCurrentHighBidCount = STREAK_THRESHOLD;

                // for each round before the rounds in the streak, vote high bid next
                for (int i = mCurrentAdvices.size() - 2; i > mCurrentAdvices.size() - STREAK_THRESHOLD - 2; i--) {
                    // Switch vote to a high vote
                    Advice prevAdvice = mCurrentAdvices.get(i);

                    prevAdvice.setVoteHigh(prevAdvice.getVoteHigh() + 1);
                    prevAdvice.setVoteLow(prevAdvice.getVoteLow() - 1);

                    // Add modified advice to memory
                    mLearnerMemBuffer.put(key, prevAdvice);
                }

            } // else nothing more to do
        }
    }

    // used when you just want to use the learned data and not actually contributing more to it
    public void addToHistory(RoundSignature r) {
        if (mCurrentHistory.size() == MAX_HISTORY_SIZE) {
            mCurrentHistory.remove(0);
            // Don't need to worry about advice list because we are not learning
        }
        // add round to history to be used in the query
        mCurrentHistory.add(r);
    }

    // Clears the temporary history
    public void clearCurrentHistory() {
        mHighBid = false;
        mCurrentHighBidProfit = 0;
        mCurrentHighBidCount = 0;
        mCurrentHistory = new LinkedList<>();
        mCurrentAdvices = new LinkedList<>();
        mCurrentAdviceKeys = new LinkedList<>();
    }

    // Finds the longest recent round match of at least size STREAK_THRESHOLD + 1 and returns the advice, null if not found
    public Advice queryNextRound() {
        // search if current history has an advice for the next round
        Advice advice = null;
        int histSize = mCurrentHistory.size();
        for (int i = 0; i <= histSize - STREAK_THRESHOLD - 1; i++) {
            // Fetch the advice for this match
            History key = new History(mCurrentHistory.subList(i, histSize));
            advice = mLearnerMemBuffer.getPlayerAdvice(key);

            if (advice != null) {
                break;
            }
        }
        return advice;
    }

    public void addTrainingCount(long count) { mLearnerMemBuffer.addTrainingCount(count); }

    public void save() {
        mLearnerMemBuffer.save();
    }

    public long getTrainedCount() {
        return mLearnerMemBuffer.getTrainedCount();
    }

    public long getAdvicesSize() {
        return mLearnerMemBuffer.getUniqueKeys();
    }

    public void close() {
        mLearnerMemBuffer.close();
    }

    public void flushBuffer() {
        mLearnerMemBuffer.flushBuffer();
    }
}
