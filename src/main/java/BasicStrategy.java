import java.util.*;
/**
 * Created by hao-linyang on 6/6/15.
 *
 * Class returns basic blackjack strategy for hand dealt
 */
public class BasicStrategy {

    public static final int MAX_HANDS = 3;

    // Get the strategy for the next action based on the cards seen. can't be null. player hand must be size 2 or above, can't be bust
    public static Action getStrategy(Card dealerCard, Hand playerHand, int numHandsTotal) {
        if (dealerCard == null) {
            System.out.println("Dealer card is null in getStrategy()! Player Hand is: " + playerHand.toString() + "numHandsTotal: " + numHandsTotal);
            return null;
        }

        // Check if the player's hand is a pair
        Card pair = playerHand.getPair();
        if (pair != null && numHandsTotal < MAX_HANDS) {
            // its a pair and we can split! respond with according strategy for each type of pair
            switch (pair) {
                case ACE: case EIGHT:
                    return Action.SPLIT;

                case TWO: case THREE:
                    switch (dealerCard) {
                        case ACE: case TEN: case JACK: case QUEEN: case KING: case NINE: case EIGHT:
                            return Action.HIT;
                        default:
                            return Action.SPLIT;
                    }

                case FOUR:
                    switch(dealerCard) {
                        case FIVE: case SIX:
                            return Action.SPLIT;
                        default:
                            return Action.HIT;
                    }

                case FIVE:
                    switch (dealerCard) {
                        case ACE: case KING: case QUEEN: case JACK: case TEN:
                            return Action.HIT;
                        default:
                            if (playerHand.size() == 2) {
                                return Action.DOUBLE;
                            } else {
                                return Action.HIT;
                            }
                    }

                case SIX:
                    switch(dealerCard) {
                        case TWO: case THREE: case FOUR: case FIVE: case SIX:
                            return Action.SPLIT;
                        default:
                            return Action.HIT;
                    }

                case SEVEN:
                    switch (dealerCard) {
                        case TWO: case THREE: case FOUR: case FIVE: case SIX: case SEVEN:
                            return Action.SPLIT;
                        default:
                            return Action.HIT;
                    }
                case NINE:
                    switch(dealerCard) {
                        case ACE: case KING: case QUEEN: case JACK: case TEN: case SEVEN:
                            return Action.STAND;
                        default:
                            return Action.SPLIT;
                    }
                case TEN:
                case JACK:
                case QUEEN:
                case KING:
                    return Action.STAND;
                default:
                    // not supposed to get here
                    System.out.println("In the Pairs switch default section of basic strategy. Cards: " + playerHand.toString());
                    return null;
            }
        } else if (playerHand.isSoft()) {
            // Hand is soft, use soft hand strategies
            int value = playerHand.getValue();
            switch (value) {
                case 12:
                    return Action.HIT;

                case 13: case 14:
                    switch (dealerCard) {
                        case FIVE: case SIX:
                            if (playerHand.size() == 2) {
                                return Action.DOUBLE;
                            } else {
                                return Action.HIT;
                            }
                        default:
                            return Action.HIT;
                    }
                case 15:case 16:
                    switch (dealerCard) {
                        case FOUR: case FIVE: case SIX:
                            if (playerHand.size() == 2) {
                                return Action.DOUBLE;
                            } else {
                                return Action.HIT;
                            }
                        default:
                            return Action.HIT;
                    }
                case 17:
                    switch (dealerCard) {
                        case THREE: case FOUR: case FIVE: case SIX:
                            if (playerHand.size() == 2) {
                                return Action.DOUBLE;
                            } else {
                                return Action.HIT;
                            }
                        default:
                            return Action.HIT;
                    }
                case 18:
                    switch (dealerCard) {
                        case SEVEN: case EIGHT:
                            return Action.STAND;
                        case TWO: case THREE: case FOUR: case FIVE: case SIX:
                            if (playerHand.size() == 2) {
                                return Action.DOUBLE;
                            } else {
                                return Action.STAND;
                            }
                        default:
                            return Action.HIT;
                    }
                case 19:
                    switch (dealerCard) {
                        case SIX:
                            if (playerHand.size() == 2) {
                                return Action.DOUBLE;
                            } else {
                                return Action.STAND;
                            }
                        default:
                            return Action.STAND;
                    }
                case 20: case 21:
                    return Action.STAND;
                default:
                    // if we reach here something is wrong
                    System.out.println("ERROR Soft value is not any of 12 to 21 for cards: " + playerHand.toString() + "value: " + playerHand.getValue());
                    return null;

            }
        } else {
            // This is a regular hard hand
            int value = playerHand.getValue();
            switch (value) {
                case 4: case 5: case 6: case 7:case 8:
                    return Action.HIT;
                case 9:
                    switch (dealerCard) {
                        case THREE: case FOUR: case FIVE: case SIX:
                            if (playerHand.size() == 2) {
                                return Action.DOUBLE;
                            } else {
                                return Action.HIT;
                            }
                        default:
                            return Action.HIT;
                    }
                case 10:
                    switch (dealerCard) {
                        case TEN: case JACK: case QUEEN: case KING: case ACE:
                            return Action.HIT;
                        default:
                            if (playerHand.size() == 2) {
                                return Action.DOUBLE;
                            } else {
                                return Action.HIT;
                            }
                    }

                case 11:
                    if (playerHand.size() == 2) {
                        return Action.DOUBLE;
                    } else {
                        return Action.HIT;
                    }
                case 12:
                    switch (dealerCard) {
                        case FOUR: case FIVE: case SIX:
                            return Action.STAND;
                        default:
                            return Action.HIT;
                    }
                case 13: case 14: case 15: case 16:
                    switch (dealerCard) {
                        case TWO: case THREE: case FOUR: case FIVE: case SIX:
                            return Action.STAND;
                        default:
                            return Action.HIT;
                    }
                case 17: case 18: case 19: case 20: case 21:
                    return Action.STAND;
                default:
                    // again something must be wrong if we got here
                    System.out.println("Default sectio nof regular player hand section. Cards: " + playerHand.toString() + " value: " + playerHand.getValue());
                    return null;
            }
        }
    }
}
