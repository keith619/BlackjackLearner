import com.sleepycat.persist.model.Entity;
import com.sleepycat.persist.model.PrimaryKey;
import org.bson.Document;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.List;

/**
 * Created by hao-linyang on 6/5/15.
 */
@Entity
public class Advice {
    private int mHistoryLength;
    private int mVoteHigh;
    private int mVoteLow;

    public Advice() {
        this(0,0,0);
    }

    public Advice(String dat) {
        String[] dats = dat.split(":");
        mVoteHigh = Integer.parseInt(dats[0]);
        mVoteLow = Integer.parseInt(dats[2]);
    }

    public Advice(int historyLength, int voteHigh, int voteLow) {
        mHistoryLength = historyLength;
        mVoteHigh = voteHigh;
        mVoteLow = voteLow;
    }

    public void setVoteHigh(int vote) { mVoteHigh = (short) vote; }

    public void setVoteLow(int vote) { mVoteLow = (short) vote; }

    public void setHistoryLength(int length) { mHistoryLength = (short) length; }

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

    public String toDat() {
        return mHistoryLength + ":" + mVoteHigh + ":" + mVoteLow;
    }

    public byte[] toBytes() {
        byte[] bytes = ByteBuffer.allocate(12).putInt(mVoteHigh).putInt(mVoteLow).putInt(mHistoryLength).array();
        return bytes;
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

        return hash;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null) return false;
        if (o == this) return true;
        if (!(o instanceof Advice))return false;
        Advice other = (Advice) o;

        return this.mHistoryLength == other.mHistoryLength &&
                this.mVoteHigh == other.mVoteHigh &&
                this.mVoteLow == other.mVoteLow;
    }

    public static Advice fromBytes(byte[] bytes) {
        IntBuffer buf = ByteBuffer.wrap(bytes).asIntBuffer();
        int voteHigh = buf.get(0);
        int voteLow = buf.get(1);
        int historyLength = buf.get(2);
        Advice advice = new Advice(historyLength, voteHigh, voteLow);
        return advice;
    }
}

