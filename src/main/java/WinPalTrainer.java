import java.util.*;
/**
 * Created by hao-linyang on 6/7/15.
 */
public class WinPalTrainer {

    // Trains the given learner with the given deck of cards
    public static void trainLearnerWithDeck(WinPalLearner learner, WinPalDeck deck) {
        // First clear the learner's history of any previous data from another deck
        learner.clearCurrentHistory();

        // Keep playing rounds and recording data until the deck is empty
        boolean lastRoundCompleted = true;
        while(lastRoundCompleted) {
            // Play a round with the cards remaining in the deck

            // Initialize all the bookkeeping
            boolean roundOver = false;
            int numHands = 1;
            int currentHand = 1;
            Card dealerKnown = null;
            Card dealerUnkown = null;
            Hand playerHands[] = {new Hand(new ArrayList<Card>()), new Hand(new ArrayList<Card>()), new Hand(new ArrayList<Card>())};
            float stakes[] = {1, 0 , 0};

            if (deck.size() < 4) {
                // Deck does not have enough cards for another round
                roundOver = true;
                lastRoundCompleted = false;
            } else {
                // Deal out the starting cards for the round
                playerHands[0].addCard(deck.dealCard());
                dealerKnown = deck.dealCard();
                playerHands[0].addCard(deck.dealCard());
                dealerUnkown = deck.dealCard();
            }

            // Check if either the player or the dealer has a blackjack
            if (!roundOver) {
                // Make a hand from the dealer cards
                Hand dealerHand = new Hand(new ArrayList<Card>());
                dealerHand.addCard(dealerKnown);
                dealerHand.addCard(dealerUnkown);
                if (dealerHand.getValue() == 21 || playerHands[0].getValue() == 21) {
                    roundOver = true;
                }
            }

            Action action = null;

            // Continue playing with basic strategy until round is over
            while(!roundOver) {
                Hand hand = playerHands[currentHand-1];

                if (hand.size() < 2) {
                    System.out.println("Player hand size is less than 2! " + " numHands: " + numHands + " currentHand: "+ currentHand);
                    System.out.println("Player Hand 1: " + playerHands[0]);
                    System.out.println("Player Hand 2: " + playerHands[1]);
                    System.out.println("Player Hand 3: " + playerHands[2]);
                    System.out.println("Dealer Known: " + dealerKnown + " Dealer Unknown: " + dealerUnkown);
                    System.out.println("Previous action: " + action);

                }

                // get the basic strategy from the seen cards. The unknown card must not be factored in
                action = BasicStrategy.getStrategy(dealerKnown, hand, numHands);

                // Apply the action to the current conditions
                switch (action) {
                    case HIT:
                        if (deck.isEmpty()) {
                            roundOver = true;
                            lastRoundCompleted = false;
                        } else {
                            // deal a card to the player
                            hand.addCard(deck.dealCard());
                            if (hand.getValue() >= 21) {
                                if (currentHand < numHands) {
                                    // If there are more hands switch to the next one, deal a card to it if it was from a split and only has one card
                                    currentHand++;
                                    if (playerHands[currentHand-1].size() == 1) {
                                        if (deck.isEmpty()) {
                                            roundOver = true;
                                            lastRoundCompleted = false;
                                        } else {
                                            playerHands[currentHand-1].addCard(deck.dealCard());
                                        }
                                    }
                                } else {
                                    roundOver = true;
                                }
                            }
                        }
                        break;
                    case STAND:
                        if (currentHand < numHands) {
                            // If there are more hands switch to the next one, deal a card to it if it was from a split and only has one card
                            currentHand++;
                            if (playerHands[currentHand-1].size() == 1) {
                                if (deck.isEmpty()) {
                                    roundOver = true;
                                    lastRoundCompleted = false;
                                } else {
                                    playerHands[currentHand-1].addCard(deck.dealCard());
                                }
                            }
                        } else {
                            roundOver = true;
                        }
                        break;
                    case DOUBLE:
                        if (deck.isEmpty()) {
                            roundOver = true;
                            lastRoundCompleted = false;
                        } else {
                            // deal a card to the player
                            hand.addCard(deck.dealCard());

                            // double the stakes
                            stakes[currentHand-1] = 2;

                            if (currentHand < numHands) {
                                // If there are more hands switch to the next one, deal a card to it if it was from a split and only has one card
                                currentHand++;
                                if (playerHands[currentHand-1].size() == 1) {
                                    if (deck.isEmpty()) {
                                        roundOver = true;
                                        lastRoundCompleted = false;
                                    } else {
                                        playerHands[currentHand-1].addCard(deck.dealCard());
                                    }
                                }
                            } else {
                                // Round is over if this is the last hand
                                roundOver = true;
                            }
                        }
                        break;
                    case SPLIT:
                        if (hand.getPair().equals(Card.ACE)) {
                            // Aces only get one more dealt card after a split
                            if (deck.size() < 2) {
                                // deck does not have enough cards left
                                roundOver = true;
                                lastRoundCompleted = false;

                            } else {
                                // Move a card from hand 1 to hand 2 and deal a card to each
                                Card c = hand.removeCard(1);
                                playerHands[1].addCard(c);
                                playerHands[0].addCard(deck.dealCard());
                                playerHands[1].addCard(deck.dealCard());
                                numHands++;
                                currentHand++;
                                stakes[1] = 1;
                                roundOver = true;
                            }
                        } else {
                            if (currentHand == 1) {
                                if (numHands == 2) {
                                    Card c = hand.removeCard(1);
                                    playerHands[2].addCard(c);
                                    stakes[2] = 1;
                                    numHands++;
                                } else if (numHands == 3) {
                                    // shouldn't get here
                                    System.out.println("ERROR: Trainer is trying to split first hand when already have three hands");
                                    System.exit(1);
                                } else {
                                    Card c = hand.removeCard(1);
                                    playerHands[1].addCard(c);
                                    stakes[1] = 1;
                                    numHands++;
                                }
                            } else if (currentHand == 2) {
                                if (numHands == 3) {
                                    // shouldn't get here
                                    System.out.println("ERROR: Trainer is trying to split second hand when already have three hands");
                                    System.exit(1);
                                } else {
                                    Card c = hand.removeCard(1);
                                    playerHands[2].addCard(c);
                                    stakes[2] = 1;
                                    numHands++;
                                }
                            } else {
                                // shouldn't get here
                                System.out.println("ERROR: Trainer is trying to split the third hand");
                                System.exit(1);
                            }

                            // Deal card to the hand
                            if (deck.isEmpty()) {
                                roundOver = true;
                                lastRoundCompleted = false;
                            } else {
                                hand.addCard(deck.dealCard());

                                // If got 21 then this hand is finished deal a card to the next one and check
                                if (hand.getValue() == 21) {
                                    if (deck.isEmpty()) {
                                        roundOver = true;
                                        lastRoundCompleted = false;
                                    } else {
                                        currentHand++;
                                        hand = playerHands[currentHand-1];
                                        hand.addCard(deck.dealCard());
                                        if (hand.getValue() == 21) {
                                            roundOver = true;
                                        }
                                    }
                                }
                            }
                        }
                        break;
                    default:
                        break;
                }
            }



            // See if all of the player's hands are bust, if so don't deal to dealer's hand anymore
            boolean allBust = true;
            for (int i = 0; i < numHands; i++) {
                allBust = allBust && playerHands[i].isBust();
            }

            Hand dealerHand = new Hand(new ArrayList<Card>());

            if (lastRoundCompleted && (numHands > 1 || (numHands == 1 && (playerHands[0].size() > 2 || playerHands[0].getValue() != 21)))) {
                dealerHand.addCard(dealerKnown);
                dealerHand.addCard(dealerUnkown);

                // If not all of the player's hands are bust then keep dealing to dealer's hand
                if (!allBust) {
                    while (dealerHand.getValue() < 17 || (dealerHand.getValue() == 17 && dealerHand.isSoft())) {
                        if (deck.isEmpty()) {
                            lastRoundCompleted = false;
                            break;
                        } else {
                            dealerHand.addCard(deck.dealCard());
                        }
                    }
                }
            }

            // Calculate profit if last round was finished
            if (lastRoundCompleted) {
                float profit = 0;
                // Compare hands to the dealer to see which won and what stakes to add or subtract from profit
                if (numHands == 1 && playerHands[0].isBlackJack() && !dealerHand.isBlackJack()) {
                    // Blackjack! Pay 3:2
                    profit += (float) 1.5;
                } else {
                    // Compare each hand against the dealer and adjust the profit
                    for (int i = 0; i < numHands; i++) {
                        Result result = playerHands[i].compare(dealerHand);
                        switch (result) {
                            case WIN:
                                profit += stakes[i];
                                break;
                            case LOSE:
                                profit -= stakes[i];
                                break;
                            default:
                                break;
                        }
                    }
                }
                /*System.out.println();
                System.out.println("Dealer Hand: " + dealerHand);
                System.out.println("Player Hand 1: " + playerHands[0] + " value: " + playerHands[0].getValue());
                System.out.println("Player Hand 2: " + playerHands[1] + " value: " + playerHands[1].getValue());
                System.out.println("Player Hand 3: " + playerHands[2] + " value: " + playerHands[2].getValue());
                */


                // Let the learner record the profits
                learner.learnRound(new RoundSignature(profit));
            }
        }
    }
}
