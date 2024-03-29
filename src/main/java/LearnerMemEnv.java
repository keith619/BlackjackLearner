/**
 * Created by hao-linyang on 6/10/15.
 */

import java.io.File;

import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;

import com.sleepycat.je.EnvironmentMutableConfig;
import com.sleepycat.persist.EntityStore;
import com.sleepycat.persist.StoreConfig;


public class LearnerMemEnv {

    private Environment myEnv;
    private EntityStore store;

    // Our constructor does nothing
    public LearnerMemEnv() {}

    // The setup() method opens the environment and store
    // for us.
    public void setup(File envHome)
            throws DatabaseException {

        EnvironmentConfig myEnvConfig = new EnvironmentConfig();
        myEnvConfig.setAllowCreate(true);
        StoreConfig storeConfig = new StoreConfig();

        myEnvConfig.setReadOnly(false);
        storeConfig.setReadOnly(false);

        // If the environment is opened for write, then we want to be
        // able to create the environment and entity store if
        // they do not exist.
        myEnvConfig.setAllowCreate(true);
        storeConfig.setAllowCreate(true);

        // Open the environment and entity store
        myEnv = new Environment(envHome, myEnvConfig);
        store = new EntityStore(myEnv, "EntityStore", storeConfig);

        EnvironmentMutableConfig config = myEnv.getMutableConfig();
        config.setCachePercent(70);
        myEnv.setMutableConfig(config);
    }

    // Return a handle to the entity store
    public EntityStore getEntityStore() {
        return store;
    }

    // Return a handle to the environment
    public Environment getEnv() {
        return myEnv;
    }

    // Close the store and environment.
    public void close() {
        if (store != null) {
            try {
                store.close();
            } catch(DatabaseException dbe) {
                System.err.println("Error closing store: " +
                        dbe.toString());
                System.exit(-1);
            }
        }

        if (myEnv != null) {
            try {
                // Finally, close the environment.
                myEnv.close();
            } catch(DatabaseException dbe) {
                System.err.println("Error closing MyDbEnv: " +
                        dbe.toString());
                System.exit(-1);
            }
        }
    }
}
