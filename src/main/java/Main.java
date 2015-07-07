
import java.io.*;
import java.time.Duration;
import java.util.*;

public class Main {
    public static final int RESHUFFLE_DEPTH = 156;
    public static final int NUM_DECKS = 6;
    public static final int PROGRESS_REPORT_INTERVAL = 3 * 1000;


    private static WinPalLearner learner = new WinPalLearner();
    private static int wins;
    private static int pushes;
    private static int rounds;
    private static double profit;

    private static boolean DEBUG = true;

    public static void main(String[] args) {
        System.out.println("Welcome to the blackjack learner! The more you let it learn the smarter it gets! (Hopefully)");
        System.out.println();

        System.out.println("Memory Available: " + Runtime.getRuntime().maxMemory());

        // Capture user input
        Scanner scanner = new Scanner(System.in);

        String command = "";
        while (true){
            System.out.print("Enter command (h for help): ");
            command = scanner.nextLine();
            command = command.toLowerCase();
            switch (command) {
                case "h" :
                    mainMenu();
                    break;
                case "q" :
                    shutdown();
                    break;
                case "p" :
                    play(scanner);
                    break;
                case "s" :
                    stats();
                    break;
                case "t":
                    trainLearner(scanner);
                    break;
                case "f":
                    flushBuffer();
                    break;
                default :
                    break;
            }
        }
    }

    public static void shutdown() {
        learner.close();
        System.exit(0);
    }

    public static void flushBuffer() {
        learner.flushBuffer();
    }

    // TODO make training multithreaded
    public static void trainLearner(Scanner in) {
        System.out.println("Let's train the learner!");
        rounds = promptTrainingRounds(in);
        long startTime = System.currentTimeMillis();
        long lastReportTime = startTime;
        WinPalDeck newDeck;
        for (int i = 0; i < rounds; i++) {
            newDeck = WinPalDeck.getRandomDeck(NUM_DECKS, RESHUFFLE_DEPTH);
            //System.out.print("Deck size is: " + newDeck.size() + " ");
            //System.out.println("Deck Contains: " + newDeck.toString());
            WinPalTrainer.trainLearnerWithDeck(learner, newDeck);
            //System.out.println("Finished one deck and played " + learner.getRoundsSinceReset() + " rounds");
            long roundFinishTime = System.currentTimeMillis();
            if (roundFinishTime - lastReportTime > PROGRESS_REPORT_INTERVAL) {
                System.out.println("Training progress: " + (i+1) + "/" + rounds + " ETA: " + msToClockTime((long)( ((rounds - i - 1) / (double) (i+1)) * (roundFinishTime - startTime))));
                lastReportTime = roundFinishTime;
            }

        }
        long endTime = System.currentTimeMillis();
        learner.addTrainingCount(rounds);
        System.out.println("Done training " + Stat.addCommas(rounds) + " rounds! Took: " + msToClockTime(endTime - startTime) + " Current table key count is: " + Stat.addCommas(learner.getAdvicesSize())+ " Total rounds trained: "+ Stat.addCommas(learner.getTrainedCount()));
        learner.save();
    }

    public static void play(Scanner in) {
        System.out.println("Playing! Enter 0 for bet to quit.");
        int bet = promptBet(in);
        while (bet != 0) {
            rounds++;
            double playerProfit = promptProfit(in);
            if (playerProfit > 0) {
                wins++;
                profit += playerProfit * bet;
            } else if (playerProfit == 0) {
                pushes++;
            } else {
                profit -= playerProfit * bet;
            }

            learner.addToHistory(new RoundSignature((float) playerProfit));

            Advice advice = learner.queryNextRound();
            if (advice == null) {
                System.out.println("Learner does not have an advice for this situation");
            } else {
                System.out.println("Learner has an Advice! --> " + advice.toString());
            }

            bet = promptBet(in);
        }
    }

    public static void stats() {
        System.out.println("<<<<<<<<<<Statistics>>>>>>>>>>");
        System.out.println("Won: " + wins + " Pushes: " + pushes + " Lose: " + (rounds - wins - pushes) + " rounds out of " + rounds);
        System.out.println("Cumulative Profit: " + profit);
        System.out.println();
    }

    public static void mainMenu() {
        System.out.println("Command menu: t -> Train Learner, p -> Play, s->Stats, q -> Quit, h -> help");
    }

    public static int promptBet(Scanner in) {
        System.out.print("Bet? ");
        String bet = in.nextLine();
        return Integer.parseInt(bet);
    }

    public static double promptProfit(Scanner in) {
        System.out.print("Profit? ");
        String profit = in.nextLine();
        return Double.parseDouble(profit);
    }

    public static int promptTrainingRounds(Scanner in) {
        System.out.print("Number of training rounds (0 to quit)? ");
        String rounds = in.nextLine();
        return Integer.parseInt(rounds);
    }

    public static String msToClockTime(long ms) {
        long secondsTotal = (ms / 1000);
        long minutesTotal = (secondsTotal / 60);
        long hoursTotal = ( minutesTotal / 60);
        return String.format("%02d:%02d:%02d", hoursTotal, minutesTotal % 60, secondsTotal % 60);
    }


}
