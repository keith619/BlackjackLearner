import java.util.*;
/**
 * Created by hao-linyang on 6/6/15.
 */
public class Game {
    private Hand mDealer;
    private Hand mPlayerHand1;
    private Hand mPlayerHand2;
    private Hand mPlayerHand3;
    private int mNumHands;
    private boolean mGameOver;

    public Game(Hand dealer, Hand player) {
        mDealer = dealer;
        mPlayerHand1 = player;
        mNumHands = 1;
    }



}
