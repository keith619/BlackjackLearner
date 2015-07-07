

import java.util.List;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by hao-linyang on 6/2/15.
 */
public class Learner {
    private Map<Environment, Options> mRecords;
    private List<Move> mMoves;

    public Learner() {
        this(new HashMap<Environment, Options>());
    }

    public Learner(Map<Environment, Options> record) {
        this.mRecords = record;
        this.mMoves = new ArrayList<Move>();
    }

    public Options get(Environment e) {
        if (this.mRecords.containsKey(e)) {
            return this.mRecords.get(e);
        } else {
            return null;
        }
    }

    public void addWin(Environment e, Action a, double val) {
        if (!this.mRecords.containsKey(e)) {
            this.mRecords.put(e, new Options());
        }
        this.mRecords.get(e).addWin(a, val);
    }

    public void addLose(Environment e, Action a, double val) {
        if (!this.mRecords.containsKey(e)) {
            this.mRecords.put(e, new Options());
        }
        this.mRecords.get(e).addLose(a, val);
    }

    public void makeMove(Environment e, Action a) {
        mMoves.add(new Move(e, a));
    }

    // Adjust the results of the moves made based on the result of the game
    public void gameOver(Result result, double adjustmentFactor) {

        if (result == Result.LOSE) {
            for (int i = mMoves.size()-1; i >= 0; i--) {
                addLose(mMoves.get(i).mEnvironment, mMoves.get(i).mAction, adjustmentFactor);
                adjustmentFactor /= Action.values().length;
            }
        } else if (result == Result.WIN) {
            for (int i = mMoves.size()-1; i >= 0; i--) {
                addWin(mMoves.get(i).mEnvironment, mMoves.get(i).mAction, adjustmentFactor);
                adjustmentFactor /= Action.values().length;
            }
        } // else no adjustment
        mMoves = new ArrayList<Move>();
    }

    public Map<Environment, Options> getmRecords() {
        return this.mRecords;
    }
}
