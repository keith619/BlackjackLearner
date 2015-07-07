import java.io.Serializable;
import java.util.List;

/**
 * Created by hao-linyang on 6/2/15.
 */
public class Environment implements Serializable{
    private Deck mDeck;
    private List<Card> mDealer;
    private List<Card> mPlayerHand1;
    private List<Card> mPlayerHand2;
    private List<Card> mPlayerHand3;
    private int mNumHands;
    private int mCurrentHand;

    public Environment(Deck deck, List<Card> dealerHand, List<Card> playerHand1, List<Card> playerHand2, List<Card> playerHand3, int hands, int current) {
        this.mDeck = deck;
        this.mDealer = dealerHand;
        this.mPlayerHand1 = playerHand1;
        this.mPlayerHand2 = playerHand2;
        this.mPlayerHand3 = playerHand3;
        this.mNumHands = hands;
        this.mCurrentHand = current;
    }
    @Override
    public int hashCode() {
        return mDeck.hashCode() + mDealer.hashCode() + mPlayerHand1.hashCode() + mPlayerHand2.hashCode() + mPlayerHand3.hashCode() + mNumHands + mCurrentHand;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null) return false;
        if (o == this) return true;
        if (!(o instanceof Environment))return false;
        Environment other = (Environment) o;

        // Check sizes of all hands
        if (this.mDealer.size() != other.mDealer.size() ||
                this.mPlayerHand1.size() != other.mPlayerHand1.size() ||
                this.mPlayerHand2.size() != other.mPlayerHand2.size() ||
                this.mPlayerHand3.size() != other.mPlayerHand3.size()) {
            return false;
        }

        // Check dealer hand
        for(int i = 0; i < mDealer.size(); i++) {
            if (!mDealer.get(i).equals(other.mDealer.get(i))) {
                return false;
            }
        }

        // check hand 1
        for(int i = 0; i < mPlayerHand1.size(); i++) {
            if (!mPlayerHand1.get(i).equals(other.mPlayerHand1.get(i))) {
                return false;
            }
        }

        // check hand 2
        for(int i = 0; i < mPlayerHand2.size(); i++) {
            if (!mPlayerHand2.get(i).equals(other.mPlayerHand2.get(i))) {
                return false;
            }
        }

        // check hand 3
        for(int i = 0; i < mPlayerHand3.size(); i++) {
            if (!mPlayerHand3.get(i).equals(other.mPlayerHand3.get(i))) {
                return false;
            }
        }

        // Check Deck
        if (!this.mDeck.equals(other.mDeck)) return false;

        // check current hand
        if (this.mCurrentHand != other.mCurrentHand) {return false;}

        // check number of hands
        if (this.mNumHands != other.mNumHands) {return false;}

        // Passed all comparisons
        return true;
    }

    public String toString() {
        String temp = "Environment state: ";
        temp += mDeck.toString() + "\n";
        temp += "Dealer hand:" + handToString(mDealer) + "\n";
        temp += "Player hand 1:" + handToString(mPlayerHand1) + "\n";
        temp += "Player hand 2:" + handToString(mPlayerHand2) + "\n";
        temp += "Player hand 3:" + handToString(mPlayerHand3);
        temp += "Current Hand: " + this.mCurrentHand + " of " + this.mNumHands;
        return temp;
    }

    public String toDat() {
        String temp = mDeck.toDat();
        temp += "|" + handToDat(mDealer);
        temp += "|" + handToDat(mPlayerHand1);
        temp += "|" + handToDat(mPlayerHand2);
        temp += "|" + handToDat(mPlayerHand3);
        temp += "|" + this.mCurrentHand;
        temp += "|" + this.mNumHands;
        return temp;
    }

    private String handToDat(List<Card> hand) {
        String temp = "";
        for (Card c : hand) {
            temp += c.toString() + "-";
        }
        if (temp.length() > 0) {
            return temp.substring(0, temp.length()-1);
        } else {
            return "";
        }
    }

    private String handToString(List<Card> hand) {
        String temp = "";
        for(Card c: hand) {
            temp += " " + c.toString();
        }
        return temp;
    }
}
