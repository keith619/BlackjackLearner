import com.sleepycat.persist.model.Entity;
import com.sleepycat.persist.model.PrimaryKey;

import java.util.List;

/**
 * Created by hao-linyang on 6/5/15.
 */
@Entity
public class AdviceBerkeleyDB {
    private short mHistoryLength;
    private short mVoteHigh;
    private short mVoteLow;

    @PrimaryKey
    private String mHistoryKey;

    public void setVoteHigh(int vote) { mVoteHigh = (short) vote; }

    public void setVoteLow(int vote) { mVoteLow = (short) vote; }

    public void setHistoryLength(int length) { mHistoryLength = (short) length; }

    public void setHistoryKey(String key) { mHistoryKey = key; }

    public double getHighPercent() {
        return (double) mVoteHigh / (mVoteHigh + mVoteLow) * 100;
    }

    public int getVoteHigh() {
        return mVoteHigh;
    }

    public int getVoteLow() {
        return mVoteLow;
    }

    public int getHistoryLength() { return mHistoryLength; }

    public String getKey() {
        return mHistoryKey;
    }

    @Override
    public String toString() {
        return "Key length: " + mHistoryLength + " High bid votes: " + mVoteHigh + "/" + (mVoteLow + mVoteHigh) + "(" + getHighPercent() + "%)";
    }

    @Override
    public int hashCode() {
        int hash = 31;
        hash += 13 * mHistoryLength;
        hash *= 29;
        hash += 17 * mVoteHigh;
        hash *= 11;
        hash += 47 * mVoteLow;
        hash *= 111;
        hash += mHistoryKey.hashCode();

        return hash;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null) return false;
        if (o == this) return true;
        if (!(o instanceof AdviceBerkeleyDB))return false;
        AdviceBerkeleyDB other = (AdviceBerkeleyDB) o;

        return this.mHistoryLength == other.mHistoryLength &&
                this.mVoteHigh == other.mVoteHigh &&
                this.mVoteLow == other.mVoteLow &&
                this.mHistoryKey.equals(other.mHistoryKey);
    }
}

