import com.sun.org.apache.xml.internal.security.keys.keyresolver.implementations.DEREncodedKeyValueResolver;

/**
 * Created by hao-linyang on 6/2/15.
 */
public class Move {
    public Environment mEnvironment;
    public Action mAction;

    public Move(Environment e, Action a) {
        this.mAction = a;
        this.mEnvironment = e;
    }
}
