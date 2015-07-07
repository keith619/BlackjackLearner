import java.util.HashMap;
import java.util.Map;

/**
 * Created by hao-linyang on 6/2/15.
 */
public class Deck {
    private Map<Card, Integer> mCards;
    private int mSize;

    /**
     * Constructor. Creates a deck of the given size
     * @param num number of card decks in the deck
     */
    public Deck(int num) {
        mCards = new HashMap<Card, Integer>();
        for (Card c : Card.values()) {
            mCards.put(c, 4 * num);
        }
        mSize = 52 * num;
    }

    public Deck(Map<Card, Integer> cards, int size) {
        this.mCards = cards;
        this.mSize = size;
    }

    /**
     * Removes the specified card from the deck
     * @param c card to remove
     * @return true if card was removed
     */
    public boolean removeCard(Card c){
        if (mCards.get(c) > 0) {
            mCards.put(c, mCards.get(c)-1);
            mSize--;
            return true;
        } else {
            return false;
        }

    }

    /**
     *
     * @return integer representing the hash code of this deck
     */
    @Override
    public int hashCode() {
        int sum = 0;
        for (Card c : mCards.keySet()) {
            sum += mCards.get(c);
        }
        return sum;
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
        if (!(o instanceof Deck))return false;
        Deck other = (Deck) o;
        if (this.mSize != other.mSize) return false;
        for (Card c : mCards.keySet()) {
            if(this.mCards.get(c) != other.mCards.get(c)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Make a copy of the current deck
     * @return a new Deck that is a copy of this one
     */
    public Deck copy() {
        Map<Card, Integer> temp = new HashMap<Card, Integer>();
        for(Card c : this.mCards.keySet()) {
            temp.put(c, this.mCards.get(c));
        }
        return new Deck(temp, this.mSize);
    }

    public String toString() {
        String temp = "Deck total size: " + mSize + " Contents: ";
        for (Card c : mCards.keySet()) {
            temp += c.toString() + ":" + mCards.get(c) + " ";
        }
        return temp;
    }

    public String toDat() {
        String temp = "";
        for(Card c : mCards.keySet()) {
            temp += c.toString()+"-"+mCards.get(c) + "^";
        }
        return temp.substring(0, temp.length()-1);
    }

}
