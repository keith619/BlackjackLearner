import java.util.HashMap;
import java.util.Map;

/**
 * Created by hao-linyang on 6/2/15.
 */
public class Options {
    private Map<Action, Record> mOptions;


    public Options() {
        this.mOptions = new HashMap<Action, Record>();
        for(Action a: Action.values()) {
            mOptions.put(a, new Record());
        }
    }

    public String toString() {
        StringBuffer sb = new StringBuffer();
        for (Action a : mOptions.keySet()) {
            double winPercentage = mOptions.get(a).winPercent();
            if (winPercentage == -1) {
                sb.append("Action: " + a +" Have not executed with win/lose yet\n");
            } else {
                sb.append("Action: " + a + " Win Percent: " + winPercentage + "%\n");
            }
        }
        return sb.toString();
    }

    public void addWin(Action a, double val){
        this.mOptions.get(a).addWin(val);
    }

    public void addLose(Action a, double val) {
        this.mOptions.get(a).addLose(val);
    }

    public String toDat() {
        String temp = "";
        for(Action a : mOptions.keySet()) {
            temp += a.toString() + "^" + mOptions.get(a).toDat() + "|";
        }
        return temp.substring(0, temp.length()-1);
    }
}
