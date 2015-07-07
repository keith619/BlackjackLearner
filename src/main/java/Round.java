import java.util.List;

/**
 * Created by hao-linyang on 6/5/15.
 */
public class Round {
    private List<Card> mDealerHand; // Hand that dealer ended with at the end of the round
    private List<Card> mPlayerHand1; // Hands that player ended up with at the end of round up to three
    private List<Card> mPlayerHand2;
    private List<Card> mPlayerHand3;

    private double mProfit;

    public Round (List<Card> dealer, List<Card> player1, List<Card> player2, List<Card> player3, double profit) {
        this.mDealerHand = dealer;
        this.mPlayerHand1 = player1;
        this.mPlayerHand2 = player2;
        this.mPlayerHand3 = player3;
        this.mProfit = profit;
    }

    public double getProfit() {
        return this.mProfit;
    }


    @Override
    public int hashCode() {
        return mDealerHand.hashCode() + mPlayerHand1.hashCode() + mPlayerHand2.hashCode() + mPlayerHand3.hashCode() + (int) mProfit;
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
        if (!(o instanceof Round))return false;
        Round other = (Round) o;

        return this.mDealerHand.equals(other.mDealerHand) &&
                this.mPlayerHand1.equals(other.mPlayerHand1) &&
                this.mPlayerHand2.equals(other.mPlayerHand2) &&
                this.mPlayerHand3.equals(other.mPlayerHand3) &&
                this.mProfit == other.mProfit;
    }
}
