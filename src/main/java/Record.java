/**
 * Created by hao-linyang on 6/2/15.
 */
public class Record {
    private double mWin;
    private double mLose;


    public Record(){
        this(0,0);
    }

    public Record(double win, double lose) {
        this.mLose = lose;
        this.mWin = win;
    }


    public void addWin(double delta) {
        this.mWin += delta;
    }

    public void addLose(double delta) {
        this.mLose += delta;
    }

    public double winPercent() {
        if (mWin == 0 && mLose == 0) {
            return -1;
        } else {
            return mWin / (mWin + mLose) * 100;
        }
    }
    public String toDat() {
        return mWin + "-" + mLose;
    }
}
