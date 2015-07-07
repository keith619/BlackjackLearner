/**
 * Created by hao-linyang on 6/14/15.
 */
public class Stat {
    // used for progress reports
    public long NUM_KEYS_TOTAL;
    public long LAST_REPORT_TIME;
    public long START_TIME;
    public long WRITE_PROGRESS;
    public int CURRENT_PROGRESS;

    // used for statistic reports
    public int NUM_TO_FLUSH;
    public int NUM_DB_HITS;
    public long TIME_SAVED;
    public long TIME_TOTAL;
    public long READ_TIME;
    public long WRITE_TIME;
    public long CURRENT_DB_COUNT;
    public long CURRENT_DB_SIZE;
    public long TOTAL_TIME_SAVED;

    @Override
    public String toString(){
        String temp = "";

        temp += "\t<---Flush Statistics--->\n";
        temp += "\tFlushed: " + String.format("%1$,d", NUM_TO_FLUSH) + "\n";
        temp += "\tDB Hits: " + String.format("%1$,d", NUM_DB_HITS) + String.format("(%.1f%%)", (NUM_DB_HITS / (double) NUM_TO_FLUSH) * 100) + "\n";
        temp += "\tCurrent DB Key Count: " + String.format("%1$,d", CURRENT_DB_COUNT) + "\n";
        temp += "\tCurrent DB Size: " + String.format("%1$,d", CURRENT_DB_SIZE) + "\n";
        temp += "\tTotal time: " + Main.msToClockTime(TIME_TOTAL) + "\n";
        temp += "\tTime saved: " + Main.msToClockTime(TIME_SAVED) + "\n";
        temp += "\tTotal time saved: " + Main.msToClockTime(TOTAL_TIME_SAVED) + "\n";
        temp += "\tRead time: " + Main.msToClockTime(READ_TIME) + "\n";
        temp += "\tWrite time: " + Main.msToClockTime(WRITE_TIME) + "\n";
        temp += "\t<---       END      --->\n";

        return temp;
    }

    public static String addCommas(long num) {
        String numString = "" + num;
        String temp = "";

        // add high order digits before comma
        temp += numString.substring(0, numString.length() % 3);

        // add commas and their trailing digits
        for (int i = numString.length() % 3; i <= numString.length() - 3; i+=3) {
            String numSeg = numString.substring(i, i+3);
            if (i == 0) {
                temp += numSeg;
            } else {
                temp += "," + numSeg;
            }
        }

        return temp;
    }
}
