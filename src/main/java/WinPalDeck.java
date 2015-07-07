import java.util.*;
/**
 * Created by hao-linyang on 6/7/15.
 *
 * Represents the top half of a potential newly shuffled deck.
 * Only dealing from deck is allowed for efficiency reasons
 */
public class WinPalDeck {

    private List<Card> mDeck; // List of cards in the deck, the front is the top

    public WinPalDeck(List<Card> deck) {
        this.mDeck = new LinkedList<Card>(deck);
    }

    public boolean isEmpty() {
        return mDeck.size() == 0;
    }

    public int size() {
        return mDeck.size();
    }

    // Deals a card from the Deck. if it is empty the returned card is null
    public Card dealCard() {
        if (mDeck.size() == 0) {
            return null;
        } else {
            return mDeck.remove(0);
        }
    }

    @Override
    public String toString() {
        String temp = "";
        for (Card c : mDeck) {
            temp += c + " ";
        }
        return temp;
    }



    // Returns a randomly generated deck with given depth from a deck with given size
    public static WinPalDeck getRandomDeck(int numDecksTotal, int depth) {
        Map<Card, Integer> cards = new TreeMap<Card, Integer>();

        for (Card c : Card.values()) {
            cards.put(c, 4 * numDecksTotal); // Each deck has four of each card type
        }

        Random rand = new Random(System.nanoTime()); // Seed random generator with current time

        // pull cards from the remaining pool at random and put it into a deck
        List<Card> cardList = new ArrayList<Card>();
        int currentDeckSize = 52 * numDecksTotal;
        for (int i = 0; i < Math.min(depth, 52*numDecksTotal); i++) {
            int takeIndex = rand.nextInt(currentDeckSize);
            int currentIndex = 0;
            for (Card c : cards.keySet()) {
                int numLeft = cards.get(c);
                if ( takeIndex < currentIndex + numLeft) {
                    // it's this card! add to list and decrement counter for that card
                    cardList.add(c);
                    cards.put(c, cards.get(c) - 1);

                    // also decrease the number of cards left so the index does not become too high
                    currentDeckSize--;
                    break;
                } else {
                    currentIndex += numLeft;
                }
            }
        }
        WinPalDeck deck = new WinPalDeck(cardList);
        return deck;
    }
}
