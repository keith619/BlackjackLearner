/**
 * Created by hao-linyang on 6/7/15.
 */
public class RoundSignature {
    private float mProfit;

    // Constructor takes profit of each round
    public RoundSignature(float profit) {
        mProfit = profit;
    }

    public double getProfit() {
        return mProfit;
    }

    @Override
    public int hashCode() {
        return (int) mProfit * 10;
    }
    @Override
    public boolean equals(Object o){
        if (o == null) return false;
        if (o == this) return true;
        if (!(o instanceof RoundSignature))return false;
        RoundSignature other = (RoundSignature) o;

        return this.mProfit == other.mProfit;
    }
    @Override
    public String toString() {
        return "" + mProfit;
    }
}
