import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * Created by hao-linyang on 6/5/15.
 */
public class History {
    private List<RoundSignature> mHistory;

    public History(List<RoundSignature> history) {
        this.mHistory = new LinkedList<RoundSignature>(history);
    }

    public int size() {
        return mHistory.size();
    }

    // Returns a new history with only the size most recent entries
    // requires size to be larger than 0 and less than or equal to current size
    //public History truncate(int size) {
    //    return new History(new ArrayList<Round>(mHistory.subList(0, size)));
    //}

    @Override
    public int hashCode() {
        int hash = 0;
        for (RoundSignature r : mHistory) {
            hash = hash * 31 + r.hashCode();
        }
        return hash;
    }

    /**
     * Equals method
     * @param o other object to compare
     * @return true if equal, false if not
     */
    @Override
    public boolean equals(Object o){
        if (o == null) return false;
        if (o == this) return true;
        if (!(o instanceof History))return false;
        History other = (History) o;

        return this.mHistory.equals(other.mHistory);
    }

    @Override
    public String toString() {
        String temp = "";
        for (RoundSignature rs : mHistory) {
            temp += rs.toString();
        }
        temp += ":" + mHistory.size();
        return temp;
    }

    // Returns a copy of this history key
    public History copy() {
        return new History(mHistory);
    }
}
