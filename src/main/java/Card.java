

/**
 * Created by hao-linyang on 6/2/15.
 */
public enum Card {
    ACE(11), TWO(2), THREE(3), FOUR(4), FIVE(5), SIX(6), SEVEN(7), EIGHT(8), NINE(9), TEN(10), JACK(10), QUEEN(10), KING(10);

    private int mVal;

    Card(int val) {
        this.mVal = val;
    }

    public int getVal() {
        return mVal;
    }

    public static Card fromString(String s) {
        switch(s) {
            case "A" : return Card.ACE;
            case "K" : return Card.KING;
            case "J" : return Card.JACK;
            case "Q" : return Card.QUEEN;
            case "2" : return Card.TWO;
            case "3" : return Card.THREE;
            case "4" : return Card.FOUR;
            case "5" : return Card.FIVE;
            case "6" : return Card.SIX;
            case "7" : return Card.SEVEN;
            case "8" : return Card.EIGHT;
            case "9" : return Card.NINE;
            case "10" : return Card.TEN;
            default : return null;
        }
    }
}
