import java.util.*;
/**
 * Created by hao-linyang on 6/6/15.
 */
public class Hand {
    private List<Card> mHand;
    private boolean mSoft; // initialized in value() for convenience =p
    private int mValue;
    private Card mPair;

    public Hand(List<Card> hand) {
        this.mHand = hand;
        this.mValue = value();
        this.mPair = pair();
    }

    public int getValue() {
        return mValue;
    }

    public boolean isSoft() {
        return mSoft;
    }

    public Card getPair() {
        return mPair;
    }

    public int size() {
        return mHand.size();
    }

    public boolean isBust() {
        return mValue > 21;
    }

    public boolean isBlackJack() {
        return mValue == 21 && mHand.size() == 2;
    }

    // Compare to see if this hand wins the other hand
    public Result compare(Hand other) {
        int selfValue = mValue;
        int otherValue = other.getValue();
        if (selfValue > 21) {
            return Result.LOSE;
        } else if (otherValue == selfValue) {
            return Result.PUSH;
        } else if (selfValue > otherValue || otherValue > 21) {
            return Result.WIN;
        } else {
            return Result.LOSE;
        }
    }

    public void addCard(Card c) {
        mHand.add(c);
        mValue = value();
        mPair = pair();
    }

    // removes card at index
    public Card removeCard(int index) {
        return mHand.remove(index);
    }

    // returns the value of this hand
    private int value() {
        int numAce = 0;
        int sum = 0;
        // sum the cards and count aces without adding to sum
        for (Card c : mHand) {
            if (c.equals(Card.ACE)) {
                numAce++;
            } else {
                sum += c.getVal();
            }
        }

        // determine if this is a soft hand
        if (numAce > 0 && numAce + sum <= 11 && mHand.size() >= 2) {
            mSoft = true;
        } else {
            mSoft = false;
        }

        // Add in the aces
        for (int i = 0; i < numAce; i++) {
            if (numAce - i + sum > 11 || sum > 10) {
                // if aces left plus sum is larger than 11 or sum is larger than 10 then can't count as 11 otherwise will go bust.
                sum += 1;
            } else {
                // if sum is less than or equal to 10 and num aces left won't go bust
                sum += 11;
            }
        }
        return sum;
    }

    // Returns the card the pair is made of, null if it is not a pair
    private Card pair() {
        if (mHand.size() != 2) {
            // if size is not 2 then this is definitely not a pair
            return null;
        } else if (mHand.get(0).equals(mHand.get(1))) {
            // if the size is 2 and the cards are the same its a pair!
            return mHand.get(0);
        } else {
            // otherwise its just two cards not a pair
            return null;
        }
    }


    @Override
    public int hashCode() {
        return mHand.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (o == null) return false;
        if (o == this) return true;
        if (!(o instanceof Hand))return false;
        Hand other = (Hand) o;
        if (this.mHand.size() != other.mHand.size()) return false;
        for (int i = 0; i < this.mHand.size(); i ++) {
            if(!this.mHand.get(i).equals(other.mHand.get(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        String temp = "";
        for (Card c : mHand) {
            temp += c.toString() + " ";
        }
        return temp;
    }
}
